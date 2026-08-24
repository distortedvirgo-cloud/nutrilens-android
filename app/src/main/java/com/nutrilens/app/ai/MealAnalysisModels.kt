package com.nutrilens.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Результат анализа еды — структура, которую Gemini возвращает как JSON-строку.
 * Порт из веб-версии (src/utils/ai.ts, функция analyzeMealImage).
 */
@Serializable
data class MealAnalysisResult(
    val name: String,
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val aiThoughts: String = "",
    val reasoning: String = "",
    @SerialName("confidence_score") val confidenceScore: Double = 0.0,
    /**
     * Оценка полезности блюда по составу и БЖУ (0–100) от ИИ и нейтральная
     * короткая фраза о составе. null — модель не вернула (старые записи
     * показывают детерминированную оценку по БЖУ).
     */
    @SerialName("health_score") val healthScore: Double? = null,
    @SerialName("health_note") val healthNote: String? = null,
    val items: List<AnalyzedItemResult> = emptyList()
)

/**
 * Поэлементная разбивка одного блюда/продукта (массив items).
 */
@Serializable
data class AnalyzedItemResult(
    val name: String,
    @SerialName("estimated_weight_g") val weightG: Double = 0.0,
    @SerialName("portion_basis") val portionBasis: String = "",
    @SerialName("calorie_density") val calorieDensity: Double = 0.0,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val carbs: Double = 0.0,
    val breakdown: String = ""
)

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