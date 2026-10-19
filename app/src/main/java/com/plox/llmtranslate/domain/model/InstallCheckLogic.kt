package com.plox.llmtranslate.domain.model

import java.util.Locale

/**
 * Чистая логика проверки установки модели. Вынесена для unit-тестов.
 */
object InstallCheckLogic {

    /**
     * Определить тип раскладки карточки по её URLам скачивания: одиночный
     * файл, архив (unpack-пик 2x) или gz-артефакты slimt-пачки ( gunzip-пик
     * ≈1.5–1.7x). Совпадает с классификацией гейта в ModelDownloadWorker и
     * используется единой формулой [requiredDownloadFreeBytes] (ревью).
     */
    private fun cardLayoutFlags(card: ModelCard): Pair<Boolean, Boolean> {
        val names = card.downloadUrls().map { url ->
            url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        }
        val isArchiveLayout = names.any { ArchiveFormats.isArchive(it) }
        val isGzipLayout = !isArchiveLayout &&
            names.any { ArchiveFormats.isCompressedArtifact(it) }
        return isArchiveLayout to isGzipLayout
    }


    /**
     * Проверить возможность установки модели на устройство.
     * @param card карточка модели
     * @param device информация об устройстве
     * @return результат проверки
     */
    fun evaluate(card: ModelCard, device: DeviceInfo): InstallCheck {
        if (device.sdkInt < card.minAndroidSdk) {
            return InstallCheck.Blocked(
                "Требуется Android ${card.minAndroidSdk}+, у вас ${device.sdkInt}"
            )
        }
        val warnings = mutableListOf<String>()
        // Требуемое место считаем той же формулой, что и гейт скачивания в
        // воркере (requiredDownloadFreeBytes), — прежний универсальный x2.0
        // давал ложные предупреждения для одиночных файлов (фактический
        // гейт 1.2x) и расходился с gz-гейтом 1.8x (ревью).
        val (isArchiveLayout, isGzipLayout) = cardLayoutFlags(card)
        val requiredBytes = requiredDownloadFreeBytes(card.sizeBytes, isArchiveLayout, isGzipLayout)
        if (requiredBytes > 0L && device.freeDiskBytes < requiredBytes) {
            // Текст предупреждения ветвится по типу раскладки (ревью):
            // «распаковка» уместна только для архива и gz-артефактов.
            val headroomText = when {
                isArchiveLayout -> "с запасом на распаковку архива"
                isGzipLayout -> "с запасом на распаковку gzip-артефактов"
                else -> "с запасом на запись скачанного файла"
            }
            warnings.add(
                "Мало места на диске: нужно ${formatSize(requiredBytes)} ($headroomText)"
            )
        }
        // Порог ОЗУ перед скачиванием — 60% от ОБЪЁМА устройства (total):
        // НЕ унифицировать с 70% из DeviceInfo.isNearRamLimit ([ramWarning]) —
        // тот порог про суммарные ОЗУ уже АКТИВНЫХ моделей в UI («близко к
        // лимиту» в рантайме), а этот предупреждает до установки про запас
        // под саму модель. Дополнительно — 80% от фактически ДОСТУПНОЙ ОЗУ
        // (availableRam, если известна >0): модель может не запуститься,
        // даже если от общего объёма места хватает.
        if (card.ramRequiredMb > 0 &&
            ((device.totalRamMb > 0 && card.ramRequiredMb * 100 > device.totalRamMb * 60) ||
                (device.availableRamMb > 0 &&
                    card.ramRequiredMb * 100 > device.availableRamMb * 80))
        ) {
            warnings.add("Мало ОЗУ: модель требует ~${card.ramRequiredMb} МБ")
        }
        return if (warnings.isEmpty()) {
            InstallCheck.Ready
        } else {
            InstallCheck.Warning(warnings.joinToString("\n"))
        }
    }

    /**
     * Требуемый запас свободного места для начала скачивания в воркере
     * (ревью, п.6): гейт InstallCheck.Warning остаётся предупреждением в UI,
     * но сам воркер перед загрузкой обязан сверить фактическое место с пиком
     * установки — для архивной распаковки пик ~(архив + содержимое) = 2x,
     * для одиночного файла (скачивание в .part, пика распаковки нет) - 1.2x.
     * gzip-артефакты slimt-пучков (ревью): пик при gunzip ~(сжатый артефакт +
     * распакованное содержимое) ≈ 1.5–1.7x размера — отдельный коэффициент 1.8x
     * с запасом, НЕ смешивать с архивной 2x (иначе перерасход места).
     * Размер неизвестен (<=0) — проверка пропускается (0).
     */
    fun requiredDownloadFreeBytes(
        sizeBytes: Long,
        isArchiveLayout: Boolean,
        isCompressedArtifactLayout: Boolean = false
    ): Long = when {
        sizeBytes <= 0L -> 0L
        isArchiveLayout -> sizeBytes * 2
        isCompressedArtifactLayout -> sizeBytes + sizeBytes * 4 / 5
        else -> sizeBytes + sizeBytes / 5
    }

    /**
     * Проверить суммарную ОЗУ активных LLM+STT моделей.
     * @return предупреждение или null.
     */
    fun ramWarning(llmMb: Int?, sttMb: Int?, device: DeviceInfo): String? {
        if (llmMb == null || sttMb == null) return null
        val total = (llmMb + sttMb).toLong()
        return if (device.isNearRamLimit(total)) {
            "Активные модели требуют ~$total МБ ОЗУ — близко к лимиту устройства"
        } else {
            null
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1f ГБ", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(Locale.US, "%.0f МБ", bytes / 1_048_576.0)
        else -> String.format(Locale.US, "%.0f КБ", bytes / 1024.0)
    }
}
