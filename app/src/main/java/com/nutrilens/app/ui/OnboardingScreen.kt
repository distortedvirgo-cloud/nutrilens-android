package com.nutrilens.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.WeightRepository
import com.nutrilens.app.data.hasAnyAiKey
import com.nutrilens.app.ui.theme.NutriTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Первый запуск: знакомство → данные о себе (норма калорий считается сама) →
 * выбор нейросети и ключ. Всё можно пропустить, но без ключа на «Сегодня» и
 * в Профиле висит плашка «Подключите нейросеть».
 */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "done"

    /** Показывать ли онбординг. Старые пользователи с ключом — сразу «пройден». */
    suspend fun needed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return false
        val settings = SettingsRepository(NutriLensDatabase.getInstance(context).settingsDao()).get()
        if (settings.hasAnyAiKey) {
            markDone(context)
            return false
        }
        return true
    }

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}

/** Норма по Миффлину — Сан Жеору × активность, с поправкой на цель; шаг 50 ккал. */
internal fun calorieNorm(male: Boolean, age: Int, heightCm: Double, weightKg: Double, activity: Int, goal: Int): Int {
    val bmr = 10 * weightKg + 6.25 * heightCm - 5 * age + if (male) 5 else -161
    val tdee = bmr * listOf(1.3, 1.5, 1.7)[activity.coerceIn(0, 2)]
    val adjusted = tdee * listOf(0.85, 1.0, 1.1)[goal.coerceIn(0, 2)]
    return ((adjusted / 50).roundToInt() * 50).coerceIn(1200, 4500)
}

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }

    var male by rememberSaveable { mutableStateOf(true) }
    var age by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableIntStateOf(0) }
    var activity by rememberSaveable { mutableIntStateOf(1) }
    var notes by rememberSaveable { mutableStateOf("") }
    var kcalOverride by rememberSaveable { mutableStateOf("") }

    var provider by rememberSaveable { mutableStateOf("nanogpt") }
    var key by rememberSaveable { mutableStateOf("") }

    val num = { s: String -> s.replace(',', '.').toDoubleOrNull() }
    val ageN = age.toIntOrNull()
    val heightN = num(height)
    val weightN = num(weight)
    val bodyOk = ageN != null && ageN in 12..100 && heightN != null && heightN in 120.0..230.0 &&
        weightN != null && weightN in 30.0..300.0
    val computed = if (bodyOk) calorieNorm(male, ageN!!, heightN!!, weightN!!, activity, goal) else null

    fun finish(saveKey: Boolean) {
        scope.launch {
            val db = NutriLensDatabase.getInstance(context)
            val goalText = listOf("похудеть", "держать вес", "набрать массу")[goal]
            val activityText = listOf("низкая", "средняя", "высокая")[activity]
            SettingsRepository(db.settingsDao()).update { s ->
                var out = s.copy(analysisMode = provider)
                if (bodyOk) {
                    val about = buildString {
                        append(if (male) "Я мужчина" else "Я женщина")
                        append(", $ageN лет, рост ${heightN!!.roundToInt()} см, вес ${"%.1f".format(weightN)} кг. ")
                        append("Цель — $goalText, активность $activityText.")
                        if (notes.isNotBlank()) append(" ").append(notes.trim())
                    }
                    out = out.copy(
                        userContext = about,
                        dailyGoal = (num(kcalOverride) ?: computed!!.toDouble()).coerceIn(800.0, 6000.0)
                    )
                } else if (notes.isNotBlank()) {
                    out = out.copy(userContext = notes.trim())
                }
                if (saveKey && key.isNotBlank()) {
                    out = if (provider == "gemini") out.copy(apiKey = key.trim()) else out.copy(nanoApiKey = key.trim())
                }
                out
            }
            if (bodyOk) WeightRepository(db.weightDao()).addWeight(LocalDate.now().toString(), weightN!!)
            Onboarding.markDone(context)
            onFinish()
        }
    }

    BackHandler(enabled = step > 0) { step-- }

    NutriGlowBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
        ) {
            // Прогресс шагов
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (i <= step) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * dir / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it * dir / 4 } + fadeOut())
                },
                label = "onboarding",
                modifier = Modifier.weight(1f)
            ) { s ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (s) {
                        0 -> WelcomeStep()
                        1 -> {
                            StepTitle("Расскажите о себе", "Посчитаем дневную норму калорий. ИИ учтёт это при разборе еды")
                            SegmentedSwitcher(
                                options = listOf(true to "Мужчина", false to "Женщина"),
                                selected = male,
                                onSelect = { male = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NumberField(age, { age = it }, "Возраст", Modifier.weight(1f), KeyboardType.Number)
                                NumberField(height, { height = it }, "Рост, см", Modifier.weight(1f))
                                NumberField(weight, { weight = it }, "Вес, кг", Modifier.weight(1f))
                            }
                            FieldLabel("Цель")
                            SegmentedSwitcher(
                                options = listOf(0 to "Похудеть", 1 to "Держать", 2 to "Набрать"),
                                selected = goal,
                                onSelect = { goal = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                            FieldLabel("Активность")
                            SegmentedSwitcher(
                                options = listOf(0 to "Низкая", 1 to "Средняя", 2 to "Высокая"),
                                selected = activity,
                                onSelect = { activity = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (computed != null) {
                                TonalSection(color = MaterialTheme.colorScheme.primary) {
                                    Text("Ваша норма", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        "${num(kcalOverride)?.roundToInt() ?: computed} ккал в день",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "Рассчитано по формуле Миффлина — Сан Жеора. Можно поменять:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    NumberField(kcalOverride, { kcalOverride = it }, "Своя норма, ккал (необязательно)", Modifier.fillMaxWidth(), KeyboardType.Number)
                                }
                            }
                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                label = { Text("Особенности (необязательно)") },
                                placeholder = { Text("Например: жарю на 5 г масла, не ем свинину") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            StepTitle("Подключите нейросеть", "Она считает калории по фото и отвечает в чате. Нужен ключ — вставьте его один раз")
                            AiProviderCards(selected = provider, onSelect = { provider = it; key = "" })
                            OutlinedTextField(
                                value = key,
                                onValueChange = { key = it },
                                label = { Text(if (provider == "gemini") "Ключ Gemini" else "Ключ NanoGPT") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth()
                            )
                            GetKeyLink(provider)
                            Text(
                                "Ключ хранится только на этом телефоне. Поменять или добавить второй можно в Профиль → Нейросеть.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // Кнопки
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                when (step) {
                    0 -> GlowButton("Начать", onClick = { step = 1 }, modifier = Modifier.fillMaxWidth())
                    1 -> {
                        GlowButton("Дальше", onClick = { step = 2 }, enabled = bodyOk, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) {
                            Text("Пропустить", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    else -> {
                        GlowButton("Готово", onClick = { finish(saveKey = true) }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { finish(saveKey = false) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Добавлю ключ позже", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    val c = NutriTheme.colors
    Spacer(Modifier.height(40.dp))
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.brandGradient)
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = c.onBrand, modifier = Modifier.size(42.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text("Добро пожаловать в NutriLens", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    Text(
        "Сфотографируйте еду — ИИ посчитает калории и БЖУ. Настроим всё за минуту: пара вопросов о вас и ключ нейросети.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    type: KeyboardType = KeyboardType.Decimal
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = modifier
    )
}
