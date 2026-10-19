package com.plox.llmtranslate.util

import java.io.File

/**
 * SHA-256 файла в hex (нижний регистр). Общий для сверки бэкапа
 * (ModelBackupManager) и контрольных сумм импорта (ModelManagerImpl);
 * потоковый инференс-хеш с границами отмены — отдельный
 * ([com.plox.llmtranslate.data.download.BaseDownloader.verifySha256]).
 */
internal fun sha256Hex(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            // EOF — строго < 0; 0 для ненулевого буфера не возвращается (ревью, п.8).
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Защита от zip-slip: файл лежит внутри каталога [dir]
 * (сравнение по каноническим путям, сам каталог тоже считается «внутри»).
 * Бросает [java.io.IOException] при недостижимости канонического пути —
 * вызывающий решает, отклонять ли операцию.
 */
internal fun isFileInsideDir(dir: File, file: File): Boolean {
    val dirCanonical = dir.canonicalPath
    val fileCanonical = file.canonicalPath
    return fileCanonical == dirCanonical ||
        fileCanonical.startsWith(dirCanonical + File.separator)
}
