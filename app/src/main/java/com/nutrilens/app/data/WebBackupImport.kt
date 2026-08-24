package com.nutrilens.app.data

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/**
 * Импорт резервной копии из веб-версии (NutriLens PWA).
 *
 * Веб-бэкап — это JSON-объект, где каждый ключ хранит JSON-СТРОКУ из
 * localStorage (см. FreshSettings.tsx / useStore.tsx):
 *   { settings, meals, favorites, weights, grocery, grocery_checked, chat_history }
 * meals[] имеют поля: id, date, time, name, calories, protein, fat, carbs,
 * ai_thoughts, reasoning, confidence_score, images[] (base64), image (base64),
 * items[] (разбор по продуктам), dailyGoalSnapshot.
 *
 * Конвертер «умеет» и нестроковые значения (на случай ручных правок файла) и
 * дополнительные ключи веба: water, workouts, habits_log.
 */
object WebBackupImport {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Признак веб-формата: ключевые поля приходят строками. */
    fun isWebFormat(root: JsonObject): Boolean {
        fun looksWeb(key: String): Boolean {
            val v = root[key] ?: return false
            return v is JsonPrimitive && (v.isString || v.contentOrNull != null)
        }
        return looksWeb("meals") || looksWeb("favorites") || looksWeb("weights")
    }

    /**
     * Поля веб-бэкапа — JSON-строки; одиночные правки могли оставить сырые
     * значения. Парсим строку лениво, иначе возвращаем как есть.
     */
    private fun parseField(root: JsonObject, key: String): JsonElement? {
        val raw = root[key] ?: return null
        if (raw is JsonPrimitive) {
            val content = raw.contentOrNull ?: return raw
            return runCatching { json.parseToJsonElement(content) }.getOrElse { raw }
        }
        return raw
    }

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject

    private fun arr(e: JsonElement?): List<JsonElement>? = (e as? JsonArray)?.toList()

    private fun str(o: JsonObject, key: String): String? =
        o[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun num(o: JsonObject, key: String): Double? =
        o[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

    suspend fun import(context: Context, db: NutriLensDatabase, root: JsonObject) {
        // Папка для фото из веба (base64 → jpg).
        val importDir = withContext(Dispatchers.IO) {
            File(context.filesDir, "photos/import").apply { mkdirs() }
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

            importSettings(db, root)
            importMeals(context, db, root, importDir)
            importFavorites(db, root)
            importWeights(db, root)
            importWater(db, root)
            importWorkouts(db, root)
            importHabits(db, root)
        }

        importChat(context, root)
        importGrocery(context, root)
    }

    // ---- settings ----

    private suspend fun importSettings(db: NutriLensDatabase, root: JsonObject) {
        val s = obj(parseField(root, "settings")) ?: return
        val current = db.settingsDao().get() ?: SettingsEntity()
        val mode = when (str(s, "apiMode")) {
            "simple" -> "simple"
            "advanced" -> "advanced"
            else -> "free"
        }
        db.settingsDao().upsert(
            current.copy(
                apiKey = str(s, "apiKey") ?: current.apiKey,
                nanoApiKey = str(s, "nanoApiKey") ?: current.nanoApiKey,
                nanoApiEndpoint = str(s, "nanoApiEndpoint") ?: current.nanoApiEndpoint,
                dailyGoal = num(s, "dailyGoal") ?: current.dailyGoal,
                userContext = str(s, "userContext") ?: current.userContext,
                analysisMode = mode
            )
        )
    }

    // ---- meals ----

    private suspend fun importMeals(
        context: Context,
        db: NutriLensDatabase,
        root: JsonObject,
        importDir: File
    ) {
        val meals = arr(parseField(root, "meals")) ?: return
        meals.forEach { el ->
            val m = obj(el) ?: return@forEach
            val id = str(m, "id") ?: UUID.randomUUID().toString()
            val date = str(m, "date") ?: return@forEach
            val time = str(m, "time") ?: "12:00"
            db.mealDao().insertMeal(
                MealEntity(
                    id = id,
                    date = date,
                    time = time,
                    name = str(m, "name") ?: "Блюдо",
                    calories = num(m, "calories") ?: 0.0,
                    protein = num(m, "protein") ?: 0.0,
                    fat = num(m, "fat") ?: 0.0,
                    carbs = num(m, "carbs") ?: 0.0,
                    aiThoughts = str(m, "ai_thoughts") ?: "",
                    reasoning = str(m, "reasoning") ?: "",
                    confidenceScore = num(m, "confidence_score") ?: 0.0,
                    healthScore = num(m, "health_score")?.toInt(),
                    healthNote = str(m, "health_note"),
                    dailyGoalSnapshot = num(m, "dailyGoalSnapshot"),
                    createdAt = parseDateMillis(date) ?: System.currentTimeMillis()
                )
            )
            importMealImages(context, db, m, id, importDir)
            importMealItems(db, m, id)
        }
    }

    private suspend fun importMealImages(
        context: Context,
        db: NutriLensDatabase,
        m: JsonObject,
        mealId: String,
        importDir: File
    ) {
        val images = buildList {
            (m["images"] as? JsonArray)?.forEach { add(it) }
            m["image"]?.let { add(it) }
        }
        val saved = mutableListOf<MealImageEntity>()
        images.forEachIndexed { index, el ->
            val b64 = (el as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
            runCatching {
                val clean = b64.substringBefore("base64,").let { if (it.isNotEmpty()) b64.substringAfter("base64,") else b64 }
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                if (bytes.isEmpty()) return@forEachIndexed
                val file = File(importDir, "${mealId}_$index.jpg")
                file.writeBytes(bytes)
                saved += MealImageEntity(
                    id = UUID.randomUUID().toString(),
                    mealId = mealId,
                    path = file.absolutePath,
                    kind = "FULL",
                    sortIndex = index
                )
            }
        }
        if (saved.isNotEmpty()) db.mealDao().insertImages(saved)
    }

    private suspend fun importMealItems(db: NutriLensDatabase, m: JsonObject, mealId: String) {
        val items = (m["items"] as? JsonArray)?.toList() ?: return
        val saved = mutableListOf<MealItemEntity>()
        items.forEach { el ->
            val itm = obj(el) ?: return@forEach
            saved += MealItemEntity(
                id = UUID.randomUUID().toString(),
                mealId = mealId,
                name = str(itm, "name") ?: "Продукт",
                weightG = num(itm, "estimated_weight_g") ?: 0.0,
                portionBasis = str(itm, "portion_basis") ?: "",
                calorieDensity = num(itm, "calorie_density") ?: 0.0,
                calories = num(itm, "calories") ?: 0.0,
                protein = num(itm, "protein") ?: 0.0,
                fat = num(itm, "fat") ?: 0.0,
                carbs = num(itm, "carbs") ?: 0.0,
                breakdown = str(itm, "breakdown") ?: ""
            )
        }
        if (saved.isNotEmpty()) db.mealDao().insertItems(saved)
    }

    // ---- favorites / weights / water / workouts / habits ----

    private suspend fun importFavorites(db: NutriLensDatabase, root: JsonObject) {
        arr(parseField(root, "favorites"))?.forEach { el ->
            val f = obj(el) ?: return@forEach
            db.favoriteDao().upsert(
                FavoriteEntity(
                    id = str(f, "id") ?: UUID.randomUUID().toString(),
                    name = str(f, "name") ?: return@forEach,
                    calories = num(f, "calories") ?: 0.0,
                    protein = num(f, "protein") ?: 0.0,
                    fat = num(f, "fat") ?: 0.0,
                    carbs = num(f, "carbs") ?: 0.0
                )
            )
        }
    }

    private suspend fun importWeights(db: NutriLensDatabase, root: JsonObject) {
        arr(parseField(root, "weights"))?.forEach { el ->
            val w = obj(el) ?: return@forEach
            val date = str(w, "date") ?: return@forEach
            val weight = num(w, "weight") ?: return@forEach
            db.weightDao().upsert(WeightEntity(date = date, weight = weight))
        }
    }

    private suspend fun importWater(db: NutriLensDatabase, root: JsonObject) {
        // Веб хранит воду как { 'YYYY-MM-DD': мл }.
        obj(parseField(root, "water"))?.forEach { (date, v) ->
            val ml = (v as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            db.waterDao().upsert(WaterEntity(date = date, ml = ml))
        }
    }

    private suspend fun importWorkouts(db: NutriLensDatabase, root: JsonObject) {
        obj(parseField(root, "workouts"))?.forEach { (date, v) ->
            val done = (v as? JsonPrimitive)?.booleanOrNull ?: (v as? JsonPrimitive)?.contentOrNull == "true"
            db.workoutDao().upsert(WorkoutEntity(date = date, done = done))
        }
    }

    private suspend fun importHabits(db: NutriLensDatabase, root: JsonObject) {
        obj(parseField(root, "habits_log"))?.forEach { (date, ids) ->
            (ids as? JsonArray)?.forEach { h ->
                val habitId = (h as? JsonPrimitive)?.contentOrNull ?: return@forEach
                db.habitLogDao().insert(HabitLogEntity(date = date, habitId = habitId))
            }
        }
    }

    // ---- chat / grocery ----

    private suspend fun importChat(context: Context, root: JsonObject) {
        val messages = arr(parseField(root, "chat_history"))?.mapNotNull { el ->
            val o = obj(el) ?: return@mapNotNull null
            ChatMessage(
                role = str(o, "role") ?: return@mapNotNull null,
                text = str(o, "text") ?: ""
            )
        } ?: return
        if (messages.isNotEmpty()) ChatStore.save(context, messages)
    }

    private suspend fun importGrocery(context: Context, root: JsonObject) {
        val data = runCatching {
            json.decodeFromJsonElement<GroceryData?>(parseField(root, "grocery") ?: JsonNull)
        }.getOrNull() ?: return
        GroceryStore.save(context, data)
    }

    /** Миллисекунды из даты 'YYYY-MM-DD' (полдень по локальному времени). */
    private fun parseDateMillis(date: String): Long? =
        runCatching {
            java.time.LocalDate.parse(date)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
}