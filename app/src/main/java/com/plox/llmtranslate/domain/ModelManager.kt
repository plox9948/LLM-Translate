package com.plox.llmtranslate.domain

import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import kotlinx.coroutines.flow.Flow

/**
 * Единая точка входа для UI: каталог, установка, выбор и жизненный цикл моделей.
 */
interface ModelManager {

    /** Каталог моделей (из assets, опционально обогащённый удалённым источником). */
    val catalog: Flow<List<ModelCard>>

    /** Установленные модели; category == null — все категории. */
    fun installedModels(category: ModelCategory? = null): Flow<List<InstalledModel>>

    /** ID активной модели для категории (null, если не выбрана). */
    fun activeModelId(category: ModelCategory): Flow<String?>

    /** Активная модель для категории (null, если не выбрана или не готова). */
    fun activeModel(category: ModelCategory): Flow<InstalledModel?>

    /** Карта «код языка → id STT-модели»: STT-модель на каждый язык. */
    val sttByLanguage: Flow<Map<String, String>>

    /**
     * Назначить STT-модель языку (null — снять назначение).
     * Модель должна быть установлена и готова; иначе назначение игнорируется.
     */
    suspend fun setSttForLanguage(language: String, modelId: String?)

    /** Карта «код языка → id Piper TTS-модели»: TTS-модель на каждый язык (issue #50). */
    val ttsByLanguage: Flow<Map<String, String>>

    /**
     * Назначить Piper TTS-модель языку (null — снять назначение).
     * Модель должна существовать в каталоге, быть категории TTS с рантаймом
     * PIPER_ONNX, установлена и готова; иначе назначение игнорируется.
     */
    suspend fun setTtsForLanguage(language: String, modelId: String?)

    /** Свободно место на диске под модели. */
    val diskUsage: Flow<DiskUsage>

    /**
     * Модели, загруженные в ОЗУ рантаймами в текущий момент
     * (движки перевода + STT). Реактивно: новый список при каждой
     * загрузке/разгрузке; пуст после [releaseAllRuntimes].
     */
    val loadedModels: Flow<List<LoadedModelInfo>>

    /**
     * Выбрать активную модель категории.
     * @return true, если модель существует и выбрана.
     */
    suspend fun setActive(category: ModelCategory, modelId: String): Boolean

    /** Проверить, можно ли установить модель на это устройство. */
    fun checkInstall(card: ModelCard, device: DeviceInfo): InstallCheck

    /** Запустить скачивание модели (WorkManager, докачка). */
    fun startDownload(card: ModelCard)

    /** Отменить скачивание модели. */
    fun cancelDownload(modelId: String)

    /**
     * Импорт модели из SAF-uri (ACTION_OPEN_DOCUMENT_TREE или файл).
     * @return id импортированной модели или null при ошибке.
     */
    suspend fun importFromUri(uri: android.net.Uri): String?

    /**
     * Экспортировать модель в SAF-uri, выбранный пользователем.
      * Файл копируется напрямую; каталог (мультифайл ONNX/sherpa) — в zip.
     * @return true при успехе.
     */
    suspend fun exportToUri(modelId: String, targetUri: android.net.Uri): Boolean

    /** Удалить модель (файл + запись). */
    fun delete(modelId: String)

    /**
     * Освободить память: разгрузить все рантаймы (включая активные —
     * они будут заново загружены при следующем инференсе).
     * Вызывается [com.plox.llmtranslate.domain.BackgroundUnloadScheduler]
     * после ON_STOP процесса по истечении тайм-аута из настроек.
     * Асинхронно: разгрузка каждого
     * движка ожидает окончания активного инференса.
     */
    fun releaseAllRuntimes()

    /**
     * Информация об устройстве для проверки совместимости.
     * Чтение ActivityManager/StatFs блокирует — только на IO-диспетчере.
     */
    suspend fun deviceInfo(): DeviceInfo
}

/**
 * Использование диска моделями.
 */
data class DiskUsage(
    val totalBytes: Long,
    val modelsDir: String
)
