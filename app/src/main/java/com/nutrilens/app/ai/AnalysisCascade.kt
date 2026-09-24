package com.nutrilens.app.ai

import com.nutrilens.app.data.SettingsEntity

/** NanoGPT-слаги моделей каскада: старшая (качественная) и младшая (быстрая/дешёвая). */
const val NANO_MODEL_SIMPLE = "google/gemini-3.8-flash"
const val NANO_MODEL_ADVANCED = "google/gemini-3.8-flash"

/**
 * Младшая модель первого прохода — openai/gpt-6-luna; оба слага каскада ходят
 * в сервисном тире flex (service_tier ставится в NanoGptApi для всех запросов).
 */
const val NANO_MODEL_FAST = "openai/gpt-6-luna"

/**
 * Каскад провайдеров анализа еды, как в веб-версии:
 * - free: свой ключ Gemini; при сбое — NanoGPT-фолбэк, если ключ задан;
 * - simple: NanoGPT — младшая модель, при сбое старшая; без ключа — Gemini;
 * - advanced: NanoGPT — сразу старшая модель.
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
            // Сначала младшая модель; сбой (сеть, парсинг, пустая форма ответа) —
            // откат на старшую.
            try {
                val fast = nano(NANO_MODEL_FAST)
                if (fast.items.isNotEmpty() || fast.calories > 0.0) fast else nano(NANO_MODEL_SIMPLE)
            } catch (e: Exception) {
                nano(NANO_MODEL_SIMPLE)
            }
        } else gemini()
        "advanced" -> if (hasNano) {
            // Старшая модель и есть первый вызов advanced: эскалация на ту же модель
            // с тем же промптом ничего не добавляет, поэтому один вызов без повторов.
            nano(NANO_MODEL_ADVANCED)
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
            // Как в анализе еды: младшая модель первой, старшая — фолбэк.
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
