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
    SYSTEM_TTS,
    RHVOICE,
    ESPEAK
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
    val license: String,
    val minAndroidSdk: Int,
    val recommendedRamMb: Int
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
    object ModelNotSelected : RuntimeError()
    object ModelNotReady : RuntimeError()
}

/**
 * Человекочитаемое сообщение об ошибке рантайма для показа в UI.
 * Имя userMessage — чтобы не затенять Throwable.message.
 */
fun RuntimeError.userMessage(): String = when (this) {
    is RuntimeError.LoadFailed -> "Не удалось загрузить модель: $reason"
    is RuntimeError.InferenceFailed -> "Ошибка: $reason"
    RuntimeError.ModelNotSelected -> "Модель не выбрана"
    RuntimeError.ModelNotReady -> "Модель не готова"
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
