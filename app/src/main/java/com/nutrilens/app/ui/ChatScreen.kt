package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriTheme
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
                android.util.Log.d(
                    "NutriChat",
                    "send: history=${history.size} roles=${history.joinToString { it.role }}"
                )
                val reply = chatWithCascade(settings, system, history)
                android.util.Log.d("NutriChat", "reply len=${reply.length}")
                val modelMessage = ChatMessage(role = "model", text = reply)
                _messages.value = _messages.value + modelMessage
                ChatStore.append(getApplication(), modelMessage)
            } catch (e: Exception) {
                android.util.Log.e("NutriChat", "chat failed", e)
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
fun ChatScreen(
    onBack: () -> Unit,
    initialMessage: String? = null,
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<String>>(emptyList()) }

    // Вопрос, заданный с экрана «Коуч», отправляется сразу (один раз).
    var initialSent by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(initialMessage) {
        val q = initialMessage?.trim().orEmpty()
        if (!initialSent && q.isNotEmpty()) {
            initialSent = true
            viewModel.send(q, emptyList())
        }
    }

    // Отступ под клавиатуру даёт NavHost (imePadding на корне) — здесь не дублируем.
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
        ) {
            BackPill(onClick = onBack)
            Spacer(Modifier.width(14.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(NutriTheme.colors.brandGradient)
            ) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = NutriTheme.colors.onBrand,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Диетолог",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    if (loading) "печатает…" else "ИИ-консультант, знает ваш дневник",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loading) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = viewModel::clearChat) {
                Icon(
                    Icons.Outlined.DeleteOutline,
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
                .padding(horizontal = Space.Screen),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            if (loading) {
                item(key = "typing") { TypingBubble(Modifier.animateItem()) }
            }
            val reversed = messages.asReversed()
            items(reversed.size, key = { messages.size - 1 - it }) { i ->
                ChatBubble(reversed[i], Modifier.animateItem())
            }
            if (messages.isEmpty() && !loading) {
                item(key = "empty") { ChatEmptyHint() }
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
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { path ->
                    Box {
                        AsyncImage(
                            model = File(path),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(Radius.Small))
                        )
                        IconButton(
                            onClick = { attachments = attachments - path },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Убрать",
                                tint = Color.White,
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
            canSend = inputText.isNotBlank() || attachments.isNotEmpty(),
            onAttach = { paths -> attachments = attachments + paths },
            onSend = {
                viewModel.send(inputText.trim(), attachments)
                inputText = ""
                attachments = emptyList()
            }
        )
    }
}

/** Пустой чат: короткая подсказка, о чём можно спросить. */
@Composable
private fun ChatEmptyHint() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp)
    ) {
        IconBadge(Icons.Rounded.AutoAwesome, MaterialTheme.colorScheme.primary, size = 64)
        Spacer(Modifier.height(16.dp))
        Text(
            "Спросите что угодно о питании",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Например: «чем добрать белок сегодня?» или пришлите фото блюда",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** «Печатает…»: три точки по очереди подпрыгивают. */
@Composable
private fun TypingBubble(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, NutriTheme.colors.hairline)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                repeat(3) { i ->
                    val y by t.animateFloat(
                        initialValue = 0f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            keyframes {
                                durationMillis = 1100
                                0f at (i * 140)
                                -5f at (i * 140 + 220)
                                0f at (i * 140 + 440)
                            }
                        ),
                        label = "dot$i"
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .graphicsLayer { translationY = y.dp.toPx() }
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, modifier: Modifier = Modifier) {
    val isUser = msg.role == "user"
    val c = NutriTheme.colors
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = if (isUser) 22.dp else 8.dp,
        bottomEnd = if (isUser) 8.dp else 22.dp
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = shape,
            color = if (isUser) Color.Transparent else MaterialTheme.colorScheme.surface,
            border = if (isUser) null else BorderStroke(1.dp, c.hairline),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                Modifier
                    .then(if (isUser) Modifier.background(c.brandGradient) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                if (msg.imagePaths.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        msg.imagePaths.take(3).forEach { path ->
                            AsyncImage(
                                model = File(path),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        }
                    }
                    if (msg.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (msg.text.isNotBlank()) {
                    if (isUser) {
                        Text(
                            msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.onBrand
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
    canSend: Boolean,
    onAttach: (List<String>) -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val c = NutriTheme.colors
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = context.copyToChatImage(uri)
            if (path != null) onAttach(listOf(path))
        }
    }
    val barShape = RoundedCornerShape(28.dp)
    Surface(
        shape = barShape,
        color = c.glass,
        border = BorderStroke(1.dp, c.glassBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .softShadow(barShape, 12)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(6.dp)
        ) {
            IconButton(onClick = {
                pickLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) {
                Icon(
                    Icons.Rounded.AddPhotoAlternate,
                    contentDescription = "Прикрепить фото",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Спросите о питании…") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                maxLines = 4
            )
            val enabled = canSend && !loading
            val sendInteraction = remember { MutableInteractionSource() }
            val sendAlpha by animateFloatAsState(if (enabled) 1f else 0.4f, tween(Motion.Medium), label = "sendAlpha")
            Surface(
                onClick = onSend,
                enabled = enabled,
                interactionSource = sendInteraction,
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(sendInteraction, 0.88f)
                    .graphicsLayer { alpha = sendAlpha }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(c.brandGradient)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = c.onBrand
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Отправить",
                            tint = c.onBrand,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
