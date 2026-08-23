package com.nutrilens.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GroceryData(
    val plan: String,
    val categories: List<GroceryCategoryData>,
    val checked: List<String> = emptyList()
)

@Serializable
data class GroceryCategoryData(
    val category: String,
    val items: List<String>
)

/**
 * Хранит список покупок в файле grocery.json в filesDir.
 */
object GroceryStore {
    const val FILE_NAME = "grocery.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    suspend fun load(context: Context): GroceryData? = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString<GroceryData>(f.readText()) }.getOrNull()
    }

    suspend fun save(context: Context, data: GroceryData) = withContext(Dispatchers.IO) {
        file(context).writeText(json.encodeToString(data))
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        file(context).delete()
    }

    suspend fun toggleItem(context: Context, item: String): GroceryData = withContext(Dispatchers.IO) {
        val current = load(context) ?: return@withContext GroceryData(plan = "", categories = emptyList())
        val newChecked = if (item in current.checked) current.checked - item else current.checked + item
        val updated = current.copy(checked = newChecked)
        save(context, updated)
        updated
    }
}