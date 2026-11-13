package com.nutrilens.app.steps

import android.content.Context
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Health Connect — необязательное улучшение: там собраны шаги и с часов/браслетов.
 * Приложение ставится APK вне Google Play, и не проверено, что Health Connect
 * вообще пускает такие приложения. Поэтому ВСЁ здесь в try/catch и с проверкой
 * доступности: любой сбой — тихо возвращаем null, и шаги берутся с датчика телефона.
 */
object HealthConnectSteps {
    private const val TAG = "HealthConnectSteps"

    /** Строка разрешения READ_STEPS; null — библиотека не смогла её отдать. */
    fun readPermission(): String? = try {
        HealthPermission.getReadPermission(StepsRecord::class)
    } catch (t: Throwable) {
        null
    }

    /** Health Connect установлен и готов к работе (на Android 14+ он встроен в систему). */
    fun isAvailable(context: Context): Boolean = try {
        HealthConnectClient.getSdkStatus(context.applicationContext) == HealthConnectClient.SDK_AVAILABLE
    } catch (t: Throwable) {
        false
    }

    private fun client(context: Context): HealthConnectClient? {
        if (!isAvailable(context)) return null
        return try {
            HealthConnectClient.getOrCreate(context.applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "Health Connect недоступен: ${t.message}")
            null
        }
    }

    /** Выдано ли разрешение на чтение шагов. */
    suspend fun isConnected(context: Context): Boolean {
        val permission = readPermission() ?: return false
        val c = client(context) ?: return false
        return try {
            permission in c.permissionController.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось проверить разрешения: ${t.message}")
            false
        }
    }

    /** Контракт системного окна разрешений Health Connect; null — библиотека недоступна. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>>? = try {
        PermissionController.createRequestPermissionResultContract()
    } catch (t: Throwable) {
        null
    }

    /**
     * Шаги за местный календарный день [date] (сумма по всем источникам без двойного
     * счёта — это делает сам Health Connect). null — нет доступа, данных или что-то сломалось.
     * Из фона чтение может быть запрещено (нужно отдельное разрешение) — тогда тоже null.
     */
    suspend fun stepsFor(context: Context, date: LocalDate = LocalDate.now()): Int? {
        if (!isConnected(context)) return null
        val c = client(context) ?: return null
        return try {
            val zone = ZoneId.systemDefault()
            val start = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            val now = Instant.now()
            val end = if (now.isBefore(dayEnd)) now else dayEnd
            if (!end.isAfter(start)) return null
            val result = c.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val total: Long? = result[StepsRecord.COUNT_TOTAL]
            total?.coerceIn(0L, 200_000L)?.toInt()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось прочитать шаги: ${t.message}")
            null
        }
    }
}
