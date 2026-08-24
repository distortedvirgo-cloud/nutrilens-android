package com.nutrilens.app.ai

import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
import kotlin.math.abs

/**
 * Клиент Gemini REST API для анализа фото еды. Порт веб-версии
 * (src/utils/ai.ts, функция analyzeMealImage).
 *
 * Запрос идёт на gemini-3.1-flash-lite через generateContent, тело собирается
 * через kotlinx.serialization (buildJsonObject), ответ парсится в
 * [MealAnalysisResult].
 */
class GeminiApi(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Анализирует фото еды: собирает промпт + inline-изображения, шлёт запрос
     * Gemini и применяет фикс дрейфа КБЖУ (сумма items надёжнее итоговых полей).
     */
    suspend fun analyzeMeal(
        imagesJpeg: List<ByteArray>,
        userContext: String,
        userNote: String,
        recentMealsContext: String = ""
    ): MealAnalysisResult {
        val prompt = buildMealAnalysisPrompt(
            userContext,
            userNote,
            recentMealsContext = recentMealsContext,
            photoCount = imagesJpeg.size
        )
        val bodyJson = buildRequestBodyJson(prompt, imagesJpeg)

        val request = Request.Builder()
            .url(URL)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = execute(request)

        // Текст ответа: склеиваем все parts из content кандидата 0.
        val text = parseResponseBody(responseBody)
        val jsonText = stripJsonFence(text)

        val result = try {
            mealJson.decodeFromString<MealAnalysisResult>(jsonText)
        } catch (e: Exception) {
            throw RuntimeException(
                "Ошибка парсинга: ${e.message}. Ответ: ${text.take(300)}",
                e
            )
        }

        return fixMealDrift(result)
    }

    /**
     * Выполняет HTTP-запрос через OkHttp в suspend-стиле. Возвращает тело ответа.
     * При коде != 200 бросает RuntimeException("Gemini HTTP <code>: <тело, первые 300 символов>").
     */
    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        cont.resume(body)
                    } else {
                        val friendly = if (
                            body.contains("safety", true) || body.contains("SAFETY") ||
                            body.contains("blocked", true)
                        ) {
                            "Запрос был заблокирован фильтрами безопасности Google Gemini."
                        } else {
                            "Gemini HTTP ${resp.code}: ${body.take(300)}"
                        }
                        cont.resumeWithException(RuntimeException(friendly))
                    }
                }
            }
        })
    }

    /** Достаёт текст из candidates[0].content.parts[].text. Пусто — ошибка «Пустой ответ модели». */
    private fun parseResponseBody(body: String): String {
        val response = mealJson.decodeFromString<GeminiResponse>(body)
        val text = response.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("Пустой ответ модели")
        return text
    }

    /** Собирает JSON-тело запроса generateContent (промпт + inline_data на каждое фото). */
    private fun buildRequestBodyJson(prompt: String, imagesJpeg: List<ByteArray>): String {
        return buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        // Текст промпта первым.
                        addJsonObject {
                            put("text", prompt)
                        }
                        // Каждое фото — inline_data с base64 (NO_WRAP).
                        imagesJpeg.forEach { bytes ->
                            addJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", "image/jpeg")
                                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        put("name", buildJsonObject { put("type", "STRING") })
                        put("calories", buildJsonObject { put("type", "NUMBER") })
                        put("protein", buildJsonObject { put("type", "NUMBER") })
                        put("fat", buildJsonObject { put("type", "NUMBER") })
                        put("carbs", buildJsonObject { put("type", "NUMBER") })
                        put("aiThoughts", buildJsonObject { put("type", "STRING") })
                        put("reasoning", buildJsonObject { put("type", "STRING") })
                        put("confidence_score", buildJsonObject { put("type", "NUMBER") })
                        putJsonObject("items") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("name", buildJsonObject { put("type", "STRING") })
                                    put("estimated_weight_g", buildJsonObject { put("type", "NUMBER") })
                                    put("portion_basis", buildJsonObject { put("type", "STRING") })
                                    put("calorie_density", buildJsonObject { put("type", "NUMBER") })
                                    put("calories", buildJsonObject { put("type", "NUMBER") })
                                    put("protein", buildJsonObject { put("type", "NUMBER") })
                                    put("fat", buildJsonObject { put("type", "NUMBER") })
                                    put("carbs", buildJsonObject { put("type", "NUMBER") })
                                    put("breakdown", buildJsonObject { put("type", "STRING") })
                                }
                                put(
                                    "required",
                                    buildJsonArray {
                                        add("name")
                                        add("estimated_weight_g")
                                        add("portion_basis")
                                        add("calorie_density")
                                        add("calories")
                                        add("protein")
                                        add("fat")
                                        add("carbs")
                                        add("breakdown")
                                    }
                                )
                            }
                        }
                    }
                    put(
                        "required",
                        buildJsonArray {
                            add("name")
                            add("calories")
                            add("protein")
                            add("fat")
                            add("carbs")
                            add("aiThoughts")
                            add("reasoning")
                            add("confidence_score")
                            add("items")
                        }
                    )
                }
            }
            putJsonArray("safetySettings") {
                addJsonObject {
                    put("category", "HARM_CATEGORY_HATE_SPEECH")
                    put("threshold", "BLOCK_NONE")
                }
                addJsonObject {
                    put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                    put("threshold", "BLOCK_NONE")
                }
                addJsonObject {
                    put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                    put("threshold", "BLOCK_NONE")
                }
                addJsonObject {
                    put("category", "HARM_CATEGORY_HARASSMENT")
                    put("threshold", "BLOCK_NONE")
                }
            }
        }.toString()
    }

    companion object {
        private const val MODEL = "gemini-3.1-flash-lite"
        private const val URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    }
}

/** Срезает обёртку ```json ... ``` если модель её добавила. */
internal fun stripJsonFence(text: String): String {
    val trimmed = text.trim()
    if (trimmed.startsWith("```")) {
        val firstNewline = trimmed.indexOf('\n')
        val lastFence = trimmed.lastIndexOf("```")
        if (firstNewline > 0 && lastFence > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastFence).trim()
        }
    }
    return trimmed
}

/**
 * Фикс дрейфа (как в веб-версии): если сумма КБЖУ по items существенно
 * расходится с итоговыми полями верхнего уровня — верим сумме items.
 */
internal fun fixMealDrift(result: MealAnalysisResult): MealAnalysisResult {
    if (result.items.isEmpty()) return result

    var sumCalories = 0.0
    var sumProtein = 0.0
    var sumFat = 0.0
    var sumCarbs = 0.0
    for (item in result.items) {
        sumCalories += item.calories
        sumProtein += item.protein
        sumFat += item.fat
        sumCarbs += item.carbs
    }

    if (abs(sumCalories - result.calories) > maxOf(30.0, result.calories * 0.10)) {
        return result.copy(
            calories = sumCalories,
            protein = sumProtein,
            fat = sumFat,
            carbs = sumCarbs
        )
    }
    return result
}