package com.plox.llmtranslate.domain.model

/**
 * Категория модели.
 */
enum class ModelCategory {
    TRANSLATION,
    STT,
    TTS
}

/**
 * Тип рантайма, определяющий формат модели и способ загрузки.
 */
enum class RuntimeType {
    // Инференс-слой: sherpa-onnx (STT) и ONNX-переводы (Marian/IndicTrans2).
    SHERPA,
    MARIAN_ONNX,
    INDICTRANS_ONNX,

    // Языковые паки Firefox Translations (Bergamot .bin) через slimt (C++/JNI).
    MARIAN_SLIMT,
    SYSTEM_TTS,
    RHVOICE,
    ESPEAK,

    // Piper/VITS TTS через sherpa-onnx OfflineTts: скачиваемая модель
    // (tar.bz2 с .onnx + espeak-ng-data), не движок-ярлык — ttsEngineId() null.
    PIPER_ONNX
}

/**
 * Канонические идентификаторы TTS-движков — значения настройки `tts_engine`
 * (DataStore). Единственный источник строковых литералов для всех слоёв
 * (настройки, экраны, рантайм, бэкап).
 */
object TtsEngines {
    /** Системный Android TTS (движок по умолчанию). */
    const val SYSTEM = "system"

    /** RHVoice. */
    const val RHVOICE = "rhvoice"

    /** eSpeak NG. */
    const val ESPEAK = "espeak"
}

/**
 * Идентификатор TTS-движка (настройка ttsEngine), сопоставленный этому рантайму,
 * или null, если рантайм не относится к TTS.
 */
fun RuntimeType.ttsEngineId(): String? = when (this) {
    RuntimeType.SYSTEM_TTS -> TtsEngines.SYSTEM
    RuntimeType.RHVOICE -> TtsEngines.RHVOICE
    RuntimeType.ESPEAK -> TtsEngines.ESPEAK
    else -> null
}

/**
 * Движки TTS, предлагаемые в блоке «Движок синтеза речи» настроек (issue #55),
 * в порядке отображения. Связь карточки каталога с движком — по рантайму
 * ([ttsEngineId]), а не по id карточки: возврат движка в интерфейс — одна
 * правка флага hidden в каталоге. Системный движок показывается всегда;
 * движок со скрытой карточкой (hidden=true) не предлагается, кроме случая,
 * когда он уже выбран в настройке [selected] — ранее сделанный выбор не теряется.
 */
fun visibleTtsEngineIds(catalog: List<ModelCard>, selected: String): List<String> {
    val hiddenEngines = catalog.mapNotNull { card ->
        card.runtime.ttsEngineId()?.takeIf { card.hidden }
    }.toSet()
    return listOf(TtsEngines.SYSTEM, TtsEngines.RHVOICE, TtsEngines.ESPEAK)
        .filter { it == TtsEngines.SYSTEM || it !in hiddenEngines || it == selected }
}

/**
 * Статус установки модели.
 */
enum class ModelStatus {
    DOWNLOADING,
    READY,
    ERROR
}

/**
 * Карточка модели из каталога (assets или удалённый источник).
 */
data class ModelCard(
    val id: String,
    val category: ModelCategory,
    val displayName: String,
    val description: String,
    val runtime: RuntimeType,
    val sizeBytes: Long,
    val ramRequiredMb: Int,
    val languages: List<String>,
    val quality: Int,
    val speed: Int,
    val url: String,
    val urls: List<String>,
    val sha256: String?,
    /** Контрольные суммы по индексам [urls]; пустой список — только [sha256] для одиночного файла. */
    val sha256s: List<String?> = emptyList(),
    val license: String,
    val minAndroidSdk: Int,
    val recommendedRamMb: Int,
    /**
     * Скрыта из интерфейса каталога (issue #55): карточка не показывается
     * в секциях экрана «Модели» и не предлагается чипами направления;
     * в каталоге и коде модель остаётся (быстрый возврат).
     */
    val hidden: Boolean = false,
    /**
     * Язык, для которого эта карточка — TTS-карточка по умолчанию в каталоге
     * (поле каталога default_for_language, ревью); null — в каталоге не
     * задана (тогда применяется резервная константа
     * [TtsModelSelector.DEFAULT_LANGUAGE_CARDS] — только при наличии
     * карточки с резервным id в каталоге, ревью #83).
     */
    val defaultForLanguage: String? = null
) {
    /**
     * Лицензия требует подтверждения (некоммерческая). Сверка по токенам
     * (ревью: прежнее `contains("NC")` ложно срабатывало на подстроках вроде
     * «TRANSCENDENTAL»): лицензия разбивается по разделителям, и ищется
     * точный токен «NC» (покрывает «CC-BY-NC-4.0», «-NC» и т.п.) либо
     * словосочетание NON-COMMERCIAL в любом написании разделителя.
     */
    val requiresLicenseConfirmation: Boolean
        get() {
            val upper = license.uppercase()
            return "NC" in LICENSE_TOKEN_DELIMITERS.split(upper) ||
                NON_COMMERCIAL_PHRASE.containsMatchIn(upper)
        }

    /** Все URL для скачивания: urls (мультифайл) или url (один файл). */
    fun downloadUrls(): List<String> = resolveDownloadUrls(url, urls)
}

/**
 * Общий разбор URL скачивания для [ModelCard] и [InstalledModel]:
 * urls (мультифайл) или url (один файл).
 */
private fun resolveDownloadUrls(url: String, urls: List<String>): List<String> =
    if (urls.isNotEmpty()) urls else listOfNotNull(url.takeIf { it.isNotBlank() })

/** Разделитель токенов лицензии для [ModelCard.requiresLicenseConfirmation]. */
private val LICENSE_TOKEN_DELIMITERS = Regex("[^A-Z0-9]+")

/** Варианты записи «non-commercial» с любым разделителем (или слитно). */
private val NON_COMMERCIAL_PHRASE = Regex("NON[-_ ]?COMMERCIAL")

/**
 * Разделитель многосоставных строковых колонок БД (urls, languages) и
 * входных данных воркера — перенос строки: URL допустимо содержать запятые
 * (query-параметры), прежний разделитель ',' разъедал такие URL на части.
 */
internal const val LIST_DELIMITER = '\n'

/**
 * Версионный префикс однозначного кодирования списков (контрольный аудит,
 * п.4): запись всегда с префиксом «v1:», одноэлементный URL с запятой больше
 * не калечится legacy-фолбэком на ',' при чтении.
 */
internal const val LIST_FORMAT_PREFIX = "v1:"

/**
 * Разбор колонки-списка БЕЗ префикса: новый разделитель «\n»; если его нет —
 * legacy-форма с ',' (старые строки БД до смены разделителя; URL без запятых
 * декодируются в один элемент корректно).
 */
internal fun splitDelimitedList(raw: String): List<String> =
    if (raw.contains(LIST_DELIMITER)) raw.split(LIST_DELIMITER) else raw.split(',')

/** Кодирование списка строк (languages) в колонку БД. */
internal fun joinDelimitedList(items: List<String>): String =
    items.joinToString(LIST_DELIMITER.toString())

/**
 * Кодирование списка значений, способных содержать разделители (URL) —
 * всегда с версионным префиксом «v1:» (пустой список — пустое тело после
 * префикса).
 */
internal fun encodeDelimitedList(items: List<String>): String =
    LIST_FORMAT_PREFIX + items.joinToString(LIST_DELIMITER.toString())

/**
 * Обратное к [encodeDelimitedList] чтение: с префиксом «v1:» — split по «\n»
 * (пустое тело — пустой список); без префикса — legacy-разбор
 * [splitDelimitedList] (строки эпох «\n» без префикса и «,»).
 */
internal fun decodeDelimitedList(raw: String): List<String> {
    if (raw.startsWith(LIST_FORMAT_PREFIX)) {
        val body = raw.removePrefix(LIST_FORMAT_PREFIX)
        return if (body.isEmpty()) emptyList() else body.split(LIST_DELIMITER)
    }
    return splitDelimitedList(raw)
}

/**
 * Кодирование списка по-URL SHA-256 в колонку БД / inputData воркера:
 * пустой элемент — хеш неизвестен; пустой список — пустая строка
 * (совпадает с DEFAULT '' колонок). С префиксом «v1:» (единообразно с
 * [encodeDelimitedList]; хеши заведомо не содержат переносов).
 */
internal fun sha256sToCsv(sha256s: List<String?>): String =
    if (sha256s.isEmpty()) ""
    else LIST_FORMAT_PREFIX + sha256s.joinToString(LIST_DELIMITER.toString()) {
        it?.takeIf { value -> value.isNotBlank() }.orEmpty()
    }

/**
 * Обратное к [sha256sToCsv] декодирование (пустая строка — пустой список);
 * «v1:» — split по «\n»; без префикса — legacy-разделители «\n»/«,»
 * (старые строки БД и ещё не переварившиеся постановки воркера).
 */
internal fun sha256sFromCsv(raw: String): List<String?> {
    if (raw.isEmpty()) return emptyList()
    val parts = when {
        raw.startsWith(LIST_FORMAT_PREFIX) ->
            raw.removePrefix(LIST_FORMAT_PREFIX).split(LIST_DELIMITER)
        raw.contains(LIST_DELIMITER) -> raw.split(LIST_DELIMITER)
        else -> raw.split(',')
    }
    return parts.map { it.takeIf { value -> value.isNotBlank() } }
}

/**
 * Установленная модель (каталожная или импортированная).
 */
data class InstalledModel(
    val id: String,
    val category: ModelCategory,
    val displayName: String,
    val description: String,
    val runtime: RuntimeType,
    val sizeBytes: Long,
    val ramRequiredMb: Int,
    val languages: List<String>,
    val quality: Int,
    val speed: Int,
    val url: String,
    val urls: List<String>,
    val sha256: String?,
    /** Контрольные суммы по индексам [urls] (пустой список — legacy [sha256] для одного файла). */
    val sha256s: List<String?> = emptyList(),
    val license: String,
    val minAndroidSdk: Int,
    val recommendedRamMb: Int,
    val status: ModelStatus,
    val progress: Int,
    val filePath: String?,
    val bytesDone: Long,
    val isBuiltin: Boolean
) {
    val isReady: Boolean
        get() = status == ModelStatus.READY && !filePath.isNullOrBlank()

    /** Все URL для скачивания: urls (мультифайл) или url (один файл). */
    fun downloadUrls(): List<String> = resolveDownloadUrls(url, urls)
}

/**
 * Результат проверки возможности установки модели.
 */
sealed class InstallCheck {
    /** Установка возможна без замечаний. */
    object Ready : InstallCheck()

    /** Установка возможна, но с предупреждением (не блокирует). */
    data class Warning(val message: String) : InstallCheck()

    /** Установка невозможна. */
    data class Blocked(val reason: String) : InstallCheck()
}

/**
 * Ошибки рантаймов (LLM/STT/TTS).
 */
sealed class RuntimeError : Exception() {
    data class LoadFailed(val runtime: String, val reason: String) : RuntimeError()
    data class InferenceFailed(val runtime: String, val reason: String) : RuntimeError()

    /** Язык не поддерживается движком озвучивания (TTS). */
    data class LanguageNotSupported(val engine: String, val language: String) : RuntimeError()
    object ModelNotSelected : RuntimeError()
    object ModelNotReady : RuntimeError()

    /**
     * Ни один движок перевода не поддерживает [runtime] модели
     * (ревью #79): раньше роутер молча подставлял Marian как fallback,
     * и ошибочный тип рантайма TRANSLATION-модели переводился чужим
     * движком вместо явной ошибки.
     */
    data class UnsupportedRuntime(val runtime: String) : RuntimeError()

    /**
     * Активный пучок движка перевода загружен для другой языковой пары, чем
     * запрошено в translate (ревью #79): гонка между ensureLoaded другой
     * модели и translate не должна приводить к молчаливому переводу чужой
     * моделью.
     */
    data class DirectionMismatch(val runtime: String, val reason: String) : RuntimeError()
}

/**
 * Человекочитаемое сообщение об ошибке рантайма для показа в UI.
 * Имя userMessage — чтобы не затенять Throwable.message.
 */
fun RuntimeError.userMessage(): String = when (this) {
    is RuntimeError.LoadFailed -> "Не удалось загрузить модель: $reason"
    is RuntimeError.InferenceFailed -> "Ошибка: $reason"
    is RuntimeError.LanguageNotSupported -> "Язык не поддерживается движком озвучивания: $language"
    RuntimeError.ModelNotSelected -> "Модель не выбрана"
    RuntimeError.ModelNotReady -> "Модель не готова"
    is RuntimeError.UnsupportedRuntime -> "Неподдерживаемый тип движка модели: $runtime"
    is RuntimeError.DirectionMismatch -> "Модель загружена для другого направления перевода: $reason"
}

/**
 * Информация об устройстве для проверки совместимости.
 */
data class DeviceInfo(
    val sdkInt: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val freeDiskBytes: Long
) {
    /**
     * Суммарная ОЗУ, требуемая активными моделями, близка к лимиту.
     * Порог — доля общего объёма ОЗУ, вынесена в именованную константу
     * [NEAR_RAM_LIMIT_PERCENT] (ревью).
     */
    fun isNearRamLimit(requiredMb: Long): Boolean =
        requiredMb * 100 > totalRamMb * NEAR_RAM_LIMIT_PERCENT

    companion object {
        /** Процент общего объёма ОЗУ, при превышении которого модель считается «около лимита». */
        private const val NEAR_RAM_LIMIT_PERCENT = 70
    }
}
