package com.nutrilens.app.ai

import com.nutrilens.app.data.SettingsEntity

/** NanoGPT-слаги как в веб-версии (src/utils/models.ts). */
const val NANO_MODEL_SIMPLE = "google/gemini-3.1-flash-lite"
const val NANO_MODEL_ADVANCED = "google/gemini-3.5-flash-thinking"

/** Порог эскалации advanced-каскада: уверенность ниже — зовём thinking-модель. */
const val ADVANCED_ESCALATION_THRESHOLD = 7

/**
 * Каскад провайдеров анализа еды, как в веб-версии:
 * - free: свой ключ Gemini; при сбое — NanoGPT-фолбэк, если ключ задан;
 * - simple: NanoGPT (лёгкая модель), без ключа — Gemini;
 * - advanced: NanoGPT-каскад lite → thinking при низкой уверенности.
 */
suspend fun analyzeMealCascade(
    settings: SettingsEntity,
    imagesJpeg: List<ByteArray>,
    userNote: String,
    recentMealsContext: String
): MealAnalysisResult {
    val hasGemini = settings.apiKey.isNotBlank()
    val hasNano = settings.nanoApiKey.isNotBlank()

    suspend fun gemini(): MealAnalysisResult = GeminiApi(settings.apiKey).analyzeMeal(
        imagesJpeg, settings.userContext, userNote, recentMealsContext
    )

    suspend fun nano(model: String): MealAnalysisResult = NanoGptApi.analyzeMeal(
        settings.nanoApiKey, settings.nanoApiEndpoint, model,
        imagesJpeg, settings.userContext, userNote, recentMealsContext
    )

    return when (settings.analysisMode) {
        "simple" -> if (hasNano) nano(NANO_MODEL_SIMPLE) else gemini()
        "advanced" -> if (hasNano) {
            val first = nano(NANO_MODEL_SIMPLE)
            if (first.confidenceScore < ADVANCED_ESCALATION_THRESHOLD) {
                nano(NANO_MODEL_ADVANCED)
            } else {
                first
            }
        } else {
            gemini()
        }
        else -> if (hasGemini) {
            try {
                gemini()
            } catch (e: Exception) {
                if (hasNano) nano(NANO_MODEL_SIMPLE) else throw e
            }
        } else if (hasNano) {
            nano(NANO_MODEL_SIMPLE)
        } else {
            throw RuntimeException("Укажите ключ Gemini или NanoGPT в настройках")
        }
    }
}

/**
 * Ответы диетолога (чат) с тем же каскадом провайдеров, что и анализ еды:
 * simple/advanced при наличии ключа NanoGPT идут через NanoGPT, иначе — Gemini.
 * Ключей нет — понятная ошибка, которую покажет экран чата.
 */
suspend fun chatWithCascade(
    settings: SettingsEntity,
    system: String,
    history: List<GeminiTools.ChatTurn>
): String {
    val hasGemini = settings.apiKey.isNotBlank()
    val hasNano = settings.nanoApiKey.isNotBlank()

    suspend fun gemini(): String = GeminiTools.chat(settings.apiKey, system, history)
    suspend fun nano(model: String): String = NanoGptApi.complete(
        settings.nanoApiKey, settings.nanoApiEndpoint, model, system,
        history.map { NanoGptApi.Msg(it.role, it.text, it.imagesBase64) },
        jsonMode = false
    )

    return when {
        settings.analysisMode == "advanced" && hasNano -> nano(NANO_MODEL_ADVANCED)
        settings.analysisMode == "simple" && hasNano -> nano(NANO_MODEL_SIMPLE)
        hasGemini -> gemini()
        hasNano -> nano(NANO_MODEL_SIMPLE)
        else -> throw RuntimeException("Укажите ключ Gemini или NanoGPT в настройках")
    }
}
