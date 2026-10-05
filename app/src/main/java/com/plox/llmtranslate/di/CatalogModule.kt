package com.plox.llmtranslate.di

import android.content.Context
import com.plox.llmtranslate.data.catalog.AssetsModelCatalogProvider
import com.plox.llmtranslate.data.catalog.ModelCatalogParser
import com.plox.llmtranslate.data.catalog.ModelCatalogProvider
import com.plox.llmtranslate.data.catalog.RemoteCatalogProvider
import com.plox.llmtranslate.data.download.GgufInstaller
import com.plox.llmtranslate.data.download.ModelInstaller
import com.plox.llmtranslate.data.download.OnnxInstaller
import com.plox.llmtranslate.data.download.VoskInstaller
import com.plox.llmtranslate.data.prefs.ModelPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.multibindings.IntoList
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt-модуль каталога и инсталляторов.
 */
@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {

    @Provides
    @Singleton
    fun provideAssetsCatalogProvider(@ApplicationContext context: Context): AssetsModelCatalogProvider =
        AssetsModelCatalogProvider(context)

    @Provides
    @Singleton
    fun provideCatalogParser(): ModelCatalogParser = ModelCatalogParser()

    @Provides
    @Singleton
    fun provideCatalogProvider(
        @ApplicationContext context: Context,
        assetsProvider: AssetsModelCatalogProvider,
        parser: ModelCatalogParser,
        preferences: ModelPreferences
    ): ModelCatalogProvider =
        RemoteCatalogProvider(context, assetsProvider, parser, preferences)

    @Provides
    @IntoList
    fun provideGgufInstaller(): ModelInstaller = GgufInstaller()

    @Provides
    @IntoList
    fun provideOnnxInstaller(): ModelInstaller = OnnxInstaller()

    @Provides
    @IntoList
    fun provideVoskInstaller(): ModelInstaller = VoskInstaller()
}
