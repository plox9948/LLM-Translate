package com.plox.llmtranslate.di

import android.content.Context
import com.plox.llmtranslate.data.catalog.AssetsModelCatalogProvider
import com.plox.llmtranslate.data.catalog.ModelCatalogProvider
import com.plox.llmtranslate.data.download.GgufInstaller
import com.plox.llmtranslate.data.download.ModelInstaller
import com.plox.llmtranslate.data.download.OnnxInstaller
import com.plox.llmtranslate.data.download.VoskInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
    fun provideCatalogProvider(@ApplicationContext context: Context): ModelCatalogProvider =
        AssetsModelCatalogProvider(context)

    @Provides
    @Singleton
    fun provideInstallers(): List<ModelInstaller> =
        listOf(GgufInstaller(), OnnxInstaller(), VoskInstaller())
}
