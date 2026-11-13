package com.nutrilens.app.steps

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nutrilens.app.data.DayActivityRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.StepsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Шаги за сегодня в БД: Health Connect (если подключён) или датчик телефона —
 * выбирается само, ручной ввод всегда главнее. Вызывается при выходе приложения
 * на передний план, открытии «Сегодня» и из почасового воркера. Никогда не падает.
 */
object StepsSync {
    private const val TAG = "StepsSync"
    private const val UNIQUE_WORK = "steps_sync"

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch { refresh(appContext) }
    }

    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        try {
            mutex.withLock {
                withContext(Dispatchers.IO) { refreshLocked(appContext) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Шаги не обновились: ${t.message}")
        }
    }

    private suspend fun refreshLocked(context: Context) {
        val db = NutriLensDatabase.getInstance(context)
        val settings = SettingsRepository(db.settingsDao()).get()
        if (!settings.stepsEnabled) return
        val today = LocalDate.now()
        val hc = HealthConnectSteps.stepsFor(context, today)
        val sensor = StepCounter.readToday(context, today)
        // Health Connect предпочтительнее (часы, браслет), но если в нём нет телефонных
        // шагов и датчик насчитал больше — берём датчик.
        val (steps, source) = when {
            hc != null && (sensor == null || hc >= sensor) -> hc to StepsSource.HEALTH_CONNECT
            sensor != null -> sensor to StepsSource.SENSOR
            else -> return
        }
        DayActivityRepository(db.dayActivityDao()).setAutoSteps(today.toString(), steps, source)
    }

    /** Откуда сейчас берутся шаги: "health_connect" / "sensor" / "manual". */
    suspend fun currentSource(context: Context): String {
        val appContext = context.applicationContext
        return try {
            when {
                HealthConnectSteps.isConnected(appContext) -> StepsSource.HEALTH_CONNECT
                StepCounter.isUsable(appContext) -> StepsSource.SENSOR
                else -> StepsSource.MANUAL
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            StepsSource.MANUAL
        }
    }

    /** Почасовой фоновый опрос шагов, пока шаги включены. Без foreground-сервиса. */
    fun schedule(context: Context, enabled: Boolean) {
        try {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<StepsWorker>(1, TimeUnit.HOURS).build()
                wm.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                wm.cancelUniqueWork(UNIQUE_WORK)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось запланировать шаги: ${t.message}")
        }
    }
}

/** Раз в час: коротко прочитать датчик/Health Connect и записать шаги за сегодня. */
class StepsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        StepsSync.refresh(applicationContext)
        return Result.success()
    }
}
