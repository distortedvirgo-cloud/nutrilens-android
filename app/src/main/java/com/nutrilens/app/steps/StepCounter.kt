package com.nutrilens.app.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Встроенный шагомер телефона (Sensor.TYPE_STEP_COUNTER) — базовый источник шагов.
 *
 * Датчик отдаёт счётчик шагов С МОМЕНТА ЗАГРУЗКИ телефона, а не за день. Поэтому
 * храним в SharedPreferences «базу»: значение счётчика при первом чтении за день.
 * Шаги за сегодня = накопленное по прошлым отрезкам + (счётчик − база).
 * Перезагрузка (счётчик стал меньше прошлого значения или elapsedRealtime «откатился»)
 * начинает новый отрезок с нуля, а пройденное до неё переносится в накопленное.
 *
 * Ограничение (принято осознанно): шаги между полуночью и первым чтением за день
 * не попадают ни в сегодня, ни во вчера — точная граница неизвестна. Чтения идут
 * при каждом открытии приложения и из почасового фонового воркера, так что потеря
 * обычно не больше часа ходьбы. Фоновый сервис ради этого не держим — батарея дороже.
 */
object StepCounter {
    private const val PREFS = "steps_sensor"
    private const val KEY_DATE = "date"
    private const val KEY_BASE = "base"
    private const val KEY_LAST = "last"
    private const val KEY_ACC = "acc"
    private const val KEY_ELAPSED = "elapsed"

    private const val DEFAULT_TIMEOUT_MS = 8_000L

    private val lock = Any()

    /** Есть ли в телефоне аппаратный шагомер. */
    fun hasSensor(context: Context): Boolean = try {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    } catch (e: Exception) {
        false
    }

    /** До Android 10 разрешение не нужно; с 10 — ACTIVITY_RECOGNITION. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun isUsable(context: Context): Boolean = hasSensor(context) && hasPermission(context)

    /**
     * Шаги за сегодня по датчику; null — датчика или разрешения нет, либо он не ответил.
     * Слушатель регистрируется ненадолго и снимается сразу после первого значения или таймаута.
     */
    suspend fun readToday(context: Context, today: LocalDate = LocalDate.now()): Int? {
        if (!isUsable(context)) return null
        val counter = readCounter(context.applicationContext, DEFAULT_TIMEOUT_MS) ?: return null
        return todayFromCounter(context.applicationContext, counter, today)
    }

    /** Одно значение счётчика с момента загрузки. На части телефонов приходит только после шага — отсюда таймаут. */
    private suspend fun readCounter(context: Context, timeoutMs: Long): Float? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Float?> { cont ->
                val done = AtomicBoolean(false)
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        val value = event?.values?.firstOrNull() ?: return
                        if (done.compareAndSet(false, true)) {
                            runCatching { sm.unregisterListener(this) }
                            if (cont.isActive) cont.resume(value)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { runCatching { sm.unregisterListener(listener) } }
                val registered = try {
                    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                } catch (e: Exception) {
                    false
                }
                if (!registered && done.compareAndSet(false, true)) {
                    runCatching { sm.unregisterListener(listener) }
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** Пересчёт «счётчик с загрузки» → «шаги за сегодня» с учётом базы дня и перезагрузок. */
    private fun todayFromCounter(context: Context, counter: Float, today: LocalDate): Int = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today.toString()
        val elapsedNow = SystemClock.elapsedRealtime()
        val storedDate = prefs.getString(KEY_DATE, null)
        val base = prefs.getFloat(KEY_BASE, 0f)
        val last = prefs.getFloat(KEY_LAST, 0f)
        val acc = prefs.getLong(KEY_ACC, 0L)
        val lastElapsed = prefs.getLong(KEY_ELAPSED, 0L)
        // elapsedRealtime уменьшается только после перезагрузки; счётчик тоже обнуляется.
        val rebooted = elapsedNow < lastElapsed || counter < last

        val newBase: Float
        val newAcc: Long
        when {
            // Первое чтение за день — оно и есть база (см. ограничение в описании объекта).
            storedDate != day -> {
                newBase = counter
                newAcc = 0L
            }
            // Перезагрузка сегодня: пройденное до неё сохраняем, новый отрезок — с нуля.
            rebooted -> {
                newBase = 0f
                newAcc = acc + (last - base).toLong().coerceAtLeast(0L)
            }
            else -> {
                newBase = base
                newAcc = acc
            }
        }
        prefs.edit()
            .putString(KEY_DATE, day)
            .putFloat(KEY_BASE, newBase)
            .putFloat(KEY_LAST, counter)
            .putLong(KEY_ACC, newAcc)
            .putLong(KEY_ELAPSED, elapsedNow)
            .apply()
        (newAcc + (counter - newBase).toLong().coerceAtLeast(0L)).coerceIn(0L, 200_000L).toInt()
    }
}
