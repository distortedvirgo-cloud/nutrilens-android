package com.nutrilens.app.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.nutrilens.app.insights.Cycle
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Учёт цикла (3.27) — только даты начала месячных, только на этом телефоне:
 * не уходят ни в ИИ, ни в лидерборд, ни в бэкап. Включается вручную и только
 * в женском профиле. Источники: кнопка «Начались» и (если выдано разрешение)
 * Health Connect — туда пишут многие трекеры цикла.
 */
object CycleStore {
    private const val TAG = "CycleStore"
    private const val PREFS = "cycle"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STARTS = "starts"
    private const val KEY_NOT_YET = "notYet"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        DataEvents.notifyChanged()
    }

    /** Даты начала, по возрастанию, без дублей одного цикла. */
    fun starts(context: Context): List<LocalDate> =
        Cycle.normalize(prefs(context).getStringSet(KEY_STARTS, emptySet()).orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() })

    /** Учитывать ли цикл в расчётах: включено и профиль женский. */
    fun active(context: Context, settings: SettingsEntity): Boolean =
        isEnabled(context) && !BodyComposition.isMale(settings)

    fun addStart(context: Context, date: LocalDate = LocalDate.now()) {
        val set = prefs(context).getStringSet(KEY_STARTS, emptySet()).orEmpty().toMutableSet()
        set += date.toString()
        // Хватит двух лет — больше для средней длины не нужно.
        val cut = LocalDate.now().minusYears(2).toString()
        set.removeAll { it < cut }
        prefs(context).edit().putStringSet(KEY_STARTS, set).remove(KEY_NOT_YET).apply()
        DataEvents.notifyChanged()
    }

    /** «Ещё нет» — сегодня больше не спрашиваем. */
    fun notYet(context: Context, today: LocalDate = LocalDate.now()) {
        prefs(context).edit().putString(KEY_NOT_YET, today.toString()).apply()
        DataEvents.notifyChanged()
    }

    fun notYetOn(context: Context): LocalDate? =
        prefs(context).getString(KEY_NOT_YET, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Пора ли спросить «Начались?» — для карточки на «Сегодня» и уведомления. */
    fun shouldAsk(context: Context, settings: SettingsEntity, today: LocalDate = LocalDate.now()): Boolean =
        active(context, settings) && Cycle.shouldAsk(today, starts(context), notYetOn(context))

    // ---------- Health Connect ----------

    /** Разрешение на чтение менструаций; null — библиотека не смогла его отдать. */
    fun healthPermission(): String? = try {
        HealthPermission.getReadPermission(MenstruationPeriodRecord::class)
    } catch (t: Throwable) {
        null
    }

    /**
     * Подтянуть начала месячных из Health Connect за последний год. Тихо ничего не делает,
     * если Health Connect нет, разрешения нет или что-то сломалось. Возвращает, сколько добавлено.
     */
    suspend fun syncFromHealthConnect(context: Context): Int {
        val permission = healthPermission() ?: return 0
        return try {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return 0
            val client = HealthConnectClient.getOrCreate(context.applicationContext)
            if (permission !in client.permissionController.getGrantedPermissions()) return 0
            val now = Instant.now()
            val response = client.readRecords(
                ReadRecordsRequest(
                    MenstruationPeriodRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(400L * 24 * 3600), now)
                )
            )
            val zone = ZoneId.systemDefault()
            val before = starts(context).toSet()
            val found = response.records.map { it.startTime.atZone(zone).toLocalDate() }
            val fresh = found.filter { it !in before }
            if (fresh.isNotEmpty()) {
                val set = prefs(context).getStringSet(KEY_STARTS, emptySet()).orEmpty().toMutableSet()
                fresh.forEach { set += it.toString() }
                prefs(context).edit().putStringSet(KEY_STARTS, set).apply()
                DataEvents.notifyChanged()
            }
            fresh.size
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось прочитать цикл из Health Connect: ${t.message}")
            0
        }
    }
}
