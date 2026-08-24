package com.nutrilens.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2: в settings добавлено поле updateRepo (репозиторий автообновлений). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE settings ADD COLUMN updateRepo TEXT NOT NULL DEFAULT 'distortedvirgo-cloud/nutrilens-android'"
        )
    }
}

/** v3: NanoGPT-ключ, свой эндпоинт и режим анализа (free/simple/advanced). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN nanoApiKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE settings ADD COLUMN nanoApiEndpoint TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE settings ADD COLUMN analysisMode TEXT NOT NULL DEFAULT 'free'")
    }
}

@Database(
    entities = [
        MealEntity::class,
        MealImageEntity::class,
        MealItemEntity::class,
        FavoriteEntity::class,
        WeightEntity::class,
        WaterEntity::class,
        WorkoutEntity::class,
        HabitLogEntity::class,
        SettingsEntity::class,
        AnalysisJobEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NutriLensDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao
    abstract fun weightDao(): WeightDao
    abstract fun waterDao(): WaterDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun habitLogDao(): HabitLogDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun settingsDao(): SettingsDao
    abstract fun analysisJobDao(): AnalysisJobDao

    companion object {
        @Volatile
        private var INSTANCE: NutriLensDatabase? = null

        fun getInstance(context: Context): NutriLensDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NutriLensDatabase::class.java,
                    "nutrilens.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
        }
    }
}