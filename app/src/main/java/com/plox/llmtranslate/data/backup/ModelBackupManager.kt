package com.plox.llmtranslate.data.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plox.llmtranslate.data.download.DownloadLayout
import com.plox.llmtranslate.data.download.ModelDownloadWorker
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.data.storage.ModelStorage
import com.plox.llmtranslate.domain.model.ArchiveFormats
import com.plox.llmtranslate.domain.model.InstallCheckLogic
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.util.isFileInsideDir
import com.plox.llmtranslate.util.sha256Hex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Чистый маппинг восстановительных операций поязыкового назначения
 * (ревью #53, уточнение ревью #5): операции строятся только по языкам
 * секции назначений бэкапа [saved] — язык, отсутствующий в частичном
 * архиве, остаётся как есть (снять назначение можно только для языка,
 * явно присутствующего в манифесте). [saved] == null (секции нет в старом
 * архиве) — операций нет, настройку не трогаем. Вынесено для unit-теста
 * без Android-специфики.
 */
internal fun assignmentOps(
    saved: Map<String, String?>?
): List<Pair<String, String?>> {
    if (saved == null) return emptyList()
    return saved.map { (lang, id) -> lang to id }
}

/**
 * Сверка контрольных сумм распакованной записи модели с хэшами манифеста
 * (ревью раунд 9 + ревью #3). Одиночный файл с непустым
 * [BackupModelEntry.sha256] — строгое сравнение SHA-256 байт файла с
 * hex-хэшем манифеста (регистронезависимо); пустой хэш (импортированная
 * модель) — сверки нет. Каталог-пучок — пофайловая сверка по
 * [BackupModelEntry.sha256s] (см. [bundleShaOk]); skip остаётся только
 * там, где сверка формально невозможна: archive-установки и gzip-артефакты.
 */
internal fun backupShaOk(entry: BackupModelEntry, file: File): Boolean {
    if (file.isDirectory) return bundleShaOk(entry, file)
    val expected = entry.sha256?.takeIf { it.isNotBlank() } ?: return true
    val actual = runCatching { sha256Hex(file) }.getOrNull() ?: return false
    return actual.equals(expected, ignoreCase = true)
}

/**
 * Пофайловая сверка каталога-пучка по [BackupModelEntry.sha256s]
 * (ревью #3: раньше каталоги не сверялись вовсе, хотя для пучков без
 * gz-артефактов хэши соответствуют байтам установленных файлов).
 * Индексы [BackupModelEntry.sha256s] соответствуют
 * [BackupModelEntry.urls], имена файлов ищутся по кандидатам раскладки
 * загрузок [DownloadLayout] (как при проверке импорта ZIP в
 * ModelManagerImpl.verifyImportChecksums). Пропуск сверки — только для
 * архивных URL (хэш относится к архиву до распаковки) и сжатых
 * gzip-артефактов slimt (хэш сжатых байт, на диске — распакованные
 * файлы, см. [ArchiveFormats.isCompressedArtifact]). Пустой sha у элемента
 * или отсутствие непустых sha в списке — сверять нечем, ok.
 */
private fun bundleShaOk(entry: BackupModelEntry, dir: File): Boolean {
    if (entry.urls.isEmpty() || entry.sha256s.none { hash -> !hash.isNullOrBlank() }) return true
    entry.urls.forEachIndexed { index, url ->
        val expected = entry.sha256s.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: return@forEachIndexed
        val fileName = DownloadLayout.sanitizedBasename(url, index)
        if (ArchiveFormats.isArchive(fileName) || ArchiveFormats.isCompressedArtifact(fileName)) {
            return@forEachIndexed
        }
        val candidates = DownloadLayout.installedFileCandidates(entry.id, url, index)
        val installed = candidates.asSequence()
            .map { name -> File(dir, name) }
            .firstOrNull { it.isFile }
        val actual = installed?.let { f -> runCatching { sha256Hex(f) }.getOrNull() }
        if (actual == null || !actual.equals(expected, ignoreCase = true)) return false
    }
    return true
}

/**
 * Проверка манифеста бэкапа на дубликаты записей: повторяющийся id или
 * повторяющийся relativePath. Дубль id — два upsert одной записи, где
 * поздний READY затирает ранний; дубль relativePath — две записи указывают
 * на один файл на диске, поздняя подмена (moveToTarget) тихо ломает пучок
 * ранней. Первая находка — описание дубликата, null — манифест чистый.
 * internal — покрыто unit-тестом без Android.
 */
internal fun findManifestDuplicate(models: List<BackupModelEntry>): String? {
    val seenIds = mutableSetOf<String>()
    val seenPaths = mutableSetOf<String>()
    for (entry in models) {
        if (!seenIds.add(entry.id)) {
            return "дубликат id модели «${entry.id}» в манифесте"
        }
        if (!seenPaths.add(entry.relativePath)) {
            return "дубликат пути модели «${entry.relativePath}» в манифесте (id ${entry.id})"
        }
    }
    return null
}

/**
 * Оценка места под установку записи бэкапа (ревью раунда 3): sizeBytes
 * манифеста — размер скачиваемых артефактов, без раздувания распаковки
 * archive (.tar.gz/.zip/.tar.bz2 -> x2) и gz-артефактов slimt (gunzip -> x1.8).
 * Тип раскладки определяется единой классификацией гейта скачивания
 * [InstallCheckLogic.urlLayoutFlags] (те же имена URL, что в UI-подсказке и
 * гейте ModelDownloadWorker; archive/gz-имена — [ArchiveFormats]), требуемый
 * объём — единой функцией [InstallCheckLogic.requiredDownloadFreeBytes]
 * (для одиночных файлов её запас 1.2x заменяет прежний x1.1 гейта импорта).
 * Пустые urls (импортированные модели) — одиночная раскладка.
 */
internal fun backupInstallRequiredBytes(entry: BackupModelEntry): Long {
    val (isArchiveLayout, isGzipLayout) = InstallCheckLogic.urlLayoutFlags(entry.urls)
    return InstallCheckLogic.requiredDownloadFreeBytes(
        entry.sizeBytes, isArchiveLayout, isGzipLayout
    )
}

/**
 * Префикс temp-каталога импорта бэкапа (соседствует с корнем моделей) и
 * temp-файла экспорта (кэш). Используются и генерацией, и стартовым GC
 * [cleanupOrphanBackupTemp] (issue #83, п.6).
 */
internal const val BACKUP_IMPORT_TEMP_PREFIX = "backup_import_"
internal const val BACKUP_EXPORT_TEMP_PREFIX = "backup_export_"

/**
 * Возраст осиротевших temp-артефактов бэкапа, удаляемых при старте
 * приложения (issue #83, п.6, по образцу осиротевших записей
 * AudioRecorder.cleanupOrphanRecordings): моложе порога — возможная живая
 * работа текущей сессии, не трогаем.
 */
internal const val BACKUP_TEMP_TTL_MS = 24 * 60 * 60 * 1000L

/**
 * Стартовый GC осиротевших temp-артефактов бэкапа (issue #83, п.6): каталоги
 * backup_import_* и файлы backup_export_*.zip, оставленные процессами
 * импорта/экспорта, убитыми до finally-очистки. [dirs] — каталоги-родители
 * (сосед корня моделей и кэш экспорта); отсутствующий/недоступный каталог
 * пропускается.
 * @return число удалённых артефактов. internal — покрыто unit-тестом на
 * временных файлах без Android.
 */
internal fun cleanupOrphanBackupTemp(
    dirs: List<File?>,
    ttlMs: Long = BACKUP_TEMP_TTL_MS,
    now: Long = System.currentTimeMillis()
): Int {
    var removed = 0
    for (dir in dirs) {
        val stale = dir?.listFiles()?.filter { f ->
            val name = f.name
            (name.startsWith(BACKUP_IMPORT_TEMP_PREFIX) ||
                name.startsWith(BACKUP_EXPORT_TEMP_PREFIX)) &&
                f.lastModified() < now - ttlMs
        } ?: continue
        for (f in stale) {
            if (f.deleteRecursively()) removed++
            else Timber.w("Не удалось удалить осиротевший temp-артефакт бэкапа: ${f.path}")
        }
    }
    if (removed > 0) {
        Timber.d("Удалено осиротевших temp-артефактов бэкапа: $removed")
    }
    return removed
}

/**
 * Best-effort каталог файловой системы, содержащий целевой SAF-документ,
 * по его document id — для StatFs-гейта места ДО усечения существующего
 * документа (issue #83, п.4). Поддержаны форматы ids провайдера внешнего
 * хранилища: "raw:<абсолютный путь>" и "primary:<путь относительно корня
 * тома>". Прочие провайдеры/форматы — null: оценить том произвольного
 * SAF-провайдера нельзя, но и отказывать экспорту из-за этого нельзя
 * (гейт пропускается с логом). internal — unit-тест без Android.
 */
internal fun dirHintFromDocumentId(docId: String?, externalRoot: File?): File? {
    docId ?: return null
    return when {
        docId.startsWith("raw:") ->
            File(docId.removePrefix("raw:")).takeIf { it.isAbsolute }?.parentFile
        docId.startsWith("primary:") ->
            externalRoot?.let { File(it, docId.removePrefix("primary:")).parentFile }
        else -> null
    }
}
/**
 * Наблюдение состояния живой скачивающей работы при импорте бэкапа
 * (регресс-ревью issue #83, п.4): QUERY_FAILED — запрос WorkManager не
 * выполнен (таймаут/сбой), живость работы НЕ подтверждена ни в одну
 * сторону.
 */
internal enum class ImportWorkObservation {
    LIVE,
    NOT_LIVE,
    QUERY_FAILED
}

/**
 * Политика подмены файлов модели при импорте бэкапа (регресс-ревью
 * issue #83, п.4): подмену разрешаем только при ПОДТВЕРЖДЁННОМ отсутствии
 * живой работы (терминальные статусы воркера). QUERY_FAILED означает, что
 * живой воркер не распознан: он позже завершится и cleanupCancelledModel
 * сотрёт восстановленный бандл, поэтому такую работу считаем живой и
 * подмену этой id пропускаем (скип с логом у вызывающего). internal —
 * политика покрыта unit-тестом без Android.
 */
internal fun importSwapAllowed(observation: ImportWorkObservation): Boolean =
    observation == ImportWorkObservation.NOT_LIVE

/**
 * Пост-cancel-политика опроса (ревью #83, итерация 3, п.4): когда отмена
 * УЖЕ отправлена, QUERY_FAILED при опросе не может «считать работу живой» —
 * заказанный cancel в любом случае доведёт воркер до его
 * cleanupCancelledModel, и подмену разрешаем. Запрет «не подтверждено =
 * живо» ([importSwapAllowed]) относится только к наблюдениям ДО cancel.
 * internal — покрыто unit-тестом без Android.
 */
internal fun postCancelImportSettled(observation: ImportWorkObservation): Boolean =
    observation != ImportWorkObservation.LIVE

/**
 * Лимит чтения служебных записей архива (manifest.json/settings.json), байт
 * (ревью аудита, п.4): заборстопленный архив с сотни-мегабайтным
 * распакованным манифестом вызывал OOM на readBytes() ДО любых гейтов места.
 * Манифест/настройки по своей природе килобайты — превышение лимита =
 * «битый архив». internal — лимит и ошибка покрыты unit-тестом.
 */
internal const val MANIFEST_ENTRY_READ_LIMIT_BYTES = 2L * 1024 * 1024

/**
 * Бюджет распаковки во втором проходе импорта, когда обе оценки размеров
 * недоступны: манифест с sizeBytes = 0 (легитимен для старых архивов) И
 * ZIP-записи без объявленных распакованных размеров (size = -1 — раскладка
 * local header с data descriptor, которой пишет и наш ZipOutputStream),
 * байт (ревью повт. аудита, п.2: прежний фиксированный floor 64 МБ
 * отклонял легитимные старые архивы с пучками в сотни МБ). Потолок завышен
 * столь сильно, чтобы пропускал штатные бэкапы в гигабайты, и служит
 * страховкой только от совсем уж безразмерных zip-bomb'ов.
 */
internal const val BACKUP_EXTRACT_UNKNOWN_BUDGET_BYTES = 6L * 1024 * 1024 * 1024

/**
 * Жёсткий лимит СУММАРНОГО объёма распаковки записей models/ (ревью аудита,
 * п.4 + ревью повт. аудита, п.2): StatFs-гейт считается по манифесту, а
 * второй проход раньше извлекал ВСЕ записи models/ вне зависимости от
 * манифеста — завышенный контент при маленьком манифесте писался до упора
 * диска без явной ошибки. Бюджет — МАКСИМУМ двух оценок с запасом 50 %:
 * оценки гейта места [backupInstallRequiredBytes] (уже включает раздувание
 * archive/gz-раскладок) и суммы ОБЪЯВЛЕННЫХ распакованных размеров записей
 * models/ из заголовков ZIP ([ZipEntry.getSize]; -1 — размер не объявлен,
 * в сумму не входит). Вторая оценка спасает легитимные старые архивы с
 * sizeBytes = 0 и пучками в сотни МБ. При недоступности обеих оценок —
 * фолбэк [BACKUP_EXTRACT_UNKNOWN_BUDGET_BYTES]. Выход за лимит — ошибка
 * импорта без касания существующих файлов моделей. internal — unit-тест.
 */
internal fun backupExtractLimitBytes(neededBytes: Long, declaredZipBytes: Long): Long =
    when {
        neededBytes > 0 && declaredZipBytes > 0 -> {
            val base = if (neededBytes >= declaredZipBytes) neededBytes else declaredZipBytes
            base + base / 2
        }
        neededBytes > 0 -> neededBytes + neededBytes / 2
        declaredZipBytes > 0 -> declaredZipBytes + declaredZipBytes / 2
        else -> BACKUP_EXTRACT_UNKNOWN_BUDGET_BYTES
    }

/**
 * Скопировать запись ZIP в [out] в пределах ОСТАТКА бюджета распаковки
 * [budgetRemaining], ОБЩЕГО для всех записей второго прохода
 * (zip-bomb моделей; ревью повт. аудита, п.1: счётчик больше не локален
 * на запись — иначе N записей суммарно писали N×лимит). [totalLimit] —
 * полный бюджет прохода, только для текста ошибки. Превышение —
 * [IllegalStateException] с текстом для UI: импорт прерывается до
 * регистрации моделей, temp-каталог чистится finally, существующие файлы
 * моделей не тронуты.
 * @return остаток бюджета для последующих записей прохода.
 */
internal fun copyEntryCapped(
    zip: ZipInputStream,
    out: OutputStream,
    budgetRemaining: Long,
    totalLimit: Long
): Long {
    val buffer = ByteArray(8192)
    var used = 0L
    while (true) {
        val n = zip.read(buffer)
        if (n < 0) break
        used += n
        if (used > budgetRemaining) {
            throw IllegalStateException(
                "Архив повреждён или слишком большой: суммарный объём распаковки " +
                    "моделей превысил расчётный лимит $totalLimit байт"
            )
        }
        out.write(buffer, 0, n)
    }
    return budgetRemaining - used
}

/**
 * Тестовая страховка (ревью #83, итерация 3, п.7 — замечание 6 пред.
 * раунда): «mkdirs temp-каталога ДО StatFs-гейта места» вынесено в
 * предикт [preparedTempDirBeforeSpaceGate], порядок вызовов фиксирует
 * константа-комментарий ниже (предикт вызывается строго до [hasFreeSpaceFor]).
 */
internal const val TEMP_MUST_EXIST_BEFORE_SPACE_GATE =
    "Temp-каталог распаковки обязан существовать ДО StatFs-гейта: на несуществующем пути " +
        "StatFs бросает исключение, и гейт места всегда «пропускался» (регресс issue #83)."

/** true, если temp-каталог распаковки доступен (см. [TEMP_MUST_EXIST_BEFORE_SPACE_GATE]). */
internal fun preparedTempDirBeforeSpaceGate(tempDir: File): Boolean =
    tempDir.mkdirs() || tempDir.isDirectory

/**
 * Решение каркаса [decideImportWorkSwap] о подмене файлов модели при
 * импорте бэкапа (ревью повт. аудита, п.7). Кроме самого запрета
 * различает, БЫЛ ЛИ ОТПРАВЛЕН cancel живой загрузки: в случае
 * [DENIED_AFTER_CANCEL] работа пользователя уже прервана, но запись
 * архива так и не восстановлена — факт фиксируется в [ImportResult] и
 * доводится до UI, чтобы тихая потеря загрузки была различима.
 */
internal enum class ImportSwapDecision {
    SWAP_ALLOWED,
    DENIED_WITHOUT_CANCEL,
    DENIED_AFTER_CANCEL
}

/**
 * Инъектируемый каркас решения [ModelBackupManager.cancelLiveDownloadForImport]
 * (ревью #83, итерация 3, п.4 и п.7): наблюдения, отмена, часы и интервалы
 * подаются параметрами — порядок «решение ДО cancel по [importSwapAllowed] →
 * cancel → первый ре-чек с паузой → опрос до терминала/дедлайна» покрыт
 * unit-тестом без WorkManager (в тестовом classpath его нет). Логика
 * идентична вызову из метода.
 */
internal suspend fun decideImportWorkSwap(
    modelId: String,
    firstObservation: ImportWorkObservation,
    query: () -> ImportWorkObservation,
    cancelLive: () -> Unit,
    nowMs: () -> Long,
    pollIntervalMs: Long,
    terminalTimeoutMs: Long
): ImportSwapDecision {
    // Решение ДО cancel — за importSwapAllowed: NOT_LIVE — живой работы не
    // было; QUERY_FAILED — состояние не подтверждено, cancel НЕ отправлен,
    // подмену запрещаем.
    if (firstObservation != ImportWorkObservation.LIVE) {
        if (!importSwapAllowed(firstObservation)) {
            Timber.w(
                "Подмена файлов $modelId пропущена: состояние живой загрузки " +
                    "подтвердить не удалось — работа считается живой"
            )
            return ImportSwapDecision.DENIED_WITHOUT_CANCEL
        }
        return ImportSwapDecision.SWAP_ALLOWED
    }
    cancelLive()
    val deadline = nowMs() + terminalTimeoutMs
    // Первый ре-чек после cancel — с паузой (итерация 3, п.4): немедленный
    // опрос почти наверняка ещё видит RUNNING — воркер не успевает
    // отреагировать на stop-request.
    delay(pollIntervalMs)
    var observation = query()
    while (observation == ImportWorkObservation.LIVE) {
        if (nowMs() >= deadline) {
            // Аудит: подтверждённый LIVE по истечении дедлайна — живой воркер
            // всё ещё работает, подмену НЕ разрешаем (KDoc importSwapAllowed:
            // только подтверждённое отсутствие живой работы). Запись
            // пропускается с логом; повторный импорт позже увидит уже
            // завершённую (отменённую) работу. Cancel при этом УЖЕ отправлен —
            // загрузка пользователя потеряна, фиксируется отдельно
            // (ревью повт. аудита, п.7).
            Timber.w(
                "Воркер загрузки $modelId не достиг терминального статуса " +
                    "через $terminalTimeoutMs мс после отмены — подмена запрещена"
            )
            return ImportSwapDecision.DENIED_AFTER_CANCEL
        }
        delay(pollIntervalMs)
        observation = query()
    }
    // Наблюдение != LIVE — терминальное (итерация 3, п.4), в т.ч.
    // QUERY_FAILED: отмена уже отправлена, поздняя очистка частей докачки
    // восстановленному бандлу не мешает.
    if (observation == ImportWorkObservation.QUERY_FAILED) {
        Timber.w(
            "Терминальный статус воркера $modelId после отмены не подтверждён — " +
                "подмену разрешаем: cancel уже отправлен"
        )
    }
    Timber.d("Живая загрузка $modelId отменена перед импортом бэкапа")
    return if (postCancelImportSettled(observation)) {
        ImportSwapDecision.SWAP_ALLOWED
    } else {
        ImportSwapDecision.DENIED_AFTER_CANCEL
    }
}

/**
 * Менеджер резервного копирования моделей и настроек.
 *
 * Формат архива:
 * - manifest.json — метаданные моделей
 * - settings.json — настройки (опционально)
 * - models/... — файлы моделей
 */
@Singleton
class ModelBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ModelRepository,
    private val runtimeRouter: RuntimeRouter,
    private val preferences: ModelPreferences,
    private val json: Json
) {

    companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val SETTINGS_NAME = "settings.json"
        const val MODELS_PREFIX = "models/"

        /** Таймаут запроса состояний unique-работы WorkManager, с. */
        private const val WORK_QUERY_TIMEOUT_SECONDS = 5L

        /** Интервал опроса терминального статуса после отмены загрузки, мс. */
        private const val CANCEL_POLL_INTERVAL_MS = 250L

        /** Предел ожидания терминального статуса отменённого воркера, мс. */
        private const val CANCEL_TERMINAL_TIMEOUT_MS = 10_000L
    }

    /**
     * Результат импорта.
     *
     * [downloadsInterrupted] (ревью повт. аудита, п.7): число живых загрузок
     * пользователя, которые импорт отменил ([cancelLiveDownloadForImport]),
     * но так и не заменил извлечённой моделью (воркер не достиг терминального
     * статуса к дедлайну, или подмена файлов не удалась уже после отмены) —
     * иначе пользователь тихо теряет и загрузку, и импорт. При успешной
     * подмене отмена не считается потерей: содержимое модели пришло из
     * архива.
     */
    data class ImportResult(
        val importedCount: Int,
        val skippedCount: Int,
        val settingsRestored: Boolean,
        val error: String? = null,
        val downloadsInterrupted: Int = 0
    )

    /**
     * Экспортировать все модели и настройки в ZIP по Uri.
     *
     * Ревью #4: архив сначала собирается целиком во временный файл, и только
     * готовый копируется в выходной Uri ОДНИМ потоком copy — ошибка сериализации
     * ZIP не оставляет в целевом документе полусжатый поток, и обрыв SAF-потока
     * или ENOSPC не оставляет пользователю «полусжатый» архив от потоковой
     * сборки. Ревью (раунд повторный): temp пишется в getExternalCacheDir()
     * (фолбэк cacheDir) — пик «модели + ZIP» не должен целиком ложиться на
     * /data; перед сборкой — StatFs-гейт тома temp (суммарный размер
     * включаемых файлов x1.1). Временный файл удаляется в любом случае.
     *
     * Issue #83, п.4 (ограничение SAF — фиксируем поведение явно):
     * openOutputStream УСЕКАЕТ уже существующий документ до записи — SAF не
     * даёт атомарно подменить документ целиком, поэтому «старый рабочий
     * архив» при обрыве копирования полностью сохранить нельзя. Минимум
     * надёжности ДО усечения: StatFs-гейт свободного места на целевом volume
     * ([targetVolumeDir]/[dirHintFromDocumentId], аналог [hasFreeSpaceFor]) —
     * если место заведомо кончится, экспорт отказывает, не трогая старый
     * архив; для провайдера, чей том оценить нельзя, гейт пропускается с
     * предупреждением. При обрыве уже начатого копирования недописанный
     * документ удаляется ПОСЛЕ попытки удаления с логом результата
     * ([deleteFailedDocument]) — не молча.
     *
     * Ревью аудита, п.1 (уточнение ревью повт. аудита, п.8а): штатная отмена
     * корутины ([CancellationException]) не глотается — пробрасывается
     * наверх. При отмене недописанный SAF-документ не удаляется (в отличие
     * от ошибочного обрыва копирования), но он и НЕ целый: семантика
     * [android.content.ContentResolver.openOutputStream] усекает документ
     * ещё при открытии потока, поэтому после отмены пользователь видит
     * УСЕЧЁННЫЙ (пустой/недописанный) документ — прежний архив уже потерян,
     * «отмена ничего не портит» здесь неверно.
     *
     * Ревью аудита, п.2: обход файлов и упаковка моделей во временный ZIP
     * выполняются под общим [ModelImportLock] — иначе живой импорт/удаление/
     * отмена скачивания rename-ит файлы во время обхода, и в архив попадает
     * смесь старой/новой установки при sha256 манифеста из прежнего снимка
     * БД (при восстановлении [backupShaOk] молча скипает такую модель).
     * Лок берётся через [kotlinx.coroutines.sync.Mutex.tryLock] без
     * ожидания: долгий импорт бэкапа не блокирует экспорт — при занятом
     * локе экспорт отказывает с логом, пользователь повторяет позже.
     * Снимается сразу после упаковки temp-архива (SAF-копирование файлов
     * моделей не касается), а при исключении/отмене в критическом участке —
     * во finally по флагу, лок не остаётся захваченным.
     *
     * Осознанный долг (ревью повт. аудита, п.8б/в): упаковка всего набора
     * READY-пучков под [ModelImportLock] длится минуты — в это время
     * удаление моделей, отмена скачивания и импорт той же id ждут тот же
     * лок и в UI выглядят «зависшими» без объяснения причины. Снятие
     * упаковки из-под лока требует согласованного файлового снимка (иначе
     * в архив вернётся смесь установок) — отложено. Отказ tryLock также
     * неразличим в UI: экспорт возвращает тот же false, что и прочие сбои
     * («Ошибка экспорта бэкапа» без подсказки «повторите позже»), причина
     * видна только в логе.
     * @return true при успехе.
     */
    suspend fun exportToUri(targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Внешний кэш не выделяется (отсутствует примонтованный volume) —
        // фолбэк на внутренний cacheDir.
        val tempRoot = context.externalCacheDir?.takeIf { it.exists() || it.mkdirs() }
            ?: context.cacheDir
        // Уникальный суффикс: параллельные экспорты не затирают общий temp.
        val tempZip = File(tempRoot, BACKUP_EXPORT_TEMP_PREFIX + UUID.randomUUID() + ".zip")
        // Копирование в Uri уже началось — по исключению документ обрезан.
        var outputOpened = false
        // Лок моделей снят штатно сразу после упаковки архива; флаг нужен,
        // чтобы при исключении/отмене в критическом участке он освобождался
        // в finally.
        var modelsLocked = false
        try {
            if (!ModelImportLock.mutex.tryLock()) {
                Timber.w(
                    "Экспорт бэкапа отклонён: выполняется подмена файлов моделей " +
                        "(импорт/удаление/отмена скачивания) — повторите позже"
                )
                return@withContext false
            }
            modelsLocked = true

            val readyModels = repository.observeAll().first().filter { it.isReady }
            // Инвариант READY⇒filePath (ревью аудита, п.5): READY-запись без
            // filePath экспортировалась с пустым relativePath, а импорт
            // резолвил "" в сам корень моделей (moveToTarget переносил
            // temp-каталог поверх корня) — такую запись пропускаем с логом.
            val models = readyModels.filter { model ->
                if (model.filePath.isNullOrBlank()) {
                    Timber.w(
                        "Модель ${model.id} пропущена экспортом: READY-запись без filePath"
                    )
                    false
                } else {
                    true
                }
            }
            if (models.isEmpty()) {
                Timber.w("Нет моделей для экспорта")
                return@withContext false
            }

            // Гейт свободного объёма под temp-ZIP до сборки: ZIP почти не
            // сжимает бинарные модели, требуется ~суммарный размер файлов
            // (x1.1 запас); нехватка — понятный отказ до начала записи.
            val neededBytes = includedContentBytes(models) * 11 / 10
            if (!hasFreeSpaceFor(tempRoot, neededBytes)) {
                Timber.e(
                    "Недостаточно места под временный архив бэкапа: требуется ~$neededBytes " +
                        "байт на томе ${tempRoot.absolutePath}"
                )
                return@withContext false
            }

            val sources = buildManifestEntries(models)
            val manifest = BackupManifest(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                models = sources.map { it.entry }
            )

            tempZip.outputStream().buffered().use { out ->
                ZipOutputStream(out).use { zip ->
                    // 1. Манифест.
                    zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                    zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                    zip.closeEntry()

                    // 2. Настройки.
                    val settings = buildSettings()
                    zip.putNextEntry(ZipEntry(SETTINGS_NAME))
                    zip.write(json.encodeToString(BackupSettings.serializer(), settings).toByteArray())
                    zip.closeEntry()

                    // 3. Файлы моделей — по парам «запись БД <-> запись
                    // манифеста» (issue #83, п.8: раньше связь держалась
                    // индексом forEachIndexed; после дедупликации путей,
                    // ревью #2) — иначе ZIP-запись и манифест могут разойтись.
                    sources.forEach { source ->
                        val file = source.model.filePath?.let(::File) ?: return@forEach
                        if (!file.exists()) return@forEach
                        if (file.isDirectory) {
                            writeDirectoryToZip(zip, file, source.entry.relativePath)
                        } else {
                            writeFileToZip(zip, file, source.entry.relativePath)
                        }
                    }
                }
            }
            // Снимак файлов упакован — лок моделей больше не нужен
            // (остальная часть метода работает только с temp-файлом и Uri).
            ModelImportLock.mutex.unlock()
            modelsLocked = false

            // Гейт целевого volume ДО открытия выходного потока (issue #83,
            // п.4): openOutputStream усечёт существующий SAF-документ — если
            // на целевом томе не хватит места даже для готового архива,
            // старый рабочий архив был бы уничтожен зря.
            val archiveBytes = tempZip.length()
            val targetDir = targetVolumeDir(targetUri)
            if (targetDir == null) {
                Timber.w(
                    "Том целевого документа бэкапа не определим — гейт места перед " +
                        "усечением существующего документа пропущен: $targetUri"
                )
            } else if (!hasFreeSpaceFor(targetDir, archiveBytes)) {
                Timber.e(
                    "Недостаточно места на томе целевого документа: требуется ~$archiveBytes " +
                        "байт на ${targetDir.absolutePath} — отказ до усечения документа"
                )
                return@withContext false
            }

            val output = context.contentResolver.openOutputStream(targetUri)
                ?: return@withContext false
            outputOpened = true
            try {
                output.use { out ->
                    tempZip.inputStream().use { it.copyTo(out) }
                }
            } catch (e: CancellationException) {
                // Ревью аудита, п.1: отмена копирования — не «обрыв с
                // недописанным мусором»: документ и так усечён семантикой
                // SAF, но удалять его отменённой операцией нельзя —
                // просто пробрасываем отмену.
                throw e
            } catch (e: Exception) {
                // Обрыв copyTo: в Uri остался усечённый документ — удаляем его
                // с логом (не молча), иначе пользователь получит «битый архив»
                // вместо отсутствия файла. Прежний архив при этом уже потерян:
                // SAF усекает документ при открытии потока, см. KDoc метода.
                Timber.e(e, "Обрыв копирования бэкапа в $targetUri — удаляем недописанный документ")
                if (outputOpened) deleteFailedDocument(targetUri)
                throw e
            }

            Timber.d("Экспортировано ${models.size} моделей")
            true
        } catch (e: CancellationException) {
            // Ревью аудита, п.1: штатная отмена корутины (scope) — не
            // «ошибка экспорта»; temp и лок освобождаются в finally.
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Ошибка экспорта бэкапа")
            false
        } finally {
            if (modelsLocked) ModelImportLock.mutex.unlock()
            tempZip.delete()
        }
    }

    /**
     * Каталог тома, на котором лежит целевой документ (для гейта места до
     * усечения, issue #83, п.4). file:// — путь напрямую; content:// —
     * best-effort разбор document id внешнего хранилища
     * ([dirHintFromDocumentId]); прочие провайдеры и сбои — null (гейт
     * пропускается с логом в вызывающем).
     */
    private fun targetVolumeDir(uri: Uri): File? = try {
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it).parentFile }
            "content" -> dirHintFromDocumentId(
                DocumentsContract.getDocumentId(uri),
                Environment.getExternalStorageDirectory()
            )
            else -> null
        }
    } catch (e: Exception) {
        Timber.w(e, "Не удалось определить том целевого документа бэкапа: $uri")
        null
    }

    /** Суммарный размер файлов, попадающих в ZIP (кандидат гейта temp-объёма). */
    private fun includedContentBytes(models: List<InstalledModel>): Long =
        models.sumOf { model ->
            val file = model.filePath?.let(::File) ?: return@sumOf 0L
            when {
                file.isFile -> file.length()
                file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                else -> 0L
            }
        }

    /**
     * Достаточно ли свободного места на томе [dir] под [neededBytes].
     * Неготовность оценки (StatFs-исключение — в т.ч. unit-test JVM без
     * android.os) не блокирует экспорт (true), по образцу
     * ModelManagerImpl.hasEnoughFreeSpace.
     */
    private fun hasFreeSpaceFor(dir: File, neededBytes: Long): Boolean = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong >= neededBytes
    } catch (e: Exception) {
        Timber.w(e, "Не удалось оценить свободное место под архив бэкапа — гейт пропущен")
        true
    }

    /**
     * Удалить SAF-документ, оставшийся недописанным после обрыва копирования.
     * Работает для document Uri из SAF-пикера; для прочих Uri (или при
     * отказе самого провайдера) — только лог.
     */
    private fun deleteFailedDocument(targetUri: Uri) {
        try {
            if (!DocumentsContract.deleteDocument(context.contentResolver, targetUri)) {
                Timber.w("Недописанный SAF-документ бэкапа не удалён: $targetUri")
            }
        } catch (e: Exception) {
            Timber.w(e, "Не удалось удалить недописанный SAF-документ бэкапа: $targetUri")
        }
    }
    /**
     * Импортировать модели и настройки из ZIP по Uri.
     *
     * Issue #83, п.1: всё тело импорта под общим [ModelImportLock] — тем же,
     * под которым ModelManagerImpl выполняет одиночный/ZIP-импорт, удаление и
     * отмену скачивания. Без него импорт бэкапа гонял moveToTarget/upsert с
     * живым [ModelDownloadWorker] или параллельным импортом той же id, порча
     * восстановленную READY-запись. Перед подменой файлов каждой id из
     * манифеста её живая загрузка отменяется и ожидается терминальный статус
     * воркера ([cancelLiveDownloadForImport], по образцу
     * ModelManagerImpl.cancelSupersededDownload).
     *
     * Issue #83, п.5: распаковка ДВУХПРОХОДНАЯ. Первый проход читает из архива
     * только manifest.json/settings.json, НИЧЕГО не записывая, поэтому
     * StatFs-гейт места по оценке манифеста гарантированно выполняется ДО
     * появления файлов models/ в temp (раньше при манифесте в конце архива
     * файлы уже писались, и при отказе места оставались до finally; а при
     * манифесте с путями внутри models/ фолбэк [findExtractedEntry] мог
     * резолвить пучок в сырой файл архивной раскладки). Второй проход
     * (повторное открытие Uri — SAF это разрешает) извлекает только записи
     * models/.
     *
     * Ревью повт. аудита, п.6: temp-распаковка пишется на тот же volume,
     * что и каталог моделей ([ModelStorage.modelsRoot]) — раньше шла в
     * context.cacheDir (внутренний /data) при моделях на внешнем разделе,
     * поэтому [moveToTarget] всегда падал к cross-volume copy, а StatFs-гейт
     * объёма вообще не проверялся на импорте. Temp кладётся соседом корня
     * моделей (по образцу staging в ModelManagerImpl.promoteExtractedDir);
     * оценка гейта учитывает раздувание распаковки archive/gz-раскладок
     * той же формулой, что гейт скачивания (ревью раунда 3),
     * [backupInstallRequiredBytes]. Осиротевшие от убитого процесса
     * temp-каталоги чистит стартовый GC [cleanupOrphanBackupTemp].
     *
     * Ревью аудита, п.4: чтение manifest.json/settings.json ограничено
     * [MANIFEST_ENTRY_READ_LIMIT_BYTES] (заборстопленный архив с огромным
     * манифестом = «битый архив» до любых гейтов), а второй проход
     * извлекает models/ в пределах СУММАРНОГО бюджета
     * [backupExtractLimitBytes] (максимум оценок манифеста и заголовков
     * ZIP; остаток перетекает между записями прохода, ревью повт. аудита
     * п.1/п.2) — выход за лимит прерывает импорт ошибкой, существующие
     * файлы моделей не тронуты.
     *
     * Ревью аудита, п.1: штатная отмена корутины ([CancellationException])
     * пробрасывается наверх, а не превращается в «частичный импорт» с
     * текстом ошибки; temp-каталог чистится в finally.
     */
    suspend fun importFromUri(sourceUri: Uri): ImportResult =
        ModelImportLock.mutex.withLock {
            withContext(Dispatchers.IO) { importLocked(sourceUri) }
        }

    /** Тело импорта под [ModelImportLock] — см. [importFromUri]. */
    private suspend fun importLocked(sourceUri: Uri): ImportResult {
        var imported = 0
        var skipped = 0
        var settingsRestored = false
        // Ревью повт. аудита, п.7: отменённые импортом и не заменённые
        // загрузки пользователя — факт доводится до UI ([ImportResult]).
        var downloadsInterrupted = 0
        // Уникальный суффикс temp-каталога: два параллельных импорта бэкапа
        // не должны делить один каталог (каждый чистит в finally свой).
        // Temp — сосед каталога моделей (тот же volume), а не cacheDir:
        // иначе moveToTarget всегда уходит в cross-volume copy, а
        // StatFs-гейт оценивает не тот том (ревью повт. аудита, п.6).
        val modelsRoot = ModelStorage.modelsRoot(context)
        val tempRoot = modelsRoot.parentFile ?: modelsRoot
        val tempDir = File(tempRoot, BACKUP_IMPORT_TEMP_PREFIX + UUID.randomUUID())

        try {
            // --- Проход 1: только метаданные (manifest + settings), файлов
            // models/ не касаемся (issue #83, п.5). Заодно накапливаем
            // объявленные распакованные размеры записей models/ из заголовков
            // ZIP — вторая оценка бюджета второго прохода (ревью повт.
            // аудита, п.2); записи без объявления размера (size = -1) не
            // учитываются, фолбэк — по манифесту/потолку. ---
            var manifest: BackupManifest? = null
            var settings: BackupSettings? = null
            var declaredZipModelBytes = 0L
            if (!forEachZipEntry(sourceUri) { zip, entry ->
                    when {
                        entry.isDirectory -> Unit
                        entry.name == MANIFEST_NAME ->
                            manifest = json.decodeFromString(
                                BackupManifest.serializer(),
                                readEntryCapped(zip, MANIFEST_NAME)
                            )
                        entry.name == SETTINGS_NAME ->
                            settings = json.decodeFromString(
                                BackupSettings.serializer(),
                                readEntryCapped(zip, SETTINGS_NAME)
                            )
                        entry.name.startsWith(MODELS_PREFIX) && entry.size >= 0 ->
                            declaredZipModelBytes += entry.size
                    }
                }) {
                return ImportResult(0, 0, false, "Не удалось открыть архив")
            }

            val manifestData = manifest
                ?: return ImportResult(0, 0, false, "Нет манифеста в архиве")

            // Дубликаты id/relativePath в манифесте — архив отклоняется ЯВНО
            // до любых записей: иначе поздняя запись той же id/path тихо
            // затирает раннюю (READY-подмена одного файла двумя записями).
            findManifestDuplicate(manifestData.models)?.let { duplicate ->
                Timber.e("Архив бэкапа отклонён: $duplicate")
                return ImportResult(0, 0, false, "Архив повреждён: $duplicate")
            }

            // tempDir создаётся ДО гейта места (регресс-ревью issue #83;
            // порядок зафиксирован предиктом [preparedTempDirBeforeSpaceGate]
            // и константой [TEMP_MUST_EXIST_BEFORE_SPACE_GATE]): StatFs на
            // ещё не существующем пути бросает исключение, и гейт формально
            // «пропускался» всегда. mkdirs() сам файлов моделей не пишет —
            // гарантию двухпроходности (гейт ДО появления записей models/ в
            // temp) это не нарушает.
            if (!preparedTempDirBeforeSpaceGate(tempDir)) {
                Timber.e(
                    "Не удалось создать временный каталог распаковки: ${tempDir.absolutePath}"
                )
                return ImportResult(
                    0, 0, false,
                    "Не удалось создать временный каталог распаковки бэкапа"
                )
            }

            // StatFs-гейт ПОСЛЕ чтения манифеста и ДО записи файлов моделей
            // (двухпроходность, issue #83, п.5): sizeBytes манифеста — размер
            // СКАЧИВАЕМЫХ артефактов, а на диск ложится распакованное
            // содержимое archive (x2) и gz (x1.8) раскладок. Оценка — единой
            // формулой гейта скачивания InstallCheckLogic
            // ([backupInstallRequiredBytes]; архивные/gz имена — те же
            // ArchiveFormats и DownloadLayout, что в bundleShaOk); том — тот,
            // где лежит temp (он же том каталога моделей). Нехватка — явный
            // отказ до записи файла модели.
            val neededBytes = manifestData.models.sumOf { entry ->
                backupInstallRequiredBytes(entry)
            }
            if (!hasFreeSpaceFor(tempDir, neededBytes)) {
                Timber.e(
                    "Недостаточно места под распаковку бэкапа: " +
                        "требуется ~$neededBytes байт на томе " +
                        "${tempDir.absolutePath}"
                )
                return ImportResult(
                    0, 0, false,
                    "Недостаточно свободного места для распаковки бэкапа"
                )
            }

            // --- Проход 2: только файлы моделей (повторное открытие Uri —
            // SAF это разрешает). ---
            // Ревью аудита, п.4 + ревью повт. аудита, п.1: второй проход
            // извлекает ВСЕ records models/ вне зависимости от манифеста,
            // а гейт места считается по манифесту — СУММАРНЫЙ объём
            // распаковки ограничен бюджетом [backupExtractLimitBytes]
            // (максимум оценки манифеста и объявленных размеров ZIP + 50 %);
            // остаток бюджета ТЕЧЁТ между записями прохода (не на запись!),
            // выход за лимит = ошибка импорта (существующие файлы моделей
            // ещё не тронуты, temp чистится finally).
            val extractLimitBytes = backupExtractLimitBytes(neededBytes, declaredZipModelBytes)
            var extractBudgetRemaining = extractLimitBytes
            if (!forEachZipEntry(sourceUri) { zip, entry ->
                    val name = entry.name
                    if (!entry.isDirectory && name.startsWith(MODELS_PREFIX)) {
                        // Сохраняем файл во временный каталог.
                        val outFile = File(tempDir, name)
                        // Защита от zip-slip: файл не должен выходить
                        // за пределы временного каталога.
                        if (!isFileInsideDir(tempDir, outFile)) {
                            Timber.w("ZIP-запись вне каталога, пропущена: $name")
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                extractBudgetRemaining =
                                    copyEntryCapped(zip, out, extractBudgetRemaining, extractLimitBytes)
                            }
                        }
                    }
                }) {
                return ImportResult(0, 0, false, "Не удалось повторно открыть архив для файлов моделей")
            }

            // Создаём записи моделей.
            val restoredIds = mutableSetOf<String>()
            manifestData.models.forEach { entry ->
                // Контрольный аудит: id из манифеста — внешние данные, он
                // попадает в путь каталога записи (DownloadLayout.modelDir)
                // и в очистки с deleteRecursively — небезопасный id с «/»,
                // «..» удалял/подменял файлы вне каталога рантайма.
                // Такая запись отклоняется явно, без тихой санитизации.
                if (!DownloadLayout.isSafeModelId(entry.id)) {
                    Timber.w(
                        "Модель из архива пропущена: id «${entry.id}» " +
                            "содержит недопустимые файловые символы"
                    )
                    skipped++
                    return@forEach
                }
                // Ревью аудита, п.5: пустой relativePath ( READY-запись без
                // filePath в источнике старого архива) резолвился бы в сам
                // корень моделей, а findExtractedEntry(tempDir, "") — во весь
                // temp-каталог, и moveToTarget перенёс бы его поверх корня.
                if (entry.relativePath.isBlank()) {
                    Timber.w("Модель из архива пропущена: пустой relativePath в манифесте (id ${entry.id})")
                    skipped++
                    return@forEach
                }
                // Путь из манифеста может выйти за пределы каталога моделей.
                val targetPath = resolveTargetPath(entry)
                if (targetPath == null) {
                    Timber.w("Некорректный путь модели в архиве: ${entry.relativePath}")
                    skipped++
                    return@forEach
                }
                // relativePath в манифестах текущей версии уже начинается с
                // MODELS_PREFIX (см. toRelativePath); вариант с префиксом —
                // fallback для манифестов, где путь записан без него.
                val tempFile = findExtractedEntry(tempDir, entry.relativePath)

                if (tempFile == null) {
                    Timber.w("Файл модели не найден в архиве: ${entry.relativePath}")
                    skipped++
                    return@forEach
                }

                // Контрольная сумма из манифеста (ревью раунд 9, ревью #3):
                // битый файл модели в архиве не должен регистрироваться READY.
                // Одиночные файлы сверяются с sha256, каталоги-пучки —
                // пофайлово с sha256s; без сверки остаются только archive-
                // установки и gzip-артефакты (хэш относится к скачанному
                // архиву/сжатым байтам, а не к содержимому), см. [backupShaOk].
                if (!backupShaOk(entry, tempFile)) {
                    Timber.w(
                        "SHA-256 файла модели ${entry.id} в архиве не совпала с манифестом — пропуск"
                    )
                    skipped++
                    return@forEach
                }

                // Одна битая запись (например, category/runtime из будущей версии
                // приложения) не должна прерывать весь импорт — пропускаем её.
                val model = try {
                    InstalledModel(
                        id = entry.id,
                        category = ModelCategory.valueOf(entry.category),
                        displayName = entry.displayName,
                        description = entry.description,
                        runtime = RuntimeType.valueOf(entry.runtime),
                        sizeBytes = entry.sizeBytes,
                        ramRequiredMb = entry.ramRequiredMb,
                        languages = entry.languages,
                        quality = entry.quality,
                        speed = entry.speed,
                        url = entry.url,
                        urls = entry.urls,
                        sha256 = entry.sha256,
                        sha256s = entry.sha256s,
                        license = entry.license,
                        minAndroidSdk = entry.minAndroidSdk,
                        recommendedRamMb = entry.recommendedRamMb,
                        status = ModelStatus.READY,
                        progress = 100,
                        filePath = targetPath,
                        bytesDone = entry.sizeBytes,
                        isBuiltin = entry.isBuiltin
                    )
                } catch (e: IllegalArgumentException) {
                    Timber.w("Запись ${entry.id} пропущена: неизвестные данные архива (${e.message})")
                    skipped++
                    return@forEach
                }

                // Issue #83, п.1: перед подменой файлов отменяем живую загрузку
                // той же id — иначе WorkManager-воркер дописывает .part и
                // переименовывает цели раскладки поверх восстановленного пучка
                // (гейт finalizeDownload по статусу защищает только запись БД).
                // Регресс-ревью #83: если состояние работы подтвердить не удалось
                // (таймаут/сбой запроса WorkManager), воркер считается живым и
                // подмена этой id пропускается — иначе cleanupCancelledModel
                // воркера позже удаляет уже восстановленный бандл.
                // Ревью повт. аудита, п.7: если cancel УЖЕ отправлен, но
                // терминального статуса не дождались — загрузка пользователя
                // прервана без замещения; факт считаем в [downloadsInterrupted].
                when (val swapDecision = cancelLiveDownloadForImport(entry.id)) {
                    ImportSwapDecision.SWAP_ALLOWED -> Unit
                    else -> {
                        if (swapDecision == ImportSwapDecision.DENIED_AFTER_CANCEL) {
                            downloadsInterrupted++
                        }
                        skipped++
                        return@forEach
                    }
                }

                // Issue #70: импорт подменяет файлы модели той же id — eviction
                // кэша движков по образцу promoteExtractedDir (#69) ДО подмены:
                // иначе LRU-кэш по id продолжает инференс старым нативным
                // handle до перезапуска процесса, а удаление прежнего файла
                // снимает живой mmap. Порядок: release -> подмена -> upsert.
                repository.getById(entry.id)?.let { runtimeRouter.releaseModel(it) }

                // Переименовываем временный файл в целевой.
                if (moveToTarget(tempFile, targetPath)) {
                    repository.upsert(model)
                    restoredIds += entry.id
                    imported++
                } else {
                    skipped++
                }
            }

            // Восстанавливаем настройки.
            val settingsData = settings
            if (settingsData != null) {
                restoreSettings(settingsData, restoredIds)
                settingsRestored = true
            }

            return ImportResult(imported, skipped, settingsRestored, downloadsInterrupted = downloadsInterrupted)
        } catch (e: CancellationException) {
            // Ревью аудита, п.1: отмена scope — не «частичный импорт с
            // e.message»; пробрасываем отмену (temp удалится в finally).
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Ошибка импорта бэкапа")
            return ImportResult(
                imported, skipped, settingsRestored,
                e.message ?: "Ошибка импорта",
                downloadsInterrupted
            )
        } finally {
            // Временный каталог удаляется в любом случае; осиротевшие от
            // убитого процесса каталоги чистит стартовый GC (issue #83, п.6).
            tempDir.deleteRecursively()
        }
    }

    /**
     * Отменить живую загрузку модели [modelId] перед подменой её файлов из
     * бэкапа (issue #83, п.1, по образцу
     * ModelManagerImpl.cancelSupersededDownload): живой unique-воркер после
     * upsert продолжил бы дописывать .part и переименовывать целевые файлы
     * раскладки этой id поверх восстановленного пучка. После cancel ждём
     * терминального статуса — отменённый, но ещё не прерванный воркер иначе
     * дописывает файлы уже после moveToTarget. Части докачки (.part/.part.meta)
     * чистит сам воркер в cleanupCancelledModel при отмене.
     *
     * Регресс-ревью #83, п.4: если состояние работы подтвердить нельзя ДО
     * отмены (таймаут/сбой запроса WorkManager), воркер считается ЖИВЫМ и
     * подмена файлов этой id пропускается ([importSwapAllowed]) — вслепую
     * восстановленный бандл был уничтожен поздним cleanupCancelledModel
     * отменённого воркера.
     *
     * Ревью #83 (итерация 3, п.4): после ОТПРАВЛЕННОГО cancel QUERY_FAILED
     * при опросе трактуется как терминальный исход ([postCancelImportSettled],
     * подмену разрешаем) — запрет «не подтверждено = живо» действует только
     * для наблюдения до cancel; первый ре-чек после cancel выполняется с
     * паузой [CANCEL_POLL_INTERVAL_MS]. Решение вынесено в
     * [decideImportWorkSwap] (тестируется без WorkManager).
     *
     * Ревью аудита, п.3: подтверждённый LIVE по истечении
     * [CANCEL_TERMINAL_TIMEOUT_MS] — подмену НЕ разрешаем (fail-closed,
     * живой воркер ещё работает); ранее этот путь молча возвращал true,
     * противореча KDoc [importSwapAllowed]. Ревью повт. аудита, п.7: этот
     * случай отличается от отказа ДО cancel —cancel уже отправлен и живая
     * загрузка пользователя потеряна, поэтому решение различает
     * [ImportSwapDecision.DENIED_AFTER_CANCEL] (факт попадает в число
     * [ImportResult.downloadsInterrupted] и сообщение UI).
     *
     * @return [ImportSwapDecision.SWAP_ALLOWED], если подмену файлов
     *         выполнять можно (живой работы не было; либо она отменена и
     *         достигла терминального статуса или состояние после отмены не
     *         подтвердилось); [ImportSwapDecision.DENIED_WITHOUT_CANCEL] —
     *         состояние неподтверждено ДО cancel (cancel не отправлялся);
     *         [ImportSwapDecision.DENIED_AFTER_CANCEL] — cancel отправлен,
     *         терминальный статус не дождён. internal — покрыто
     *         unit-тестом каркаса [decideImportWorkSwap].
     */
    internal suspend fun cancelLiveDownloadForImport(modelId: String): ImportSwapDecision =
        decideImportWorkSwap(
            modelId = modelId,
            firstObservation = queryImportWorkObservation(modelId),
            query = { queryImportWorkObservation(modelId) },
            cancelLive = { ModelDownloadWorker.cancel(context, modelId) },
            nowMs = System::currentTimeMillis,
            pollIntervalMs = CANCEL_POLL_INTERVAL_MS,
            terminalTimeoutMs = CANCEL_TERMINAL_TIMEOUT_MS
        )

    /**
     * Наблюдение unique-работы [ModelDownloadWorker] модели (ENQUEUED/RUNNING/
     * BLOCKED — LIVE; см. ModelManagerImpl.isWorkLive). Таймаут/сбой запроса
     * к инициализированному WorkManager — QUERY_FAILED: состояние не
     * подтверждено, решение о подмене принимает [importSwapAllowed]
     * (регресс-ревью #83, п.4).
     *
     * Ревью аудита, п.3: неинициализированный WorkManager
     * (IllegalStateException) — тоже QUERY_FAILED, а не NOT_LIVE:
     * unique-работы переживают перезапуск процесса и состояние живой
     * загрузки такой сборкой не подтверждается — fail-closed, подмена
     * этой id пропускается (повтор импорта позже увидит инициализированный
     * WorkManager и реальное состояние).
     * internal — покрытие unit-тестом с явным моком WorkManager.
     */
    internal fun queryImportWorkObservation(modelId: String): ImportWorkObservation = try {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ModelDownloadWorker.workName(modelId))
            .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (infos.any { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED
            }
        ) {
            ImportWorkObservation.LIVE
        } else {
            ImportWorkObservation.NOT_LIVE
        }
    } catch (e: IllegalStateException) {
        Timber.w(
            "WorkManager не инициализирован — состояние живой загрузки $modelId " +
                "не подтверждено, подмену пропускаем (fail-closed)"
        )
        ImportWorkObservation.QUERY_FAILED
    } catch (e: Exception) {
        Timber.w(e, "WorkManager недоступен при проверке живой загрузки $modelId")
        ImportWorkObservation.QUERY_FAILED
    }
    /**
     * Пары «запись БД <-> запись манифеста» (issue #83, п.8): запись файлов
     * в ZIP идёт по этим парам, а не по индексу forEachIndexed — раньше
     * параллельный сдвиг составов manifest.models и models молча разъезжался.
     *
     * Ревью #2: фолбэк [toRelativePath] для файлов вне корня моделей теряет
     * структуру каталога — одноимённые файлы разных моделей (например
     * model.onnx у двух установок) получают одинаковый relativePath и
     * затирают друг друга в ZIP, а при импорте оба upsert резолвятся в
     * один файл. Коллизию разрешаем уникальным путём с id модели (id
     * уникален в БД), о чём пишем в лог.
     */
    private fun buildManifestEntries(models: List<InstalledModel>): List<ManifestSource> {
        val usedPaths = mutableSetOf<String>()
        return models.map { model ->
            val basePath = toRelativePath(model)
            val relativePath = when {
                basePath.isBlank() || usedPaths.add(basePath) -> basePath
                else -> {
                    val unique = MODELS_PREFIX + model.id + "/" + File(model.filePath.orEmpty()).name
                    Timber.w("Путь $basePath в манифесте уже занят — модель ${model.id} пишется как $unique")
                    usedPaths += unique
                    unique
                }
            }
            ManifestSource(
                model = model,
                entry = BackupModelEntry(
                    id = model.id,
                    category = model.category.name,
                    displayName = model.displayName,
                    description = model.description,
                    runtime = model.runtime.name,
                    sizeBytes = model.sizeBytes,
                    ramRequiredMb = model.ramRequiredMb,
                    languages = model.languages,
                    quality = model.quality,
                    speed = model.speed,
                    url = model.url,
                    urls = model.urls,
                    sha256 = model.sha256,
                    sha256s = model.sha256s,
                    license = model.license,
                    minAndroidSdk = model.minAndroidSdk,
                    recommendedRamMb = model.recommendedRamMb,
                    relativePath = relativePath,
                    isBuiltin = model.isBuiltin
                )
            )
        }
    }

    /** Пара «запись БД <-> запись манифеста» (issue #83, п.8). */
    private data class ManifestSource(val model: InstalledModel, val entry: BackupModelEntry)

    /**
     * Построить настройки (ревью #53: полные поязыковые назначения, тайм-аут
     * выгрузки, удержание моделей при смене направления, последнее направление).
     *
     * Регресс (исправление): чтение — СЫРЫМИ raw*-потоками, как prefetch
     * uiLanguage: recover-обёртка flowWithRecovery при сбое DataStore гасит
     * исключение и эмитит дефолт, поэтому экспорт писал бы в архив дефолтные
     * настройки вместо отказа, а restore затем затирал бы реальные значения
     * дефолтами. Здесь сбой чтения — исключение и общая ошибка экспорта
     * (как было до введения обёртки).
     */
    private suspend fun buildSettings(): BackupSettings {
        return BackupSettings(
            activeTranslation = preferences.getActiveModelId(ModelCategory.TRANSLATION),
            activeStt = preferences.getActiveModelId(ModelCategory.STT),
            activeTts = preferences.getActiveModelId(ModelCategory.TTS),
            ttsEngine = preferences.rawTtsEngine.first(),
            uiLanguage = preferences.rawUiLanguage.first(),
            theme = preferences.rawTheme.first(),
            sttByLanguage = preferences.rawSttByLanguage.first(),
            ttsByLanguage = preferences.rawTtsByLanguage.first(),
            unloadTimeoutMinutes = preferences.rawUnloadTimeoutMinutes.first(),
            modelDirectionRetention = preferences.modelDirectionRetention.first(),
            lastSourceLang = preferences.rawLastSourceLang.first(),
            lastTargetLang = preferences.rawLastTargetLang.first()
        )
    }

    /**
     * Восстановить настройки. Presence-семантика (ревью #53, уточнение
     * ревью #5): ключ секции в JSON = применять по языкам самой секции;
     * отсутствие ключа (null из старого архива) = не трогать; язык,
     * отсутствующий в секции частично-языкового бэкапа, остаётся как есть;
     * null-значение элемента карты = снять назначение этого языка.
     * Скалярные ttsEngine/uiLanguage/theme — та же Presence-семантика
     * (null из старого архива без секции НЕ затирает реальные настройки
     * дефолтами). Модельные id (активные модели и поязыковые назначения)
     * применяются только для моделей, реально восстановленных в этом
     * импорте ([restoredIds]): иначе настройка осталась бы висячей на
     * отсутствующую модель (кроме сентинеля системного TTS — он не требует
     * модели).
     *
     * Все применения — через `edit`-сеттеры [ModelPreferences] (без
     * recover-обёрток): сбой записи DataStore бросает исключение и
     * превращается в общую ошибку импорта внешним catch (проверено — путь
     * restore не читает обёрнутые потоки, регресса фолбэков здесь нет).
     */
    private suspend fun restoreSettings(settings: BackupSettings, restoredIds: Set<String>) {
        restoreActiveIfRestored(ModelCategory.TRANSLATION, settings.activeTranslation, restoredIds)
        restoreActiveIfRestored(ModelCategory.STT, settings.activeStt, restoredIds)
        restoreActiveIfRestored(ModelCategory.TTS, settings.activeTts, restoredIds)
        // Скалярные настройки — Presence-семантика: null (старый архив без
        // поля) не затирает текущее значение дефолтом.
        settings.ttsEngine?.let { preferences.setTtsEngine(it) }
        settings.uiLanguage?.let { preferences.setUiLanguage(it) }
        settings.theme?.let { preferences.setTheme(it) }
        // Поязыковые назначения: только языки из секции назначений архива
        // (ревью #5: язык, отсутствующий в частичном бэкапе, не снимается).
        // Id не восстановленных в этом импорте моделей пропускаем
        // (сентинель «system» назначается всегда).
        restoreLanguageAssignments(
            settings.sttByLanguage, "STT", null, restoredIds,
            preferences::setSttForLanguage
        )
        restoreLanguageAssignments(
            settings.ttsByLanguage, "TTS", TtsModelSelector.SYSTEM_SENTINEL, restoredIds,
            preferences::setTtsForLanguage
        )
        settings.unloadTimeoutMinutes?.let { preferences.setUnloadTimeoutMinutes(it) }
        settings.modelDirectionRetention?.let { preferences.setModelDirectionRetention(it) }
        val lastSource = settings.lastSourceLang
        val lastTarget = settings.lastTargetLang
        if (lastSource != null && lastTarget != null) {
            preferences.setLastDirection(lastSource, lastTarget)
        }
    }

    /** Применить активный id категории только если модель восстановлена в этом импорте. */
    private suspend fun restoreActiveIfRestored(
        category: ModelCategory,
        modelId: String?,
        restoredIds: Set<String>
    ) {
        modelId ?: return
        if (modelId !in restoredIds) {
            Timber.w(
                "Активная модель $category $modelId пропущена: не восстановлена в этом импорте"
            )
            return
        }
        preferences.setActiveModelId(category, modelId)
    }

    /**
     * Применить одну секцию поязыковых назначений (STT/TTS — общий цикл,
     * см. presence-семантику в [restoreSettings]): [label] — для логов,
     * [sentinel] — id, не требующий восстановленной модели (TTS «system»).
     */
    private suspend fun restoreLanguageAssignments(
        saved: Map<String, String?>?,
        label: String,
        sentinel: String?,
        restoredIds: Set<String>,
        setter: suspend (language: String, modelId: String?) -> Unit
    ) {
        saved?.let {
            assignmentOps(it).forEach { (lang, id) ->
                if (!isRestoredOrSentinel(id, restoredIds, sentinel)) {
                    Timber.w(
                        "Назначение $label $id языку $lang пропущено: модель не восстановлена в этом импорте"
                    )
                    return@forEach
                }
                setter(lang, id)
            }
        }
    }

    /** null-операция (снять) / sentinel, не требующий модели / восстановленная модель. */
    private fun isRestoredOrSentinel(id: String?, restoredIds: Set<String>, sentinel: String?): Boolean =
        id == null || id == sentinel || id in restoredIds

    /**
     * Найти распакованную запись модели во временном каталоге: сначала по
     * точному relativePath из манифеста (текущий формат уже содержит
     * "models/"), затем — с префиксом MODELS_PREFIX (обратная совместимость
     * с манифестами, где путь записан без префикса).
     * @return файл/каталог во временном каталоге или null, если записи нет.
     */
    private fun findExtractedEntry(tempDir: File, relativePath: String): File? {
        val direct = File(tempDir, relativePath)
        if (direct.exists()) return direct
        if (!relativePath.startsWith(MODELS_PREFIX)) {
            val legacy = File(tempDir, MODELS_PREFIX + relativePath)
            if (legacy.exists()) return legacy
        }
        return null
    }

    /**
     * Относительный путь модели в архиве.
     *
     * Ревью #1: корень моделей считается через единую точку
     * [ModelStorage.modelsRoot] (с фолбэком на filesDir), а не прямым
     * вызовом getExternalFilesDir — иначе при недоступном external storage
     * экспорт теряет подпуть, а импорт возвращает null для всех записей.
     * Issue #83, п.7: принадлежность корню проверяется ГРАНИЦЕЙ РАЗДЕЛИТЕЛЯ
     * ([isFileInsideDir], канонические пути) — раньше startsWith по
     * absolutePath без границы матчил соседний каталог /data/models_x как
     * внутренний /data/models, и модель получала изуродованный путь вида
     * "_x/файл". Файл вне корня — путь по имени, чтобы путь не менялся
     * после импорта.
     */
    private fun toRelativePath(model: InstalledModel): String {
        val path = model.filePath ?: return ""
        val file = File(path)
        val modelsRoot = ModelStorage.modelsRoot(context)
        val inside = try {
            isFileInsideDir(modelsRoot, file)
        } catch (e: Exception) {
            Timber.w(e, "Не удалось канонализировать путь модели: $path")
            false
        }
        return if (inside) {
            MODELS_PREFIX + file.canonicalPath
                .removePrefix(modelsRoot.canonicalPath)
                .removePrefix(File.separator)
        } else {
            // Используем имя файла (с расширением), чтобы путь не менялся после импорта.
            MODELS_PREFIX + file.name
        }
    }

    /**
     * Целевой путь модели при импорте.
     *
     * Ревью #1: единая точка расчёта корня [ModelStorage.modelsRoot] вместо
     * прямого getExternalFilesDir — при недоступном external storage
     * фолбэк на filesDir, импорт не возвращает null для всех записей.
     * @return абсолютный путь внутри каталога моделей или null,
     *   если путь из манифеста выходит за его пределы (zip-slip).
     */
    private fun resolveTargetPath(entry: BackupModelEntry): String? {
        val modelsRoot = ModelStorage.modelsRoot(context)
        val relative = entry.relativePath.removePrefix(MODELS_PREFIX)
        val target = File(modelsRoot, relative)
        return try {
            if (isFileInsideDir(modelsRoot, target)) target.absolutePath else null
        } catch (e: Exception) {
            Timber.w(e, "Не удалось канонализировать путь: ${target.absolutePath}")
            null
        }
    }

    /**
     * Переместить временный файл в целевой путь.
     *
     * Существующая цель удаляется только после успешного переноса нового
     * содержимого: между томами (cacheDir и getExternalFilesDir) rename
     * почти всегда падает, поэтому копируем в `<target>.tmp` рядом с целью,
     * затем удаляем старый файл и переименовываем tmp на его место. При
     * провале копирования tmp удаляется, старый файл остаётся нетронутым.
     */
    private fun moveToTarget(tempFile: File, targetPath: String): Boolean {
        return try {
            val targetFile = File(targetPath)
            targetFile.parentFile?.mkdirs()
            if (tempFile.renameTo(targetFile)) return true
            val staged = File(targetFile.parentFile, targetFile.name + ".tmp")
            staged.deleteRecursively()
            val copied = tempFile.copyRecursively(staged, overwrite = true)
            if (!copied) {
                staged.deleteRecursively()
                Timber.w("Не удалось скопировать ${tempFile.name} в ${staged.name} — оставляем прежний файл")
                return false
            }
            tempFile.deleteRecursively()
            if (targetFile.exists() && !targetFile.deleteRecursively()) {
                Timber.w("Не удалось удалить прежний $targetPath перед подменой")
                staged.deleteRecursively()
                return false
            }
            if (staged.renameTo(targetFile)) return true
            // rename по каким-то причинам не прошёл (тот же том — редкость):
            // копируем staging в цель напрямую и подчищаем staging.
            val moved = staged.copyRecursively(targetFile, overwrite = true)
            staged.deleteRecursively()
            moved
        } catch (e: Exception) {
            Timber.e(e, "Ошибка перемещения файла в $targetPath")
            false
        }
    }

    /**
     * Записать файл в ZIP.
     */
    private fun writeFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Записать каталог в ZIP рекурсивно.
     */
    private fun writeDirectoryToZip(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(dir).path
            writeFileToZip(zip, file, "$prefix/$relative")
        }
    }

    /**
     * Открыть архив по [sourceUri] и обойти все его записи: поток и ZIP-читатель
     * закрываются здесь, каждая запись — [onEntry] — после обработки закрыть
     * через closeEntry. Используется всеми проходами двухпроходного импорта
     * (Uri открывается заново на каждый проход).
     * @return false, если потоковый вход архива не открылся.
     */
    private fun forEachZipEntry(
        sourceUri: Uri,
        onEntry: (zip: ZipInputStream, entry: ZipEntry) -> Unit
    ): Boolean {
        val input = context.contentResolver.openInputStream(sourceUri) ?: return false
        input.use { ins ->
            ZipInputStream(ins.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    onEntry(zip, entry)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return true
    }

    /**
     * Прочитать содержимое служебной записи ZIP ([name]) целиком с жёстким
     * лимитом [MANIFEST_ENTRY_READ_LIMIT_BYTES] (ревью аудита, п.4):
     * безлимитное readBytes() на заборстопленном архиве = OOM на
     * манифесте/настройках до всех гейтов места. Превышение —
     * [IllegalStateException] с понятным текстом для UI («битый архив»).
     */
    private fun readEntryCapped(zip: ZipInputStream, name: String): String {
        val buffer = ByteArray((MANIFEST_ENTRY_READ_LIMIT_BYTES + 1).toInt())
        var total = 0
        while (total < buffer.size) {
            val read = zip.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        if (total > MANIFEST_ENTRY_READ_LIMIT_BYTES) {
            throw IllegalStateException(
                "Архив повреждён: размер записи $name в архиве превышает лимит " +
                    "$MANIFEST_ENTRY_READ_LIMIT_BYTES байт"
            )
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }
}
