package com.nutrilens.app.data

import android.content.Context
import android.net.Uri
import com.nutrilens.app.ai.ImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Фото прогресса тела (3.25, по желанию). Хранятся файлами только на этом устройстве:
 * не в базе — поэтому в резервную копию и лидерборд не попадают. Каждое фото — папка
 * body_photos/<дата>_<uuid> с full/thumb JPEG и, если просили, оценкой ИИ estimate.json.
 */
object BodyPhotos {

    @Serializable
    data class Estimate(val low: Double, val high: Double, val comment: String = "", val at: Long = 0L)

    data class Photo(val id: String, val date: LocalDate, val full: File, val estimate: Estimate?)

    private val json = Json { ignoreUnknownKeys = true }

    private fun root(context: Context) = File(context.filesDir, "body_photos")

    /** Все фото по дате, старые первыми. */
    suspend fun list(context: Context): List<Photo> = withContext(Dispatchers.IO) {
        root(context).listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { dir ->
            val date = runCatching { LocalDate.parse(dir.name.substringBefore('_')) }.getOrNull() ?: return@mapNotNull null
            val full = dir.listFiles().orEmpty().firstOrNull { it.name.startsWith("full_") } ?: return@mapNotNull null
            val est = File(dir, "estimate.json").takeIf { it.exists() }
                ?.let { runCatching { json.decodeFromString(Estimate.serializer(), it.readText()) }.getOrNull() }
            Photo(dir.name, date, full, est)
        }
    }

    suspend fun add(context: Context, uri: Uri, date: LocalDate = LocalDate.now()): Unit = withContext(Dispatchers.IO) {
        val dir = File(root(context), "${date}_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ImagePrep.process(context, uri, dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    suspend fun delete(context: Context, id: String): Unit = withContext(Dispatchers.IO) {
        File(root(context), id).takeIf { it.parentFile == root(context) }?.deleteRecursively()
    }

    suspend fun saveEstimate(context: Context, id: String, e: Estimate): Unit = withContext(Dispatchers.IO) {
        val dir = File(root(context), id)
        if (dir.isDirectory) File(dir, "estimate.json").writeText(json.encodeToString(Estimate.serializer(), e))
    }
}
