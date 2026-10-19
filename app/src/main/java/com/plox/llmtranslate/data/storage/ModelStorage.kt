package com.plox.llmtranslate.data.storage

import android.content.Context
import java.io.File

/**
 * Единая точка расчёта корня каталога моделей (ревью, п.9): раньше
 * `getExternalFilesDir("models")` вызывался в нескольких местах с разным
 * поведением фолбэка (ModelBackupManager при null вообще отказывался
 * работать, ModelManagerImpl откатывался на filesDir) — пути расходились.
 * Единственный вариант: внешние файлы приложения с фолбэком на filesDir.
 */
object ModelStorage {

    /** Каталог хранения моделей: external files «models», при недоступности — filesDir. */
    fun modelsRoot(context: Context): File =
        context.getExternalFilesDir("models") ?: context.filesDir
}
