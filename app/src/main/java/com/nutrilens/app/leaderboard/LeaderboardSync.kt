package com.nutrilens.app.leaderboard

import com.nutrilens.app.insights.effectiveDailyGoal
import android.content.Context
import android.util.Log
import com.nutrilens.app.BuildConfig
import com.nutrilens.app.data.Avatar
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Лидерборд через прокси leaderboard-worker (тот же, что у веб-версии). Токена GitHub
 * в приложении больше нет: прокси держит его у себя, а запись в свой файл
 * data/p_<id>.json защищена секретом устройства. Наружу прокси отдаёт не id, а его
 * хеш — свою строку находим, сравнивая с [publicId] своего id.
 */
class LeaderboardSync(context: Context) {

    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Свои последние дни (сегодня — всегда) → PUT. Ник пуст или прокси не задан — false. */
    suspend fun pushOwn(): Boolean {
        if (API.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val db = NutriLensDatabase.getInstance(appContext)
                val settings = SettingsRepository(db.settingsDao()).get()
                val nickname = settings.leaderboardNickname.trim()
                if (nickname.isEmpty()) return@withContext false
                val today = LocalDate.now()
                val meals = db.mealDao().mealsBetween(today.minusDays(SYNC_DAYS - 1).toString(), today.toString())
                val days = meals.groupBy { it.date }.mapValues { (_, list) ->
                    list.sumOf { it.calories } to (list.firstNotNullOfOrNull { it.dailyGoalSnapshot } ?: settings.effectiveDailyGoal)
                }.toMutableMap()
                days[today.toString()] = (days[today.toString()]?.first ?: 0.0) to settings.effectiveDailyGoal
                val body = buildJsonObject {
                    put("secret", PlayerIdentity.secret(appContext))
                    put("nickname", nickname)
                    put("avatar", runCatching { Avatar.smallDataUrl(appContext) }.getOrDefault(""))
                    put("source", "android")
                    putJsonObject("days") {
                        days.forEach { (date, v) ->
                            putJsonObject(date) { put("calories", (v.first * 10).toLong() / 10.0); put("goal", v.second) }
                        }
                    }
                }.toString()
                val req = Request.Builder().url("$API/entries/${PlayerIdentity.id(appContext)}")
                    .put(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) Log.w(TAG, "Пуш лидерборда: HTTP ${resp.code} ${resp.body?.string()?.take(120)}")
                    resp.isSuccessful
                }
            } catch (e: Exception) {
                Log.w(TAG, "Пуш лидерборда не удался: ${e.message}")
                false
            }
        }
    }

    /** Все участники (id — публичные хеши). Сбой — исключение: экран покажет ошибку. */
    suspend fun fetchAll(): List<LeaderboardEntry> {
        if (API.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$API/entries").get().build()
            val text = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("Лидерборд недоступен (HTTP ${resp.code})")
                resp.body?.string().orEmpty()
            }
            val arr = json.parseToJsonElement(text).jsonObject["entries"]?.jsonArray ?: return@withContext emptyList()
            LeaderboardMath.dedupe(arr.mapNotNull { runCatching { json.decodeFromJsonElement(LeaderboardEntry.serializer(), it) }.getOrNull() })
        }
    }

    companion object {
        private const val TAG = "LeaderboardSync"
        private const val SYNC_DAYS = 7L
        /** Адрес прокси (не секрет): BuildConfig.LEADERBOARD_API. */
        val API: String get() = BuildConfig.LEADERBOARD_API.trimEnd('/')

        /** Публичный id — как его отдаёт прокси: первые 16 hex SHA-256("nutrilens:" + id). */
        fun publicId(id: String): String =
            MessageDigest.getInstance("SHA-256").digest("nutrilens:$id".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)

        private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Fire-and-forget пуш своего дня: после изменений еды и при старте. */
        fun pushAsync(context: Context) {
            val appContext = context.applicationContext
            pushScope.launch { runCatching { LeaderboardSync(appContext).pushOwn() } }
        }
    }
}
