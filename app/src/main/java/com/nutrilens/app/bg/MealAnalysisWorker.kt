package com.nutrilens.app.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutrilens.app.ai.ImagePrep
import com.nutrilens.app.ai.analyzeMealCascade
import com.nutrilens.app.ai.buildRecentMealsContext
import com.nutrilens.app.data.AnalysisJobRepository
import com.nutrilens.app.data.MealEntity
import com.nutrilens.app.data.MealImageEntity
import com.nutrilens.app.data.MealItemEntity
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.notifications.NotificationHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Фоновая обработка анализа еды: читает подготовленные фото, зовёт Gemini,
 * сохраняет приём пищи и шлёт уведомление о результате.
 */
class MealAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(EXTRA_JOB_ID) ?: return Result.failure()
        val db = NutriLensDatabase.getInstance(applicationContext)
        val jobRepo = AnalysisJobRepository(db.analysisJobDao())
        val settingsRepo = SettingsRepository(db.settingsDao())

        val job = jobRepo.byId(jobId) ?: return Result.failure()
        // Защита от повторов: задача уже обработана.
        if (job.status == "DONE") return Result.success()

        val settings = settingsRepo.get()
        if (settings.apiKey.isBlank() && settings.nanoApiKey.isBlank()) {
            val msg = "Не задан ключ Gemini или NanoGPT — укажите его в настройках"
            jobRepo.markFailed(jobId, msg)
            postFailureNotification(jobId, msg)
            return Result.failure()
        }

        jobRepo.markRunning(jobId)
        // Прогресс-уведомление с тем же id, что и результат: анализ виден в
        // шторке, приложение можно свернуть/закрыть — воркер доведёт до конца
        // и заменит это уведомление итоговым.
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_ANALYSIS,
            jobId.hashCode(),
            "Анализируем ваше фото 🍽️",
            "Результат придёт уведомлением — приложение можно свернуть",
            NotificationHelper.mainActivityPendingIntent(applicationContext, jobId.hashCode()),
            ongoing = true
        )

        val photos = try {
            parsePhotos(job.photoPaths)
        } catch (e: Exception) {
            val error = "Некорректные данные фотографий: ${e.message ?: ""}"
            jobRepo.markFailed(jobId, error)
            postFailureNotification(jobId, error)
            return Result.failure()
        }
        if (photos.isEmpty()) {
            val msg = "Нет фотографий для анализа"
            jobRepo.markFailed(jobId, msg)
            postFailureNotification(jobId, msg)
            return Result.failure()
        }

        return try {
            val fullBytes = photos.map { ImagePrep.readBytes(File(it.first)) }
            val today = LocalDate.now().toString()
            val recent = buildRecentMealsContext(db.mealDao().mealsBetween(today, today))
            val result = analyzeMealCascade(settings, fullBytes, job.note, recent)

            val meal = saveMeal(db, settings.dailyGoal, photos, result)
            jobRepo.markDone(jobId, meal.id)
            postSuccessNotification(jobId, meal)
            Result.success()
        } catch (e: Exception) {
            val error = e.message ?: e.javaClass.simpleName
            val lower = error.lowercase()
            val nonRetryable = lower.contains("http 400") ||
                lower.contains("http 403") ||
                lower.contains("ключ")
            if (!nonRetryable && runAttemptCount < 2) {
                Result.retry()
            } else {
                jobRepo.markFailed(jobId, error)
                postFailureNotification(jobId, error)
                Result.failure()
            }
        }
    }

    private suspend fun saveMeal(
        db: NutriLensDatabase,
        dailyGoal: Double,
        photos: List<Pair<String, String>>,
        result: com.nutrilens.app.ai.MealAnalysisResult
    ): MealEntity {
        val mealId = UUID.randomUUID().toString()
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

        val meal = MealEntity(
            id = mealId,
            date = date,
            time = time,
            name = result.name,
            calories = result.calories,
            protein = result.protein,
            fat = result.fat,
            carbs = result.carbs,
            aiThoughts = result.aiThoughts,
            reasoning = result.reasoning,
            confidenceScore = result.confidenceScore,
            dailyGoalSnapshot = dailyGoal
        )

        val images = photos.flatMapIndexed { index, (full, thumb) ->
            listOf(
                MealImageEntity(
                    id = UUID.randomUUID().toString(),
                    mealId = mealId,
                    path = full,
                    kind = "FULL",
                    sortIndex = index
                ),
                MealImageEntity(
                    id = UUID.randomUUID().toString(),
                    mealId = mealId,
                    path = thumb,
                    kind = "THUMB",
                    sortIndex = index
                )
            )
        }

        val items = result.items.map { item ->
            MealItemEntity(
                id = UUID.randomUUID().toString(),
                mealId = mealId,
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

        val mealRepo = MealRepository(db.mealDao(), db.waterDao(), db.weightDao(), db.workoutDao())
        mealRepo.addMeal(meal, images, items)
        return meal
    }

    /**
     * Парсит photoPaths задачи: JSON-массив элементов. Поддерживает объекты
     * {"full":...,"thumb":...} и строки вида "<full>|<thumb>".
     */
    private fun parsePhotos(photoPathsJson: String): List<Pair<String, String>> {
        val array = JSONArray(photoPathsJson)
        val result = mutableListOf<Pair<String, String>>()
        for (i in 0 until array.length()) {
            val element = array.opt(i)
            when (element) {
                is JSONObject -> {
                    val full = element.optString("full")
                    val thumb = element.optString("thumb")
                    result.add(full to thumb)
                }
                else -> {
                    val raw = element?.toString().orEmpty()
                    val parts = raw.split("|", limit = 2)
                    val full = parts.getOrNull(0).orEmpty()
                    val thumb = parts.getOrNull(1).orEmpty()
                    result.add(full to thumb)
                }
            }
        }
        return result
    }

    private fun postSuccessNotification(jobId: String, meal: MealEntity) {
        val title = "Анализ готов ✅"
        val text = "${meal.name} · ${meal.calories.roundToInt()} ккал · " +
            "Б${meal.protein.roundToInt()} Ж${meal.fat.roundToInt()} У${meal.carbs.roundToInt()}"
        val intent = NotificationHelper.mainActivityPendingIntent(
            applicationContext,
            jobId.hashCode(),
            navigate = "dashboard",
            date = meal.date
        )
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_ANALYSIS,
            jobId.hashCode(),
            title,
            text,
            intent
        )
    }

    private fun postFailureNotification(jobId: String, error: String) {
        val title = "Не удалось проанализировать 😔"
        val text = error.take(200)
        val intent = NotificationHelper.mainActivityPendingIntent(applicationContext, jobId.hashCode())
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_ANALYSIS,
            jobId.hashCode(),
            title,
            text,
            intent
        )
    }

    companion object {
        const val EXTRA_JOB_ID = "jobId"
    }
}