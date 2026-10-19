package com.plox.llmtranslate.testutil

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Общие фикстуры unit-тестов: кодирование архивов и контрольные суммы.
 * Реализации независимы от продакшн-кода (эталон для сверки SHA-256
 * в тестах бэкапа и импорта моделей).
 */

/** ZIP из пары «имя записи → байты». */
internal fun zipOf(entries: Map<String, ByteArray>): ByteArray =
    ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }.toByteArray()

/** gzip-сжатие байт (slimt-пачки в каталоге отдаются `.gz`). */
internal fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().apply {
        GZIPOutputStream(this).use { it.write(bytes) }
    }.toByteArray()

/** SHA-256 байт в hex (нижний регистр). */
internal fun sha256HexOf(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/** SHA-256 файла в hex (нижний регистр). */
internal fun sha256HexOf(file: File): String =
    sha256HexOf(file.readBytes())
