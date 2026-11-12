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

/** v7: ник участника лидерборда в настройках. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN leaderboardNickname TEXT NOT NULL DEFAULT ''")
    }
}

/** v8: книга рецептов (рецепт целиком JSON-строкой). */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS recipes (id TEXT NOT NULL, json TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, favorite INTEGER NOT NULL, PRIMARY KEY(id))"
        )
    }
}

/**
 * v9: цель по проценту жира — замеры тела (обхваты + свой процент) и в settings
 * цель, рост и пол. Схема таблицы обязана совпадать с BodyMeasurementEntity один-в-один,
 * иначе Room упадёт при обновлении.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `body_measurements` (" +
                "`date` TEXT NOT NULL, `waistCm` REAL, `neckCm` REAL, `hipsCm` REAL, " +
                "`manualFatPercent` REAL, `fatPercent` REAL NOT NULL, PRIMARY KEY(`date`))"
        )
        db.execSQL("ALTER TABLE settings ADD COLUMN targetFatPercent REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN heightCm REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN sex TEXT NOT NULL DEFAULT ''")
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
        ToolJobEntity::class,
        RecipeEntity::class,
        BodyMeasurementEntity::class
    ],
    version = 9,
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
    abstract fun recipeDao(): RecipeDao
    abstract fun bodyMeasurementDao(): BodyMeasurementDao

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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
                    )
                    .build().also { INSTANCE = it }
            }
        }
    }
}