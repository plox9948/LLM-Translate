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
     * [TtsModelSelector.DEFAULT_LANGUAGE_CARDS]).
     */
    val defaultForLanguage: String? = null
) {
    /** Лицензия требует подтверждения (некоммерческая). */
    val requiresLicenseConfirmation: Boolean
        get() {
            val upper = license.uppercase()
            return upper.contains("NC") || upper.contains("NON-COMMERCIAL")
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

/**
 * Кодирование списка по-URL SHA-256 в CSV-колонку БД: пустой элемент —
 * хеш неизвестен, пустая строка — пустой список.
 */
internal fun sha256sToCsv(sha256s: List<String?>): String =
    sha256s.joinToString(",") { it?.takeIf { value -> value.isNotBlank() }.orEmpty() }

/** Обратное к [sha256sToCsv] декодирование (пустая строка — пустой список). */
internal fun sha256sFromCsv(raw: String): List<String?> =
    if (raw.isEmpty()) emptyList() else raw.split(',').map { it.takeIf { value -> value.isNotBlank() } }

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
    /** Суммарная ОЗУ, требуемая активными моделями, близка к лимиту. */
    fun isNearRamLimit(requiredMb: Long): Boolean =
        requiredMb * 100 > totalRamMb * 70
}
