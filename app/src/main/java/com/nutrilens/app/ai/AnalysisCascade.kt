package com.nutrilens.app.ai

import android.util.Base64
import com.nutrilens.app.data.SettingsEntity

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
 * - free: свой ключ Gemini; при сбое — NanoGPT-фолбэк, если ключ задан;
 * - simple/advanced: NanoGPT — роутер → младшая/старшая; без ключа — Gemini.
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

    return when (settings.analysisMode) {
        "simple", "advanced" -> if (hasNano) {
            val complexity = estimateComplexity(settings, imagesJpeg, userNote)
            when {
                // Роутер не ответил: advanced (режим качества) — старшая,
                // simple — прежнее поведение «младшая с откатом».
                complexity == null ->
                    if (settings.analysisMode == "advanced") nano(NANO_MODEL_SENIOR) else juniorThenSenior()
                complexity >= COMPLEXITY_SENIOR_THRESHOLD -> nano(NANO_MODEL_SENIOR)
                else -> juniorThenSenior()
            }
        } else gemini()
        else -> if (hasGemini) {
            try {
                gemini()
            } catch (e: Exception) {
                if (hasNano) nano(NANO_MODEL_SENIOR) else throw e
            }
        } else if (hasNano) {
            nano(NANO_MODEL_SENIOR)
        } else {
            throw RuntimeException("Укажите ключ Gemini или NanoGPT в настройках")
        }
    }
}

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
 * Ответы диетолога (чат) с тем же каскадом провайдеров, что и анализ еды:
 * simple/advanced при наличии ключа NanoGPT идут через NanoGPT, иначе — Gemini.
 * Роутера сложности у чата нет (сложность там не в блюде): simple — младшая
 * модель с откатом на старшую, advanced — сразу старшая.
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
        settings.analysisMode == "advanced" && hasNano -> nano(NANO_MODEL_SENIOR)
        settings.analysisMode == "simple" && hasNano -> {
            // Младшая модель первой, старшая — фолбэк.
            try {
                val junior = nano(NANO_MODEL_JUNIOR)
                if (junior.isNotBlank()) junior else nano(NANO_MODEL_SENIOR)
            } catch (e: Exception) {
                nano(NANO_MODEL_SENIOR)
            }
        }
        hasGemini -> gemini()
        hasNano -> nano(NANO_MODEL_SENIOR)
        else -> throw RuntimeException("Укажите ключ Gemini или NanoGPT в настройках")
    }
}
