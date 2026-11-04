package com.nutrilens.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Книга рецептов. Формат рецепта — тот же JSON, что в веб-версии (web/src/data/db.ts),
 * поэтому рецепты переезжают через бэкап в обе стороны. В Room рецепт лежит целиком
 * JSON-строкой: структура вложенная, а запросы нужны простые (список, по id).
 */

@Serializable
data class RecipeIngredient(
    val name: String,
    /** Количество на исходное число порций; null — «по вкусу». */
    val amount: Double? = null,
    val unit: String = "",
    /** Примерный вес на весь рецепт, г. */
    val grams: Double? = null,
    /** Ккал ингредиента на весь рецепт. */
    val calories: Double? = null,
    val emoji: String = "🥄",
    val group: String = "",
    val note: String = ""
)

@Serializable
data class RecipeStep(val text: String, val minutes: Double? = null)

@Serializable
data class RecipeNutrition(val calories: Double = 0.0, val protein: Double = 0.0, val fat: Double = 0.0, val carbs: Double = 0.0)

@Serializable
data class RecipeSource(val kind: String = "text", val url: String = "", val author: String = "")

@Serializable
data class Recipe(
    val id: String,
    val title: String,
    val description: String = "",
    val emoji: String = "🍽️",
    /** URL превью (видео/страница) или "file:<путь>" — кадр/фото на устройстве. */
    val cover: String? = null,
    val servings: Int = 2,
    val totalMinutes: Int? = null,
    val difficulty: String = "medium",
    val category: String = "lunch",
    val tags: List<String> = emptyList(),
    val ingredients: List<RecipeIngredient> = emptyList(),
    val steps: List<RecipeStep> = emptyList(),
    /** КБЖУ на одну порцию. */
    val nutrition: RecipeNutrition = RecipeNutrition(),
    val tips: List<String> = emptyList(),
    val source: RecipeSource = RecipeSource(),
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val cookedCount: Int = 0
)

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val json: String,
    val createdAt: Long,
    val favorite: Boolean
)

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes ORDER BY createdAt DESC")
    suspend fun all(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observe(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun get(id: String): RecipeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun delete(id: String)
}

val recipeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

private fun RecipeEntity.toRecipe(): Recipe? = runCatching { recipeJson.decodeFromString<Recipe>(json) }.getOrNull()

class RecipeRepository(private val db: NutriLensDatabase) {
    private val dao = db.recipeDao()

    fun observeAll(): Flow<List<Recipe>> = dao.observeAll().map { list -> list.mapNotNull { it.toRecipe() } }
    fun observe(id: String): Flow<Recipe?> = dao.observe(id).map { it?.toRecipe() }
    suspend fun get(id: String): Recipe? = dao.get(id)?.toRecipe()
    suspend fun all(): List<Recipe> = dao.all().mapNotNull { it.toRecipe() }

    suspend fun put(r: Recipe) {
        dao.upsert(RecipeEntity(r.id, recipeJson.encodeToString(r), r.createdAt, r.favorite))
        DataEvents.notifyChanged()
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        DataEvents.notifyChanged()
    }

    /** Съеденные порции — в дневник: КБЖУ на порцию × порции, состав — ингредиенты. */
    suspend fun logToDiary(r: Recipe, portions: Double): MealEntity {
        val settings = SettingsRepository(db.settingsDao()).get()
        val f = portions / r.servings
        val n = r.nutrition
        val meal = MealEntity(
            id = UUID.randomUUID().toString(),
            date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
            time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            name = if (portions == 1.0) r.title else "${r.title} ×${RecipeFormat.amount(portions, "шт")}",
            calories = (n.calories * portions).roundToInt().toDouble(),
            protein = round1(n.protein * portions),
            fat = round1(n.fat * portions),
            carbs = round1(n.carbs * portions),
            aiThoughts = "Из книги рецептов: ${RecipeFormat.amount(portions, "шт")} порц. из ${r.servings}.",
            confidenceScore = 8.0,
            dailyGoalSnapshot = settings.dailyGoal
        )
        db.mealDao().insertMeal(meal)
        db.mealDao().insertItems(
            r.ingredients.filter { (it.calories ?: 0.0) > 0 || (it.grams ?: 0.0) > 0 }.map {
                MealItemEntity(
                    id = UUID.randomUUID().toString(), mealId = meal.id, name = it.name,
                    weightG = ((it.grams ?: 0.0) * f).roundToInt().toDouble(),
                    calories = ((it.calories ?: 0.0) * f).roundToInt().toDouble(),
                    protein = 0.0, fat = 0.0, carbs = 0.0,
                    breakdown = RecipeFormat.ingredientAmount(it, f)
                )
            }
        )
        put(r.copy(cookedCount = r.cookedCount + 1))
        return meal
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}

/** Красивые количества: 1½, 0,3, 250 — как в веб-версии. */
object RecipeFormat {
    private val fractions = listOf(0.25 to "¼", 0.33 to "⅓", 0.5 to "½", 0.67 to "⅔", 0.75 to "¾")
    private val countable = Regex("^(шт|зубч|ст\\. ?л|ч\\. ?л|стак|пуч|щеп|лист|ломт|банк|упак)", RegexOption.IGNORE_CASE)

    fun amount(v: Double, unit: String): String {
        if (countable.containsMatchIn(unit) && v < 20) {
            val whole = floor(v).toInt()
            val frac = v - whole
            if (frac < 0.06) return (if (whole == 0) 1 else whole).toString()
            if (frac > 0.94) return (whole + 1).toString()
            fractions.firstOrNull { abs(frac - it.first) < 0.06 }?.let { return (if (whole > 0) "$whole" else "") + it.second }
        }
        return when {
            v >= 100 -> ((v / 5).roundToInt() * 5).toString()
            v >= 10 -> v.roundToInt().toString()
            else -> ((v * 10).roundToInt() / 10.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString().replace('.', ',') }
        }
    }

    fun ingredientAmount(i: RecipeIngredient, factor: Double): String {
        val a = i.amount
        if (a == null || a <= 0) return i.unit.ifBlank { "по вкусу" }
        return "${amount(a * factor, i.unit)} ${i.unit}".trim()
    }

    fun shoppingText(r: Recipe, servings: Int): String {
        val f = servings.toDouble() / r.servings
        return (listOf("${r.emoji} ${r.title} — $servings порц.") + r.ingredients.map { "• ${it.name} — ${ingredientAmount(it, f)}" })
            .joinToString("\n")
    }

    val CATEGORY = mapOf(
        "breakfast" to "Завтраки", "lunch" to "Обеды", "dinner" to "Ужины",
        "snack" to "Перекусы", "dessert" to "Десерты", "drink" to "Напитки"
    )
    val DIFFICULTY = mapOf("easy" to "Легко", "medium" to "Средне", "hard" to "Сложно")
}

/** Рецепты из бэкапа (своего или веб-версии): добавляются к книге, совпавшие по id — обновляются. */
suspend fun importRecipesFromBackup(db: NutriLensDatabase, el: kotlinx.serialization.json.JsonElement?) {
    val arr = el as? kotlinx.serialization.json.JsonArray ?: return
    val repo = RecipeRepository(db)
    arr.forEach { item ->
        runCatching { recipeJson.decodeFromJsonElement(Recipe.serializer(), item) }.getOrNull()
            ?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() && it.ingredients.isNotEmpty() }
            ?.let { r -> repo.put(if (r.cover?.startsWith("photo:") == true) r.copy(cover = null) else r) }
    }
}

/** Для бэкапа: обложки-файлы с устройства не переносятся, ссылки-превью остаются. */
suspend fun recipesForBackup(db: NutriLensDatabase): kotlinx.serialization.json.JsonElement =
    recipeJson.encodeToJsonElement(
        kotlinx.serialization.builtins.ListSerializer(Recipe.serializer()),
        RecipeRepository(db).all().map { if (it.cover?.startsWith("file:") == true) it.copy(cover = null) else it }
    )
