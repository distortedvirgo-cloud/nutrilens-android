package com.nutrilens.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nutrilens.app.ai.RecipeAi
import com.nutrilens.app.bg.RecipeImporter
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.Recipe
import com.nutrilens.app.data.RecipeFormat
import com.nutrilens.app.data.RecipeRepository
import com.nutrilens.app.ui.theme.NutriTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class RecipesViewModel(app: Application) : AndroidViewModel(app) {
    val repo = RecipeRepository(NutriLensDatabase.getInstance(app))
    val recipes: StateFlow<List<Recipe>?> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun toggleFavorite(r: Recipe) = viewModelScope.launch { repo.put(r.copy(favorite = !r.favorite)) }
}

/** Цвета обложки без картинки — по категории блюда (как в веб-версии). */
val CATEGORY_TINT = mapOf(
    "breakfast" to (Color(0xFFFFB868) to Color(0xFFF2766B)), "lunch" to (Color(0xFF5FD49B) to Color(0xFF16936A)),
    "dinner" to (Color(0xFF8C9BFF) to Color(0xFF5A4FCF)), "snack" to (Color(0xFF5CD5D0) to Color(0xFF1E8FA3)),
    "dessert" to (Color(0xFFFF9CC2) to Color(0xFFD9508A)), "drink" to (Color(0xFF7FC8FF) to Color(0xFF3A7BE0))
)

@Composable
fun RecipeCover(r: Recipe, modifier: Modifier, emojiSize: Int = 56) {
    val (a, b) = CATEGORY_TINT[r.category] ?: CATEGORY_TINT.getValue("lunch")
    Box(modifier.background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        val cover = r.cover
        if (cover == null) {
            Text(r.emoji, fontSize = emojiSize.sp)
        } else {
            Text(r.emoji, fontSize = emojiSize.sp)
            AsyncImage(
                model = if (cover.startsWith("file:")) File(cover.removePrefix("file:")) else cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun RecipesScreen(
    onOpen: (String) -> Unit,
    viewModel: RecipesViewModel = viewModel()
) {
    val context = LocalContext.current
    val recipes by viewModel.recipes.collectAsStateWithLifecycle()
    val jobs by RecipeImporter.jobs.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var textSheet by remember { mutableStateOf(false) }

    val list = (recipes ?: emptyList()).filter { r ->
        (filter == "all" || (if (filter == "fav") r.favorite else r.category == filter)) &&
            (query.isBlank() || "${r.title} ${r.tags.joinToString(" ")} ${r.ingredients.joinToString(" ") { it.name }}"
                .contains(query.trim(), ignoreCase = true))
    }
    val count = recipes?.size ?: 0
    val categories = (recipes ?: emptyList()).map { it.category }.distinct()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.Screen, end = Space.Screen, top = 12.dp, bottom = 130.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(Modifier.staggeredIn(0)) {
                Text("Рецепты", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    if (count > 0) "$count ${plural(count, "рецепт", "рецепта", "рецептов")} в книге" else "Твоя книга рецептов",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item(span = { GridItemSpan(2) }) { ImportCard(onText = { textSheet = true }, modifier = Modifier.staggeredIn(1)) }
        jobs.forEach { job -> item(span = { GridItemSpan(2) }, key = job.id) { JobCard(job) } }
        if (recipes != null && count == 0 && jobs.isEmpty()) item(span = { GridItemSpan(2) }) { EmptyBook() }
        if (count > 0) {
            if (count >= 4) item(span = { GridItemSpan(2) }) {
                NutriTextField(
                    value = query, onValueChange = { query = it }, placeholder = "Блюдо или продукт", singleLine = true,
                    trailingIcon = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.outline) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(span = { GridItemSpan(2) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { NutriChip("Все", onClick = { filter = "all" }, selected = filter == "all") }
                    if (recipes.orEmpty().any { it.favorite }) item {
                        NutriChip("Избранное", onClick = { filter = "fav" }, selected = filter == "fav", icon = Icons.Rounded.Favorite)
                    }
                    items(categories) { c -> NutriChip(RecipeFormat.CATEGORY[c] ?: c, onClick = { filter = c }, selected = filter == c) }
                }
            }
            if (list.isEmpty()) item(span = { GridItemSpan(2) }) {
                Text("Ничего не нашлось", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp))
            }
            items(list, key = { it.id }) { r -> RecipeCard(r, onOpen = { onOpen(r.id) }, onFavorite = { viewModel.toggleFavorite(r) }) }
        }
    }

    if (textSheet) TextImportSheet(onDismiss = { textSheet = false })
}

@Composable
private fun ImportCard(onText: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = NutriTheme.colors
    var link by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val submit: (String) -> Unit = { text ->
        val url = RecipeAi.findUrl(text)
        if (url == null) hint = "Вставьте ссылку на видео или страницу с рецептом"
        else { RecipeImporter.fromLink(context, url); link = ""; hint = "Разбираю рецепт — это займёт до минуты" }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val big = RecipeImporter.videoSize(context, uri) > RecipeAi.MAX_VIDEO_BYTES
        RecipeImporter.fromVideo(context, uri)
        hint = if (big) "Видео большое — сначала сожму его, потом посмотрю" else "Смотрю видео — это займёт до минуты"
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris: List<Uri> ->
        if (uris.isNotEmpty()) { RecipeImporter.fromPhotos(context, uris); hint = "Читаю рецепт с фото" }
    }
    FreshCard(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(c.brandGradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = c.onBrand, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Добавить рецепт", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Ссылка на YouTube, TikTok, Instagram или сайт", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NutriTextField(
                    value = link, onValueChange = { link = it }, placeholder = "https://…", singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit(link) }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = {
                        if (link.isNotBlank()) submit(link) else {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (RecipeAi.findUrl(text) != null) submit(text) else hint = "В буфере нет ссылки"
                        }
                    },
                    shape = CircleShape,
                    color = if (link.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (link.isNotBlank()) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.ContentPaste,
                            contentDescription = if (link.isNotBlank()) "Разобрать" else "Вставить ссылку",
                            tint = if (link.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceButton(Icons.Rounded.Videocam, "Видео", Modifier.weight(1f)) {
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
                SourceButton(Icons.Rounded.PhotoCamera, "Фото", Modifier.weight(1f)) {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SourceButton(Icons.AutoMirrored.Rounded.Notes, "Текст", Modifier.weight(1f), onText)
            }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun SourceButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun JobCard(job: RecipeImporter.Job) {
    FreshCard(Modifier.fillMaxWidth()) {
        if (job.error != null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${job.label.replaceFirstChar { it.uppercase() }}: не получилось", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(job.error.removePrefix(com.nutrilens.app.bg.VideoDownloader.IG_LOGIN_HINT).trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val needsLogin = job.error.startsWith(com.nutrilens.app.bg.VideoDownloader.IG_LOGIN_HINT)
                var login by remember { mutableStateOf(false) }
                if (login) InstagramLoginDialog(onDismiss = { login = false }, onLoggedIn = { login = false; job.retry() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (needsLogin) NutriChip("Войти в Instagram", onClick = { login = true }, primary = true)
                    NutriChip("Повторить", onClick = job.retry, primary = !needsLogin, icon = Icons.Rounded.Refresh)
                    NutriChip("Скрыть", onClick = { RecipeImporter.dismiss(job.id) })
                }
            }
        } else {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Разбираю: ${job.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Ингредиенты, шаги и КБЖУ на порцию. Можно уйти с экрана — пришлю уведомление.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(r: Recipe, onOpen: () -> Unit, onFavorite: () -> Unit) {
    FreshCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column {
            Box {
                RecipeCover(r, Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)), emojiSize = 50)
                Surface(
                    onClick = onFavorite, shape = CircleShape, color = Color.Black.copy(alpha = 0.32f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (r.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (r.favorite) "Убрать из избранного" else "В избранное",
                            tint = if (r.favorite) Color(0xFFFF5E7E) else Color.White, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
                Text(
                    r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.heightIn(min = 40.dp)
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    r.totalMinutes?.let {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" $it мин   ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${r.nutrition.calories.toInt()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyBook() {
    FreshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🍝🥗🍰", fontSize = 42.sp)
            Text("Собери свою книгу рецептов", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Увидел рецепт в Reels, Shorts или TikTok — нажми «Поделиться» → NutriLens или вставь ссылку выше. ИИ разберёт ингредиенты, шаги и посчитает КБЖУ на порцию.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun TextImportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    NutriSheet(onDismiss = onDismiss, title = "Текст рецепта", subtitle = "Вставьте рецепт из заметок, чата или описания под видео") {
        NutriTextField(value = text, onValueChange = { text = it }, minLines = 6, placeholder = "Сырники: творог 400 г, яйцо 1, мука 3 ст. л…", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        GlowButton("Разобрать", onClick = { RecipeImporter.fromText(context, text.trim()); onDismiss() }, enabled = text.trim().length >= 20, modifier = Modifier.fillMaxWidth())
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && (m100 < 12 || m100 > 14) -> few
        else -> many
    }
}

/** Вход на instagram.com во встроенном браузере. Как только появилась сессия — закрываем и повторяем разбор. */
@Composable
private fun InstagramLoginDialog(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Вход в Instagram", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Нужен, чтобы скачивать рилсы. Пароль вводится на сайте Instagram, вход хранится только на телефоне. Можно запасной аккаунт.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    NutriChip("Закрыть", onClick = onDismiss)
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        if (com.nutrilens.app.bg.InstagramSession.isLoggedIn()) {
                            android.webkit.CookieManager.getInstance().flush()
                            onLoggedIn(); break
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = android.webkit.WebViewClient()
                            loadUrl("https://www.instagram.com/accounts/login/")
                        }
                    },
                    onRelease = { it.destroy() }
                )
            }
        }
    }
}
