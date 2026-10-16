package com.plox.llmtranslate.domain.model

import java.util.Locale

/**
 * Определяет язык UI по языку системы: ru→ru, ne→ne, любой другой→en.
 *
 * Используется для детерминированного выбора локали в режиме «по системе»:
 * вместо прямого сброса на системную локаль (которая может быть любым языком)
 * система однозначно отображается в один из поддерживаемых языков интерфейса.
 * Код языка приводится к нижнему регистру, поэтому регистр входа не значим;
 * null и пустая строка дают запасной «en».
 *
 * @param systemLanguage код языка системной локали (например «ru», «EN»),
 *                       может быть null, если язык определить не удалось.
 * @return код языка интерфейса: «ru», «ne» или «en».
 */
fun defaultUiLanguageForSystemLocale(systemLanguage: String?): String =
    when (systemLanguage?.lowercase(Locale.ROOT)) {
        "ru" -> "ru"
        "ne" -> "ne"
        else -> "en"
    }
