package com.nutrilens.app.ai

import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Клиент NanoGPT (OpenAI-совместимый эндпоинт chat/completions). Порт веб-версии
 * (src/utils/fallback.ts): Bearer-ключ, модели со слагом "google/…", фото как
 * image_url data-URI, JSON-режим через инструкцию в последнем сообщении.
 */
object NanoGptApi {

    /** role: "user" | "assistant" | "system". */
    data class Msg(
        val role: String,
        val text: String,
        val imagesBase64: List<String> = emptyList()
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Reasoning-модели (glm-5.3-flash, qwen thinking) на сложных промптах с фото
        // могут думать дольше минуты — читаем ответ до 5 минут, иначе клиент обрывает
        // соединение, пока запрос уже дошёл до провайдера и «висит» там.
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Базовый вызов chat/completions; возвращает текст ассистента. */
    suspend fun complete(
        apiKey: String,
        endpoint: String,
        model: String,
        system: String?,
        messages: List<Msg>,
        jsonMode: Boolean
    ): String {
        val base = endpoint.ifBlank { "https://nano-gpt.com" }.trimEnd('/')
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                if (!system.isNullOrBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                }
                messages.forEachIndexed { index, msg ->
                    addJsonObject {
                        put("role", msg.role)
                        val text = if (jsonMode && index == messages.lastIndex) {
                            msg.text + "\n\nIMPORTANT: Return ONLY valid JSON without any " +
                                "markdown formatting wrappers like ```json."
                        } else {
                            msg.text
                        }
                        if (msg.imagesBase64.isEmpty()) {
                            put("content", text)
                        } else {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                }
                                msg.imagesBase64.forEach { b64 ->
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:image/jpeg;base64,$b64")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()

        val request = Request.Builder()
            .url("$base/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = execute(request)
        val root = json.parseToJsonElement(responseBody).jsonObject
        val text = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
            ?: throw RuntimeException("Пустой ответ NanoGPT")
        return if (jsonMode) stripJsonFence(text) else text
    }

    /** Анализ фото еды через NanoGPT с тем же промптом, что и веб-версия. */
    suspend fun analyzeMeal(
        apiKey: String,
        endpoint: String,
        model: String,
        imagesJpeg: List<ByteArray>,
        userContext: String,
        userNote: String,
        recentMealsContext: String,
        currentResultContext: String = ""
    ): MealAnalysisResult {
        val basePrompt = buildMealAnalysisPrompt(
            userContext,
            userNote,
            recentMealsContext = recentMealsContext,
            photoCount = imagesJpeg.size,
            currentResultContext = currentResultContext
        )

        suspend fun attempt(prompt: String): MealAnalysisResult {
            val text = complete(
                apiKey = apiKey,
                endpoint = endpoint,
                model = model,
                system = null,
                messages = listOf(
                    Msg(
                        role = "user",
                        text = prompt,
                        imagesBase64 = imagesJpeg.map { Base64.encodeToString(it, Base64.NO_WRAP) }
                    )
                ),
                jsonMode = true
            )
            return try {
                mealJson.decodeFromString<MealAnalysisResult>(text)
            } catch (e: Exception) {
                throw RuntimeException("Ошибка парсинга NanoGPT: ${e.message}. Ответ: ${text.take(300)}", e)
            }
        }

        val first = attempt(basePrompt)
        if (first.items.isNotEmpty() || first.calories > 0.0) {
            return fixMealDrift(first)
        }
        // Reasoning-модели иногда отдают разбор только текстом: без массива items и
        // без итоговых КБЖУ — приём сохранился бы с нулями. Переспрашиваем с поправкой.
        val corrective = basePrompt +
            "\n\nВАЖНО (исправление): в прошлом ответе НЕ БЫЛО массива items и итоговых " +
            "calories/protein/fat/carbs. Верни ПОЛНЫЙ JSON: обязательный массив items " +
            "(по каждому блюду: name, estimated_weight_g, portion_basis, calorie_density, " +
            "calories, protein, fat, carbs, breakdown) И итоговые calories/protein/fat/carbs " +
            "на верхнем уровне."
        val second = attempt(corrective)
        if (second.items.isEmpty() && second.calories <= 0.0) {
            throw RuntimeException(
                "Модель не вернула разбивку по продуктам и итоговые КБЖУ — повторите анализ"
            )
        }
        return fixMealDrift(second)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(readableNetworkError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        cont.resume(body)
                    } else {
                        cont.resumeWithException(
                            RuntimeException("NanoGPT HTTP ${resp.code}: ${body.take(300)}")
                        )
                    }
                }
            }
        })
    }

    /** Читаемые сообщения для сетевых сбоев — они попадают в карточку сбоя и уведомление. */
    private fun readableNetworkError(e: IOException): RuntimeException = when (e) {
        is java.net.SocketTimeoutException -> RuntimeException(
            "Ответ не пришёл вовремя (таймаут сети) — проверьте интернет или VPN и повторите"
        )
        else -> when {
            e.message?.contains("ECONNREFUSED", ignoreCase = true) == true ->
                RuntimeException("Соединение отклонено — проверьте интернет")
            e.message?.contains("Unable to resolve", ignoreCase = true) == true ->
                RuntimeException("Нет доступа к сети (DNS не решается) — проверьте интернет или VPN")
            else -> RuntimeException("Сбой сети: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Диагностика соединения с NanoGPT для кнопки в настройках: проверяет доступ к
     * списку моделей и делает крошечный реальный вызов модели, возвращает отчёт.
     * Ошибки не выбрасываются — превращаются в человекочитаемые строки отчёта.
     */
    suspend fun diagnose(apiKey: String, endpoint: String, simpleModel: String): String {
        if (apiKey.isBlank()) return "Ключ NanoGPT не задан"
        val base = endpoint.ifBlank { "https://nano-gpt.com" }.trimEnd('/')
        val report = StringBuilder()

        // 1) Доступность API: GET /api/v1/models.
        val t0 = System.currentTimeMillis()
        report.append("1) Список моделей: ")
        try {
            val modelsReq = Request.Builder()
                .url("$base/api/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val modelsBody = execute(modelsReq)
            val count = runCatching {
                json.parseToJsonElement(modelsBody).jsonObject["data"]?.jsonArray?.size
            }.getOrNull()
            report.append(
                "OK за ${((System.currentTimeMillis() - t0) / 100) / 10.0} с" +
                    (count?.let { ", доступно моделей: $it" } ?: "")
            )
        } catch (e: Exception) {
            report.append("СБОЙ — ${e.message?.take(160)}")
            return report.toString()
        }

        // 2) Реальный крошечный вызов модели: отвечает ли она вообще.
        report.append("\n2) Тестовый вызов $simpleModel: ")
        val t1 = System.currentTimeMillis()
        try {
            complete(
                apiKey, endpoint, simpleModel, null,
                listOf(Msg("user", "Ответь ровно одним словом: работает")), jsonMode = false
            )
            report.append("OK за ${((System.currentTimeMillis() - t1) / 100) / 10.0} с — соединение в порядке")
        } catch (e: Exception) {
            report.append("СБОЙ — ${e.message?.take(160)}")
        }
        return report.toString()
    }
}
