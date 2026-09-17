package com.nutrilens.app.ai

import com.nutrilens.app.data.SettingsEntity

/** NanoGPT-слаги моделей каскада: лёгкая для simple, thinking-модель для advanced. */
const val NANO_MODEL_SIMPLE = "z-ai/glm-5.3-flash"
const val NANO_MODEL_ADVANCED = "qwen/qwen3.8-max:thinking"

/**
 * Быстрая модель первого прохода для simple-каскада — та же, на которой работал
 * веб-анализатор (промпт писался под неё). У glm-5.3-flash провайдер не даёт
 * отключить reasoning («GLM 5.3 always thinks»), поэтому время от 14 до 58 с;
 * gemini-3.1-flash-lite отвечает без фазы размышлений стабильно за ~8 с.
 */
const val NANO_MODEL_FAST = "google/gemini-3.1-flash-lite"

/** Порог эскалации advanced-каскада: уверенность ниже — зовём thinking-модель. */
const val ADVANCED_ESCALATION_THRESHOLD = 7

/**
 * Каскад провайдеров анализа еды, как в веб-версии:
 * - free: свой ключ Gemini; при сбое — NanoGPT-фолбэк, если ключ задан;
 * - simple: NanoGPT — быстрая модель, при сбое glm; без ключа — Gemini;
 * - advanced: NanoGPT-каскад lite → thinking при низкой уверенности.
 */
suspend fun analyzeMealCascade(
    settings: SettingsEntity,
    imagesJpeg: List<ByteArray>,
    userNote: String,
    recentMealsContext: String,
    currentResultContext: String = ""
): MealAnalysisResult {
    val hasGemini = settings.apiKey.isNotBlank()
    val hasNano = settings.nanoApiKey.isNotBlank()

    suspend fun gemini(): MealAnalysisResult = GeminiApi(settings.apiKey).analyzeMeal(
        imagesJpeg, settings.userContext, userNote, recentMealsContext, currentResultContext
    )

    suspend fun nano(model: String): MealAnalysisResult = NanoGptApi.analyzeMeal(
        settings.nanoApiKey, settings.nanoApiEndpoint, model,
        imagesJpeg, settings.userContext, userNote, recentMealsContext, currentResultContext
    )

    return when (settings.analysisMode) {
        "simple" -> if (hasNano) {
            // Сначала быстрая модель без reasoning-фазы; сбой (сеть, парсинг, пустая
            // форма ответа) — откат на glm-каскад с его переспросами.
            try {
                val fast = nano(NANO_MODEL_FAST)
                if (fast.items.isNotEmpty() || fast.calories > 0.0) fast else nano(NANO_MODEL_SIMPLE)
            } catch (e: Exception) {
                nano(NANO_MODEL_SIMPLE)
            }
        } else gemini()
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
        // Роли в ChatTurn — гемини-формат ("user"/"model"); NanoGPT принимает
        // только system/user/assistant/tool, "model" → 400 invalid_message_role.
        history.map { NanoGptApi.Msg(if (it.role == "model") "assistant" else it.role, it.text, it.imagesBase64) },
        jsonMode = false
    )

    return when {
        settings.analysisMode == "advanced" && hasNano -> nano(NANO_MODEL_ADVANCED)
        settings.analysisMode == "simple" && hasNano -> {
            // Как в анализе еды: быстрая модель без reasoning-фазы первой, glm — фолбэк.
            try {
                val fast = nano(NANO_MODEL_FAST)
                if (fast.isNotBlank()) fast else nano(NANO_MODEL_SIMPLE)
            } catch (e: Exception) {
                nano(NANO_MODEL_SIMPLE)
            }
        }
        hasGemini -> gemini()
        hasNano -> nano(NANO_MODEL_SIMPLE)
        else -> throw RuntimeException("Укажите ключ Gemini или NanoGPT в настройках")
    }
}
