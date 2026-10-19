package com.plox.llmtranslate.data.download

/**
 * Общие константы слоя загрузки (ревью, п.7): буфер копирования и HTTP-таймауты
 * использовались одинаково в 3+ местах (BaseDownloader, ArchiveInstaller,
 * SlimtInstaller) — единый источник вместо дублей.
 */

/** Буфер потокового копирования (скачивание, распаковка, хеширование) — 64 КиБ. */
internal const val TRANSFER_BUFFER_BYTES = 64 * 1024

/** Таймаут установки HTTP-соединения при скачивании. */
internal const val HTTP_CONNECT_TIMEOUT_MS = 30_000

/** Таймаут чтения HTTP-потока при скачивании (медленные CDN, GB-файлы). */
internal const val HTTP_READ_TIMEOUT_MS = 120_000

/**
 * Шаг эскалации прогресса при неизвестном общем размере (chunked без
 * Content-Length, ревью п.6): [onProgress] вызывается не чаще, чем каждые
 * 4 МиБ полученных байт — редкие обновления вместо зависания на нуле.
 */
internal const val PROGRESS_UNKNOWN_TOTAL_STEP_BYTES = 4L * 1024 * 1024

/**
 * Оценка «остатка» при неизвестном total: процент считается как
 * knownBytes*100/(knownBytes+эталон) — монотонно растёт и асимптотически
 * приближается к 99 (100 выставляется только по факту завершения файла),
 * совместимо с процентным индикатором в UI.
 */
internal const val PROGRESS_UNKNOWN_TOTAL_REFERENCE_BYTES = 64L * 1024 * 1024
