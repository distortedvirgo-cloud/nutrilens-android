package com.nutrilens.app.bg

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nutrilens.app.ai.ImagePrep
import com.nutrilens.app.data.AnalysisJobRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.RefinementJobEntity
import com.nutrilens.app.data.ToolJobRepository
import java.io.File
import java.util.UUID

/**
 * Подготавливает фото и ставит фоновую задачу анализа в WorkManager.
 */
object AnalysisScheduler {

    /**
     * Подготавливает фото (full+thumb), создаёт запись очереди и ставит
     * задачу в WorkManager. Возвращает id задачи.
     */
    suspend fun enqueueBackground(context: Context, note: String, photoUris: List<Uri>): String {
        val dirId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "photos/$dirId")
        dir.mkdirs()

        // AnalysisJobRepository.createJob принимает List<String> (список строк-путей),
        // поэтому кодируем пару full|thumb строкой с разделителем "|".
        val photoPaths = photoUris.map { uri ->
            val processed = ImagePrep.process(context, uri, dir)
            "${processed.full.absolutePath}|${processed.thumb.absolutePath}"
        }

        val jobRepo = AnalysisJobRepository(NutriLensDatabase.getInstance(context).analysisJobDao())
        // Id записи очереди = id, который получит воркер: createJob генерирует
        // собственный UUID, поэтому локальный jobId больше не используется.
        val job = jobRepo.createJob(note, photoPaths)

        WorkManager.getInstance(context).enqueue(analysisWorkRequest(job.id))
        return job.id
    }

    /**
     * Повторный запуск неудавшейся задачи анализа (например, после сбоя API):
     * возвращает её в очередь с чистым статусом и ставит в WorkManager заново.
     * Фото уже лежат в filesDir и переживают сбой, поэтому пересаживать их не нужно.
     */
    suspend fun retry(context: Context, jobId: String) {
        val jobRepo = AnalysisJobRepository(NutriLensDatabase.getInstance(context).analysisJobDao())
        val job = jobRepo.byId(jobId) ?: return
        if (job.status == "RUNNING") return // уже в работе — не дублируем
        jobRepo.requeueForRetry(job)
        WorkManager.getInstance(context).enqueue(analysisWorkRequest(jobId))
    }

    /**
     * Уточнение уже добавленного блюда («Поправить»): создаёт задачу
     * уточнения и ставит MealRefinementWorker — он пересчитает блюдо с учётом правки.
     */
    suspend fun scheduleRefinement(context: Context, mealId: String, correction: String): String {
        val dao = NutriLensDatabase.getInstance(context).refinementJobDao()
        // Завершённые/неудавшиеся задачи этого блюда больше не нужны — чистим,
        // чтобы индикатор и список неудач не засорялись старыми правками.
        dao.deleteFinishedByMeal(mealId)
        val job = RefinementJobEntity(
            id = UUID.randomUUID().toString(),
            mealId = mealId,
            correction = correction
        )
        dao.upsert(job)

        val request = OneTimeWorkRequestBuilder<MealRefinementWorker>()
            .setInputData(workDataOf(MealRefinementWorker.EXTRA_JOB_ID to job.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return job.id
    }

    /** Повтор неудавшегося уточнения по кнопке «Повторить» на карточке сбоя. */
    fun retryRefinement(context: Context, jobId: String) {
        val request = OneTimeWorkRequestBuilder<MealRefinementWorker>()
            .setInputData(workDataOf(MealRefinementWorker.EXTRA_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    /**
     * Фоновая задача ИИ-инструмента «Ещё» (ideas/fridge/menu/grocery/water/habit).
     * inputJson — параметры, замороженные экраном в момент запуска (фото к этому
     * моменту уже скопированы в filesDir, в JSON кладутся их абсолютные пути).
     */
    suspend fun enqueueTool(context: Context, kind: String, inputJson: String): String {
        val db = NutriLensDatabase.getInstance(context)
        val dao = db.toolJobDao()
        // Готовые/неудавшиеся задачи этого инструмента больше не нужны —
        // новая задача стартует с чистым экраном и свежим результатом.
        dao.deleteFinishedByKind(kind)
        val jobRepo = ToolJobRepository(dao)
        val job = jobRepo.createJob(kind, inputJson)
        WorkManager.getInstance(context).enqueue(toolWorkRequest(job.id))
        return job.id
    }

    /** Повтор неудавшейся задачи инструмента (кнопка «Повторить»). */
    suspend fun retryTool(context: Context, jobId: String) {
        val jobRepo = ToolJobRepository(NutriLensDatabase.getInstance(context).toolJobDao())
        val job = jobRepo.byId(jobId) ?: return
        if (job.status == "RUNNING") return // уже в работе — не дублируем
        jobRepo.requeueForRetry(jobId)
        WorkManager.getInstance(context).enqueue(toolWorkRequest(jobId))
    }

    private fun toolWorkRequest(jobId: String) =
        OneTimeWorkRequestBuilder<ToolJobWorker>()
            .setInputData(workDataOf(ToolJobWorker.EXTRA_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

    private fun analysisWorkRequest(jobId: String) =
        OneTimeWorkRequestBuilder<MealAnalysisWorker>()
            .setInputData(workDataOf(MealAnalysisWorker.EXTRA_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
}