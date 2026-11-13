package com.nutrilens.app.ai

import android.util.Base64
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.aiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/**
 * Оценка процента жира по фото (3.25). Грубый ориентир: независимых проверок точности
 * таких оценок нет, поэтому просим диапазон, а в план он не идёт — план считается только
 * по замерам лентой. Та же логика — web/src/ai/bodyfat.ts.
 */
object BodyFatAi {

    data class Estimate(val low: Double, val high: Double, val comment: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun prompt(male: Boolean?, heightCm: Double?, weight: Double?): String {
        val who = listOfNotNull(
            male?.let { if (it) "мужчина" else "женщина" },
            heightCm?.let { "рост ${it.roundToInt()} см" },
            weight?.let { "вес ${(it * 10).roundToInt() / 10.0} кг" }
        ).joinToString(", ")
        return """На фото — человек, который следит за составом тела${if (who.isNotEmpty()) " ($who)" else ""}.
Оцени процент жира в теле по внешнему виду: рельеф, распределение жира, талия.
Это грубая визуальная оценка, поэтому дай диапазон шириной 3–6 процентных пунктов.
Если на фото не видно тела (лицо, одежда скрывает фигуру, нет человека) — верни low и high равными 0 и объясни в comment, какое фото нужно.
comment — 1–2 коротких предложения по-русски на «ты», без оценок внешности: на что ты смотрел и как сделать следующее фото сравнимым (тот же свет, поза, расстояние).
Ответ — только JSON: {"low": число, "high": число, "comment": "строка"}"""
    }

    fun parse(text: String): Estimate {
        val obj = runCatching { json.parseToJsonElement(stripJsonFence(text)).jsonObject }.getOrNull()
            ?: throw RuntimeException("ИИ вернул непонятный ответ — попробуй ещё раз")
        val low = (obj["low"] as? JsonPrimitive)?.doubleOrNull
        val high = (obj["high"] as? JsonPrimitive)?.doubleOrNull
        val comment = (obj["comment"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(400).orEmpty()
        if (low == null || high == null) throw RuntimeException("ИИ не смог оценить фото — попробуй другое")
        if (low <= 0 || high <= 0) throw RuntimeException(comment.ifBlank { "На фото не видно фигуры — нужно фото в полный рост или по пояс" })
        val lo = minOf(low, high).coerceAtLeast(3.0)
        val hi = maxOf(low, high).coerceAtMost(60.0)
        return Estimate((lo * 10).roundToInt() / 10.0, (hi * 10).roundToInt() / 10.0, comment)
    }

    /** [jpeg] — фото тела. Ошибки — понятным текстом (нет ключа, лимит, сеть). */
    suspend fun estimate(settings: SettingsEntity, jpeg: ByteArray, male: Boolean?, heightCm: Double?, weight: Double?): Estimate {
        val p = prompt(male, heightCm, weight)
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        if (settings.aiProvider == "gemini") {
            if (settings.apiKey.isBlank()) throw RuntimeException("Нет ключа Gemini — добавь его в Профиле → Нейросеть")
            val body = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject { put("text", p) }
                            addJsonObject { putJsonObject("inline_data") { put("mime_type", "image/jpeg"); put("data", b64) } }
                        }
                    }
                }
                putJsonObject("generationConfig") { put("responseMimeType", "application/json") }
            }.toString()
            val raw = GeminiTransport.generate(settings.apiKey, body)
            val text = json.parseToJsonElement(raw).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.joinToString("") { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                ?.takeIf { it.isNotBlank() } ?: throw RuntimeException("Пустой ответ модели")
            return parse(text)
        }
        if (settings.nanoApiKey.isBlank()) throw RuntimeException("Нет ключа NanoGPT — добавь его в Профиле → Нейросеть")
        val text = NanoGptApi.complete(
            settings.nanoApiKey, settings.nanoApiEndpoint, NANO_MODEL_SENIOR, null,
            listOf(NanoGptApi.Msg("user", p, listOf(b64))), jsonMode = true
        )
        return parse(text)
    }
}
