package com.plox.llmtranslate.domain.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * Агрегация состояний движков: какие модели загружены в ОЗУ прямо сейчас.
 * Чистая логика — вынесена из [RuntimeRouter] для unit-тестов.
 */
object LoadedModelsAggregator {

    /**
     * Слить состояния движков в единый реактивный список загруженных моделей.
     *
     * @param engines состояния движков в порядке отображения
     *   (сначала движки перевода, затем STT, затем TTS); каждый движок
     *   отдаёт список
     *   своих загруженных моделей (активная — первой), пустой список —
     *   движок разгружен.
     * @return сплющенный список всех загруженных моделей в порядке движков;
     *   эмитируется при любом изменении любого из состояний.
     */
    fun merge(engines: List<StateFlow<List<LoadedModelInfo>>>): Flow<List<LoadedModelInfo>> {
        // Ревью #83: явная диагностика вместо «List is empty» из combine —
        // пустой список движков означает ошибку сборки роутера, а не штатный
        // «все движки разгружены» (штатный случай — движки с пустыми состояниями).
        require(engines.isNotEmpty()) {
            "LoadedModelsAggregator.merge вызван со списком из 0 движков — " +
                "агрегировать состояния не из чего"
        }
        return combine(engines) { values: Array<out List<LoadedModelInfo>> ->
            values.flatMap { it }
        }
    }
}
