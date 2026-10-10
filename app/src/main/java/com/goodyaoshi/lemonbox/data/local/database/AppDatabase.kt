package com.goodyaoshi.lemonbox.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.goodyaoshi.lemonbox.data.local.dao.AnniversaryDao
import com.goodyaoshi.lemonbox.data.local.dao.CategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerBudgetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecordDao
import com.goodyaoshi.lemonbox.data.local.dao.LocationDao
import com.goodyaoshi.lemonbox.data.local.dao.ReminderDao
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.Reminder

/**
 * 应用数据库装配入口。
 *
 * 只保留「实体清单 + DAO 暴露 + 建库」三件事：迁移集中在 `Migrations.kt`，
 * 种子数据 / 历史数据归一化集中在 `SeedDataProvider.kt`（F9），
 * 单个文件因此控制在百行以内，改动数据库版本时不必在千行文件里翻找。
 */
@Database(
    entities = [
        Item::class,
        Category::class,
        Location::class,
        Reminder::class,
        Anniversary::class,
        LedgerRecord::class,
        LedgerCategory::class,
        LedgerAsset::class,
        LedgerBudget::class
    ],
    version = 26,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun categoryDao(): CategoryDao
    abstract fun locationDao(): LocationDao
    abstract fun reminderDao(): ReminderDao
    abstract fun anniversaryDao(): AnniversaryDao
    abstract fun ledgerRecordDao(): LedgerRecordDao
    abstract fun ledgerCategoryDao(): LedgerCategoryDao
    abstract fun ledgerAssetDao(): LedgerAssetDao
    abstract fun ledgerBudgetDao(): LedgerBudgetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun buildDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lemonbox.db"
                )
                    // 迁移集中在 Migrations.kt（F9），装配处只引用同一份有序列表。
                    .addMigrations(*Migrations.ALL)
                    .addCallback(PrepopulateCallback())
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }

    /**
     * 种子数据只在数据库首次创建时写入；历史数据的归一化交给一次性迁移处理，
     * 不再每次打开数据库都跑一遍 SQL。
     */
    private class PrepopulateCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            SeedDataProvider.ensureSeedData(db)
            SeedDataProvider.ensureProtectedSeedData(db)
            SeedDataProvider.ensureLedgerSeedData(db)
        }
    }
}
