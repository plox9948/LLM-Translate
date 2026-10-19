package com.plox.llmtranslate.domain.model

import java.util.Locale

/**
 * Чистая логика проверки установки модели. Вынесена для unit-тестов.
 */
object InstallCheckLogic {

    /**
     * Запас свободного места к размеру модели. Для archive-установок пик
     * распаковки ~(архив + распакованное содержимое) ≈ 2x размера, прежний
     * x1.2 пропускал загрузку, которая затем не помещалась при распаковке.
     */
    private const val DISK_HEADROOM_FACTOR = 2.0

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
        if (device.freeDiskBytes < card.sizeBytes * DISK_HEADROOM_FACTOR) {
            // Требуемый запас в тексте — по тому же множителю, что и критерий
            // (ревью раунд 2): прежний размер модели без запаса расходился
            // с фактическим порогом и дезориентировал пользователя.
            val requiredBytes = (card.sizeBytes * DISK_HEADROOM_FACTOR).toLong()
            warnings.add(
                "Мало места на диске: нужно ${formatSize(requiredBytes)} (с запасом на распаковку)"
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
     * Размер неизвестен (<=0) — проверка пропускается (0).
     */
    fun requiredDownloadFreeBytes(sizeBytes: Long, isArchiveLayout: Boolean): Long = when {
        sizeBytes <= 0L -> 0L
        isArchiveLayout -> sizeBytes * 2
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
