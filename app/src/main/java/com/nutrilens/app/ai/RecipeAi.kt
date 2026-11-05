package com.nutrilens.app.ai

import android.util.Base64
import com.nutrilens.app.data.Recipe
import com.nutrilens.app.data.RecipeIngredient
import com.nutrilens.app.data.RecipeNutrition
import com.nutrilens.app.data.RecipeStep
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.aiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Рецепт из ссылки, видео, фото или текста — порт web/src/ai/recipe.ts.
 * YouTube (в т.ч. Shorts) уходит в модель самим видео: Gemini — file_data,
 * NanoGPT — video_url через google/gemini-3.1-flash-lite; не принял — по описанию.
 * TikTok/Instagram/сайты — подпись, описание, разметка Recipe и текст страницы.
 */
object RecipeAi {

    data class Input(
        val kind: String,
        val url: String = "",
        val title: String = "",
        val description: String = "",
        val author: String = "",
        val thumbnail: String = "",
        val jsonLd: String = "",
        val pageText: String = "",
        /** Почему не скачался ролик — покажем, если и ссылка не сработала. */
        val downloadError: String = "",
        /** Прямая ссылка на ролик (TikTok playAddr / og:video), если страница её отдала. */
        val videoUrl: String = "",
        val note: String = "",
        val imagesJpeg: List<ByteArray> = emptyList(),
        val video: Pair<ByteArray, String>? = null
    )

    private const val NANO_RECIPE_MODEL = "google/gemini-3.1-flash-lite"
    /** Видео уходит в base64 внутри запроса: у Gemini предел запроса 20 МБ, у NanoGPT — 20 МБ на файл. */
    const val MAX_VIDEO_BYTES = 14L * 1024 * 1024
    private val CATEGORIES = listOf("breakfast", "lunch", "dinner", "snack", "dessert", "drink")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    // ---------- Ссылки ----------

    fun youtubeWatchUrl(url: String): String? = runCatching {
        val u = URI(url)
        val host = (u.host ?: "").removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val path = u.path ?: ""
        val id = when (host) {
            "youtu.be" -> path.trimStart('/').substringBefore('/')
            "youtube.com", "youtube-nocookie.com" ->
                u.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                    ?: Regex("^/(shorts|embed|live|v)/([^/?#]+)").find(path)?.groupValues?.get(2)
            else -> null
        }
        id?.takeIf { Regex("^[\\w-]{6,20}$").matches(it) }?.let { "https://www.youtube.com/watch?v=$it" }
    }.getOrNull()

    fun kindOf(url: String): String {
        if (youtubeWatchUrl(url) != null) return "youtube"
        val host = runCatching { URI(url).host ?: "" }.getOrDefault("")
        return when {
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "tiktok"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "instagram"
            else -> "web"
        }
    }

    fun findUrl(text: String): String? =
        Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd(')', '.', ',', '!', '?', '»')

    /** Подпись, описание, превью, разметка Recipe и текст страницы. Ошибки — тихо: что собрали, то и отдаём. */
    suspend fun resolve(url: String): Input = withContext(Dispatchers.IO) {
        val kind = kindOf(url)
        var out = Input(kind = kind, url = url)
        runCatching {
            val yt = youtubeWatchUrl(url)
            when {
                yt != null -> {
                    fetchJson("https://www.youtube.com/oembed?format=json&url=${enc(yt)}")?.let { o ->
                        out = out.copy(title = o.str("title"), author = o.str("author_name"), thumbnail = o.str("thumbnail_url"))
                    }
                    fetchText("$yt&hl=ru")?.let { html ->
                        Regex("\"shortDescription\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(html)?.groupValues?.get(1)?.let { raw ->
                            runCatching { json.parseToJsonElement("\"$raw\"").jsonPrimitive.content }.getOrNull()
                                ?.let { out = out.copy(description = it) }
                        }
                    }
                }
                kind == "tiktok" -> {
                    fetchJson("https://www.tiktok.com/oembed?url=${enc(url)}")?.let { o ->
                        out = out.copy(description = o.str("title"), author = o.str("author_name"), thumbnail = o.str("thumbnail_url"))
                    }
                    fetchText(url)?.let { html ->
                        Regex("\"playAddr\":\"(https:[^\"]+)\"").find(html)?.groupValues?.get(1)?.let { raw ->
                            runCatching { json.parseToJsonElement("\"$raw\"").jsonPrimitive.content }.getOrNull()
                                ?.let { out = out.copy(videoUrl = it) }
                        }
                    }
                }
                else -> fetchText(url)?.let { html -> out = parsePage(html, out) }
            }
        }
        out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun fetchJson(url: String): JsonObject? =
        fetchText(url, json = true)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun fetchText(url: String, json: Boolean = false): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "ru,en;q=0.8")
            .header("Accept", if (json) "application/json" else "text/html,application/xhtml+xml")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val source = resp.body?.source() ?: return null
            source.request(1_500_000L)
            val n = minOf(source.buffer.size, 1_500_000L)
            source.buffer.readUtf8(n)
        }
    }

    /**
     * Скачать ролик по прямой ссылке (до 14 МБ). Ссылки CDN подписаны и часто требуют
     * Referer страницы — без них отдадут 403; тогда null и разбор пойдёт по подписи.
     */
    suspend fun downloadVideo(videoUrl: String, pageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(videoUrl)
                .header("User-Agent", BROWSER_UA)
                .header("Referer", pageUrl)
                .build()
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { resp ->
                    val body = resp.body ?: return@use null
                    val type = body.contentType()?.toString().orEmpty()
                    if (!resp.isSuccessful || !(type.startsWith("video/") || type.contains("octet-stream"))) return@use null
                    if (body.contentLength() > MAX_VIDEO_BYTES) return@use null
                    val source = body.source()
                    source.request(MAX_VIDEO_BYTES + 1)
                    if (source.buffer.size > MAX_VIDEO_BYTES) null else source.buffer.readByteArray()
                }
        }.getOrNull()
    }

    private fun meta(html: String, prop: String): String {
        val tag = Regex("<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)?.value ?: return ""
        return decode(Regex("content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1).orEmpty())
    }

    private fun parsePage(html: String, base: Input): Input {
        var jsonLd = ""
        for (m in Regex("<script[^>]+application/ld\\+json[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).findAll(html)) {
            val found = runCatching { findRecipe(json.parseToJsonElement(m.groupValues[1].trim())) }.getOrNull()
            if (found != null) { jsonLd = found.toString().take(8000); break }
        }
        val text = if (jsonLd.isNotEmpty()) "" else decode(
            html.replace(Regex("<(script|style|noscript|svg|nav|footer|header)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
        ).replace(Regex("\\s+"), " ").trim().take(9000)
        return base.copy(
            title = meta(html, "og:title").ifBlank { decode(Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1).orEmpty()) },
            description = meta(html, "og:description").ifBlank { meta(html, "description") },
            thumbnail = meta(html, "og:image"),
            videoUrl = meta(html, "og:video:secure_url").ifBlank { meta(html, "og:video:url") }.ifBlank { meta(html, "og:video") },
            jsonLd = jsonLd,
            pageText = text
        )
    }

    private fun findRecipe(node: JsonElement?): JsonElement? = when (node) {
        is JsonArray -> node.firstNotNullOfOrNull { findRecipe(it) }
        is JsonObject -> {
            val t = node["@type"]
            val isRecipe = (t as? JsonPrimitive)?.contentOrNull == "Recipe" ||
                (t as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "Recipe" } == true
            if (isRecipe) JsonObject(node.filterKeys { it in setOf("name", "description", "recipeYield", "totalTime", "recipeIngredient", "recipeInstructions", "nutrition") })
            else findRecipe(node["@graph"])
        }
        else -> null
    }

    private fun decode(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isNotEmpty()) 16 else 10)
            code?.let { String(Character.toChars(it)) } ?: ""
        }

    // ---------- Промпт ----------

    private val KIND_LABEL = mapOf(
        "youtube" to "видео YouTube", "tiktok" to "видео TikTok", "instagram" to "пост Instagram", "web" to "страница сайта",
        "video" to "видео из галереи", "photo" to "фото рецепта", "text" to "текст рецепта"
    )

    private const val JSON_SHAPE = """{"is_recipe": true, "reason": "", "title": "", "description": "", "emoji": "🍝", "servings": 2, "total_minutes": 30,
"difficulty": "easy|medium|hard", "category": "breakfast|lunch|dinner|snack|dessert|drink", "tags": [""],
"ingredients": [{"name": "", "emoji": "🍅", "amount": 2, "unit": "шт", "grams": 240, "calories": 43, "group": "", "note": ""}],
"steps": [{"text": "", "minutes": 5}], "nutrition_per_serving": {"calories": 0, "protein": 0, "fat": 0, "carbs": 0}, "tips": [""], "cover_time_sec": 42}"""

    fun prompt(src: Input, hasVideo: Boolean): String = buildList {
        add("Ты — кулинарный редактор. Составь из материала ниже аккуратный рецепт для книги рецептов.")
        add("")
        add("ПРАВИЛА:")
        add("- Пиши по-русски, даже если источник на другом языке. Название — короткое и аппетитное, description — 1–2 предложения о блюде.")
        add("- Ингредиенты: метрические единицы (г, мл, шт, ст. л., ч. л., щепотка). amount — число на ВСЕ порции рецепта; «по вкусу» — amount null, unit «по вкусу».")
        add("- grams — примерный вес ингредиента на весь рецепт; calories — его ккал на весь рецепт по справочным значениям.")
        add("- nutrition_per_serving — КБЖУ одной порции: сумма calories ингредиентов / servings, и 4×белки + 9×жиры + 4×углеводы ≈ калории.")
        add("- emoji ингредиента — ОДИН эмодзи, максимально похожий на продукт: 🍅 помидор, 🧅 лук, 🧄 чеснок, 🥕 морковь, 🥔 картофель, 🫑 перец, 🌶️ острый перец, 🥒 огурец, 🥬 салат/капуста, 🌿 зелень, 🍄 грибы, 🥑 авокадо, 🍋 лимон, 🍎 яблоко, 🍌 банан, 🫐 ягоды, 🥚 яйцо, 🥛 молоко/сливки, 🧀 сыр/творог, 🧈 сливочное масло, 🫒 растительное масло, 🍗 курица, 🥩 мясо, 🥓 бекон, 🐟 рыба, 🦐 морепродукты, 🍚 рис/крупы, 🍝 паста, 🍞 хлеб, 🌾 мука, 🧂 соль/специи, 🍯 мёд, 🍫 шоколад, 🥜 орехи, 💧 вода. Нет точного — ближайший по смыслу.")
        add("- emoji рецепта — одно эмодзи готового блюда.")
        add("- group — подраздел («Тесто», «Соус», «Для подачи»), если в рецепте есть части; иначе пусто. note — уточнение («комнатной температуры»), можно пусто.")
        add("- steps — по одному действию на шаг, коротко и понятно; minutes — время шага, если оно есть или очевидно, иначе null.")
        add("- total_minutes — общее время; category — к какому приёму пищи блюдо подходит больше всего; tags — 2–4 коротких метки («быстро», «без глютена», «на ужин»).")
        add("- tips — 0–3 полезных совета из источника, без воды.")
        add("- Нет граммовок в источнике — оцени реалистично по видео или описанию. Ничего не выдумывай сверх нужного: если продукт не упомянут, не добавляй его.")
        if (hasVideo) {
            add("- Главный и единственный источник — ВИДЕО: смотри, что и в каком порядке делают, читай надписи на экране и слушай, что говорят.")
            add("- Перечисли ВСЕ ингредиенты, которые появляются в кадре или называются вслух/текстом, включая специи, подсластители, жидкости и посыпки. Не пропускай ничего, что кладут в блюдо.")
            add("- НИКОГДА не добавляй продукты, которых нет в видео, даже если они типичны для такого блюда. Название и описание ролика — только чтобы уточнить граммовки уже увиденных продуктов.")
        }
        add(
            if (hasVideo) "- cover_time_sec — секунда видео, где готовое блюдо видно лучше всего: крупно, аппетитно, без рук и текста поверх (обычно ближе к концу)."
            else "- cover_time_sec — null (видео нет)."
        )
        add("- Если в материале нет рецепта еды — is_recipe false, reason — коротко почему; остальные поля можно заполнить пустыми значениями.")
        add("")
        add("ИСТОЧНИК: ${KIND_LABEL[src.kind] ?: src.kind}${if (src.url.isNotBlank()) " — ${src.url}" else ""}")
        if (src.title.isNotBlank()) add("НАЗВАНИЕ: ${src.title}")
        if (src.author.isNotBlank()) add("АВТОР: ${src.author}")
        if (src.description.isNotBlank()) add("ОПИСАНИЕ / ПОДПИСЬ:\n${src.description.take(5000)}")
        if (src.jsonLd.isNotBlank()) add("РАЗМЕТКА РЕЦЕПТА СО СТРАНИЦЫ (schema.org):\n${src.jsonLd.take(6000)}")
        if (src.pageText.isNotBlank()) add("ТЕКСТ СТРАНИЦЫ:\n${src.pageText.take(8000)}")
        if (src.note.isNotBlank()) add("ОТ ПОЛЬЗОВАТЕЛЯ:\n${src.note.take(4000)}")
    }.filter { it.isNotEmpty() }.joinToString("\n")

    // ---------- Вызов модели ----------

    private fun schema() = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            put("is_recipe", buildJsonObject { put("type", "BOOLEAN") })
            listOf("reason", "title", "description", "emoji").forEach { put(it, buildJsonObject { put("type", "STRING") }) }
            put("servings", buildJsonObject { put("type", "NUMBER") })
            put("total_minutes", buildJsonObject { put("type", "NUMBER"); put("nullable", true) })
            put("difficulty", buildJsonObject { put("type", "STRING"); putJsonArray("enum") { add("easy"); add("medium"); add("hard") } })
            put("category", buildJsonObject { put("type", "STRING"); putJsonArray("enum") { CATEGORIES.forEach { add(it) } } })
            put("tags", buildJsonObject { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } })
            putJsonObject("ingredients") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        listOf("name", "unit", "emoji", "group", "note").forEach { put(it, buildJsonObject { put("type", "STRING") }) }
                        listOf("amount", "grams", "calories").forEach { put(it, buildJsonObject { put("type", "NUMBER"); put("nullable", true) }) }
                    }
                    putJsonArray("propertyOrdering") { listOf("name", "emoji", "amount", "unit", "grams", "calories", "group", "note").forEach { add(it) } }
                    putJsonArray("required") { add("name"); add("emoji"); add("unit") }
                }
            }
            putJsonObject("steps") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        put("text", buildJsonObject { put("type", "STRING") })
                        put("minutes", buildJsonObject { put("type", "NUMBER"); put("nullable", true) })
                    }
                    putJsonArray("required") { add("text") }
                }
            }
            putJsonObject("nutrition_per_serving") {
                put("type", "OBJECT")
                putJsonObject("properties") { listOf("calories", "protein", "fat", "carbs").forEach { put(it, buildJsonObject { put("type", "NUMBER") }) } }
                putJsonArray("required") { listOf("calories", "protein", "fat", "carbs").forEach { add(it) } }
            }
            put("tips", buildJsonObject { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } })
            put("cover_time_sec", buildJsonObject { put("type", "NUMBER"); put("nullable", true) })
        }
        putJsonArray("required") { listOf("is_recipe", "title", "servings", "ingredients", "steps", "nutrition_per_serving").forEach { add(it) } }
    }

    private suspend fun call(settings: SettingsEntity, src: Input, withVideo: Boolean): String {
        // YouTube без скачанного файла — по ссылке: Gemini открывает её сам, NanoGPT — через Gemini-маршрут.
        val yt = if (withVideo && src.kind == "youtube" && src.video == null) youtubeWatchUrl(src.url) else null
        val file = if (withVideo) src.video else null
        // Ролик не скачался на телефон — NanoGPT может скачать его по ссылке сам; Gemini по URL видео не берёт.
        val direct = if (withVideo && yt == null && file == null && settings.aiProvider != "gemini") src.videoUrl.ifBlank { null } else null
        val p = prompt(src, yt != null || file != null || direct != null)
        val images = src.imagesJpeg.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        if (settings.aiProvider == "gemini") {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject { put("text", p) }
                            yt?.let { addJsonObject { putJsonObject("file_data") { put("file_uri", it) } } }
                            file?.let { (bytes, mime) ->
                                addJsonObject { putJsonObject("inline_data") { put("mime_type", mime); put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)) } }
                            }
                            images.forEach { b64 -> addJsonObject { putJsonObject("inline_data") { put("mime_type", "image/jpeg"); put("data", b64) } } }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema())
                    // Видео — в низком разрешении кадров: для рецепта хватает, а токенов ~в 3 раза меньше.
                    if (yt != null || file != null) put("mediaResolution", "MEDIA_RESOLUTION_LOW")
                }
            }.toString()
            val raw = GeminiTransport.generate(settings.apiKey, body)
            return json.parseToJsonElement(raw).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.joinToString("") { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                ?.takeIf { it.isNotBlank() } ?: throw RuntimeException("Пустой ответ модели")
        }
        // NanoGPT принимает видео внутри запроса только до ~4,5 МБ (лимит их хостинга):
        // большие ролики кладём во временное хранилище Worker и отдаём ссылкой (до 20 МБ).
        val videos = listOfNotNull(yt, direct, file?.let { (bytes, mime) ->
            if (bytes.size <= NANO_INLINE_MAX) "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            else uploadVideo(bytes, mime)
        })
        return NanoGptApi.complete(
            settings.nanoApiKey, settings.nanoApiEndpoint, NANO_RECIPE_MODEL, null,
            listOf(NanoGptApi.Msg("user", "$p\n\nФормат ответа — JSON строго такой формы:\n$JSON_SHAPE", images, videos)),
            jsonMode = true
        )
    }

    private const val NANO_INLINE_MAX = 3 * 1024 * 1024

    /** Ролик во временное хранилище Worker (15 мин) → https-ссылка для NanoGPT. */
    private suspend fun uploadVideo(bytes: ByteArray, mime: String): String = withContext(Dispatchers.IO) {
        val api = com.nutrilens.app.leaderboard.LeaderboardSync.API
        val type = if (mime == "video/mov") "video/quicktime" else mime
        val req = Request.Builder().url("$api/video")
            .post(bytes.toRequestBody(type.toMediaType()))
            .build()
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            .newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 501) throw RuntimeException("Ролик больше 3 МБ: для NanoGPT нужно хранилище роликов в Cloudflare (KV «VIDEOS», см. инструкцию) — или переключитесь на Gemini")
                if (!resp.isSuccessful) throw RuntimeException("Не удалось передать ролик (HTTP ${resp.code})")
                json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: throw RuntimeException("Не удалось передать ролик")
            }
    }

    private fun isHard(e: Exception): Boolean {
        val m = e.message.orEmpty()
        return listOf("хранилище роликов", "ключ", "Ключ", "баланс", "Дневной лимит", "слишком много", "интернет", "таймаут", "401", "402", "403").any { it in m }
    }

    /** Рецепт по выбранному сервису. Видео не приняли — повторяем по тексту, если он есть. */
    /** Рецепт и секунда видео с лучшим кадром готового блюда (для обложки). */
    suspend fun extract(settings: SettingsEntity, src: Input): Pair<Recipe, Double?> {
        val key = if (settings.aiProvider == "gemini") settings.apiKey else settings.nanoApiKey
        if (key.isBlank()) throw RuntimeException(if (settings.aiProvider == "gemini") NO_GEMINI_KEY_ERROR else NO_NANO_KEY_ERROR)
        // Ролики разбираются только по самому видео — никаких догадок по названию и описанию.
        val isVideoSource = src.kind in setOf("youtube", "tiktok", "instagram", "video")
        if (isVideoSource && src.video == null && src.kind != "youtube") {
            throw RuntimeException("Не удалось получить видео. Сохраните ролик и загрузите его кнопкой «Видео»")
        }
        val raw = try {
            call(settings, src, isVideoSource)
        } catch (e: Exception) {
            if (src.kind == "youtube" && src.video == null && !isHard(e)) {
                throw RuntimeException(src.downloadError.ifBlank { "Не удалось открыть ролик YouTube: ${e.message?.take(100)}" })
            }
            if (isVideoSource && !isHard(e)) throw RuntimeException("ИИ не смог посмотреть видео: ${e.message?.take(120)}")
            throw e
        }
        return parse(raw, src)
    }

    // ---------- Разбор ответа ----------

    private fun JsonObject.s(k: String, max: Int = 400) = ((this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: "").trim().take(max)
    private fun JsonObject.n(k: String): Double? = (this[k] as? JsonPrimitive)?.let { p ->
        p.doubleOrNull ?: p.contentOrNull?.replace(',', '.')?.toDoubleOrNull()
    }?.takeIf { it.isFinite() }

    private val EMOJI = Regex("\\p{So}(?:\\uFE0F|\\u200D\\p{So})*")
    private fun emoji(v: String, fallback: String) = EMOJI.find(v)?.value ?: fallback

    fun parse(text: String, src: Input): Pair<Recipe, Double?> {
        val j = runCatching { json.parseToJsonElement(stripJsonFence(text)).jsonObject }.getOrNull()
            ?: throw RuntimeException("ИИ вернул непонятный ответ — попробуйте ещё раз")
        if ((j["is_recipe"] as? JsonPrimitive)?.booleanOrNull == false) {
            val why = j.s("reason", 160)
            throw RuntimeException("Не нашёл здесь рецепта" + if (why.isNotBlank()) ": $why" else "")
        }
        val ingredients = (j["ingredients"] as? JsonArray).orEmpty().mapNotNull { el ->
            val x = el as? JsonObject ?: return@mapNotNull null
            RecipeIngredient(
                name = x.s("name", 80), amount = x.n("amount"), unit = x.s("unit", 20),
                grams = x.n("grams"), calories = x.n("calories"), emoji = emoji(x.s("emoji", 16), "🥄"),
                group = x.s("group", 40), note = x.s("note", 80)
            ).takeIf { it.name.isNotBlank() }
        }
        val steps = (j["steps"] as? JsonArray).orEmpty().mapNotNull { el ->
            when (el) {
                is JsonObject -> RecipeStep(el.s("text", 600), el.n("minutes"))
                is JsonPrimitive -> RecipeStep(el.contentOrNull.orEmpty().trim().take(600))
                else -> null
            }?.takeIf { it.text.isNotBlank() }
        }
        if (ingredients.isEmpty() || steps.isEmpty()) throw RuntimeException("Не получилось собрать рецепт: нет ингредиентов или шагов")
        val servings = (j.n("servings") ?: 2.0).roundToInt().coerceIn(1, 20)
        val nut = j["nutrition_per_serving"] as? JsonObject ?: JsonObject(emptyMap())
        var calories = nut.n("calories") ?: 0.0
        val protein = nut.n("protein") ?: 0.0
        val fat = nut.n("fat") ?: 0.0
        val carbs = nut.n("carbs") ?: 0.0
        val fromItems = ingredients.sumOf { it.calories ?: 0.0 } / servings
        if (fromItems > 0 && (calories <= 0 || abs(fromItems - calories) > maxOf(60.0, calories * 0.25))) calories = fromItems
        if (calories <= 0) calories = protein * 4 + fat * 9 + carbs * 4
        val total = j.n("total_minutes")?.takeIf { it > 0 }?.roundToInt()
            ?: steps.sumOf { it.minutes ?: 0.0 }.roundToInt().takeIf { it > 0 }
        val coverTime = j.n("cover_time_sec")?.takeIf { it >= 0 }
        return Recipe(
            id = UUID.randomUUID().toString(),
            title = j.s("title", 90).ifBlank { "Рецепт" },
            description = j.s("description", 300),
            emoji = emoji(j.s("emoji", 16), "🍽️"),
            cover = src.thumbnail.ifBlank { null },
            servings = servings,
            totalMinutes = total,
            difficulty = j.s("difficulty").takeIf { it in setOf("easy", "medium", "hard") } ?: "medium",
            category = j.s("category").takeIf { it in CATEGORIES } ?: "lunch",
            tags = (j["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(24) }.filter { it.isNotBlank() }.take(4),
            ingredients = ingredients,
            steps = steps,
            nutrition = RecipeNutrition(calories.roundToInt().toDouble(), protein.roundToInt().toDouble(), fat.roundToInt().toDouble(), carbs.roundToInt().toDouble()),
            tips = (j["tips"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(300) }.filter { it.isNotBlank() }.take(3),
            source = com.nutrilens.app.data.RecipeSource(kind = src.kind, url = src.url, author = src.author)
        ) to coverTime
    }
}
