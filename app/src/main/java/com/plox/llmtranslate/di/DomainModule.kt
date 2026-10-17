package com.plox.llmtranslate.di

import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.AndroidDeviceInfoProvider
import com.plox.llmtranslate.domain.DeviceInfoProvider
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.ModelManagerImpl
import com.plox.llmtranslate.domain.ModelRetentionProvider
import com.plox.llmtranslate.domain.UnloadTimeoutProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
         * SupervisorJob: падение одной корутины не отменяет остальные;
         * CoroutineExceptionHandler: необработанная ошибка логируется,
         * а не роняет процесс.
         */
        @Provides
        @Singleton
        fun provideAppScope(): CoroutineScope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler { _, e ->
                        Timber.e(e, "Необработанная ошибка в app-scope")
                    }
            )

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
         * Поставщик политики удержания моделей при смене направления
         * перевода (issue #36): читает настройку из DataStore в момент
         * загрузки; при ошибке чтения или неизвестном значении — keep.
         */
        @Provides
        @Singleton
        fun provideModelRetentionProvider(
            preferences: ModelPreferences
        ): ModelRetentionProvider = ModelRetentionProvider {
            try {
                preferences.modelDirectionRetention.first() !=
                    ModelPreferences.RETENTION_UNLOAD
            } catch (e: CancellationException) {
                // Отмену корутины не проглатываем — иначе движок решит,
                // что настройка прочитана, и применит дефолт вместо отмены.
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Не удалось прочитать настройку удержания моделей, беру keep")
                true
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
