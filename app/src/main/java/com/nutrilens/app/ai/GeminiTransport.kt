package com.nutrilens.app.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Общий транспорт generateContent для бесплатного Gemini (свой ключ из AI Studio).
 *
 * Бесплатный тир режется двумя лимитами на модель: запросы в минуту (RPM) и в
 * сутки (RPD, сброс в полночь по Тихоокеанскому времени). Поэтому:
 * - 429 «в минуту» — ждём столько, сколько просит Google (retryDelay), и повторяем;
 * - 429 «в сутки» — переключаемся на резервную модель (у неё своя квота) до сброса;
 * - 500/503 (перегрузка) — одна повторная попытка через пару секунд;
 * - всё остальное — понятное сообщение вместо сырого JSON.
 */
object GeminiTransport {

    const val PRIMARY_MODEL = "gemini-3.1-flash-lite"

    /** Алиас Google на актуальную Flash: отдельная дневная квота, ответ чуть медленнее. */
    const val RESERVE_MODEL = "gemini-flash-latest"

    /** Префикс ошибки исчерпанной дневной квоты: фоновые задачи её не повторяют. */
    const val DAILY_LIMIT_ERROR = "Дневной лимит бесплатного Gemini исчерпан"

    private const val MAX_MINUTE_RETRIES = 2
    private const val MAX_WAIT_SEC = 45

    private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")

    /** Тихоокеанская дата, на которую основная модель уже упёрлась в дневной лимит. */
    @Volatile private var primaryExhaustedOn: LocalDate? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun url(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    /** POST generateContent с учётом лимитов. Возвращает тело успешного ответа. */
    suspend fun generate(apiKey: String, bodyJson: String): String {
        val today = LocalDate.now(PACIFIC)
        val models = if (primaryExhaustedOn == today) listOf(RESERVE_MODEL)
        else listOf(PRIMARY_MODEL, RESERVE_MODEL)

        var dailyError: RuntimeException? = null
        for (model in models) {
            try {
                return generateWith(model, apiKey, bodyJson)
            } catch (e: DailyQuotaException) {
                if (model == PRIMARY_MODEL) primaryExhaustedOn = today
                dailyError = e
            } catch (e: RuntimeException) {
                // Резерв не ответил по иной причине (нет модели, ключ) — показываем
                // исходную причину: упёрлись в дневной лимит основной модели.
                throw dailyError ?: e
            }
        }
        throw dailyError ?: RuntimeException(DAILY_LIMIT_ERROR)
    }

    private suspend fun generateWith(model: String, apiKey: String, bodyJson: String): String {
        var minuteRetries = 0
        var serverRetries = 0
        while (true) {
            val (code, body) = post(url(model), apiKey, bodyJson)
            if (code in 200..299) return body
            when {
                code == 429 -> {
                    val quota = parseQuota(body)
                    if (quota.perDay) throw DailyQuotaException(dailyLimitMessage())
                    val wait = quota.retryAfterSec ?: 20
                    if (minuteRetries < MAX_MINUTE_RETRIES && wait <= MAX_WAIT_SEC) {
                        minuteRetries++
                        delay(wait * 1000L + 500)
                        continue
                    }
                    throw RuntimeException(
                        "Бесплатный Gemini: слишком много запросов подряд. Подождите минуту и повторите"
                    )
                }
                (code == 500 || code == 503) && serverRetries < 1 -> {
                    serverRetries++
                    delay(2500)
                    continue
                }
                else -> throw RuntimeException(friendlyError(code, body))
            }
        }
    }

    private class DailyQuotaException(message: String) : RuntimeException(message)

    private data class Quota(val perDay: Boolean, val retryAfterSec: Int?)

    /** Разбор тела 429: квота с PerDay в quotaId — суточная; retryDelay вида "36s". */
    private fun parseQuota(body: String): Quota {
        val perDay = body.contains("PerDay", ignoreCase = true) &&
            !body.contains("PerMinute", ignoreCase = true)
        val retry = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(body)
            ?.groupValues?.get(1)?.toIntOrNull()
        return Quota(perDay, retry)
    }

    /** Местное время сброса суточной квоты (полночь по Тихоокеанскому). */
    fun dailyResetLocalTime(): String {
        val reset = ZonedDateTime.now(PACIFIC).toLocalDate().plusDays(1).atStartOfDay(PACIFIC)
        return reset.withZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun dailyLimitMessage() =
        "$DAILY_LIMIT_ERROR — обновится в ${dailyResetLocalTime()}. " +
            "Можно описать блюдо позже или подключить платный режим в настройках"

    fun friendlyError(code: Int, body: String): String = when {
        body.contains("SAFETY") || body.contains("safety", true) || body.contains("blocked", true) ->
            "Запрос был заблокирован фильтрами безопасности Google Gemini."
        code == 400 && body.contains("API_KEY_INVALID") ->
            "Ключ Gemini недействителен — проверьте его в настройках (HTTP 400)"
        code == 400 && body.contains("location", true) ->
            "Gemini недоступен в вашем регионе без VPN (HTTP 400)"
        code == 403 -> "Ключ Gemini не подходит или API не включён (HTTP 403)"
        code == 404 -> "Модель Gemini не найдена (HTTP 404)"
        code == 500 || code == 503 -> "Серверы Gemini перегружены — повторите чуть позже (HTTP $code)"
        else -> "Gemini HTTP $code: ${body.take(300)}"
    }

    private suspend fun post(url: String, apiKey: String, bodyJson: String): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp -> cont.resume(resp.code to resp.body?.string().orEmpty()) }
                }
            })
        }
}
