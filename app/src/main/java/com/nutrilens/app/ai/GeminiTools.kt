package com.nutrilens.app.ai

import com.nutrilens.app.data.SettingsEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObjectBuilder
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
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * REST-клиент Gemini для инструментов приложения (чат, подбор блюд, рецепты,
 * список покупок, анализ фото, привычки, водный баланс, статистика).
 *
 * Транспорт — как в [GeminiApi]: OkHttp + suspendCancellableCoroutine,
 * заголовок x-goog-api-key, безопасность через x-goog-api-key/safetySettings,
 * ответ парсится как candidates[0].content.parts[].text, у JSON-ответов
 * срезается ```json-обёртка. Модель — gemini-3.1-flash-lite.
 */
object GeminiTools {

    private const val MODEL = "gemini-3.1-flash-lite"
    private const val URL =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ---------- Модели данных ----------

    /**
     * Один ход разговора в чате.
     * @param role "user" или "model".
     * @param text Текст сообщения (пустой при отправке только фото).
     * @param imagesBase64 Вложенные фото (base64, JPEG).
     */
    data class ChatTurn(
        val role: String, /*"user"|"model"*/
        val text: String,
        val imagesBase64: List<String> = emptyList()
    )

    @Serializable
    data class Recommendation(
        val id: String,
        val title: String,
        val shortDescription: String,
        val calories: Double,
        val recipePrompt: String
    )

    @Serializable
    data class GroceryPlan(
        val plan: String,
        val categories: List<GroceryCategory>
    )

    @Serializable
    data class GroceryCategory(
        val category: String,
        val items: List<String>
    )

    @Serializable
    private data class RecommendationsPayload(
        val recommendations: List<Recommendation>
    )

    // ---------- Публичное API ----------

    /**
     * Чат с диетологом: системная инструкция + история по ролям (с фото). Без JSON-схемы.
     */
    suspend fun chat(apiKey: String, systemInstruction: String, history: List<ChatTurn>): String {
        val bodyJson = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", systemInstruction) }
                }
            }
            addChatContents(history)
            safetySettings()
        }.toString()

        val responseBody = execute(apiKey, bodyJson)
        return parseText(responseBody)
    }

    /**
     * Подбор 3 идей для еды под оставшиеся калории. Возвращает структурированный список.
     */
    suspend fun getRecommendations(
        settings: SettingsEntity,
        userContext: String,
        userInput: String,
        remainingCalories: Int,
        recentMealsContext: String,
        macroGoals: Triple<Double, Double, Double>?
    ): List<Recommendation> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeOfDay = when {
            hour < 11 -> "Утро"
            hour >= 17 -> "Вечер"
            else -> "День"
        }
        val macroLine = if (macroGoals != null) {
            " Цели на день по макросам: Б ${macroGoals.first.roundToInt()} г / " +
                "Ж ${macroGoals.second.roundToInt()} г / У ${macroGoals.third.roundToInt()} г. " +
                "Учитывай, сколько из этих макросов уже съедено сегодня (см. «СЕГОДНЯ УЖЕ " +
                "СЪЕДЕНО») и предлагай блюда, которые помогают дотянуть белки/жиры/углеводы до " +
                "цели, не перебирая."
        } else {
            ""
        }
        val prompt = """
            Ты профессиональный диетолог. Подбери конкретные идеи для еды под оставшиеся ${remainingCalories} ккал пользователя.
            [КОНТЕКСТ ПОЛЬЗОВАТЕЛЯ]: ${userContext}
            [ОТКРЫТЫЙ ЗАПРОС / ПОЖЕЛАНИЯ СЕЙЧАС]: ${userInput.ifBlank { "Обычный прием пищи" }}
            [СЕГОДНЯ УЖЕ СЪЕДЕНО]: ${recentMealsContext.ifBlank { "пока ничего" }}
            [ТЕКУЩЕЕ ВРЕМЯ]: ${timeOfDay} (${hour}:00)${macroLine}

            Правила подбора:
            1. Если пользователь напрямую просит конкретную идею или продукт («хочу сладкое», «что-то из творога», «идеи для тренировки»), строго следуй этому запросу, но в рамках остатка калорий.
            2. Если сейчас Утро или День, НЕ предлагай блюда, которые заберут ВСЕ оставшиеся калории — оставь место для следующих приёмов пищи.
            3. Если сейчас Вечер, можно предлагать блюда на весь оставшийся остаток.
            4. Каждое предложенное блюдо НЕ должно превышать оставшийся остаток калорий.
            5. Не повторяй то, что пользователь уже ел сегодня (см. «СЕГОДНЯ УЖЕ СЪЕДЕНО»).

            Верни РОВНО 3 идеи в виде JSON-объекта со строгой структурой (см. schema). Поле 'recipePrompt' для каждого блюда — это КРАТКОЕ описание состава и способа приготовления (2-3 предложения), которое позже будет развёрнуто в полный рецепт. 'calories' — итоговая калорийность порции (целое число, не больше остатка).
        """.trimIndent()

        val responseBody = modelText(settings, buildRecommendationsBody(prompt))
        val jsonText = stripFence(parseText(responseBody))
        val payload = try {
            mealJson.decodeFromString<RecommendationsPayload>(jsonText)
        } catch (e: Exception) {
            throw RuntimeException(
                "Ошибка парсинга: ${e.message}. Ответ: ${jsonText.take(300)}",
                e
            )
        }
        return payload.recommendations
    }

    /**
     * Полный пошаговый рецепт из краткого описания. Простой текст, без JSON-схемы.
     */
    suspend fun getDetailedRecipe(settings: SettingsEntity, recipePrompt: String): String {
        val prompt = """
            Напиши подробный пошаговый рецепт для следующего блюда:
            ${recipePrompt}
            Включи ингредиенты (с граммовками) и пошаговую инструкцию. Отвечай просто текстом (без сложного форматирования, используй обычные списки с тире). Не используй JSON.
        """.trimIndent()

        val responseBody = modelText(settings, buildSchemalessBody(prompt))
        return parseText(responseBody)
    }

    /**
     * План питания на неделю + список покупок. При ошибке парсинга — пустой план.
     */
    suspend fun generateGroceryList(
        settings: SettingsEntity,
        userContext: String,
        dailyGoal: Double,
        preferences: String
    ): GroceryPlan {
        val prompt = """
            Составь план питания на неделю (на 1 человека) и соответствующий список покупок.
            Цель: ${dailyGoal.roundToInt()} ккал/день.
            Контекст: ${userContext}
            Пожелания пользователя: ${preferences.ifBlank { "Нет" }}

            План должен описывать, какие блюда готовить на неделю и на сколько дней они рассчитаны, чтобы вписаться в КБЖУ.
            При составлении списка продуктов УКАЗЫВАЙ ТОЧНОЕ КОЛИЧЕСТВО, ВЕС ИЛИ ОБЪЕМ для каждого продукта (например, "Куриное филе - 1.5 кг", "Яйца - 20 шт", "Молоко - 2 л"). Не используй Markdown форматирование (двойные звездочки и т.д.), отвечай простым текстом.

            Верни результат СТРОГО в формате JSON:
            {
              "plan": "Описание плана питания на неделю (какие блюда, на сколько дней и калорий). Без markdown-разметки.",
              "categories": [
                {
                  "category": "Название категории (Овощи, Мясо и т.д.)",
                  "items": ["Продукт 1 - X шт/кг", "Продукт 2 - Y шт/кг"]
                }
              ]
            }
        """.trimIndent()

        val responseBody = modelText(settings, buildGroceryBody(prompt))
        val jsonText = stripFence(parseText(responseBody))
        return try {
            mealJson.decodeFromString<GroceryPlan>(jsonText)
        } catch (e: Exception) {
            GroceryPlan("", emptyList())
        }
    }

    /**
     * Анализ фото холодильника/стола: 3 здоровых рецепта из того, что есть (Markdown).
     */
    suspend fun analyzeFridge(
        settings: SettingsEntity,
        userContext: String,
        dailyGoal: Double,
        remainingCalories: Int,
        useRemaining: Boolean,
        imagesBase64: List<String>
    ): String {
        val prompt = """
            Посмотри на фото продуктов (содержимое холодильника или стола). 
            Пользователь: ${userContext}.${if (useRemaining) " Цель на день: ${dailyGoal.roundToInt()} ккал. Свободно на сегодня: ${remainingCalories} ккал." else ""}
            Предложи 3 здоровых рецепта, используя ПРЕИМУЩЕСТВЕННО то, что видишь на фото${if (useRemaining) ", стараясь вписаться в оставшиеся калории (если их много — можно сытнее, если мало — более лёгкие)" else ""}. Для каждого рецепта:
            1. Название и КБЖУ (калории, белки, жиры, углеводы).
            2. Какие ингредиенты из увиденного используются.
            3. Чего не хватает (что нужно докупить по минимуму — НЕ БОЛЕЕ 2 товаров).
            4. Краткий рецепт.
            Опиши с Markdown-форматированием. Не предлагай блюда, если на фото нет соответствующих ингредиентов.
        """.trimIndent()

        val responseBody = modelText(settings, buildSchemalessBody(prompt, imagesBase64))
        return parseText(responseBody)
    }

    /**
     * Анализ фото меню ресторана/кафе: топ-3, «если хочется...», красные флаги (Markdown).
     */
    suspend fun analyzeMenu(
        settings: SettingsEntity,
        userContext: String,
        dailyGoal: Double,
        remainingCalories: Int,
        useRemaining: Boolean,
        imagesBase64: List<String>
    ): String {
        val prompt = """
            Посмотри на фото меню из ресторана/кафе.
            Пользователь: ${userContext}. Дневной лимит калорий: ${dailyGoal.roundToInt()} ккал.${if (useRemaining) " Свободно на сегодня: ${remainingCalories} ккал (подбери блюда, которые не превысят этот остаток)." else " Подбери блюда в рамках дневного лимита."}
            Твоя задача — помочь пользователю выбрать блюда:
            1. "Топ-3 лучших варианта" из меню с примерной оценкой КБЖУ для каждой позиции.
            2. "Что взять, если хочется..." — для сладкого, сытного, лёгкого (по одному варианту).
            3. "Красные флаги" — какие блюда в меню лучше избегать и почему (скрытые калории, жареное, соусы, огромные порции).
            Отвечай структурированно, используй Markdown. Не давай медицинских рекомендаций.
        """.trimIndent()

        val responseBody = modelText(settings, buildSchemalessBody(prompt, imagesBase64))
        return parseText(responseBody)
    }

    /**
     * Анализ привычки/проблемы с питанием (Markdown).
     */
    suspend fun analyzeHabit(settings: SettingsEntity, userContext: String, habit: String): String {
        val prompt = """
            Ты опытный нутрициолог-консультант (НЕ врач). Пользователь: ${userContext}.
            У пользователя есть следующая привычка или проблема с питанием: "${habit}".

            Проанализируй эту привычку:
            1. Возможные скрытые причины такого поведения (физиологические сигналы — голод/недосып/дефицит макронутриентов; эмоциональные триггеры — стресс/скука/награда).
            2. 3 конкретных практических шага, выполнимых в течение ближайшей недели, чтобы ослабить привычку или заменить её полезной альтернативой (формулируй как чёткие действия, а не общие советы).
            3. Короткая мотивирующая фраза в конце.
            Важно: не ставь медицинских диагнозов и не назначай лечение — при подозрении на расстройство питания мягко порекомендуй обратиться к специалисту.
            Структурируй ответ и используй Markdown.
        """.trimIndent()

        val responseBody = modelText(settings, buildSchemalessBody(prompt))
        return parseText(responseBody)
    }

    /**
     * Рекомендации по водному балансу (Markdown).
     */
    suspend fun waterAdvice(settings: SettingsEntity, userContext: String, weightKg: Double?): String {
        val weightLine = if (weightKg != null) {
            " Текущий вес пользователя: ${"%.1f".format(weightKg)} кг."
        } else {
            " Вес пользователя неизвестен — тогда возьми типовую норму и обязательно объясни, что точная норма зависит от веса (30-35 мл на кг)."
        }
        val prompt = """
            Ты профессиональный диетолог. Дай рекомендации по водному балансу.
            Пользователь: ${userContext}.${weightLine}

            Выполни:
            1. Рассчитай индивидуальную суточную норму воды по весу (~30-35 мл на кг массы тела, больше при тренировках/жаре) — приведи формулу и итог в литрах И в стаканах по 250 мл.
            2. Дай 3 коротких практических совета, как не забывать пить воду в течение дня.
            3. Упомяни, что часть воды поступает с едой (супы, фрукты), поэтому «чистой» воды можно чуть меньше.
            Отвечай структурированно, используй Markdown. Не давай медицинских диагнозов.
        """.trimIndent()

        val responseBody = modelText(settings, buildSchemalessBody(prompt))
        return parseText(responseBody)
    }

    /**
     * Короткая оценка рациона за последние дни (свободный текст).
     */
    suspend fun statsInsight(settings: SettingsEntity, dailyGoal: Double, recentData: String): String {
        val prompt = """
            Проанализируй рацион за последние дни:
            ${recentData}

            Цель пользователя: ${dailyGoal.roundToInt()} ккал/день.

            Дай оценку от 1 до 10 (где 10 - идеально) и 2-3 коротких конструктивных совета по улучшению нутриентов/выбора блюд. Отвечай коротко и только по делу.
        """.trimIndent()

        val responseBody = modelText(settings, buildSchemalessBody(prompt))
        return parseText(responseBody)
    }

    /**
     * Системная инструкция чата: цель калорий, контекст пользователя и что уже съедено сегодня.
     */
    fun buildChatSystemInstruction(
        dailyGoal: Double,
        userContext: String,
        todayMeals: List<Pair<String, Int>>,
        todayTotal: Int
    ): String = """
        Ты дружелюбный и профессиональный ИИ-диетолог.
        Твоя цель — помогать пользователю, отвечать на вопросы о питании, давать советы, также можешь анализировать фото еды, которые пользователь прикрепляет к сообщениям.
        Данные пользователя: цель ${dailyGoal.roundToInt()} ккал. Контекст: ${userContext}.
        Съедено сегодня: ${todayMeals.joinToString(", ") { "${it.first} (${it.second}ккал)" }} / За день: ${todayTotal} ккал.
        Старайся давать короткие, ёмкие и поддерживающие ответы. НЕ ставь медицинских диагнозов и не назначай лечение — при подозрении на расстройство питания мягко рекомендуй обратиться к специалисту.
    """.trimIndent()

    // ---------- Построение тела запроса ----------

    /** Тело для функций без JSON-схемы (текст + опциональные фото + safetySettings). */
    private fun buildSchemalessBody(prompt: String, imagesBase64: List<String> = emptyList()): String {
        return buildJsonObject {
            addPromptContent(prompt, imagesBase64)
            safetySettings()
        }.toString()
    }

    /** Тело подбора рекомендаций: промпт + schema recommendations[]. */
    private fun buildRecommendationsBody(prompt: String): String {
        return buildJsonObject {
            addPromptContent(prompt)
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("recommendations") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("id", buildJsonObject { put("type", "STRING") })
                                    put("title", buildJsonObject { put("type", "STRING") })
                                    put("shortDescription", buildJsonObject { put("type", "STRING") })
                                    put("calories", buildJsonObject { put("type", "NUMBER") })
                                    put("recipePrompt", buildJsonObject { put("type", "STRING") })
                                }
                                put(
                                    "required",
                                    buildJsonArray {
                                        add("id"); add("title"); add("shortDescription")
                                        add("calories"); add("recipePrompt")
                                    }
                                )
                            }
                        }
                    }
                    put("required", buildJsonArray { add("recommendations") })
                }
            }
            safetySettings()
        }.toString()
    }

    /** Тело списка покупок: промпт + schema plan/categories. */
    private fun buildGroceryBody(prompt: String): String {
        return buildJsonObject {
            addPromptContent(prompt)
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        put("plan", buildJsonObject { put("type", "STRING") })
                        putJsonObject("categories") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("category", buildJsonObject { put("type", "STRING") })
                                    putJsonObject("items") {
                                        put("type", "ARRAY")
                                        putJsonObject("items") { put("type", "STRING") }
                                    }
                                }
                                put(
                                    "required",
                                    buildJsonArray { add("category"); add("items") }
                                )
                            }
                        }
                    }
                    put("required", buildJsonArray { add("plan"); add("categories") })
                }
            }
            safetySettings()
        }.toString()
    }

    /** Один content с текстом промпта первым и inline_data на каждое фото. */
    private fun JsonObjectBuilder.addPromptContent(prompt: String, imagesBase64: List<String> = emptyList()) {
        putJsonArray("contents") {
            addJsonObject {
                putJsonArray("parts") {
                    addJsonObject { put("text", prompt) }
                    imagesBase64.forEach { b64 ->
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", b64)
                            }
                        }
                    }
                }
            }
        }
    }

    /** История чата по ролям; фото — inline_data, пустой текст при фото → «Вот фото». */
    private fun JsonObjectBuilder.addChatContents(history: List<ChatTurn>) {
        putJsonArray("contents") {
            history.forEach { turn ->
                addJsonObject {
                    put("role", turn.role)
                    putJsonArray("parts") {
                        if (turn.imagesBase64.isEmpty()) {
                            addJsonObject { put("text", turn.text) }
                        } else {
                            addJsonObject { put("text", turn.text.ifBlank { "Вот фото" }) }
                            turn.imagesBase64.forEach { b64 ->
                                addJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", "image/jpeg")
                                        put("data", b64)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** safetySettings: 4 категории BLOCK_NONE (как в [GeminiApi]). */
    private fun JsonObjectBuilder.safetySettings() {
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
    }

    // ---------- Транспорт ----------

    /**
     * Выполняет HTTP-запрос через OkHttp в suspend-стиле. Возвращает тело ответа.
     * При коде != 200 бросает RuntimeException с телом ответа (первые 300 символов).
     */
    private suspend fun execute(apiKey: String, bodyJson: String): String =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(URL)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
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
                                "Запрос был заблокирован фильтрами безопасности Google Gemini. " +
                                    "Тело ответа: ${body.take(300)}"
                            } else {
                                "Gemini HTTP ${resp.code}: ${body.take(300)}"
                            }
                            cont.resumeWithException(RuntimeException(friendly))
                        }
                    }
                }
            })
        }

    /**
     * Единый путь текстовых ИИ-инструментов (идеи, рецепты, покупки, холодильник,
     * ресторан, привычки, вода, оценка отчёта): при ключе NanoGPT и режиме
     * simple/advanced (или отсутствии Gemini) запрос идёт через NanoGPT с тем же
     * промптом (тело конвертируется в messages), иначе — напрямую в Gemini.
     */
    private suspend fun modelText(settings: SettingsEntity, bodyJson: String): String {
        val hasNano = settings.nanoApiKey.isNotBlank()
        val useNano = hasNano && (settings.analysisMode != "free" || settings.apiKey.isBlank())
        if (!useNano) return parseText(execute(settings.apiKey, bodyJson))

        val root = org.json.JSONObject(bodyJson)
        val system = root.optJSONObject("systemInstruction")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""
        val messages = mutableListOf<NanoGptApi.Msg>()
        root.optJSONArray("contents")?.let { contents ->
            for (i in 0 until contents.length()) {
                val c = contents.getJSONObject(i)
                val role = if (c.optString("role") == "model") "assistant" else "user"
                val parts = c.optJSONArray("parts") ?: continue
                val text = StringBuilder()
                val images = mutableListOf<String>()
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    if (part.has("text")) text.append(part.optString("text"))
                    part.optJSONObject("inlineData")?.optString("data")?.let { images += it }
                }
                messages += NanoGptApi.Msg(role, text.toString(), images)
            }
        }
        if (messages.isEmpty()) return parseText(execute(settings.apiKey, bodyJson))
        val jsonMode = root.optJSONObject("generationConfig")
            ?.optString("responseMimeType") == "application/json"
        val model = if (settings.analysisMode == "advanced") {
            com.nutrilens.app.ai.NANO_MODEL_ADVANCED
        } else {
            com.nutrilens.app.ai.NANO_MODEL_SIMPLE
        }
        return NanoGptApi.complete(
            settings.nanoApiKey, settings.nanoApiEndpoint, model, system, messages, jsonMode
        )
    }

    /** Достаёт текст из candidates[0].content.parts[].text. Пусто — ошибка «Пустой ответ модели». */
    private fun parseText(body: String): String {
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

    /** Срезает обёртку ```json ... ``` если модель её добавила. */
    private fun stripFence(text: String): String {
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
}