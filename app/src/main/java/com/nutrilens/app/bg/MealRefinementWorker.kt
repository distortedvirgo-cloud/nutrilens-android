package com.nutrilens.app.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nutrilens.app.ai.ImagePrep
import com.nutrilens.app.ai.analyzeMealCascade
import com.nutrilens.app.ai.buildRecentMealsContext
import com.nutrilens.app.data.MealItemEntity
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.notifications.NotificationHelper
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Уточнение уже добавленного блюда («Поправить»): берёт задачу из refinement_jobs,
 * пересматривает фото блюда с учётом правки пользователя (например «вес не 170 г,
 * а 125 г») и обновляет запись в дневнике: КБЖУ, разбор по продуктам, мысли ИИ.
 */
class MealRefinementWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(EXTRA_JOB_ID)
            ?: return Result.failure()
        val db = NutriLensDatabase.getInstance(applicationContext)
        val dao = db.refinementJobDao()
        val job = dao.byId(jobId) ?: return Result.failure()

        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_ANALYSIS,
            jobId.hashCode(),
            "Уточняем блюдо ✏️",
            "Правки внесём в уже записанный приём пищи — результат придёт уведомлением",
            NotificationHelper.mainActivityPendingIntent(applicationContext, jobId.hashCode()),
            ongoing = true
        )
        dao.setStatus(jobId, "RUNNING", null)

        return try {
            val meal = db.mealDao().mealById(job.mealId)
                ?: throw RuntimeException("Блюдо не найдено (уже удалено?)")
            val images = db.mealDao().imagesByMealList(job.mealId)
                .filter { it.kind == "FULL" }
                .map { ImagePrep.readBytes(File(it.path)) }
            val items = db.mealDao().itemsByMeal(job.mealId)
            val settings = SettingsRepository(db.settingsDao()).get()

            // Текущий результат как исходная точка: модель правит, а не придумывает заново.
            val currentResultContext = buildString {
                append("Название: ${meal.name}\n")
                append("КБЖУ: ${meal.calories.roundToInt()} ккал, " +
                    "Б ${meal.protein.roundToInt()} / Ж ${meal.fat.roundToInt()} / " +
                    "У ${meal.carbs.roundToInt()}\n")
                if (items.isNotEmpty()) {
                    append("По продуктам:\n")
                    items.forEach { item ->
                        append("- ${item.name}: ${item.weightG.roundToInt()} г, " +
                            "${item.calories.roundToInt()} ккал " +
                            "(Б${item.protein.roundToInt()} Ж${item.fat.roundToInt()} " +
                            "У${item.carbs.roundToInt()})\n")
                    }
                }
            }

            val today = LocalDate.now().toString()
            val recent = buildRecentMealsContext(
                db.mealDao().recentMeals(MealAnalysisWorker.RECENT_MEALS_LIMIT).asReversed()
            )
            val result = analyzeMealCascade(
                settings, images, job.correction, recent, currentResultContext
            )

            db.mealDao().updateMeal(
                meal.copy(
                    name = result.name.ifBlank { meal.name },
                    calories = result.calories,
                    protein = result.protein,
                    fat = result.fat,
                    carbs = result.carbs,
                    aiThoughts = result.aiThoughts,
                    reasoning = result.reasoning,
                    confidenceScore = result.confidenceScore,
                    healthScore = result.healthScore?.toInt(),
                    healthNote = result.healthNote
                )
            )
            db.mealDao().deleteItemsByMeal(meal.id)
            db.mealDao().insertItems(
                result.items.map { item ->
                    MealItemEntity(
                        id = UUID.randomUUID().toString(),
                        mealId = meal.id,
                        name = item.name,
                        weightG = item.weightG,
                        portionBasis = item.portionBasis,
                        calorieDensity = item.calorieDensity,
                        calories = item.calories,
                        protein = item.protein,
                        fat = item.fat,
                        carbs = item.carbs,
                        breakdown = item.breakdown
                    )
                }
            )

            com.nutrilens.app.widget.WidgetUpdater.refresh(applicationContext)
            dao.setStatus(jobId, "DONE", null)
            NotificationHelper.post(
                applicationContext,
                NotificationHelper.CHANNEL_ANALYSIS,
                jobId.hashCode(),
                "Правки внесены ✅",
                "${meal.name} · ${result.calories.roundToInt()} ккал · " +
                    "Б${result.protein.roundToInt()} Ж${result.fat.roundToInt()} " +
                    "У${result.carbs.roundToInt()}",
                NotificationHelper.mainActivityPendingIntent(
                    applicationContext,
                    jobId.hashCode(),
                    navigate = "dashboard",
                    date = meal.date
                )
            )
            Result.success()
        } catch (e: Exception) {
            var error = e.message ?: e.javaClass.simpleName
            val lower = error.lowercase()
            // Та же подсказка про фоновые ограничения, что и в анализе еды.
            if (lower.contains("dns") || lower.contains("сети") || lower.contains("таймаут")) {
                error += " · Фон ограничен телефоном? Дайте NutriLens «работу без ограничений» (Настройки → Фоновая работа)"
            }
            dao.setStatus(jobId, "FAILED", error)
            NotificationHelper.post(
                applicationContext,
                NotificationHelper.CHANNEL_ANALYSIS,
                jobId.hashCode(),
                "Не удалось уточнить блюдо 😔",
                error.take(200),
                NotificationHelper.mainActivityPendingIntent(applicationContext, jobId.hashCode())
            )
            Result.failure()
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "jobId"
    }
}
