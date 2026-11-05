package com.nutrilens.app.bg

import android.content.Context
import com.nutrilens.app.ai.RecipeAi
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Скачивание ролика по ссылке (YouTube/Shorts, TikTok, Instagram) через yt-dlp прямо
 * на телефоне — чтобы ИИ смотрел само видео, а не гадал по описанию. Берём один файл
 * со звуком ≤480p: для рецепта хватает, а запрос укладывается в лимит 14 МБ.
 * Сайты часто меняются — при сбое yt-dlp обновляется (не чаще раза в сутки) и пробует снова.
 */
object VideoDownloader {
    private val lock = Mutex()
    @Volatile private var ready = false
    private const val PREFS = "video_downloader"
    private const val KEY_UPDATED = "updated_at"

    private suspend fun init(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context.applicationContext)
        ready = true
    }

    /** Байты ролика (mp4) или исключение с понятной причиной. */
    suspend fun download(context: Context, url: String): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock {
            init(context)
            val youtube = RecipeAi.kindOf(url) == "youtube"
            try {
                attempts(context, url, youtube)
            } catch (first: Exception) {
                if (!updateIfDue(context)) throw readable(first)
                try { attempts(context, url, youtube) } catch (e: Exception) { throw readable(e) }
            }
        }
    }

    /**
     * YouTube часто не отдаёт обычный клиент без JS-проверок — пробуем клиенты, которым
     * они не нужны (android_vr, tv); если нет файла со звуком — берём видео без звука:
     * ингредиенты и надписи ИИ всё равно увидит.
     */
    private fun attempts(context: Context, url: String, youtube: Boolean): ByteArray {
        if (!youtube) return fetch(context, url, null, FORMAT)
        var last: Exception? = null
        // Проверка «not a bot» на адресах VPN включается не для всех клиентов YouTube.
        for ((client, format) in listOf(
            null to FORMAT, "android_vr,tv" to FORMAT, "tv_simply,web_embedded" to FORMAT,
            "mweb,ios" to FORMAT, "android_vr,tv,web_safari,tv_simply,web_embedded,mweb" to FORMAT_ANY
        )) {
            try { return fetch(context, url, client, format) } catch (e: Exception) { last = e }
        }
        // Открытые зеркала (Invidious/Piped) отдают ролик со своих серверов — мимо проверки «not a bot».
        val id = RecipeAi.youtubeWatchUrl(url)?.substringAfter("v=")
        if (id != null) mirrorDownload(id)?.let { return it }
        throw last ?: RuntimeException("Не удалось скачать видео")
    }

    private val INVIDIOUS = listOf("https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://yewtu.be", "https://invidious.privacyredirect.com")
    private val PIPED = listOf("https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de", "https://api.piped.private.coffee")
    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun str(o: kotlinx.serialization.json.JsonObject, k: String): String? =
        (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content

    /** Ролик через зеркало: файл со звуком, от меньшего разрешения к большему, до лимита размера. */
    private fun mirrorDownload(id: String): ByteArray? {
        val candidates = mutableListOf<String>()
        for (base in INVIDIOUS) runCatching {
            val j = getJson("$base/api/v1/videos/$id?local=true") ?: return@runCatching
            (j["formatStreams"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                .sortedBy { str(it, "resolution")?.filter(Char::isDigit)?.toIntOrNull() ?: 9999 }
                .mapNotNull { str(it, "url") }
                .forEach { u -> candidates += if (u.startsWith("/")) base + u else u }
        }
        for (base in PIPED) runCatching {
            val j = getJson("$base/streams/$id") ?: return@runCatching
            (j["videoStreams"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                .filter { str(it, "videoOnly") == "false" }
                .mapNotNull { str(it, "url") }
                .forEach { candidates += it }
        }
        for (u in candidates) {
            val bytes = runCatching {
                http.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { resp ->
                    val body = resp.body
                    if (body == null || !resp.isSuccessful || body.contentLength() > RecipeAi.MAX_VIDEO_BYTES) null
                    else {
                        val src = body.source()
                        src.request(RecipeAi.MAX_VIDEO_BYTES + 1)
                        if (src.buffer.size > RecipeAi.MAX_VIDEO_BYTES || src.buffer.size < 50_000) null else src.buffer.readByteArray()
                    }
                }
            }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    private fun getJson(url: String): kotlinx.serialization.json.JsonObject? =
        http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { resp ->
            if (!resp.isSuccessful) null
            else kotlinx.serialization.json.Json.parseToJsonElement(resp.body?.string().orEmpty()) as? kotlinx.serialization.json.JsonObject
        }

    private const val FORMAT = "best[height<=480][ext=mp4]/best[height<=480]/best[height<=720][ext=mp4]/best[height<=720]/best"
    /** Последняя попытка: допускаем видео без звука. */
    private const val FORMAT_ANY = "$FORMAT/bv*[height<=480][ext=mp4]/bv*[height<=480]/bv*[height<=720]"

    private fun fetch(context: Context, url: String, client: String?, format: String): ByteArray {
        val dir = File(context.cacheDir, "recipe_dl").apply { deleteRecursively(); mkdirs() }
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", format)
            if (client != null) addOption("--extractor-args", "youtube:player_client=$client")
            addOption("--no-playlist")
            addOption("--no-part")
            addOption("--max-filesize", "${RecipeAi.MAX_VIDEO_BYTES / (1024 * 1024)}M")
            addOption("-o", File(dir, "video.%(ext)s").absolutePath)
        }
        YoutubeDL.getInstance().execute(request)
        val file = dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.maxByOrNull { it.length() }
            ?: throw RuntimeException("Ролик слишком большой (больше 14 МБ) или недоступен")
        if (file.length() > RecipeAi.MAX_VIDEO_BYTES) throw RuntimeException("Ролик слишком большой (больше 14 МБ)")
        return file.readBytes().also { dir.deleteRecursively() }
    }

    /** Обновить yt-dlp, если не обновляли больше суток. true — обновили, есть смысл повторить. */
    private fun updateIfDue(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_UPDATED, 0L) < 24 * 60 * 60 * 1000L) return false
        prefs.edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply()
        return runCatching { YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext) }.isSuccess
    }

    private fun readable(e: Exception): RuntimeException {
        val m = e.message.orEmpty()
        return RuntimeException(
            when {
                m.contains("login", true) || m.contains("cookies", true) || m.contains("rate-limit", true) ->
                    "Instagram/TikTok не отдал ролик без входа в аккаунт. Сохраните видео и загрузите его кнопкой «Видео»"
                m.contains("Private", true) || m.contains("unavailable", true) -> "Ролик недоступен (закрыт или удалён)"
                m.contains("14 МБ") -> m
                else -> "Не удалось скачать видео (${m.lineSequence().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()?.take(120) ?: m.take(120)}). Сохраните ролик и загрузите его кнопкой «Видео»"
            }
        )
    }
}
