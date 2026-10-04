// JNI-мост для llama.cpp: загрузка GGUF-модели и генерация текста.
// Все вызовы из Kotlin идут через класс NativeBridge (domain.runtime).
// API сверен с include/llama.h субмодуля llama.cpp (v0.5.0+dev, ggml 0.25.x):
// llama_model_load_from_file, llama_init_from_model, sampler-цепочки,
// llama_batch_get_one (позиции отслеживаются автоматически).
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <cstdint>

#include "llama.h"

namespace {

struct LlamaState {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    // vocab принадлежит модели (const), освобождать llama_vocab_free НЕ нужно
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
};

LlamaState g_state;
std::mutex g_mutex;
std::atomic<bool> g_cancel(false);
bool g_backend_inited = false;

std::vector<llama_token> tokenize_text(const llama_vocab * vocab, const std::string & text) {
    std::vector<llama_token> tokens;
    if (text.empty()) return tokens;
    // llama_tokenize возвращает отрицательное число при нехватке места -
    // это количество токенов, которое потребовалось бы (|ret|), по нему и резервируем.
    int32_t n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                               nullptr, 0, /*add_special=*/true, /*parse_special=*/false);
    if (n == 0) n = (int32_t)text.size() + 8;  // reserve на худший случай
    else if (n < 0) n = -n;
    tokens.resize((size_t)n + 8);
    int32_t res = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                                 tokens.data(), (int32_t)tokens.size(),
                                 /*add_special=*/true, /*parse_special=*/false);
    if (res < 0) {
        tokens.resize((size_t)(-res));
        res = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                             tokens.data(), (int32_t)tokens.size(),
                             /*add_special=*/true, /*parse_special=*/false);
        if (res < 0) tokens.clear();
    }
    if (res >= 0) tokens.resize((size_t)res);
    return tokens;
}

// Добавляет кусок текста (из llama_token_to_piece) в буфер, пропуская
// неполные/некорректные UTF-8 последовательности: NewStringUTF падает на битой UTF-8.
void append_utf8(std::string & out, const char * data, int len) {
    int i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)data[i];
        int need;
        if (c < 0x80) need = 0;
        else if ((c & 0xE0) == 0xC0) need = 1;
        else if ((c & 0xF0) == 0xE0) need = 2;
        else if ((c & 0xF8) == 0xF0) need = 3;
        else { ++i; continue; }  // битый стартовый байт
        bool bad = c == 0xC0 || c == 0xC1 || (c >= 0xF5 && c <= 0xF7) || c >= 0xF8;
        for (int k = 1; !bad && k <= need; ++k) {
            if (i + k >= len || (((unsigned char)data[i + k] & 0xC0) != 0x80)) bad = true;
        }
        // суррогаты (ED A0..BF xx) и нулевые кодовые точки невалидны для JNI UTF-8
        if (!bad && c == 0xED && need == 2 && ((unsigned char)data[i + 1] & 0xA0)) bad = true;
        if (!bad && c == 0xE0 && need == 2 && (unsigned char)data[i + 1] < 0xA0) bad = true;
        if (bad) {
            ++i;  // хвост многобайтовой последовательности - ждём следующий полный кусок
        } else {
            out.append(data + i, (size_t)need + 1);
            i += need + 1;
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeLlamaLoadModel(JNIEnv * env,
                                                                             jclass,
                                                                             jstring path,
                                                                             jint nThreads,
                                                                             jint nCtx) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state.ctx != nullptr) {
        return -1;  // уже загружена
    }

    auto * envStr = env->GetStringUTFChars(path, nullptr);
    if (envStr == nullptr) return -2;
    std::string modelPath(envStr);
    env->ReleaseStringUTFChars(path, envStr);

    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
    }

    // В этой версии llama_model_params НЕ содержит полей потоков -
    // n_threads/n_threads_batch живут в llama_context_params (llama.h).
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model * model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        return -3;  // ошибка загрузки
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? (uint32_t)nCtx : 4096;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    const int32_t threads = nThreads > 0 ? (int32_t)nThreads : 4;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return -4;
    }

    // Жадное сэмплирование через sampler-цепочку (llama.h: llama_sampler_chain_init
    // + llama_sampler_init_greedy, выборка - llama_sampler_sample(smpl, ctx, -1)).
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (smpl == nullptr) {
        llama_free(ctx);
        llama_model_free(model);
        return -5;
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    g_state.model = model;
    g_state.ctx = ctx;
    g_state.vocab = llama_model_get_vocab(model);  // const, не освобождать отдельно
    g_state.sampler = smpl;
    g_cancel = false;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeLlamaUnloadModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state.sampler != nullptr) {
        llama_sampler_free(g_state.sampler);
        g_state.sampler = nullptr;
    }
    if (g_state.ctx != nullptr) {
        llama_free(g_state.ctx);
        g_state.ctx = nullptr;
    }
    if (g_state.model != nullptr) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
    g_state.vocab = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeLlamaIsLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.ctx != nullptr;
}

// Генерация текста до stop-токена (EoG) или maxTokens. Возвращает строку или null при ошибке.
JNIEXPORT jstring JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeLlamaGenerate(JNIEnv * env,
                                                                            jclass,
                                                                            jstring prompt,
                                                                            jint maxTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state.ctx == nullptr || g_state.vocab == nullptr || g_state.sampler == nullptr) {
        return nullptr;
    }

    auto * promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (promptStr == nullptr) return nullptr;
    std::string promptText(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    g_cancel = false;
    llama_sampler_reset(g_state.sampler);

    std::vector<llama_token> tokens = tokenize_text(g_state.vocab, promptText);
    if (tokens.empty()) return nullptr;

    // Декодирование промпта чанками не больше n_batch.
    // llama_batch_get_one не аллоцирует память - llama_batch_free для него НЕ вызывается.
    const int32_t n_batch = std::max(1, (int32_t)llama_n_batch(g_state.ctx));
    for (size_t begin = 0; begin < tokens.size(); begin += (size_t)n_batch) {
        if (g_cancel) break;
        const size_t count = std::min((size_t)n_batch, tokens.size() - begin);
        llama_batch batch = llama_batch_get_one(tokens.data() + begin, (int32_t)count);
        if (llama_decode(g_state.ctx, batch) != 0) {
            return nullptr;
        }
    }

    // Генерация.
    std::string result;
    llama_token next_token = 0;
    char piece[256];
    for (int i = 0; i < maxTokens && !g_cancel; ++i) {
        next_token = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);
        if (llama_vocab_is_eog(g_state.vocab, next_token)) break;

        const int n = llama_token_to_piece(g_state.vocab, next_token,
                                           piece, (int32_t)sizeof(piece), 0, false);
        if (n > 0) {
            append_utf8(result, piece, n < (int32_t)sizeof(piece) ? n : (int32_t)sizeof(piece));
        }

        llama_batch batch = llama_batch_get_one(&next_token, 1);
        if (llama_decode(g_state.ctx, batch) != 0) break;
    }

    if (result.empty() && g_cancel) return nullptr;
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_nativeLlamaCancel(JNIEnv *, jclass) {
    g_cancel = true;
}

}  // extern "C"
