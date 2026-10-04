// JNI-мост для whisper.cpp: загрузка модели и транскрипция WAV-файла.
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>

#include "whisper.h"

namespace {

whisper_context *g_ctx = nullptr;
whisper_model *g_model = nullptr;
std::mutex g_mutex;

}  // namespace

extern "C" {

// Возвращает 0 при успехе, иначе код ошибки.
JNIEXPORT jint JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_whisperLoadModel(JNIEnv *env,
                                                                        jclass,
                                                                        jstring path,
                                                                        jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        return -1;  // уже загружена
    }

    auto *envStr = env->GetStringUTFChars(path, nullptr);
    if (envStr == nullptr) return -2;
    std::string modelPath(envStr);
    env->ReleaseStringUTFChars(path, envStr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_TYPE_GREEDY);
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_system_info = false;

    g_model = whisper_model_load(modelPath.c_str());
    if (g_model == nullptr) {
        return -3;  // ошибка загрузки
    }

    g_ctx = whisper_init_from_model(g_model, params);
    if (g_ctx == nullptr) {
        whisper_model_free(g_model);
        g_model = nullptr;
        return -4;
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_whisperUnloadModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        whisper_model_free(g_model);
        g_model = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_whisperIsLoaded(JNIEnv *, jclass) {
    return g_ctx != nullptr;
}

// Читает WAV (16-bit PCM, mono, 16 kHz) и возвращает транскрипцию.
JNIEXPORT jstring JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_whisperTranscribe(JNIEnv *env,
                                                                         jclass,
                                                                         jstring wavPath,
                                                                         jstring language) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr) {
        return nullptr;
    }

    auto *pathStr = env->GetStringUTFChars(wavPath, nullptr);
    if (pathStr == nullptr) return nullptr;
    std::string path(pathStr);
    env->ReleaseStringUTFChars(wavPath, pathStr);

    auto *langStr = env->GetStringUTFChars(language, nullptr);
    std::string lang(langStr ? langStr : "");
    env->ReleaseStringUTFChars(language, langStr);

    // Простой парсер WAV-заголовка.
    std::FILE *f = fopen(path.c_str(), "rb");
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
                unsigned int chunkSize;
                memcpy(&chunkSize, chunkHeader + 4, 4);
                if (memcmp(chunkHeader, "data", 4) == 0) {
                    dataOffset = pos + 8;
                    dataSize = chunkSize;
                    break;
                }
                pos += 8 + chunkSize + (chunkSize & 1);
            }
            if (dataOffset > 0 && dataSize > 0) {
                std::vector<char> raw(dataSize);
                fseek(f, dataOffset, SEEK_SET);
                if (fread(raw.data(), 1, dataSize, f) == (size_t)dataSize) {
                    samples.resize(dataSize / 2);
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

    int result = whisper_full(g_ctx,
                              whisper_full_default_params(WHISPER_SAMPLING_TYPE_GREEDY),
                              lang.c_str(),
                              samples.data(),
                              (int)samples.size());
    if (result != 0) {
        return nullptr;
    }

    // Собираем текст из сегментов.
    std::string text;
    int nSegments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < nSegments; ++i) {
        const char *segmentText = whisper_full_get_segment_text(g_ctx, i);
        if (segmentText != nullptr) {
            text.append(segmentText);
        }
    }

    return env->NewStringUTF(text.c_str());
}

}  // extern "C"
