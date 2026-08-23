package com.nutrilens.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Подготовка фото еды к анализу. Порт prepareImage из веб-версии
 * (src/utils/image.ts): сохраняет EXIF-ориентацию, подготавливает полноразмерное
 * фото (макс. сторона 1536) и миниатюру (макс. сторона 256) за один decode
 * исходника (размер полного битмапа ограничен через inSampleSize).
 */
object ImagePrep {

    data class ProcessedImages(val full: File, val thumb: File)

    private const val MAX_DIMENSION = 1536
    private const val THUMB_MAX_DIMENSION = 256

    fun process(context: Context, uri: Uri, destDir: File): ProcessedImages {
        val timestamp = System.currentTimeMillis()

        val orientation = readExifOrientation(context, uri)

        val decoded = decodeBitmap(context, uri)
            ?: throw IllegalArgumentException("Не удалось декодировать изображение: $uri")

        // Применяем EXIF-поворот. Основные ориентации: 6/3/8 → 90°/180°/270°.
        // Зеркальные/комбинированные ориентации (2/4/5/7) здесь не обрабатываются —
        // для анализа еды достаточно трёх основных поворотов.
        var bitmap = applyExif(decoded, orientation)
        if (bitmap !== decoded) {
            decoded.recycle()
        }

        val full = scaleToFit(bitmap, MAX_DIMENSION)
        val thumb = scaleToFit(bitmap, THUMB_MAX_DIMENSION)

        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        val fullFile = File(destDir, "full_${timestamp}.jpg")
        val thumbFile = File(destDir, "thumb_${timestamp}.jpg")

        writeJpeg(full, fullFile, 85)
        writeJpeg(thumb, thumbFile, 60)

        // Освобождаем память от всех битмапов (distinct — без повторного recycle).
        listOf(decoded, bitmap, full, thumb)
            .distinct()
            .filter { !it.isRecycled }
            .forEach { it.recycle() }

        return ProcessedImages(fullFile, thumbFile)
    }

    fun readBytes(file: File): ByteArray = file.readBytes()

    /** Читает EXIF-ориентацию из потока (конструктор ExifInterface от InputStream). */
    private fun readExifOrientation(context: Context, uri: Uri): Int {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            return exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: throw IllegalArgumentException("Не удалось открыть файл: $uri")
    }

    /**
     * Двухпроходный decode: сначала только размеры (inJustDecodeBounds) для
     * расчёта inSampleSize, затем полный decode — чтобы декодированный размер
     * был не сильно больше MAX_DIMENSION (1536).
     */
    private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
        // 1-й проход: только размеры.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IllegalArgumentException("Не удалось открыть файл: $uri")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Не удалось прочитать изображение (файл не является картинкой): $uri")
        }

        // 2-й проход: полный decode.
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalArgumentException("Не удалось открыть файл: $uri")
    }

    /** Степень двойки, при которой декодированный размер держится около maxDimension. */
    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        val maxSide = maxOf(width, height)
        while (maxSide / (inSampleSize * 2) >= maxDimension) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /** Вписывает размеры в квадрат max×max с сохранением пропорций (как fitInside в image.ts). */
    private fun fitInside(width: Int, height: Int, max: Int): Pair<Int, Int> {
        var w = width
        var h = height
        if (w > max) {
            h = Math.round(h * max.toDouble() / w).toInt()
            w = max
        }
        if (h > max) {
            w = Math.round(w * max.toDouble() / h).toInt()
            h = max
        }
        return w to h
    }

    /**
     * Масштабирует так, чтобы максимальная сторона была <= max (пропорционально).
     * Если уже меньше — не увеличивает (возвращает исходный битмап).
     */
    private fun scaleToFit(bitmap: Bitmap, max: Int): Bitmap {
        val (w, h) = fitInside(bitmap.width, bitmap.height, max)
        if (w == bitmap.width && h == bitmap.height) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** Поворот по EXIF: ориентации 6/3/8 → 90°/180°/270°. */
    private fun applyExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }
}