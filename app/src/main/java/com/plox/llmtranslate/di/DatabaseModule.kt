package com.plox.llmtranslate.di

import android.content.Context
import androidx.room.Room
import com.plox.llmtranslate.data.model.AppDatabase
import com.plox.llmtranslate.data.model.ModelDao
import com.plox.llmtranslate.data.model.ModelRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt-модуль базы данных.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "llm_translate.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideModelDao(db: AppDatabase): ModelDao = db.modelDao()

    @Provides
    @Singleton
    fun provideModelRepository(dao: ModelDao): ModelRepository = ModelRepository(dao)
}
