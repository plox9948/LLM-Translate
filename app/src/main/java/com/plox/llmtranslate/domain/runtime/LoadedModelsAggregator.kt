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
     *   (сначала движки перевода, затем STT); null — движок разгружен.
     * @return поток непустых состояний; эмитируется при любом изменении
     *   любого из состояний.
     */
    fun merge(engines: List<StateFlow<LoadedModelInfo?>>): Flow<List<LoadedModelInfo>> {
        val states: List<Flow<LoadedModelInfo?>> = engines
        return combine(states) { values: Array<LoadedModelInfo?> -> values.filterNotNull() }
    }
}
