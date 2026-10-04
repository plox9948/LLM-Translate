package com.plox.llmtranslate.di

import com.plox.llmtranslate.domain.AndroidDeviceInfoProvider
import com.plox.llmtranslate.domain.DeviceInfoProvider
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.ModelManagerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

/**
 * Hilt-модуль домена.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindModelManager(impl: ModelManagerImpl): ModelManager

    @Binds
    @Singleton
    abstract fun bindDeviceInfoProvider(impl: AndroidDeviceInfoProvider): DeviceInfoProvider

    companion object {
        /**
         * Среда исполнения для фоновых операций жизненного цикла приложения.
         */
        @Provides
        @Singleton
        fun provideAppScope(): CoroutineScope =
            CoroutineScope(Dispatchers.IO)

        /**
         * Единый конфигурированный Json для сериализации.
         */
        @Provides
        @Singleton
        fun provideJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
