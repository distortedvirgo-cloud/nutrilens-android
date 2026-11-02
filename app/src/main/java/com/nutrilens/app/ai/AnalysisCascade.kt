package com.nutrilens.app.ai

import android.util.Base64
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.aiProvider

/** Старшая модель: полный разбор сложных блюд, фолбэк и все вызовы advanced. */
const val NANO_MODEL_SENIOR = "google/gemini-3.8-flash"

/** Младшая модель: маршрутизатор сложности и полный разбор простых блюд. */
const val NANO_MODEL_JUNIOR = "openai/gpt-6-luna"

/** Оценка сложности >= порога — полный разбор делает старшая модель. */
const val COMPLEXITY_SENIOR_THRESHOLD = 7

/**
 * Каскад провайдеров анализа еды. Оба NanoGPT-режима работают через маршрутизатор
 * сложности: младшая модель одним дешёвым вызовом (фото + короткий промпт, без
 * расчётов) оценивает блюдо в 1–10, затем полный разбор делает соответствующая
 * модель. Это заменило прежнюю эскалацию по confidence — та пересылала весь
 * дорогой запрос повторно после уже оплаченного полного разбора.
 * - nanogpt: роутер → младшая/старшая;
 * - gemini: только свой ключ Gemini.
 * Провайдер строгий: фолбэков на другой провайдер нет — нет нужного ключа,
 * будет ошибка, а не тихий запрос в чужой API.
 * Слаги ходят в сервисном тире flex (service_tier ставится в NanoGptApi).
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

    // Полный разбор младшей моделью; пустая форма ответа или сбой — откат на старшую.
    suspend fun juniorThenSenior(): MealAnalysisResult = try {
        val junior = nano(NANO_MODEL_JUNIOR)
        if (junior.items.isNotEmpty() || junior.calories > 0.0) junior else nano(NANO_MODEL_SENIOR)
    } catch (e: Exception) {
        nano(NANO_MODEL_SENIOR)
    }

    return when (settings.aiProvider) {
        "nanogpt" -> {
            if (!hasNano) throw RuntimeException(NO_NANO_KEY_ERROR)
            val complexity = estimateComplexity(settings, imagesJpeg, userNote)
            when {
                // Роутер не ответил — младшая модель с откатом на старшую.
                complexity == null -> juniorThenSenior()
                complexity >= COMPLEXITY_SENIOR_THRESHOLD -> nano(NANO_MODEL_SENIOR)
                else -> juniorThenSenior()
            }
        }
        // Режим gemini: только Gemini, без NanoGPT-фолбэка.
        else -> {
            if (!hasGemini) throw RuntimeException(NO_GEMINI_KEY_ERROR)
            gemini()
        }
    }
}

/** Ошибка «нет ключа»: экраны предлагают сразу перейти в Профиль → Нейросеть. */
const val NO_KEY_ERROR = "Нет ключа ИИ — добавьте ключ NanoGPT или Gemini в Профиль → Нейросеть"

/** Ошибки «нет ключа выбранного провайдера»: режим строгий, фолбэков на другой провайдер нет. */
const val NO_NANO_KEY_ERROR = "Нет ключа NanoGPT — добавьте его в Профиль → Нейросеть"
const val NO_GEMINI_KEY_ERROR = "Нет ключа Gemini — добавьте его в Профиль → Нейросеть"

/**
 * Оценка сложности блюда младшей моделью: только вердикт 1–10, без расчётов.
 * Фото прилагается — оценить «на глаз», из чего состоит приём пищи. Любая ошибка
 * (сеть, парсинг, пустой ответ) — null: вызывающий действует по умолчанию режима.
 */
private suspend fun estimateComplexity(
    settings: SettingsEntity,
    imagesJpeg: List<ByteArray>,
    userNote: String
): Int? = try {
    val prompt = buildString {
        append("Ты маршрутизатор, а не калькулятор. Посмотри на фото приёма пищи и оцени ")
        append("одно: насколько сложно точно рассчитать его калории и БЖУ. ")
        append("1 — просто (одно-два очевидных продукта), 10 — очень сложно (много ")
        append("компонентов, составные блюда, соусы, неизвестные рецепты, упаковки без состава). ")
        append("Калории и БЖУ НЕ считай, продуктов не перечисляй. ")
        append("Верни ровно такой JSON: {\"complexity\": <целое 1-10>}")
        if (userNote.isNotBlank()) append("\n\nЗаметка пользователя: ").append(userNote)
    }
    val text = NanoGptApi.complete(
        settings.nanoApiKey, settings.nanoApiEndpoint, NANO_MODEL_JUNIOR, null,
        listOf(
            NanoGptApi.Msg(
                "user", prompt,
                imagesJpeg.map { Base64.encodeToString(it, Base64.NO_WRAP) }
            )
        ),
        jsonMode = true
    )
    mealJson.decodeFromString<ComplexityVerdict>(text).complexity.coerceIn(1, 10)
} catch (e: Exception) {
    null
}

/**
 * Ответы диетолога (чат) с тем же строгим провайдером, что и анализ еды:
 * режим nanogpt — через NanoGPT (младшая модель с откатом на старшую),
 * режим gemini — только через Gemini.
 * Нет нужного ключа — понятная ошибка, которую покажет экран чата.
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
        settings.aiProvider == "nanogpt" -> {
            if (!hasNano) throw RuntimeException(NO_NANO_KEY_ERROR)
            // Младшая модель первой, старшая — фолбэк.
            try {
                val junior = nano(NANO_MODEL_JUNIOR)
                if (junior.isNotBlank()) junior else nano(NANO_MODEL_SENIOR)
            } catch (e: Exception) {
                nano(NANO_MODEL_SENIOR)
            }
        }
        hasGemini -> gemini()
        else -> throw RuntimeException(NO_GEMINI_KEY_ERROR)
    }
}
