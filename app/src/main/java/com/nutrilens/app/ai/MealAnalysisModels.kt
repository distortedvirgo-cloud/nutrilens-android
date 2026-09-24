package com.nutrilens.app.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Результат анализа еды — структура, которую Gemini возвращает как JSON-строку.
 * Порт из веб-версии (src/utils/ai.ts, функция analyzeMealImage).
 */
@Serializable
data class MealAnalysisResult(
    @Serializable(with = StringOrListSerializer::class)
    val name: String,
    // Итоговые КБЖУ могут отсутствовать в ответе (часть reasoning-моделей отдаёт
    // только items) — fixMealDrift достраивает их из суммы по продуктам.
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val carbs: Double = 0.0,
    // Reasoning-модели иногда отдают строковые поля массивом строк (например
    // "aiThoughts": ["Том-ям с грибами", ...]) — склеиваем в одну строку.
    @Serializable(with = StringOrListSerializer::class)
    val aiThoughts: String = "",
    @Serializable(with = StringOrListSerializer::class)
    val reasoning: String = "",
    @SerialName("confidence_score") val confidenceScore: Double = 0.0,
    /**
     * Оценка полезности блюда по составу и БЖУ (0–100) от ИИ и нейтральная
     * короткая фраза о составе. null — модель не вернула (старые записи
     * показывают детерминированную оценку по БЖУ).
     */
    @SerialName("health_score") val healthScore: Double? = null,
    @Serializable(with = StringOrListSerializer::class)
    @SerialName("health_note") val healthNote: String? = null,
    val items: List<AnalyzedItemResult> = emptyList()
)

/**
 * Поэлементная разбивка одного блюда/продукта (массив items).
 */
@Serializable
data class AnalyzedItemResult(
    @Serializable(with = StringOrListSerializer::class)
    val name: String,
    @SerialName("estimated_weight_g") val weightG: Double = 0.0,
    @Serializable(with = StringOrListSerializer::class)
    @SerialName("portion_basis") val portionBasis: String = "",
    @SerialName("calorie_density") val calorieDensity: Double = 0.0,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val carbs: Double = 0.0,
    @Serializable(with = StringOrListSerializer::class)
    val breakdown: String = ""
)

/**
 * Строковое поле, которое модель может вернуть и строкой, и массивом строк —
 * массив склеиваем в одну строку (каждый пункт с новой строки).
 */
object StringOrListSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: return decoder.decodeString()
        return when (element) {
            is JsonPrimitive -> element.contentOrNull ?: ""
            is JsonArray -> element
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            else -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

// Обёртка ответа Gemini REST API.
@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>? = null
)

@Serializable
data class GeminiPart(
    val text: String? = null
)

/** Вердикт маршрутизатора сложности (см. AnalysisCascade.estimateComplexity). */
@Serializable
data class ComplexityVerdict(
    val complexity: Int = 5
)

/**
 * Общий Json для анализа/ответов модели. ignoreUnknownKeys — модель может
 * добавить лишние поля; coerceInputValues — числа как null/пустые строки
 * превращать в default-значения; isLenient — простить нестрогий JSON.
 */
val mealJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}