package com.nutrilens.app.ui

import android.app.Application
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.Recipe
import com.nutrilens.app.data.RecipeFormat
import com.nutrilens.app.data.RecipeIngredient
import com.nutrilens.app.data.RecipeRepository
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.ui.theme.macroPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class RecipeDetailViewModel(app: Application, state: SavedStateHandle) : AndroidViewModel(app) {
    private val id: String = state["id"] ?: ""
    val repo = RecipeRepository(NutriLensDatabase.getInstance(app))
    /** null — загрузка; Recipe с пустым id — не найден. */
    val recipe: StateFlow<Recipe?> = repo.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun save(r: Recipe) = viewModelScope.launch { repo.put(r) }
    fun delete(r: Recipe) = viewModelScope.launch { repo.delete(r.id) }
    suspend fun log(r: Recipe, portions: Double) = repo.logToDiary(r, portions)
}

private val SOURCE_LABEL = mapOf("youtube" to "Открыть на YouTube", "tiktok" to "Открыть в TikTok", "instagram" to "Открыть в Instagram", "web" to "Открыть источник")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(onBack: () -> Unit, onMessage: (String) -> Unit, viewModel: RecipeDetailViewModel = viewModel()) {
    val r by viewModel.recipe.collectAsStateWithLifecycle()
    val recipe = r ?: return
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var servings by remember(recipe.id) { mutableIntStateOf(recipe.servings) }
    var have by remember(recipe.id) { mutableStateOf(setOf<Int>()) }
    var mode by remember { mutableStateOf<String?>(null) }
    val factor = servings.toDouble() / recipe.servings

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box {
                RecipeCover(recipe, Modifier.fillMaxWidth().height(320.dp), emojiSize = 110)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f), 0.3f to Color.Transparent, 0.62f to Color.Transparent, 1f to MaterialTheme.colorScheme.background
                )))
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    GlassButton(Icons.Rounded.Share, "Список покупок") {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, RecipeFormat.shoppingText(recipe, servings))
                        context.startActivity(Intent.createChooser(send, "Список покупок"))
                    }
                    GlassButton(
                        if (recipe.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (recipe.favorite) "Убрать из избранного" else "В избранное",
                        tint = if (recipe.favorite) Color(0xFFFF5E7E) else Color.White
                    ) { viewModel.save(recipe.copy(favorite = !recipe.favorite)) }
                }
            }

            Column(
                Modifier.padding(horizontal = Space.Screen).padding(top = 0.dp, bottom = 130.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallTag(RecipeFormat.CATEGORY[recipe.category] ?: "", primary = true)
                        recipe.tags.forEach { SmallTag(it) }
                    }
                    Text(recipe.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    if (recipe.description.isNotBlank()) Text(recipe.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val label = SOURCE_LABEL[recipe.source.kind]
                    if (recipe.source.url.isNotBlank() && label != null) {
                        NutriChip(label + if (recipe.source.author.isNotBlank()) " · ${recipe.source.author}" else "", onClick = { uri.openUri(recipe.source.url) }, icon = Icons.Rounded.Link)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Rounded.Schedule, recipe.totalMinutes?.let { "$it мин" } ?: "—", "время", Modifier.weight(1f))
                    StatTile(Icons.Rounded.LocalFireDepartment, "${recipe.nutrition.calories.roundToInt()}", "ккал / порц.", Modifier.weight(1f))
                    StatTile(Icons.Rounded.RestaurantMenu, RecipeFormat.DIFFICULTY[recipe.difficulty] ?: "", "сложность", Modifier.weight(1f))
                }

                FreshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("На одну порцию", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text("${recipe.nutrition.calories.roundToInt()} ккал", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val p = macroPalette()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MacroTile("Белки", recipe.nutrition.protein, p.protein, Modifier.weight(1f))
                            MacroTile("Жиры", recipe.nutrition.fat, p.fat, Modifier.weight(1f))
                            MacroTile("Углеводы", recipe.nutrition.carbs, p.carbs, Modifier.weight(1f))
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ингредиенты", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                    Stepper(servings.toDouble(), onChange = { servings = it.toInt() }, step = 1.0, min = 1.0, suffix = " порц.")
                }
                if (have.isNotEmpty()) Text("Отмечено ${have.size} из ${recipe.ingredients.size} — нажмите ещё раз, чтобы снять", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                recipe.ingredients.map { it.group }.distinct().forEach { group ->
                    if (group.isNotBlank()) TinyLabel(group)
                    val items = recipe.ingredients.withIndex().filter { it.value.group == group }
                    items.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (idx, ing) ->
                                IngredientTile(ing, RecipeFormat.ingredientAmount(ing, factor), idx in have, Modifier.weight(1f)) {
                                    have = if (idx in have) have - idx else have + idx
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Text("Приготовление", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                recipe.steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.Top) {
                        StepNumber(i + 1)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            s.minutes?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(" ${it.roundToInt()} мин", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                if (recipe.tips.isNotEmpty()) {
                    TonalSection(color = NutriTheme.colors.warnFrom) {
                        Text("💡 Советы", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        recipe.tips.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (recipe.cookedCount > 0) Text("Готовили ${recipe.cookedCount} ${plural(recipe.cookedCount, "раз", "раза", "раз")}   ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextAction("Удалить рецепт", onClick = { mode = "delete" })
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Row(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = Space.Screen, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("В дневник", onClick = { mode = "log" }, icon = Icons.Rounded.Add, modifier = Modifier.weight(1f))
                GlowButton("Готовить", onClick = { mode = "cook" }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
            }
        }

        when (mode) {
            "cook" -> CookMode(recipe, factor, onClose = { mode = null }, onFinish = { mode = "log" })
            "log" -> LogSheet(recipe, onDismiss = { mode = null }) { portions ->
                scope.launch {
                    viewModel.log(recipe, portions)
                    mode = null
                    onMessage("Записано: ${(recipe.nutrition.calories * portions).roundToInt()} ккал")
                }
            }
            "delete" -> NutriSheet(onDismiss = { mode = null }, title = "Удалить «${recipe.title}»?", subtitle = "Записи в дневнике останутся") {
                DangerButton("Удалить", onClick = { viewModel.delete(recipe); mode = null; onBack(); onMessage("Рецепт удалён") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                SoftButton("Отмена", onClick = { mode = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun GlassButton(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Black.copy(alpha = 0.32f), modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp)) }
    }
}

@Composable
private fun SmallTag(text: String, primary: Boolean = false) {
    Surface(shape = RoundedCornerShape(50), color = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    FreshCard(modifier, radius = 22) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MacroTile(label: String, value: Double, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.14f)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("${value.roundToInt()} г", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stepper(value: Double, onChange: (Double) -> Unit, step: Double, min: Double, suffix: String = "") {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(onClick = { onChange(maxOf(min, value - step)) }, enabled = value > min, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Remove, "Меньше", Modifier.size(17.dp)) }
        }
        Text(RecipeFormat.amount(value, "шт") + suffix, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp))
        Surface(onClick = { onChange(minOf(30.0, value + step)) }, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Больше", Modifier.size(17.dp)) }
        }
    }
}

/** Мягкий фон под эмодзи — стабильный цвет по названию продукта. */
@Composable
private fun tintOf(name: String): Color {
    val p = macroPalette()
    val c = NutriTheme.colors
    val tints = listOf(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary, c.warnFrom, MaterialTheme.colorScheme.secondary, p.protein, p.carbs, p.fat, c.success)
    return tints[(name.fold(7) { a, ch -> a * 31 + ch.code } and 0x7fffffff) % tints.size]
}

@Composable
private fun IngredientTile(ing: RecipeIngredient, amount: String, checked: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val tint = tintOf(ing.name)
    FreshCard(modifier.alpha(if (checked) 0.55f else 1f), radius = 22, onClick = onToggle) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text(ing.emoji, fontSize = 24.sp)
                }
                if (checked) Box(
                    Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp)) }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(ing.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (checked) TextDecoration.LineThrough else null)
                Text(amount + if (ing.note.isNotBlank()) " · ${ing.note}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepNumber(n: Int) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(NutriTheme.colors.brandGradient), contentAlignment = Alignment.Center) {
        Text("$n", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = NutriTheme.colors.onBrand)
    }
}

/** Пошаговый режим: крупный шрифт, таймер шага, экран не гаснет. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CookMode(r: Recipe, factor: Double, onClose: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    var i by remember { mutableIntStateOf(0) }
    val step = r.steps[i]
    var left by remember(i) { mutableIntStateOf(((step.minutes ?: 0.0) * 60).roundToInt()) }
    var running by remember(i) { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(running, i) {
        while (running && left > 0) {
            delay(1000)
            left -= 1
            if (left == 0) {
                running = false
                (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                    ?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300), -1))
            }
        }
    }
    val used = r.ingredients.filter { g -> step.text.contains(g.name.split(' ').first().take(5), ignoreCase = true) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(onClick = onClose, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Закрыть") }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Шаг ${i + 1} из ${r.steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                r.steps.indices.forEach { k ->
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (k <= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)) {
                Text(step.text, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (used.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        used.forEach { SmallTag("${it.emoji} ${it.name} · ${RecipeFormat.ingredientAmount(it, factor)}") }
                    }
                }
                if (step.minutes != null) {
                    FreshCard(onClick = { running = !running }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text(if (running) "идёт таймер" else if (left == 0) "готово" else "запустить таймер", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Назад", onClick = { i -= 1 }, enabled = i > 0, modifier = Modifier.width(110.dp))
                if (i == r.steps.lastIndex) GlowButton("Готово — в дневник", onClick = { onClose(); onFinish() }, icon = Icons.Rounded.Check, modifier = Modifier.weight(1f))
                else GlowButton("Дальше", onClick = { i += 1 }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LogSheet(r: Recipe, onDismiss: () -> Unit, onLog: (Double) -> Unit) {
    var portions by remember { mutableDoubleStateOf(1.0) }
    NutriSheet(onDismiss = onDismiss, title = "Сколько съели?") {
        FreshCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.emoji} Порции", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    val n = r.nutrition
                    Text("${(n.calories * portions).roundToInt()} ккал · Б ${(n.protein * portions).roundToInt()} Ж ${(n.fat * portions).roundToInt()} У ${(n.carbs * portions).roundToInt()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Stepper(portions, onChange = { portions = it }, step = 0.5, min = 0.5)
            }
        }
        Spacer(Modifier.height(16.dp))
        GlowButton("Записать в «Сегодня»", onClick = { onLog(portions) }, modifier = Modifier.fillMaxWidth())
    }
}
