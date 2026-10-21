package com.plox.llmtranslate.domain.update

/**
 * Чистая логика сравнения версий приложения (issue #95, самообновление).
 *
 * Формат версий — semver-подобный: числовые сегменты через точку
 * ("0.5.18"). Нечисловые хвосты сегментов ("-beta", "rc1") игнорируются,
 * префикс тега релиза ("v"/"V") снимается. Мусорный вход не роняет
 * сравнение: сегмент без цифр считается нулём.
 */
object UpdateVersionLogic {

    /**
     * Снять префикс тега релиза ("v"/"V") и вернуть чистую строку версии.
     * Пример: "v0.5.18" -> "0.5.18".
     */
    fun tagToVersion(tag: String): String {
        val trimmed = tag.trim()
        return if (trimmed.startsWith("v", ignoreCase = true)) trimmed.substring(1) else trimmed
    }

    /**
     * Сравнить две версии по числовым сегментам.
     *
     * Правила: сегменты разделяются точкой; в каждом сегменте учитывается
     * только ведущая последовательность цифр (нечисловой хвост игнорируется,
     * сегмент без цифр равен 0); у более короткой версии недостающие
     * сегменты считаются нулями ("1.2" == "1.2.0").
     *
     * @return > 0 если [a] новее [b], < 0 если старше, 0 если равны.
     */
    fun compareVersionNames(a: String, b: String): Int {
        val segA = versionSegments(a)
        val segB = versionSegments(b)
        val count = maxOf(segA.size, segB.size)
        for (i in 0 until count) {
            val cmp = compareNumericSegment(segA.getOrElse(i) { "" }, segB.getOrElse(i) { "" })
            if (cmp != 0) return cmp
        }
        return 0
    }

    /**
     * Новее ли [latestTagOrVersion] (тег релиза или версия) чем
     * [currentVersion] установленного приложения.
     */
    fun isNewer(latestTagOrVersion: String, currentVersion: String): Boolean =
        compareVersionNames(latestTagOrVersion, currentVersion) > 0

    /**
     * Разбить строку версии на числовые сегменты: снимается префикс тега,
     * от каждого сегмента остаётся только ведущая последовательность цифр
     * (пустая строка — сегмент без цифр, трактуется как 0).
     */
    private fun versionSegments(version: String): List<String> =
        tagToVersion(version).split('.').map { segment ->
            segment.takeWhile { it.isDigit() }
        }

    /**
     * Сравнить два числовых сегмента без риска переполнения (сегменты
     * произвольной длины): ведущие нули снимаются, дальше сравнение по
     * длине цифры, затем лексикографически.
     */
    private fun compareNumericSegment(a: String, b: String): Int {
        val na = a.trimStart('0')
        val nb = b.trimStart('0')
        if (na.length != nb.length) return na.length.compareTo(nb.length)
        return na.compareTo(nb)
    }
}
