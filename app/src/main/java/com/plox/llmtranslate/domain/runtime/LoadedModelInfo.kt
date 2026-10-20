package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError

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
 * @param languages языки модели (из [com.plox.llmtranslate.domain.model.InstalledModel.languages]);
 *        пустой список у импортированных моделей без объявленных языков —
 *        проверка направления в [directionMismatch] для них пропускается.
 */
data class LoadedModelInfo(
    val modelId: String,
    val displayName: String,
    val category: ModelCategory,
    val ramRequiredMb: Int,
    val languages: List<String> = emptyList()
)

/**
 * Проверка направления перевода (ревью #79): активный пучок движка обязан
 * покрывать запрошенную пару [from]→[to] — иначе перевод чужой моделью
 * (гонка с [TranslationEngine.ensureLoaded] другой модели) прошёл бы
 * незамеченным. Модель покрывает пару, если оба языка есть в её языках
 * (языковые паки двусторонние, поэтому направление пары не проверяется).
 * Пустой список языков (импорт без объявленных языков) — проверка
 * пропускается, возвращает null.
 *
 * Ограничение по рантайму (ревью аудита): допущение «паки двусторонние»
 * справедливо для MARIAN_SLIMT (Bergamot) и IndicTrans-пар каталога, но НЕ
 * для MARIAN_ONNX (opus-mt — односторонние графы): для него порядок пары
 * [from]→[to] этим гейтом не проверяется, и при ошибочно выбранном активном
 * пучке возможен мусорный перевод вместо DirectionMismatch. Корректность
 * направления для Marian компенсируется выше — подбором цепочки по
 * направлению в [com.plox.llmtranslate.domain.model.TranslationModelSelector]
 * (для импортов направление может быть не определено — гейт отсутствует
 * сознательно, сигнатуры не меняются).
 */
internal fun LoadedModelInfo.directionMismatch(
    runtime: String,
    from: String,
    to: String
): RuntimeError.DirectionMismatch? {
    if (languages.isEmpty()) return null
    return if (from in languages && to in languages) {
        null
    } else {
        RuntimeError.DirectionMismatch(
            runtime,
            "$from→$to (языки модели: ${languages.joinToString(", ")})"
        )
    }
}

/**
 * Путь каталога установленной модели — общая валидация начала `ensureLoaded`
 * во всех движках (Marian, IndicTrans, slimt, sherpa): null, если путь не
 * заполнен (модель не готова — [RuntimeError.ModelNotReady] возвращает
 * вызывающий).
 */
internal fun installedModelPath(model: InstalledModel): String? =
    model.filePath?.takeIf { it.isNotBlank() }

/**
 * Метаданные пучка модели перевода для публикации в `loadedModels` —
 * одинаковые во всех трёх переводческих движках (Marian, IndicTrans, slimt)
 * при загрузке в `ensureLoaded`.
 */
internal fun translationLoadedInfo(model: InstalledModel): LoadedModelInfo = LoadedModelInfo(
    modelId = model.id,
    displayName = model.displayName,
    category = ModelCategory.TRANSLATION,
    ramRequiredMb = model.ramRequiredMb,
    languages = model.languages
)

/**
 * Метаданные пучка TTS-модели для публикации в `loadedModels` (Piper) —
 * одинаковые при первой загрузке и при обновлении метаданных повторной.
 * Без записи каталога ([model] = null, импорт вручную) — id и имя берутся
 * из [fallbackName] (имя каталога), оценка ОЗУ — 0, языки пусты
 * (гейт [directionMismatch] для таких пучков пропускается, ревью #83).
 */
internal fun ttsLoadedInfo(model: InstalledModel?, fallbackName: String): LoadedModelInfo =
    LoadedModelInfo(
        modelId = model?.id ?: fallbackName,
        displayName = model?.displayName ?: fallbackName,
        category = ModelCategory.TTS,
        ramRequiredMb = model?.ramRequiredMb ?: 0,
        languages = model?.languages ?: emptyList()
    )
