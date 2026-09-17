package com.nutrilens.app.bg

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.ai.ImagePrep
import com.nutrilens.app.ai.buildRecentMealsContext
import com.nutrilens.app.ai.mealJson
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.ToolJobRepository
import com.nutrilens.app.notifications.NotificationHelper
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Фоновое выполнение одного из ИИ-инструментов «Ещё» (ideas/fridge/menu/
 * grocery/water/habit): читает замороженные в job.input параметры, зовёт
 * соответствующую функцию GeminiTools и сохраняет результат в задачу.
 * Экран инструмента наблюдает задачу из Room, поэтому результат переживает
 * и уход с экрана, и перезапуск приложения.
 */
class ToolJobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(EXTRA_JOB_ID) ?: return Result.failure()
        val db = NutriLensDatabase.getInstance(applicationContext)
        val jobRepo = ToolJobRepository(db.toolJobDao())

        val job = jobRepo.byId(jobId) ?: return Result.failure()
        // Защита от повторов: задача уже обработана.
        if (job.status == "DONE") return Result.success()

        val settings = SettingsRepository(db.settingsDao()).get()
        if (settings.apiKey.isBlank() && settings.nanoApiKey.isBlank()) {
            val msg = "Не задан ключ Gemini или NanoGPT — укажите его в настройках"
            jobRepo.markFailed(jobId, msg)
            postFailureNotification(jobId, job.kind, msg)
            return Result.failure()
        }

        jobRepo.markRunning(jobId)
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_ANALYSIS,
            jobId.hashCode(),
            "${titleFor(job.kind)}: считаем…",
            "Результат придёт уведомлением — приложение можно свернуть",
            NotificationHelper.mainActivityPendingIntent(
                applicationContext, jobId.hashCode(), navigate = routeFor(job.kind)
            ),
            ongoing = true
        )

        return try {
            val input = JSONObject(job.input)
            val result = runTool(db, settings, job.kind, input)
            jobRepo.markDone(jobId, result)
            postDoneNotification(jobId, job.kind, result)
            Result.success()
        } catch (e: Exception) {
            var error = e.message ?: e.javaClass.simpleName
            val lower = error.lowercase()
            // Типичные сетевые сбои в фоне = телефон режет сеть фоновому процессу.
            if (lower.contains("dns") || lower.contains("сети") || lower.contains("таймаут")) {
                error += " · Фон ограничен телефоном? Дайте NutriLens «работу без ограничений» (Настройки → Фоновая работа)"
            }
            val nonRetryable = lower.contains("http 400") ||
                lower.contains("http 403") ||
                lower.contains("ключ")
            if (!nonRetryable && runAttemptCount < 2) {
                Result.retry()
            } else {
                jobRepo.markFailed(jobId, error)
                postFailureNotification(jobId, job.kind, error)
                Result.failure()
            }
        }
    }

    private suspend fun runTool(
        db: NutriLensDatabase,
        settings: SettingsEntity,
        kind: String,
        input: JSONObject
    ): String = when (kind) {
        KIND_IDEAS -> {
            val mealRepo = com.nutrilens.app.data.MealRepository(
                db.mealDao(), db.waterDao(), db.weightDao(), db.workoutDao()
            )
            val today = LocalDate.now().toString()
            val recs = GeminiTools.getRecommendations(
                settings = settings,
                userContext = settings.userContext,
                userInput = input.optString("note"),
                remainingCalories = input.optInt("remainingCalories"),
                recentMealsContext = buildRecentMealsContext(mealRepo.mealsOn(today)),
                macroGoals = macroTriple(input)
            )
            mealJson.encodeToString(
                ListSerializer(GeminiTools.Recommendation.serializer()), recs
            )
        }
        KIND_FRIDGE -> GeminiTools.analyzeFridge(
            settings, settings.userContext, settings.dailyGoal,
            input.optInt("remainingCalories"), input.optBoolean("useRemaining", true),
            imagesFromInput(input)
        )
        KIND_MENU -> GeminiTools.analyzeMenu(
            settings, settings.userContext, settings.dailyGoal,
            input.optInt("remainingCalories"), input.optBoolean("useRemaining", true),
            imagesFromInput(input)
        )
        KIND_GROCERY -> mealJson.encodeToString(
            GeminiTools.GroceryPlan.serializer(),
            GeminiTools.generateGroceryList(
                settings, settings.userContext, input.optDouble("dailyGoal"), input.optString("note")
            )
        )
        KIND_WATER -> GeminiTools.waterAdvice(
            settings, settings.userContext, weightFromInput(input)
        )
        KIND_HABIT -> GeminiTools.analyzeHabit(settings, settings.userContext, input.optString("note"))
        else -> throw IllegalArgumentException("Неизвестный инструмент: $kind")
    }

    private fun macroTriple(input: JSONObject): Triple<Double, Double, Double>? {
        fun opt(name: String): Double? = input.optDouble(name, Double.NaN).takeIf { !it.isNaN() }
        val p = opt("macroP") ?: return null
        val f = opt("macroF") ?: return null
        val c = opt("macroC") ?: return null
        return Triple(p, f, c)
    }

    private fun weightFromInput(input: JSONObject): Double? {
        val w = input.optDouble("weightKg", Double.NaN)
        return if (w.isNaN()) null else w
    }

    /** Фото инструментов — уже скопированные в filesDir файлы (это делает экран при выборе). */
    private fun imagesFromInput(input: JSONObject): List<String> {
        val photos: JSONArray = input.optJSONArray("photos") ?: return emptyList()
        return (0 until photos.length()).mapNotNull { i ->
            val path = photos.optString(i)
            if (path.isBlank()) null else Base64.encodeToString(
                ImagePrep.readBytes(File(path)), Base64.NO_WRAP
            )
        }
    }

    private fun postDoneNotification(jobId: String, kind: String, result: String) {
        val text = result
            .replace(Regex("[#*`>]"), "")
            .replace("\n\n", "\n")
            .trim()
            .take(180)
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_ANALYSIS,
            jobId.hashCode(),
            "${titleFor(kind)} готово ✅",
            text,
            NotificationHelper.mainActivityPendingIntent(
                applicationContext, jobId.hashCode(), navigate = routeFor(kind)
            )
        )
    }

    private fun postFailureNotification(jobId: String, kind: String, error: String) {
        val intent = NotificationHelper.mainActivityPendingIntent(
            applicationContext, jobId.hashCode(), navigate = routeFor(kind)
        )
        val retryIntent = Intent(applicationContext, ToolJobRetryReceiver::class.java)
            .putExtra(EXTRA_JOB_ID, jobId)
        val retryPending = PendingIntent.getBroadcast(
            applicationContext,
            jobId.hashCode(),
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_ANALYSIS,
            jobId.hashCode(),
            "${titleFor(kind)}: не удалось 😔",
            error.take(200),
            intent,
            actions = listOf(
                NotificationCompat.Action.Builder(0, "Повторить", retryPending).build()
            )
        )
    }

    companion object {
        const val EXTRA_JOB_ID = "jobId"

        const val KIND_IDEAS = "ideas"
        const val KIND_FRIDGE = "fridge"
        const val KIND_MENU = "menu"
        const val KIND_GROCERY = "grocery"
        const val KIND_WATER = "water"
        const val KIND_HABIT = "habit"

        /** Сколько недавних приёмов пищи отдавать модели как контекст (как в анализе еды). */
        const val RECENT_MEALS_LIMIT = 10

        private fun titleFor(kind: String): String = when (kind) {
            KIND_IDEAS -> "💡 Идеи еды"
            KIND_FRIDGE -> "🧊 Холодильник"
            KIND_MENU -> "🍽️ Ресторан"
            KIND_GROCERY -> "🛒 Покупки"
            KIND_WATER -> "💧 Вода"
            else -> "🧠 Разбор привычек"
        }

        private fun routeFor(kind: String): String = when (kind) {
            KIND_IDEAS -> "ideas"
            KIND_FRIDGE -> "fridge"
            KIND_MENU -> "menu"
            KIND_GROCERY -> "grocery"
            KIND_WATER -> "waterTool"
            else -> "habitTool"
        }
    }
}
