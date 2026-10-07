package com.plox.llmtranslate.domain.model

import java.util.Locale

/**
 * Чистая логика проверки установки модели. Вынесена для unit-тестов.
 */
object InstallCheckLogic {

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
        if (device.freeDiskBytes < card.sizeBytes * 1.2) {
            warnings.add(
                "Мало места на диске: нужно ${formatSize(card.sizeBytes)}"
            )
        }
        if (device.totalRamMb > 0 &&
            card.ramRequiredMb > 0 &&
            card.ramRequiredMb * 100 > device.totalRamMb * 60
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
