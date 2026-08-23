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
        val jobId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "photos/$jobId")
        dir.mkdirs()

        // AnalysisJobRepository.createJob принимает List<String> (список строк-путей),
        // поэтому кодируем пару full|thumb строкой с разделителем "|".
        val photoPaths = photoUris.map { uri ->
            val processed = ImagePrep.process(context, uri, dir)
            "${processed.full.absolutePath}|${processed.thumb.absolutePath}"
        }

        val jobRepo = AnalysisJobRepository(NutriLensDatabase.getInstance(context).analysisJobDao())
        jobRepo.createJob(note, photoPaths)

        val request = OneTimeWorkRequestBuilder<MealAnalysisWorker>()
            .setInputData(workDataOf(MealAnalysisWorker.EXTRA_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        return jobId
    }
}