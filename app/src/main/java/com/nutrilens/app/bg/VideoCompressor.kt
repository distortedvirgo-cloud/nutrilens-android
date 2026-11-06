package com.nutrilens.app.bg

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.nutrilens.app.ai.RecipeAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Сжатие ролика под лимит запроса к ИИ: короткая сторона 480 px, битрейт под длительность
 * (чтобы файл вышел ~11 МБ), звук сохраняется — ИИ слушает, что говорят про ингредиенты.
 * Кодирует аппаратный кодек телефона, минута видео сжимается за несколько секунд.
 */
object VideoCompressor {
    private const val TARGET_BYTES = 11L * 1024 * 1024
    private const val AUDIO_BPS = 128_000L

    /** Байты сжатого mp4. Ошибка — если даже после сжатия ролик не влезает в лимит. */
    suspend fun compress(context: Context, src: Uri): ByteArray {
        val app = context.applicationContext
        val (w, h, durationMs) = withContext(Dispatchers.IO) { probe(app, src) }
        val seconds = (durationMs / 1000.0).coerceAtLeast(1.0)
        val videoBps = ((TARGET_BYTES * 8 / seconds) - AUDIO_BPS).toLong().coerceIn(200_000L, 1_200_000L).toInt()
        // Короткая сторона — 480 px (размеры чётные: так требуют кодеки).
        val scale = if (w > 0 && h > 0) minOf(1.0, 480.0 / minOf(w, h)) else 1.0
        val outW = if (w > 0) ((w * scale).toInt() / 2) * 2 else 480
        val outH = if (h > 0) ((h * scale).toInt() / 2) * 2 else 854
        val out = File(app.cacheDir, "compressed_${UUID.randomUUID()}.mp4")
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val transformer = Transformer.Builder(app)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(app)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBps).build())
                                .build()
                        )
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(RuntimeException("Не удалось сжать видео: ${exportException.message}"))
                            }
                        })
                        .build()
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(src))
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))))
                        .build()
                    transformer.start(item, out.absolutePath)
                    cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                }
            }
            return withContext(Dispatchers.IO) {
                if (out.length() > RecipeAi.MAX_VIDEO_BYTES) {
                    throw RuntimeException("Ролик слишком длинный для разбора даже после сжатия (${durationMs / 60000} мин). Обрежьте его до 5–7 минут")
                }
                out.readBytes()
            }
        } finally {
            out.delete()
        }
    }

    /** Ширина и высота в том виде, как ролик показывается (с учётом поворота), и длительность. */
    private fun probe(context: Context, src: Uri): Triple<Int, Int, Long> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, src)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (rot == 90 || rot == 270) Triple(h, w, d) else Triple(w, h, d)
        } catch (e: Exception) {
            Triple(0, 0, 0L)
        } finally {
            r.release()
        }
    }
}
