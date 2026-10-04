// JNI-мост для llama.cpp: загрузка GGUF-модели и генерация текста.
// Все вызовы из Kotlin идут через класс NativeBridge (domain.runtime).
#include <jni.h>
#include <string>
#include <mutex>
#include <atomic>

#include "llama.h"
#include "common.h"

namespace {

struct LlamaState {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_vocab *vocab = nullptr;
};

LlamaState g_state;
std::mutex g_mutex;
std::atomic<bool> g_cancel(false);

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_llamaLoadModel(JNIEnv *env,
                                                                      jclass,
                                                                      jstring path,
                                                                      jint nThreads,
                                                                      jint nCtx) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state.ctx != nullptr) {
        return -1;  // уже загружена
    }

    auto *envStr = env->GetStringUTFChars(path, nullptr);
    if (envStr == nullptr) return -2;
    std::string modelPath(envStr);
    env->ReleaseStringUTFChars(path, envStr);

    llama_model_params mparams = llama_model_default_params();
    mparams.nthread = nThreads > 0 ? nThreads : 4;

    auto *model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        return -3;  // ошибка загрузки
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? nCtx : 4096;
    cparams.n_batch = 512;

    auto *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return -4;
    }

    g_state.model = model;
    g_state.ctx = ctx;
    g_state.vocab = llama_model_get_vocab(model);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_llamaUnloadModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
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
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_llamaIsLoaded(JNIEnv *, jclass) {
    return g_state.ctx != nullptr;
}

// Генерация текста до stop-токена или maxTokens. Возвращает строку или null при ошибке.
JNIEXPORT jstring JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_llamaGenerate(JNIEnv *env,
                                                                     jclass,
                                                                     jstring prompt,
                                                                     jint maxTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state.ctx == nullptr || g_state.vocab == nullptr) {
        return nullptr;
    }

    auto *promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (promptStr == nullptr) return nullptr;
    std::string promptText(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    g_cancel = false;

    // Токенизация промпта.
    std::vector<llama_token> tokens;
    llama_token *tmpTokens = new llama_token[1024];
    int nTokens = llama_tokenize(g_state.vocab, promptText.c_str(),
                                 (int)promptText.size(), tmpTokens, 1024, true, false);
    if (nTokens < 0) {
        delete[] tmpTokens;
        return nullptr;
    }
    tokens.assign(tmpTokens, tmpTokens + nTokens);
    delete[] tmpTokens;

    // Декодирование промпта.
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_state.ctx, batch) != 0) {
        return nullptr;
    }
    llama_batch_free(batch);

    // Генерация.
    std::string result;
    llama_token nextToken = 0;
    for (int i = 0; i < maxTokens && !g_cancel; ++i) {
        if (llama_sample_token(g_state.ctx, nullptr, nullptr, &nextToken) != 0) break;
        if (llama_vocab_is_eog(g_state.vocab, nextToken)) break;

        char buf[256];
        int n = llama_token_to_piece(g_state.vocab, nextToken, buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);

        if (llama_decode(g_state.ctx, llama_batch_get_one(&nextToken, 1)) != 0) break;
    }

    auto *resultJstr = env->NewStringUTF(result.c_str());
    return resultJstr;
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_domain_runtime_NativeBridge_llamaCancel(JNIEnv *, jclass) {
    g_cancel = true;
}

}  // extern "C"
