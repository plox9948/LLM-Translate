package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType

/**
 * Результат установки модели.
 */
sealed class InstallResult {
    /** Модель установлена, путь к файлу/каталогу готов. */
    data class Success(val path: String) : InstallResult()
    /**
     * Ошибка установки.
     * @param permanent true — повторная попытка бессмысленна (SHA-256 не совпал
     *        после СВЕЖЕЙ полной загрузки: файл повреждён на сервере); воркер
     *        ставит модели ERROR и не повторяет загрузку. Mismatch после
     *        докачки — permanent=false: часть уже отброшена, повтор с нуля
     *        поможет (см. [DownloadResumePolicy.mismatchIsPermanent]).
     */
    data class Failure(val message: String, val permanent: Boolean = false) : InstallResult()
}

/**
 * Абстрактный инсталлятор модели. Разные рантаймы — разные форматы,
 * загрузчик НЕ унифицировать.
 */
interface ModelInstaller {

    /** Поддерживает ли инсталлятор данный рантайм. */
    fun supports(runtime: RuntimeType): Boolean

    /**
     * Установить модель: скачать (с докачкой) и проверить целостность.
     * @param urls адреса файлов (один — одиночный файл, несколько — мультифайловая модель)
     * @param sha256 контрольная сумма единственного файла (legacy; применяется,
     *        только если [sha256s] пуст — старые строки БД и импортированные модели)
     * @param sha256s контрольные суммы по индексам [urls] (null в элементе —
     *        проверка файла пропускается)
     * @param targetDir каталог для моделей
     * @param modelId id модели (имя файла/каталога)
     * @param onProgress прогресс в процентах 0..100
     * @return результат установки (путь к файлу или каталогу)
     */
    suspend fun install(
        urls: List<String>,
        sha256: String?,
        sha256s: List<String?> = emptyList(),
        targetDir: java.io.File,
        modelId: String,
        onProgress: suspend (Int) -> Unit
    ): InstallResult
}

/**
 * Общий перевод несовпадения SHA-256 в ошибку установки: mismatch после
 * докачки — часть могла быть битой, повтор с нуля поможет; после свежей
 * загрузки файл повреждён на сервере (permanent, см.
 * [DownloadResumePolicy.mismatchIsPermanent]).
 */
internal fun DownloadOutcome.Mismatch.toInstallFailure(): InstallResult.Failure =
    InstallResult.Failure(
        message,
        permanent = DownloadResumePolicy.mismatchIsPermanent(wasResumed)
    )

/**
 * Переполнение диска при скачивании (ревью, п.3): повтор без освобождения
 * места бессилен — permanent=true, воркер ставит ERROR за одну попытку.
 * `.part` сохранён инсталлятором — после освобождения места докачка
 * продолжится с байта обрыва.
 */
internal fun DownloadOutcome.InsufficientStorage.toInstallFailure(): InstallResult.Failure =
    InstallResult.Failure(message, permanent = true)

/**
 * Файловая ошибка финальной записи (rename, issue #83, п.6): сеть ни при чём,
 * автоматические повторы лишь заново скачают весь файл к тому же сбою -
 * permanent=true, модель сразу в ERROR с понятной причиной, повтор вручную.
 */
internal fun DownloadOutcome.FileError.toInstallFailure(): InstallResult.Failure =
    InstallResult.Failure(message, permanent = true)

/**
 * Общий перевод результата скачивания в терминальную ошибку установки:
 * [DownloadOutcome.Success] — null (ошибки нет); сетевая ошибка — её
 * сообщение с обычным повтором; mismatch/ENOSPC/rename — маппинги выше
 * ([toInstallFailure]). Инсталляторы с собственным текстом сетевой ошибки
 * различают её ветку до вызова.
 */
internal fun DownloadOutcome.installFailureOrNull(): InstallResult.Failure? = when (this) {
    is DownloadOutcome.Success -> null
    is DownloadOutcome.NetworkError -> InstallResult.Failure(message)
    is DownloadOutcome.Mismatch -> toInstallFailure()
    is DownloadOutcome.InsufficientStorage -> toInstallFailure()
    is DownloadOutcome.FileError -> toInstallFailure()
}
