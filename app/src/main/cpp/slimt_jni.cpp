// JNI-обёртка над slimt (SLIm Marian TRANslator) — инференс-движок
// языковых паков Firefox Translations (Bergamot), как в offline-translator.
#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <exception>
#include <fstream>
#include <future>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>

#include "slimt/slimt.hh"

using namespace slimt;  // NOLINT

namespace {

// Магический cookie живого объекта Session (ревью аудита): ДЕШЁВАЯ эвристика
// против повторного использования handle без рефкаунтинга — nativeDestroy
// переводит cookie в destroyed-состояние ПЕРЕД delete, checked_session
// сверяет его. Честная оговорка (re-review): после delete чтение cookie —
// формально read-after-free UB, паттерн «обычно ещё читается», но гарантии
// нет; полная защита — поток-контракт SlimtNative (все вызовы одного handle
// сериализуются вызывающим) и зануление handle в Kotlin SlimtRuntime после
// успешного nativeDestroy, чтобы поздние вызовы отбивались нулевой веткой
// проверки вообще без разыменования.
constexpr uint64_t kSessionLiveCookie = 0x534C494D545F4C45ULL;       // "SLIMT_LE"
constexpr uint64_t kSessionDestroyedCookie = 0x534C494D545F4445ULL;  // "SLIMT_DE"

struct Session {
    uint64_t cookie = kSessionLiveCookie;
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
    } else {
        // FindClass вернул nullptr (pending-исключение потока): нативная
        // ошибка иначе теряется без следа — снимаем JNI-состояние и пишем
        // текст ошибки в logcat (ревью).
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, "slimt_jni",
                            "throw_java: FindClass failed, native error: %s",
                            message);
    }
}

// Проверка валидности handle для всех экспортов, работающих с сессией:
// нулевой handle (Kotlin-контракт: зануляется после успешного nativeDestroy)
// или cookie != живой (освобождённый nativeDestroy handle) — одна и та же
// Java-ошибка; nullptr в ответе означает «исключение уже брошено»,
// вызывающий выходит со своим значением by default. Сверка cookie —
// эвристика (после delete чтение UB, см. комментарий констант), корректность
// даёт сериализация вызовов одним потоком.
Session *checked_session(JNIEnv *env, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);  // NOLINT
    if (session == nullptr || session->cookie != kSessionLiveCookie) {
        throw_java(env, "slimt: некорректный handle");
        return nullptr;
    }
    return session;
}

// Чтение jstring в UTF-8. std::nullopt — неудача GetStringUTFChars:
// причина либо уже pending Java-исключение (например OutOfMemoryError),
// либо тихая JNI-ошибка. Утилита САМА в JVM ничего не бросает
// (ревью аудита): прежняя связка «ThrowNew + std::runtime_error» заставляла
// catch экспорта бросать второй раз (ThrowNew перезатирал только что
// установленное исключение), а при pending-OOM FindClass внутри throw_java
// возвращал nullptr и ветка ExceptionClear() смазывала исходный
// OutOfMemoryError — экспорт возвращал «успех» без pending. Выбор ошибки и
// ровно один бросок — на ответственности экспорта (см. read_jstr).
std::optional<std::string> jstr_to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return std::string{};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        // Пустая строка молча подменила бы входные данные — возвращаем
        // признак неудачи, экспорт разберётся с причиной.
        return std::nullopt;
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
            u8(cesu[i + 3]) == 0xED && is_continuation(i + 5) &&
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
        // Одиночный (непарный) суррогат модифицированной UTF-8: ED A0-BF xx.
        // Как отдельная 3-байтовая последовательность он не является валидным
        // UTF-8, но раньше проходил молча — подменяем U+FFFD, как utf8_to_jstr
        // при обратном преобразовании (ревью).
        if (c == 0xED && i + 2 < cesu.size() && u8(cesu[i + 1]) >= 0xA0 &&
            u8(cesu[i + 1]) <= 0xBF && is_continuation(i + 2)) {
            utf8.push_back(static_cast<char>(0xEF));
            utf8.push_back(static_cast<char>(0xBF));
            utf8.push_back(static_cast<char>(0xBD));
            i += 3;
            continue;
        }
        utf8.push_back(c);
        ++i;
    }
    return utf8;
}

// Чтение jstring для экспортов: false — неудача, при этом в JVM уже
// установлено РОВНО ОДНО исключение: pending-исключение потока
// (например OutOfMemoryError из GetStringUTFChars) пробрасывается как есть,
// без подмены; тихая JNI-ошибка оформляется через throw_java один раз.
// Экспорт обязан немедленно выйти со своим default-значением.
bool read_jstr(JNIEnv *env, jstring value, std::string &out) {
    auto text = jstr_to_utf8(env, value);
    if (!text.has_value()) {
        if (!env->ExceptionCheck()) {
            throw_java(env, "slimt: JNI GetStringUTFChars failed");
        }
        return false;
    }
    out = std::move(*text);
    return true;
}

jstring utf8_to_jstr(JNIEnv *env, const std::string &text) {
    std::u16string units;
    for (size_t i = 0; i < text.size();) {
        unsigned char c = text[i];
        unsigned int cp = 0;
        size_t len = 1;
        if (c < 0x80) {
            cp = c;
        } else if (c == 0xC0 || c == 0xC1) {
            // Overlong-кодирование (0xC0/0xC1 lead): младшие ASCII-байты,
            // недопустимые как многобайтовая форма — U+FFFD (ревью).
            units.push_back(static_cast<char16_t>(0xFFFD));
            ++i;
            continue;
        } else if ((c & 0xE0u) == 0xC0u) {
            cp = c & 0x1Fu;
            len = 2;
        } else if ((c & 0xF0u) == 0xE0u) {
            cp = c & 0x0Fu;
            len = 3;
        } else if ((c & 0xF8u) == 0xF0u) {
            cp = c & 0x07u;
            len = 4;
        } else {
            // Одиночный continuation-байт (0x80..0xBF) или недопустимый
            // lead-байт (0xF8..0xFF): U+FFFD вместо U+0000.
            units.push_back(static_cast<char16_t>(0xFFFD));
            ++i;
            continue;
        }
        if (i + len > text.size()) {
            // Обрыв многобайтовой последовательности в конце строки.
            units.push_back(static_cast<char16_t>(0xFFFD));
            break;
        }
        bool malformed = false;
        for (size_t j = 1; j < len; ++j) {
            if ((static_cast<unsigned char>(text[i + j]) & 0xC0u) != 0x80u) {
                malformed = true;
                break;
            }
            cp = (cp << 6) | (static_cast<unsigned char>(text[i + j]) & 0x3Fu);
        }
        if (malformed) {
            // Битый continuation-байт: U+FFFD за lead-байтом, переосмысление
            // продолжения со следующего байта (ревью).
            units.push_back(static_cast<char16_t>(0xFFFD));
            ++i;
            continue;
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
    // NewString может вернуть nullptr без pending-исключения при нехватке
    // памяти в куче JVM (OOM при аллокации строки). ExceptionCheck здесь не
    // делается намеренно: вызывающий трактует null как «нет результата»
    // (см. KDoc SlimtNative.nativeTakeResult/nativeTakeError).
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
        // Молчаливый preset::tiny() на неоткрытом файле дал бы тихую порчу
        // перевода — это явная ошибка загрузки пакета.
        throw std::runtime_error(
            "slimt: не удалось открыть файл модели: " + model_path);
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
            // Имя слоя может завершаться ЛЮБЫМ нецифровым разделителем
            // ('_', '.', '\0', конец прочитанного заголовка) — раньше
            // требовался ровно '_'. Цикл выше остановился на нецифровом
            // байте или на границе, значит разделитель корректен (ревью).
            if (any && num > best) {
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
    try {
        // Чтение строк пакета: при неудаче read_jstr уже установил ровно
        // одно Java-исключение (pending проброшено как есть) — выходим с 0.
        // Исключения создания модели/движка перехватывает общий catch ниже.
        Package<std::string> package;
        if (!read_jstr(env, jmodel, package.model) ||
            !read_jstr(env, jvocab, package.vocabulary) ||
            !read_jstr(env, jshortlist, package.shortlist) ||
            !read_jstr(env, jtarget_vocab, package.target_vocabulary)) {
            return 0;
        }
        package.ssplit.clear();

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
    } catch (...) {
        throw_java(env, "slimt: unknown native error");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeDestroy(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *session = checked_session(env, handle);
    if (session == nullptr) {
        return;
    }
    try {
        // ПЕРЕД удалением поднимаем флаг отмены (ревью #83): воркеры Async
        // читают cancel_flag_ на шагах декода (тот же механизм, что в
        // nativeCancel), поэтому ~Async.join() не ждёт конца длинного
        // перевода. cancel объявлен в Session ДО frontend — при обратном
        // порядке деструкции ~Async завершится раньше уничтожения cancel,
        // UAF нет. Исключения деструкторов (std::system_error из join/
        // освобождения Model) иначе уходят через границу JNI — UB
        // (единственный экспорт без try/catch).
        //
        // Ревью аудита: cancel и переход cookie в destroyed-состояние
        // выполняются ПОД session->mu — дешёвая страховка на случай
        // параллельного nativePoll с тем же handle (поток-контракт
        // SlimtNative всё равно сериализует вызовы, мьютекс — не замена).
        // delete — ВНЕ мьютекса: уничтожение залоченного mutex это UB, а
        // join воркера в ~Async не должен удерживать mu. После присвоения
        // destroyed-cookie экспорт с этим handle обычно получит
        // IllegalStateException в checked_session, но чтение cookie после
        // delete — UB без гарантии; reuse надёжно исключает зануление handle
        // в Kotlin SlimtRuntime после успешного destroy (нулевая ветка
        // проверки не разыменовывает память вовсе).
        {
            std::lock_guard<std::mutex> lock(session->mu);
            session->cancel.store(true);
            session->cookie = kSessionDestroyedCookie;
        }
        delete session;  // NOLINT
    } catch (const std::exception &e) {
        throw_java(env, e.what());
    } catch (...) {
        throw_java(env, "slimt: unknown native error");
    }
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeStart(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jstring jtext) {
    auto *session = checked_session(env, handle);
    if (session == nullptr) {
        return;
    }
    // Чтение текста: при неудаче read_jstr уже установил ровно одно
    // Java-исключение (pending из GetStringUTFChars проброшено как есть) —
    // немедленно выходим. Остальное тело в try, чтобы ошибка движка всегда
    // уходила в Java как IllegalStateException.
    try {
        std::string text;
        if (!read_jstr(env, jtext, text)) {
            return;
        }
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
        session->frontend->set_cancel_flag(&session->cancel);
        Handle request = session->frontend->translate(
            session->model, std::move(text), options);
        session->future = std::move(request.future());
        session->pending = true;
    } catch (const std::exception &e) {
        throw_java(env, e.what());
    } catch (...) {
        throw_java(env, "slimt: unknown native error");
    }
}

// 0 — ещё идёт, 1 — готово, 2 — ошибка, 3 — отменено.
// Код 2 возвращается и для незапущенной/уже снятой сессии (!pending):
// на это опирается дренаж SlimtRuntime (drainNativeWorkerUnlocked),
// семантика кодов зафиксирована в KDoc SlimtNative.nativePoll.
JNIEXPORT jint JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativePoll(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *session = checked_session(env, handle);
    if (session == nullptr) {
        return 2;
    }
    std::lock_guard<std::mutex> lock(session->mu);
    if (!session->pending) {
        return 2;
    }
    if (session->future->wait_for(std::chrono::milliseconds(0)) !=
        std::future_status::ready) {
        return 0;
    }
    session->pending = false;
    std::string message;
    try {
        Response response = session->future->get();
        session->result = response.target.text;
        return 1;
    } catch (const std::exception &e) {
        message = e.what();
    } catch (...) {
        message = "slimt: unknown native error";
    }
    // Отмена: future бросает после поднятия cancel_flag_ — общая проверка
    // для обеих веток, ошибка не записывается.
    if (session->cancel.load()) {
        return 3;
    }
    session->error = std::move(message);
    return 2;
}

JNIEXPORT jstring JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeTakeResult(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *session = checked_session(env, handle);
    if (session == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(session->mu);
    // take ИЗЫМАЕТ строку (ревью аудита): после взятия поле очищается,
    // повторный take до следующего nativeStart вернёт пустую строку,
    // а не устаревший текст.
    std::string result = std::exchange(session->result, {});
    return utf8_to_jstr(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeTakeError(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *session = checked_session(env, handle);
    if (session == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(session->mu);
    // error может содержать не-ASCII (тексты slimt на языке модели):
    // NewStringUTF ждёт модифицированную UTF-8 — кодируем явно через
    // общий utf8_to_jstr. take ИЗЫМАЕТ строку (см. nativeTakeResult).
    std::string error = std::exchange(session->error, {});
    return utf8_to_jstr(env, error);
}

JNIEXPORT void JNICALL
Java_com_plox_llmtranslate_data_native_1engine_SlimtNative_nativeCancel(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *session = checked_session(env, handle);
    if (session == nullptr) {
        return;
    }
    session->cancel.store(true);
}

// NOLINTEND

}  // extern "C"
