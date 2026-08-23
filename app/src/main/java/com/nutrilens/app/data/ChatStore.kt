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
data class ChatMessage(
    val role: String,
    val text: String,
    val imagePaths: List<String> = emptyList()
)

/**
 * Хранит историю переписки с ИИ-диетологом в файле chat_history.json в filesDir.
 */
object ChatStore {
    const val FILE_NAME = "chat_history.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    suspend fun load(context: Context): List<ChatMessage> = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists()) {
            listOf(ChatMessage(role = "model", text = "Привет! Я твой ИИ-диетолог. Чем могу помочь сегодня?"))
        } else {
            runCatching { json.decodeFromString<List<ChatMessage>>(f.readText()) }
                .getOrElse {
                    listOf(ChatMessage(role = "model", text = "Привет! Я твой ИИ-диетолог. Чем могу помочь сегодня?"))
                }
        }
    }

    suspend fun save(context: Context, list: List<ChatMessage>) = withContext(Dispatchers.IO) {
        file(context).writeText(json.encodeToString(list))
    }

    suspend fun append(context: Context, msg: ChatMessage) = withContext(Dispatchers.IO) {
        save(context, load(context) + msg)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        file(context).delete()
    }
}