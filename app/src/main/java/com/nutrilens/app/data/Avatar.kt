package com.nutrilens.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.nutrilens.app.ai.ImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Аватарка пользователя: квадрат 256 px в filesDir/avatar.jpg. В лидерборд уходит
 * копия 128 px data-URL (~6 КБ) — её видят все участники, в т.ч. в веб-версии.
 */
object Avatar {
    private const val FILE = "avatar.jpg"

    /** Меняется при каждой смене фото — для обновления картинок на экранах. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun file(context: Context): File? = File(context.filesDir, FILE).takeIf { it.exists() && it.length() > 0 }

    suspend fun save(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        // ImagePrep поворачивает по EXIF; дальше — квадрат по центру.
        val tmp = File(context.cacheDir, "avatar_src").apply { mkdirs() }
        val prepared = ImagePrep.process(context, uri, tmp)
        val src = BitmapFactory.decodeFile(prepared.full.absolutePath) ?: throw RuntimeException("Не удалось открыть фото")
        val out = square(src, 256)
        File(context.filesDir, FILE).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        tmp.deleteRecursively()
        _version.value = System.currentTimeMillis()
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        _version.value = System.currentTimeMillis()
    }

    /** Маленькая копия для лидерборда: "data:image/jpeg;base64,…" или пусто. */
    fun smallDataUrl(context: Context): String {
        val f = file(context) ?: return ""
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return ""
        val small = Bitmap.createScaledBitmap(bmp, 128, 128, true)
        val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Байты картинки из data-URL участника лидерборда (null — нет или битая). */
    fun decodeDataUrl(url: String): ByteArray? =
        url.substringAfter("base64,", "").takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }

    private fun square(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }
}
