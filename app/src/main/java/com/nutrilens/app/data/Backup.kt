package com.nutrilens.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Экспорт/импорт полной резервной копии приложения.
 * Формат JSON: settings, meals, favorites, weights, water, workouts,
 * habits, body_measurements, chat_history (сырой массив из ChatStore), grocery (сырой объект из GroceryStore).
 * Старые копии без body_measurements читаются как «замеров нет».
 * settings сериализуется целиком (encodeDefaults), поэтому план режима «Сжигание жира»
 * (planCalories…planTargetFat) попадает в копию сам; в копиях до 3.21 этих полей нет —
 * берутся значения по умолчанию (плана нет), и он пересчитается при следующем запуске.
 * С 3.22 — check_ins (недельные чек-ины) и настройки чек-ина в settings; в старых копиях
 * их нет — чек-инов нет, день чек-ина воскресенье, цель силовых 3.
 * С 3.23 — day_activity (шаги и «поспал 8 часов» по дням) и настройки шагов в settings;
 * в старых копиях их нет — активности нет, шаги выключены, цель 8000.
 * С 3.24 — программа с этапами (settings.fatBurnProgram, JSON-строка) и смена этапа в check_ins;
 * в старых копиях их нет — программа соберётся сама из даты включения режима при первом запуске.
 */
object Backup {
    private const val ERROR_MESSAGE = "Неверный формат файла резервной копии"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun readRaw(context: Context, fileName: String): String? = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, fileName)
        if (f.exists()) f.readText() else null
    }

    suspend fun exportJson(context: Context, db: NutriLensDatabase): String = withContext(Dispatchers.IO) {
        val settings = db.settingsDao().get()
        val meals = db.mealDao().allMeals()
        val favorites = db.favoriteDao().allList()
        val weights = db.weightDao().all()
        val water = db.waterDao().all()
        val workouts = db.workoutDao().all()
        val habits = db.habitLogDao().all()
        val measurements = db.bodyMeasurementDao().all()
        val checkIns = db.checkInDao().all()
        val activity = db.dayActivityDao().all()

        val chatRaw = readRaw(context, ChatStore.FILE_NAME)
        val groceryRaw = readRaw(context, GroceryStore.FILE_NAME)

        buildJsonObject {
            put("settings", settings?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            put("meals", json.encodeToJsonElement(meals))
            put("favorites", json.encodeToJsonElement(favorites))
            put("weights", json.encodeToJsonElement(weights))
            put("water", json.encodeToJsonElement(water))
            put("workouts", json.encodeToJsonElement(workouts))
            put("habits", json.encodeToJsonElement(habits))
            put("body_measurements", json.encodeToJsonElement(measurements))
            put("check_ins", json.encodeToJsonElement(checkIns))
            put("day_activity", json.encodeToJsonElement(activity))
            put(
                "chat_history",
                chatRaw?.let { raw -> runCatching { json.parseToJsonElement(raw) }.getOrNull() }
                    ?: JsonArray(emptyList())
            )
            put(
                "leaderboard_player_id",
                kotlinx.serialization.json.JsonPrimitive(
                    com.nutrilens.app.leaderboard.PlayerIdentity.id(context)
                )
            )
            put("recipes", recipesForBackup(db))
            put("leaderboard_player_secret", kotlinx.serialization.json.JsonPrimitive(com.nutrilens.app.leaderboard.PlayerIdentity.secret(context)))
            put(
                "grocery",
                groceryRaw?.let { raw -> runCatching { json.parseToJsonElement(raw) }.getOrNull() }
                    ?: JsonNull
            )
        }.toString()
    }

    suspend fun importJson(context: Context, db: NutriLensDatabase, jsonString: String) {
        val root = try {
            json.parseToJsonElement(jsonString).jsonObject
        } catch (e: Exception) {
            throw RuntimeException(ERROR_MESSAGE)
        }

        // Бэкап из веб-версии — поля хранятся JSON-строками: конвертируем.
        // Рецепты — в обоих форматах одинаковые: добавляются к книге, а не заменяют её.
        importRecipesFromBackup(db, root["recipes"])

        if (WebBackupImport.isWebFormat(root)) {
            WebBackupImport.import(context, db, root)
            return
        }

        // Свой id в лидерборде переезжает вместе с данными — без дубля в таблице.
        (root["leaderboard_player_id"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
            ?.let { com.nutrilens.app.leaderboard.PlayerIdentity.restore(context, it) }
        (root["leaderboard_player_secret"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
            ?.let { com.nutrilens.app.leaderboard.PlayerIdentity.restoreSecret(context, it) }

        db.withTransaction {
            db.settingsDao().deleteAll()
            db.mealDao().deleteAll()
            db.mealDao().deleteAllImages()
            db.mealDao().deleteAllItems()
            db.favoriteDao().deleteAll()
            db.weightDao().deleteAll()
            db.waterDao().deleteAll()
            db.workoutDao().deleteAll()
            db.habitLogDao().deleteAll()
            db.bodyMeasurementDao().deleteAll()
            db.checkInDao().deleteAll()
            db.dayActivityDao().deleteAll()

            val settings = try {
                root["settings"]?.let { json.decodeFromJsonElement<SettingsEntity?>(it) }
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            settings?.let { db.settingsDao().upsert(it) }

            val meals = try {
                json.decodeFromJsonElement<List<MealEntity>>(root["meals"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            meals.forEach { db.mealDao().insertMeal(it) }

            val favorites = try {
                json.decodeFromJsonElement<List<FavoriteEntity>>(root["favorites"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            favorites.forEach { db.favoriteDao().upsert(it) }

            val weights = try {
                json.decodeFromJsonElement<List<WeightEntity>>(root["weights"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            weights.forEach { db.weightDao().upsert(it) }

            val water = try {
                json.decodeFromJsonElement<List<WaterEntity>>(root["water"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            water.forEach { db.waterDao().upsert(it) }

            val workouts = try {
                json.decodeFromJsonElement<List<WorkoutEntity>>(root["workouts"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            workouts.forEach { db.workoutDao().upsert(it) }

            val habits = try {
                json.decodeFromJsonElement<List<HabitLogEntity>>(root["habits"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            habits.forEach { db.habitLogDao().insert(it) }

            // Замеры появились в 3.20: в старых копиях поля нет — просто пусто.
            val measurements = try {
                json.decodeFromJsonElement<List<BodyMeasurementEntity>>(root["body_measurements"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            measurements.forEach { db.bodyMeasurementDao().upsert(it) }

            // Чек-ины появились в 3.22: в старых копиях поля нет — просто пусто.
            val checkIns = try {
                json.decodeFromJsonElement<List<CheckInEntity>>(root["check_ins"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            checkIns.forEach { db.checkInDao().upsert(it) }

            // Активность дня появилась в 3.23: в старых копиях поля нет — просто пусто.
            val activity = try {
                json.decodeFromJsonElement<List<DayActivityEntity>>(root["day_activity"] ?: JsonArray(emptyList()))
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            activity.forEach { db.dayActivityDao().upsert(it) }
        }

        root["chat_history"]?.let { element ->
            if (element !is JsonArray) throw RuntimeException(ERROR_MESSAGE)
            val messages = try {
                json.decodeFromJsonElement<List<ChatMessage>>(element)
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            ChatStore.save(context, messages)
        }

        root["grocery"]?.let { element ->
            val data = try {
                json.decodeFromJsonElement<GroceryData?>(element)
            } catch (e: Exception) {
                throw RuntimeException(ERROR_MESSAGE)
            }
            data?.let { GroceryStore.save(context, it) }
        }
    }
}