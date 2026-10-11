package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.ModelCategory

/**
 * Модель, загруженная в ОЗУ рантаймом в текущий момент.
 *
 * Данные фиксируются движком при успешной загрузке из [com.plox.llmtranslate.domain.model.InstalledModel]
 * (то есть из БД/каталога): оценка потребления — `ramRequiredMb` этой модели.
 *
 * @param modelId id загруженной модели.
 * @param displayName отображаемое имя модели.
 * @param category категория модели (перевод/STT).
 * @param ramRequiredMb оценка потребления ОЗУ, МБ (по замерам пика из каталога).
 */
data class LoadedModelInfo(
    val modelId: String,
    val displayName: String,
    val category: ModelCategory,
    val ramRequiredMb: Int
)
