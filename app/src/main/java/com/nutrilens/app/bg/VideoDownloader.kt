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
            try {
                fetch(context, url)
            } catch (first: Exception) {
                if (!updateIfDue(context)) throw readable(first)
                try { fetch(context, url) } catch (e: Exception) { throw readable(e) }
            }
        }
    }

    private fun fetch(context: Context, url: String): ByteArray {
        val dir = File(context.cacheDir, "recipe_dl").apply { deleteRecursively(); mkdirs() }
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", "best[height<=480][ext=mp4]/best[height<=480]/best[height<=720][ext=mp4]/best[height<=720]/best")
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
                else -> "Не удалось скачать видео. Сохраните ролик и загрузите его кнопкой «Видео»"
            }
        )
    }
}
