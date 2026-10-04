// JNI-мост для whisper.cpp: загрузка модели и транскрипция WAV-файла.
// API сверен с include/whisper.h субмодуля whisper.cpp v1.7.6:
// whisper_init_from_file_with_params(struct whisper_context_params),
// whisper_full_default_params(WHISPER_SAMPLING_GREEDY),
// whisper_full(ctx, params, samples, n_samples), language - строковый код в params.
#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <mutex>

#include "whisper.h"

namespace {

whisper_context * g_ctx = nullptr;
int g_threads = 4;
std::mutex g_mutex;

// Проверяет целостность UTF-8 и вырезает битые байты: NewStringUTF
// не определён для невалидной UTF-8.
std::string sanitize_utf8(const char * data, size_t len) {
    std::string out;
    out.reserve(len);
    size_t i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)data[i];
        size_t need;
        if (c < 0x80) need = 0;
        else if ((c & 0xE0) == 0xC0) need = 1;
        else if ((c & 0xF0) == 0xE0) need = 2;
        else if ((c & 0xF8) == 0xF0) need = 3;
        else { ++i; continue; }
        bool bad = i + need >= len;  // обрезанный хвост
        if (c == 0xC0 || c == 0xC1) bad = true;  // overlong
        for (size_t k = 1; !bad && k <= need; ++k) {
            if (((unsigned char)data[i + k] & 0xC0) != 0x80) bad = true;
        }
        if (!bad && c == 0xED && ((unsigned char)data[i + 1] & 0xA0)) bad = true;
        if (!bad && c == 0xE0 && (unsigned char)data[i + 1] < 0xA0) bad = true;
        if (bad) { ++i; }
        else { out.append(data + i, need + 1); i += need + 1; }
    }
    return out;
}

}  // namespace

extern "C" {

// Возвращает 0 при успехе, иначе код ошибки.
JNIEXPORT jint JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeWhisperLoadModel(JNIEnv * env,
                                                                               jclass,
                                                                               jstring path,
                                                                               jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        return -1;  // уже загружена
    }

    auto * envStr = env->GetStringUTFChars(path, nullptr);
    if (envStr == nullptr) return -2;
    std::string modelPath(envStr);
    env->ReleaseStringUTFChars(path, envStr);

    g_threads = nThreads > 0 ? (int)nThreads : 4;

    // В v1.7.6 модель загружается сразу в контекст:
    // whisper_init_from_file_with_params(path, whisper_context_default_params()).
    // Отдельных whisper_model_load / whisper_init_from_model в этом API нет.
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    g_ctx = whisper_init_from_file_with_params(modelPath.c_str(), cparams);
    if (g_ctx == nullptr) {
        return -3;  // ошибка загрузки
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeWhisperUnloadModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeWhisperIsLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_ctx != nullptr;
}

// Читает WAV (16-bit PCM, mono, 16 kHz) и возвращает транскрипцию.
// language: пустая строка или "auto" - автоопределение, иначе строковый код языка
// (whisper_full_params.language принимает код напрямую; whisper_lang_id - только для id).
JNIEXPORT jstring JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeWhisperTranscribe(JNIEnv * env,
                                                                                jclass,
                                                                                jstring wavPath,
                                                                                jstring language) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr) {
        return nullptr;
    }

    auto * pathStr = env->GetStringUTFChars(wavPath, nullptr);
    if (pathStr == nullptr) return nullptr;
    std::string path(pathStr);
    env->ReleaseStringUTFChars(wavPath, pathStr);

    std::string lang;
    if (language != nullptr) {
        auto * langStr = env->GetStringUTFChars(language, nullptr);
        if (langStr != nullptr) {
            lang = langStr;
            env->ReleaseStringUTFChars(language, langStr);
        }
    }

    // Простой парсер WAV-заголовка.
    std::FILE * f = fopen(path.c_str(), "rb");
    if (f == nullptr) return nullptr;

    fseek(f, 0, SEEK_END);
    long fileSize = ftell(f);
    fseek(f, 0, SEEK_SET);

    std::vector<float> samples;
    bool wavOk = false;
    if (fileSize > 44) {
        char header[44];
        if (fread(header, 1, 44, f) == 44 &&
            memcmp(header, "RIFF", 4) == 0 &&
            memcmp(header + 8, "WAVE", 4) == 0) {
            // Ищем чанк "data".
            long pos = 12;
            long dataOffset = -1;
            long dataSize = 0;
            while (pos + 8 <= fileSize) {
                char chunkHeader[8];
                fseek(f, pos, SEEK_SET);
                if (fread(chunkHeader, 1, 8, f) != 8) break;
                uint32_t chunkSize;
                memcpy(&chunkSize, chunkHeader + 4, 4);
                if (memcmp(chunkHeader, "data", 4) == 0) {
                    dataOffset = pos + 8;
                    dataSize = chunkSize;
                    // Граница: не выходим за конец файла (RIFF может врать на потоковых WAV).
                    if (dataOffset + dataSize > fileSize) {
                        dataSize = fileSize - dataOffset;
                    }
                    break;
                }
                pos += 8 + (long)chunkSize + ((long)chunkSize & 1);
            }
            if (dataOffset > 0 && dataSize >= 2) {
                dataSize &= ~1L;  // чётное число байт (16-bit семплы)
                std::vector<char> raw((size_t)dataSize);
                fseek(f, dataOffset, SEEK_SET);
                if (fread(raw.data(), 1, (size_t)dataSize, f) == (size_t)dataSize) {
                    samples.resize((size_t)dataSize / 2);
                    for (size_t i = 0; i < samples.size(); ++i) {
                        int16_t s;
                        memcpy(&s, raw.data() + i * 2, 2);
                        samples[i] = s / 32768.0f;
                    }
                    wavOk = true;
                }
            }
        }
    }
    fclose(f);

    if (!wavOk || samples.empty()) {
        return nullptr;
    }

    // Полные параметры инференса: стратегия GREEDY (в v1.7.6 константа
    // WHISPER_SAMPLING_GREEDY, НЕ WHISPER_SAMPLING_TYPE_GREEDY).
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = g_threads;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.no_timestamps = true;

    // Язык: пусто/auto -> автоопределение (language = nullptr),
    // иначе строковый код ("ru", "en", ...), который API принимает напрямую.
    std::string langCode;  // храним строку живёй до whisper_full
    if (!lang.empty() && lang != "auto") {
        langCode = lang;
        wparams.language = langCode.c_str();
        wparams.detect_language = false;
    } else {
        wparams.language = nullptr;
        wparams.detect_language = true;
    }

    // Сигнатура v1.7.6: whisper_full(ctx, params, samples, n_samples) - 4 аргумента.
    int result = whisper_full(g_ctx, wparams, samples.data(), (int)samples.size());
    if (result != 0) {
        return nullptr;
    }

    // Собираем текст из сегментов.
    std::string text;
    const int nSegments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < nSegments; ++i) {
        const char * segmentText = whisper_full_get_segment_text(g_ctx, i);
        if (segmentText != nullptr) {
            text += segmentText;
        }
    }

    return env->NewStringUTF(sanitize_utf8(text.c_str(), text.size()).c_str());
}

}  // extern "C"
