package com.nutrilens.app.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Кнопка «Повторить» в уведомлении о неудаче анализа: возвращает задачу
 * в очередь и ставит её в WorkManager заново без открытия приложения.
 */
class AnalysisRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(MealAnalysisWorker.EXTRA_JOB_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AnalysisScheduler.retry(context, jobId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
