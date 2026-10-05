package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType

/**
 * Реестр инсталляторов моделей: подбирает инсталлятор по типу рантайма.
 * Обёртка над списком инсталляторов, чтобы избежать инъекции List
 * (variance Dagger/Kotlin) в @HiltWorker.
 */
class InstallerRegistry(private val installers: List<ModelInstaller>) {

    /** Найти инсталлятор, поддерживающий заданный рантайм. */
    fun forRuntime(runtime: RuntimeType): ModelInstaller? =
        installers.firstOrNull { it.supports(runtime) }
}
