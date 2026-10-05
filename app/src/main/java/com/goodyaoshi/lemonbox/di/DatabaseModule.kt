package com.goodyaoshi.lemonbox.di

import android.content.Context
import com.goodyaoshi.lemonbox.data.local.dao.AnniversaryDao
import com.goodyaoshi.lemonbox.data.local.dao.CategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerBudgetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecordDao
import com.goodyaoshi.lemonbox.data.local.dao.LocationDao
import com.goodyaoshi.lemonbox.data.local.dao.ReminderDao
import com.goodyaoshi.lemonbox.data.local.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.buildDatabase(context)
    }

    @Provides
    fun provideItemDao(database: AppDatabase): ItemDao = database.itemDao()

    @Provides
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun provideLocationDao(database: AppDatabase): LocationDao = database.locationDao()

    @Provides
    fun provideReminderDao(database: AppDatabase): ReminderDao = database.reminderDao()

    @Provides
    fun provideAnniversaryDao(database: AppDatabase): AnniversaryDao = database.anniversaryDao()

    @Provides
    fun provideLedgerRecordDao(database: AppDatabase): LedgerRecordDao = database.ledgerRecordDao()

    @Provides
    fun provideLedgerCategoryDao(database: AppDatabase): LedgerCategoryDao =
        database.ledgerCategoryDao()

    @Provides
    fun provideLedgerAssetDao(database: AppDatabase): LedgerAssetDao = database.ledgerAssetDao()

    @Provides
    fun provideLedgerBudgetDao(database: AppDatabase): LedgerBudgetDao =
        database.ledgerBudgetDao()
}
