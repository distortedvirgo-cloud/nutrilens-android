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

/** v4: оценка полезности блюда от ИИ (health_score/health_note) взамен видимой уверенности. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE meals ADD COLUMN healthScore INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE meals ADD COLUMN healthNote TEXT DEFAULT NULL")
    }
}

/** v5: очередь уточнений уже добавленных блюд («Поправить»). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `refinement_jobs` (" +
                "`id` TEXT NOT NULL, `mealId` TEXT NOT NULL, `correction` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `error` TEXT, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/** v6: очередь фоновых задач ИИ-инструментов «Ещё» (идеи, холодильник, меню, покупки, вода, привычки). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tool_jobs` (" +
                "`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `input` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `result` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`error` TEXT, PRIMARY KEY(`id`))"
        )
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
        AnalysisJobEntity::class,
        RefinementJobEntity::class,
        ToolJobEntity::class
    ],
    version = 6,
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
    abstract fun refinementJobDao(): RefinementJobDao
    abstract fun toolJobDao(): ToolJobDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { INSTANCE = it }
            }
        }
    }
}