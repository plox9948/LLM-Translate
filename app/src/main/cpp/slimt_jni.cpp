// JNI-обёртка над slimt (SLIm Marian TRANslator) — инференс-движок
// языковых паков Firefox Translations (Bergamot), как в offline-translator.
#include <jni.h>

#include <atomic>
#include <exception>
#include <fstream>
#include <future>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>

#include "slimt/slimt.hh"

using namespace slimt;  // NOLINT

namespace {

struct Session {
    std::shared_ptr<Model> model;
    std::atomic<bool> cancel{false};
    // frontend объявлен ПОСЛЕ cancel: деструкция идёт в обратном порядке,
    // поэтому ~Async (join воркеров, читающих &cancel через cancel_flag_)
    // завершится раньше, чем будет уничтожен cancel.
    std::unique_ptr<Async> frontend;
    std::mutex mu;
    std::optional<std::future<Response>> future;
    std::string result;
    std::string error;
    bool pending = false;
};

void throw_java(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

std::string jstr_to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string cesu(chars);
    env->ReleaseStringUTFChars(value, chars);

    // GetStringUTFChars возвращает модифицированную UTF-8 (CESU-8):
    // непозиционные символы кодируются суррогатной парой. Возвращаем
    // обычный UTF-8 для slimt/sentencepiece.
    auto u8 = [](unsigned char b) { return static_cast<unsigned>(b); };
    auto is_continuation = [&](size_t i) {
        return i < cesu.size() && u8(cesu[i]) >= 0x80 && u8(cesu[i]) <= 0xBF;
    };
    auto decode3 = [&](size_t i) {
        return ((u8(cesu[i]) & 0x0Fu) << 12) | ((u8(cesu[i + 1]) & 0x3Fu) << 6) |
               (u8(cesu[i + 2]) & 0x3Fu);
    };

    std::string utf8;
    for (size_t i = 0; i < cesu.size();) {
        unsigned char c = cesu[i];
        // CESU-8 суррогатная пара: EDA0-AF 0x80-BF (высокий) +
        // ED B0-BF 0x80-BF (низкий).
        if (c == 0xED && i + 5 < cesu.size() && is_continuation(i + 2) &&
            u8(cesu[i + 1]) >= 0xA0 && u8(cesu[i + 1]) <= 0xAF &&
            cesu[i + 3] == 0xED && is_continuation(i + 5) &&
            u8(cesu[i + 4]) >= 0xB0 && u8(cesu[i + 4]) <= 0xBF) {
            unsigned int cp = 0x10000 + ((decode3(i) - 0xD800) << 10) +
                              (decode3(i + 3) - 0xDC00);
            utf8.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            utf8.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            i += 6;
            continue;
        }
        utf8.push_back(c);
        ++i;
    }
    return utf8;
}

jstring utf8_to_jstr(JNIEnv *env, const std::string &text) {
    std::u16string units;
    for (size_t i = 0; i < text.size();) {
        unsigned char c = text[i];
        unsigned int cp = 0;
        size_t len = 1;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0u) == 0xC0u) {
            cp = c & 0x1Fu;
            len = 2;
        } else if ((c & 0xF0u) == 0xE0u) {
            cp = c & 0x0Fu;
            len = 3;
        } else if ((c & 0xF8u) == 0xF0u) {
            cp = c & 0x07u;
            len = 4;
        }
        if (i + len > text.size()) {
            break;
        }
        for (size_t j = 1; j < len; ++j) {
            cp = (cp << 6) | (static_cast<unsigned char>(text[i + j]) & 0x3Fu);
        }
        i += len;
        if (cp > 0xFFFF) {
            cp -= 0x10000;
            units.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            units.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            units.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(units.data()),
                          static_cast<jsize>(units.size()));
}

// slimt v1 bin: количество слоёв берётся из имён тензоров в заголовке
// (encoder_lN_* / decoder_lN_*), а не из пресета: у модели en-ru
// (retrain_base-memory) dec-depth=4 и dim-emb=384, тогда как preset::tiny()
// задаёт 2 decoder-слоя — лишние слои молча игнорируются и декодер даёт
// вырожденный вывод. Имена тензоров идут в начале файла plaintext, поэтому
// достаточно разобрать первые байты.
// Если ни одного имени слоя распознать не удалось (чужой/битый заголовок),
// молчание на дефолте preset::tiny() даёт тихую порчу перевода — бросаем
// исключение: nativeCreate перехватывает его и прокидывает ошибку загрузки
// в Kotlin (RuntimeError.LoadFailed) (ревью #64).
Model::Config detect_config(const std::string &model_path) {
    Model::Config config = preset::tiny();
    std::ifstream file(model_path, std::ios::binary);
    if (!file) {
        return config;
    }
    std::string head(65536, '\0');
    file.read(head.data(), static_cast<std::streamsize>(head.size()));
    head.resize(static_cast<size_t>(file.gcount()));

    auto max_layer = [&head](const char *prefix) -> size_t {
        const std::string needle(prefix);
        size_t best = 0;
        for (size_t pos = head.find(needle); pos != std::string::npos;
             pos = head.find(needle, pos + 1)) {
            size_t i = pos + needle.size();
            size_t num = 0;
            bool any = false;
            while (i < head.size() && head[i] >= '0' && head[i] <= '9') {
                num = num * 10 + static_cast<size_t>(head[i] - '0');
                ++i;
                any = true;
            }
            if (any && i < head.size() && head[i] == '_' && num > best) {
                best = num;
            }
        }
        return best;
    };

    const size_t encoder_layers = max_layer("encoder_l");
    const size_t decoder_layers = max_layer("decoder_l");
    if (encoder_layers == 0 || decoder_layers == 0) {
        // Имена тензоров слоёв не распознаны: дефолт preset::tiny() молча
        // подменил бы реальную архитектуру модели — явная ошибка загрузки.
        throw std::runtime_error(
            "slimt: не удалось распознать число слоёв по имени тензоров "
            "в заголовке модели: " + model_path);
    }
    config.encoder_layers = encoder_layers;
    config.decoder_layers = decoder_layers;
    return config;
}

}  // namespace

extern "C" {

// NOLINTBEGIN

JNIEXPORT jlong JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeCreate(
    JNIEnv *env, jclass /*clazz*/, jstring jmodel, jstring jvocab,
    jstring jshortlist, jstring jtarget_vocab) {
    Package<std::string> package;
    package.model = jstr_to_utf8(env, jmodel);
    package.vocabulary = jstr_to_utf8(env, jvocab);
    package.shortlist = jstr_to_utf8(env, jshortlist);
    package.target_vocabulary = jstr_to_utf8(env, jtarget_vocab);
    package.ssplit.clear();

    try {
        auto session = std::make_unique<Session>();
        session->model =
            std::make_shared<Model>(detect_config(package.model), package);
        Config frontend_config;
        frontend_config.workers = 1;
        session->frontend = std::make_unique<Async>(frontend_config);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception &e) {
        throw_java(env, e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeDestroy(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    delete reinterpret_cast<Session *>(handle);  // NOLINT
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeStart(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jstring jtext) {
    auto *session = reinterpret_cast<Session *>(handle);  // NOLINT
    std::string text = jstr_to_utf8(env, jtext);
    std::lock_guard<std::mutex> lock(session->mu);
    if (session->pending) {
        throw_java(env, "slimt: translation already running");
        return;
    }
    // Сброс результатов предыдущего перевода: иначе при исключении в
    // translate nativePoll/nativeTakeError вернут устаревшие данные.
    session->result.clear();
    session->error.clear();
    session->cancel.store(false);
    // Options.alignment по умолчанию false — выравнивания переводу не нужны.
    Options options;
    try {
        session->frontend->set_cancel_flag(&session->cancel);
        Handle request = session->frontend->translate(
            session->model, std::move(text), options);
        session->future = std::move(request.future());
        session->pending = true;
    } catch (const std::exception &e) {
        throw_java(env, e.what());
    }
}

// 0 — ещё идёт, 1 — готово, 2 — ошибка, 3 — отменено.
JNIEXPORT jint JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativePoll(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);  // NOLINT
    std::lock_guard<std::mutex> lock(session->mu);
    if (!session->pending) {
        return 2;
    }
    if (session->future->wait_for(std::chrono::milliseconds(0)) !=
        std::future_status::ready) {
        return 0;
    }
    session->pending = false;
    try {
        Response response = session->future->get();
        session->result = response.target.text;
        return 1;
    } catch (const std::exception &e) {
        if (session->cancel.load()) {
            return 3;
        }
        session->error = e.what();
        return 2;
    }
}

JNIEXPORT jstring JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeTakeResult(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);  // NOLINT
    std::lock_guard<std::mutex> lock(session->mu);
    return utf8_to_jstr(env, session->result);
}

JNIEXPORT jstring JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeTakeError(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);  // NOLINT
    std::lock_guard<std::mutex> lock(session->mu);
    return env->NewStringUTF(session->error.c_str());
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeCancel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);  // NOLINT
    session->cancel.store(true);
}

// NOLINTEND

}  // extern "C"
