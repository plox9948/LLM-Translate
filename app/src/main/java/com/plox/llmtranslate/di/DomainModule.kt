package com.plox.llmtranslate.di

import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.AndroidDeviceInfoProvider
import com.plox.llmtranslate.domain.DeviceInfoProvider
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.ModelManagerImpl
import com.plox.llmtranslate.domain.UnloadTimeoutProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import timber.log.Timber

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
         * Поставщик тайм-аута выгрузки: читает настройку из DataStore
         * в момент планирования; при ошибке чтения — значение по умолчанию.
         */
        @Provides
        @Singleton
        fun provideUnloadTimeoutProvider(
            preferences: ModelPreferences
        ): UnloadTimeoutProvider = UnloadTimeoutProvider {
            try {
                preferences.unloadTimeoutMinutes.first()
            } catch (e: CancellationException) {
                // Отмену корутины не проглатываем — иначе scheduler решит,
                // что настройка прочитана, и выгрузит по значению по умолчанию.
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Не удалось прочитать тайм-аут выгрузки, беру значение по умолчанию")
                ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES
            }
        }

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
