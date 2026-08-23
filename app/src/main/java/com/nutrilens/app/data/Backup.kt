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
 * habits, chat_history (сырой массив из ChatStore), grocery (сырой объект из GroceryStore).
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
            put(
                "chat_history",
                chatRaw?.let { raw -> runCatching { json.parseToJsonElement(raw) }.getOrNull() }
                    ?: JsonArray(emptyList())
            )
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