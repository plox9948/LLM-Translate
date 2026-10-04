package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType

/**
 * Результат установки модели.
 */
sealed class InstallResult {
    /** Модель установлена, путь к файлу/каталогу готов. */
    data class Success(val path: String) : InstallResult()
    data class Failure(val message: String) : InstallResult()
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
     * @param url адрес файла
     * @param sha256 контрольная сумма (null — проверка пропускается)
     * @param targetDir каталог для моделей
     * @param modelId id модели (имя файла)
     * @param onProgress прогресс в процентах 0..100
     * @return результат установки
     */
    suspend fun install(
        url: String,
        sha256: String?,
        targetDir: java.io.File,
        modelId: String,
        onProgress: (Int) -> Unit
    ): InstallResult
}
