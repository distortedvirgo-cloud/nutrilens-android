package com.nutrilens.app.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Обучение (3.29): пять коротких экранов о том, как пользоваться приложением, —
 * один раз после онбординга (и один раз у тех, кто обновился). Повторить — Профиль →
 * «Как пользоваться». Без настроек и вопросов: только «что делать и зачем».
 */
object Tutorial {
    private const val PREFS = "tutorial"
    private const val KEY_DONE = "done_v1"

    fun needed(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}

private data class TutorialPage(val icon: ImageVector, val title: String, val text: String, val tip: String)

private val PAGES = listOf(
    TutorialPage(
        Icons.Rounded.PhotoCamera,
        "Сфотографируй еду",
        "Нажми «+» и сделай фото — нейросеть сама посчитает калории, белки, жиры и углеводы. Можно описать словами или добавить из избранного одним тапом.",
        "Записывай всё, даже перекусы: план точен настолько, насколько полон дневник"
    ),
    TutorialPage(
        Icons.Rounded.MonitorWeight,
        "Взвешивайся по утрам",
        "После туалета, до еды — 3–7 раз в неделю. Вес за день гуляет на 0,5–1,5 кг из-за воды, поэтому приложение смотрит на тренд, а не на одно число.",
        "Скачок вверх после солёного ужина — это вода, не жир"
    ),
    TutorialPage(
        Icons.Rounded.LocalFireDepartment,
        "Режим «Сжигание жира»",
        "Включается в Профиле. Ставишь цель по проценту жира — калории и белок считаются и подстраиваются сами, по твоему весу и еде. Между блоками дефицита — обязательные перерывы.",
        "Ничего считать не нужно — просто ешь в свою норму"
    ),
    TutorialPage(
        Icons.Rounded.EventAvailable,
        "Раз в неделю — чек-ин",
        "Меньше минуты: вес, замер талии и как прошла неделя. Только на чек-ине план меняется — и ты видишь, почему. Экран «Как идёт прогресс» покажет, хорошо ли всё идёт.",
        "Пропустил день-другой — ничего страшного, чек-ин просто подождёт"
    ),
    TutorialPage(
        Icons.Rounded.FitnessCenter,
        "Тренировки и сила",
        "Ведёшь тренировки в Hevy? Отправь экспорт в NutriLens через «Поделиться» — дни тренировок отметятся сами, а чек-ин покажет, держится ли сила.",
        "Сила держится, а талия уходит — значит, уходит жир, а не мышцы"
    )
)

/** Полноэкранное обучение. [onFinish] — и «Пропустить», и последняя кнопка. */
@Composable
fun TutorialScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val last = pager.currentPage == PAGES.lastIndex

    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = Space.Screen, vertical = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Как пользоваться · ${pager.currentPage + 1} из ${PAGES.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (!last) TextButton(onClick = onFinish) { Text("Пропустить") }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { i ->
                TutorialPageContent(PAGES[i])
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .semantics { contentDescription = "Экран ${pager.currentPage + 1} из ${PAGES.size}" }
            ) {
                PAGES.indices.forEach { i ->
                    val active = i == pager.currentPage
                    val w by animateDpAsState(if (active) 22.dp else 8.dp, label = "dot")
                    val c by animateColorAsState(if (active) scheme.primary else scheme.outlineVariant, label = "dotColor")
                    Box(Modifier.size(width = w, height = 8.dp).background(c, CircleShape))
                }
            }
            GlowButton(
                if (last) "Понятно, начнём" else "Дальше",
                onClick = {
                    if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TutorialPageContent(page: TutorialPage) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(112.dp).background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(page.icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            page.text,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(scheme.primary, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(page.tip, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Повтор из Профиля — тот же экран поверх всего. */
@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        TutorialScreen(onFinish = onDismiss)
    }
}
