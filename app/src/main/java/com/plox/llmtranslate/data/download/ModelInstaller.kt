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
     * @param urls адреса файлов (один для GGUF/Vosk, несколько для ONNX encoder+decoder)
     * @param sha256 контрольная сумма первого файла (null — проверка пропускается;
     *               для мультифайловых моделей не проверяется)
     * @param targetDir каталог для моделей
     * @param modelId id модели (имя файла/каталога)
     * @param onProgress прогресс в процентах 0..100
     * @return результат установки (путь к файлу или каталогу)
     */
    suspend fun install(
        urls: List<String>,
        sha256: String?,
        targetDir: java.io.File,
        modelId: String,
        onProgress: (Int) -> Unit
    ): InstallResult
}
