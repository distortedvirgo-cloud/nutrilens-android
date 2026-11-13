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

/**
 * v10: режим «Сжигание жира» — сохранённый недельный план в settings.
 * Типы и nullability обязаны совпадать с SettingsEntity: Double? → REAL без NOT NULL,
 * String → TEXT NOT NULL DEFAULT '', Boolean → INTEGER NOT NULL DEFAULT 0.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN planCalories REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN planProtein REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN planFat REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN planCarbs REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN planTdee REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN planNote TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE settings ADD COLUMN planDate TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN planObserved INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE settings ADD COLUMN planTargetFat REAL DEFAULT NULL")
    }
}

/**
 * v11: недельный чек-ин — таблица check_ins (схема один-в-один с CheckInEntity:
 * Double → REAL NOT NULL, Double?/Int? → REAL/INTEGER без NOT NULL, Int → INTEGER NOT NULL,
 * String → TEXT NOT NULL) и в settings день чек-ина, цель силовых, дата включения режима
 * и дата прошлого чек-ина.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `check_ins` (" +
                "`date` TEXT NOT NULL, `weight` REAL NOT NULL, `trendWeight` REAL, `fatPercent` REAL, " +
                "`hunger` INTEGER, `energy` INTEGER, `sleep` INTEGER, " +
                "`periodDays` INTEGER NOT NULL, `diaryDays` INTEGER NOT NULL, `goodDays` INTEGER NOT NULL, " +
                "`proteinDays` INTEGER NOT NULL, `workouts` INTEGER NOT NULL, `workoutGoal` INTEGER NOT NULL, " +
                "`trendChangeKg` REAL, `weeklyRatePercent` REAL, `fatChange` REAL, " +
                "`muscleSignal` TEXT NOT NULL, `signalNote` TEXT NOT NULL, `flatWeeks` INTEGER NOT NULL, " +
                "`caloriesBefore` REAL, `caloriesAfter` REAL, `proteinBefore` REAL, `proteinAfter` REAL, " +
                "`fatBefore` REAL, `fatAfter` REAL, `carbsBefore` REAL, `carbsAfter` REAL, " +
                "`note` TEXT NOT NULL, `nextAction` TEXT NOT NULL, PRIMARY KEY(`date`))"
        )
        db.execSQL("ALTER TABLE settings ADD COLUMN checkInWeekday INTEGER NOT NULL DEFAULT 7")
        db.execSQL("ALTER TABLE settings ADD COLUMN workoutGoal INTEGER NOT NULL DEFAULT 3")
        db.execSQL("ALTER TABLE settings ADD COLUMN fatBurnStartDate TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE settings ADD COLUMN lastCheckInDate TEXT DEFAULT NULL")
    }
}

/**
 * v12 (3.23): активность дня — таблица day_activity (шаги, источник шагов, «поспал 8 часов»;
 * схема один-в-один с DayActivityEntity: все поля кроме даты nullable), в settings
 * шаги включены и цель шагов, в check_ins — средняя оценка дня за период.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_activity` (" +
                "`date` TEXT NOT NULL, `steps` INTEGER, `stepsSource` TEXT, `sleptWell` INTEGER, " +
                "PRIMARY KEY(`date`))"
        )
        db.execSQL("ALTER TABLE settings ADD COLUMN stepsEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE settings ADD COLUMN stepGoal INTEGER NOT NULL DEFAULT 8000")
        db.execSQL("ALTER TABLE check_ins ADD COLUMN avgScore REAL DEFAULT NULL")
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
        BodyMeasurementEntity::class,
        CheckInEntity::class,
        DayActivityEntity::class
    ],
    version = 12,
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
    abstract fun checkInDao(): CheckInDao
    abstract fun dayActivityDao(): DayActivityDao

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
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
                    )
                    .build().also { INSTANCE = it }
            }
        }
    }
}