package com.nutrilens.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.ai.ImagePrep
import com.nutrilens.app.ai.chatWithCascade
import com.nutrilens.app.data.ChatMessage
import com.nutrilens.app.data.ChatStore
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.random.Random

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NutriLensDatabase.getInstance(application)
    private val mealRepository = MealRepository(
        database.mealDao(),
        database.waterDao(),
        database.weightDao(),
        database.workoutDao()
    )
    private val settingsRepository = SettingsRepository(database.settingsDao())

    private val today = LocalDate.now().toString()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            _messages.value = ChatStore.load(getApplication())
        }
    }

    fun send(text: String, imagePaths: List<String>) {
        if (text.isBlank() && imagePaths.isEmpty()) return
        if (_loading.value) return
        viewModelScope.launch {
            val settings = settingsRepository.get()
            if (settings.apiKey.isBlank() && settings.nanoApiKey.isBlank()) {
                _error.value = "Сначала добавьте ключ Gemini или NanoGPT в настройках"
                return@launch
            }
            val userMessage = ChatMessage(role = "user", text = text, imagePaths = imagePaths)
            _messages.value = _messages.value + userMessage
            ChatStore.append(getApplication(), userMessage)
            _loading.value = true
            _error.value = null
            try {
                val mealsToday = mealRepository.mealsOn(today)
                val system = GeminiTools.buildChatSystemInstruction(
                    dailyGoal = settings.dailyGoal,
                    userContext = settings.userContext,
                    todayMeals = mealsToday.map { it.name to it.calories.roundToInt() },
                    todayTotal = mealsToday.sumOf { it.calories }.roundToInt()
                )
                val history = _messages.value.map { msg ->
                    GeminiTools.ChatTurn(
                        role = msg.role,
                        text = msg.text,
                        imagesBase64 = msg.imagePaths.mapNotNull { path ->
                            runCatching {
                                Base64.encodeToString(
                                    ImagePrep.readBytes(File(path)),
                                    Base64.NO_WRAP
                                )
                            }.getOrNull()
                        }
                    )
                }
                val reply = chatWithCascade(settings, system, history)
                val modelMessage = ChatMessage(role = "model", text = reply)
                _messages.value = _messages.value + modelMessage
                ChatStore.append(getApplication(), modelMessage)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось получить ответ"
            }
            _loading.value = false
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            ChatStore.clear(getApplication())
            _messages.value = ChatStore.load(getApplication())
        }
    }
}

private fun Context.copyToChatImage(uri: Uri): String? = runCatching {
    val dir = File(filesDir, "chat_images").apply { mkdirs() }
    val file = File(dir, "chat_${System.currentTimeMillis()}_${Random.nextInt(100_000)}.jpg")
    contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    }
    file.absolutePath
}.getOrNull()

@Composable
fun ChatScreen(onBack: () -> Unit, viewModel: ChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "💬 Диетолог",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "ИИ-консультант, знает ваш дневник",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = viewModel::clearChat) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Очистить чат",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
        ) {
            if (loading) {
                item {
                    FreshCard {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .padding(0.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            items(messages.reversed()) { msg ->
                ChatBubble(msg)
            }
        }

        error?.let { err ->
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { path ->
                    Box {
                        AsyncImage(
                            model = File(path),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        IconButton(
                            onClick = { attachments = attachments - path },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Убрать",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        ChatInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            loading = loading,
            onAttach = { paths -> attachments = attachments + paths },
            onSend = {
                viewModel.send(inputText.trim(), attachments)
                inputText = ""
                attachments = emptyList()
            }
        )
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                if (msg.imagePaths.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        msg.imagePaths.take(3).forEach { path ->
                            AsyncImage(
                                model = File(path),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        }
                    }
                    if (msg.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (msg.text.isNotBlank()) {
                    if (isUser) {
                        Text(
                            msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        MarkdownText(msg.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    loading: Boolean,
    onAttach: (List<String>) -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = context.copyToChatImage(uri)
            if (path != null) onAttach(listOf(path))
        }
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        IconButton(onClick = {
            pickLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }) {
            Icon(
                Icons.Default.AddPhotoAlternate,
                contentDescription = "Прикрепить фото",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Спросите о питании…") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4
        )
        Surface(
            shape = CircleShape,
            color = if (loading) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    IconButton(onClick = onSend, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Отправить",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
