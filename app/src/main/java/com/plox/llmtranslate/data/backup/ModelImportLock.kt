package com.plox.llmtranslate.data.backup

import kotlinx.coroutines.sync.Mutex

/**
 * Процессный лок файловых операций с моделями (issue #83, п.1).
 *
 * Изначально мьютекс был приватным полем [com.plox.llmtranslate.domain.ModelManagerImpl]
 * (issue #75/#76: import <-> import, import <-> delete, cancelDownload <-> import
 * одной id). Импорт бэкапа [ModelBackupManager.importFromUri] в этот лок не
 * входил и гонял moveToTarget/upsert с живым
 * [com.plox.llmtranslate.data.download.ModelDownloadWorker] или параллельным
 * импортом той же id — порча восстановленной READY-записи. Лок вынесен в
 * разделяемый объект: его держат ВСЕ точки подмены файлов модели — одиночный
 * и ZIP-импорт, удаление, отмена скачивания в ModelManagerImpl и импорт
 * бэкапа в ModelBackupManager (через стандартный kotlinx Mutex.withLock).
 *
 * Порядок захвата не меняется: этот лок -> engine-локи рантаймов (через
 * [com.plox.llmtranslate.domain.runtime.RuntimeRouter.releaseModel]);
 * обратного захвата из рантаймов нет.
 */
object ModelImportLock {

    /** Общий мьютекс; блокировка — только через kotlinx [Mutex.withLock]. */
    val mutex = Mutex()
}