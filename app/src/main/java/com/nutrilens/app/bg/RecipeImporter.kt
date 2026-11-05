package com.nutrilens.app.bg

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.nutrilens.app.ai.ImagePrep
import com.nutrilens.app.ai.RecipeAi
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.RecipeRepository
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Импорт рецептов: разбор идёт в фоне процесса (10–60 с), экран можно закрыть —
 * по готовности придёт уведомление «Рецепт готов». Порт web/src/data/recipes.ts.
 */
object RecipeImporter {

    data class Job(val id: String, val label: String, val error: String? = null, internal val retry: () -> Unit = {})

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    /** Последний готовый рецепт — экран книги открывает его, если пользователь ждёт. */
    private val _finished = MutableStateFlow<String?>(null)
    val finished: StateFlow<String?> = _finished.asStateFlow()
    fun consumeFinished() { _finished.value = null }

    private val LABEL = mapOf(
        "youtube" to "видео YouTube", "tiktok" to "видео TikTok", "instagram" to "пост Instagram", "web" to "страница с рецептом",
        "video" to "видео из галереи", "photo" to "фото рецепта", "text" to "текст рецепта"
    )

    fun dismiss(id: String) = _jobs.update { list -> list.filterNot { it.id == id } }

    /** Что подготовили к разбору: вход для ИИ, запасная обложка и источник кадра (видео на устройстве). */
    private data class Prepared(val input: RecipeAi.Input, val cover: String? = null, val frameFrom: Any? = null)

    private fun run(context: Context, kind: String, prepare: suspend () -> Prepared) {
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        val label = LABEL[kind] ?: kind
        val job = Job(id, label, retry = { dismiss(id); run(app, kind, prepare) })
        _jobs.update { listOf(job) + it }
        scope.launch {
            try {
                val db = NutriLensDatabase.getInstance(app)
                val settings = SettingsRepository(db.settingsDao()).get()
                val prepared = prepare()
                val (draft, coverTime) = RecipeAi.extract(settings, prepared.input)
                // Обложка — кадр с готовым блюдом, который назвал ИИ; иначе запасная или превью ссылки.
                val best = prepared.frameFrom?.let { src -> runCatching { videoFrame(app, src, coverTime) }.getOrNull() }
                val recipe = draft.copy(cover = best ?: prepared.cover ?: draft.cover)
                (prepared.frameFrom as? File)?.delete()
                RecipeRepository(db).put(recipe)
                dismiss(id)
                _finished.value = recipe.id
                NotificationHelper.ensureChannels(app)
                NotificationHelper.post(
                    app, NotificationHelper.CHANNEL_ANALYSIS, id.hashCode(),
                    "Рецепт готов: ${recipe.title} ${recipe.emoji}",
                    "${recipe.ingredients.size} ингредиентов · ${recipe.nutrition.calories.toInt()} ккал на порцию",
                    NotificationHelper.mainActivityPendingIntent(app, id.hashCode(), navigate = "recipe/${recipe.id}")
                )
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                _jobs.update { list -> list.map { if (it.id == id) it.copy(error = msg) else it } }
            }
        }
    }

    fun fromLink(context: Context, url: String, note: String = "") = run(context, RecipeAi.kindOf(url)) {
        val resolved = RecipeAi.resolve(url).copy(note = note, videoUrl = "")
        if (resolved.kind == "web") return@run Prepared(resolved) // сайт с рецептом — текст и разметка Recipe
        // Ролик (YouTube/TikTok/Instagram) скачиваем на телефон: ИИ смотрит само видео, без догадок по описанию.
        var downloadError = ""
        val bytes = try {
            VideoDownloader.download(context, url)
        } catch (e: Exception) {
            // YouTube не скачался — модель посмотрит ролик по ссылке (Gemini — сам Google, NanoGPT — Gemini-маршрут).
            if (resolved.kind == "youtube") { downloadError = e.message.orEmpty(); null } else throw e
        }
        if (bytes != null) {
            val tmp = File(context.cacheDir, "recipe_${UUID.randomUUID()}.mp4").apply { writeBytes(bytes) }
            Prepared(resolved.copy(video = bytes to "video/mp4"), frameFrom = tmp)
        } else Prepared(resolved.copy(downloadError = downloadError))
    }

    fun fromText(context: Context, text: String) =
        run(context, "text") { Prepared(RecipeAi.Input(kind = "text", note = text)) }

    fun fromPhotos(context: Context, uris: List<Uri>) = run(context, "photo") {
        val dir = File(context.filesDir, "recipes/${UUID.randomUUID()}")
        val files = uris.take(4).mapIndexed { i, uri -> ImagePrep.process(context, uri, File(dir, "$i")).full }
        Prepared(RecipeAi.Input(kind = "photo", imagesJpeg = files.map { it.readBytes() }), cover = files.firstOrNull()?.let { "file:${it.absolutePath}" })
    }

    /** Размер видео в байтах — проверка лимита до запуска разбора. */
    fun videoSize(context: Context, uri: Uri): Long =
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L

    fun fromVideo(context: Context, uri: Uri) = run(context, "video") {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw RuntimeException("Не удалось открыть видео")
        if (bytes.size > RecipeAi.MAX_VIDEO_BYTES) throw RuntimeException("Видео больше 14 МБ — обрежьте ролик или пришлите ссылку")
        val type = context.contentResolver.getType(uri) ?: "video/mp4"
        // Gemini знает .mov как video/mov; NanoGPT принимает любой видеоформат.
        val mime = if (type == "video/quicktime") "video/mov" else type
        val cover = runCatching { videoFrame(context, uri, null) }.getOrNull()
        Prepared(RecipeAi.Input(kind = "video", video = bytes to mime), cover = cover, frameFrom = uri)
    }

    /** Кадр ролика на секунде at (по умолчанию ~1,2 с) — обложка рецепта. src — Uri или File. */
    private fun videoFrame(context: Context, src: Any, at: Double?): String? {
        val r = MediaMetadataRetriever()
        try {
            when (src) {
                is Uri -> r.setDataSource(context, src)
                is File -> r.setDataSource(src.absolutePath)
                else -> return null
            }
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = if (at != null) (at * 1_000_000).toLong().coerceIn(0L, maxOf(0L, durationMs * 1000 - 100_000)) else 1_200_000L
            val frame = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val dir = File(context.filesDir, "recipes").apply { mkdirs() }
            val file = File(dir, "cover_${UUID.randomUUID()}.jpg")
            val scaled = if (frame.width > 1280) Bitmap.createScaledBitmap(frame, 1280, frame.height * 1280 / frame.width, true) else frame
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            return "file:${file.absolutePath}"
        } finally {
            r.release()
        }
    }
}
