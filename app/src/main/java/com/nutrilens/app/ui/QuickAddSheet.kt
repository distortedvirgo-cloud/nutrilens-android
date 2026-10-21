package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.FavoriteEntity
import com.nutrilens.app.data.FavoriteRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.QuickActions
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.ui.theme.macroPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class QuickAddViewModel(application: Application) : AndroidViewModel(application) {
    val favorites: StateFlow<List<FavoriteEntity>> =
        FavoriteRepository(NutriLensDatabase.getInstance(application).favoriteDao()).observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addWater(onDone: (Int) -> Unit) {
        viewModelScope.launch { onDone(QuickActions.addWater(getApplication(), 250)) }
    }

    fun setWeight(kg: Double, onDone: () -> Unit) {
        viewModelScope.launch {
            QuickActions.setWeight(getApplication(), kg)
            onDone()
        }
    }

    fun toggleWorkout(onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(QuickActions.toggleWorkout(getApplication())) }
    }

    fun addFavorite(favorite: FavoriteEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            QuickActions.addFavorite(getApplication(), favorite)
            onDone()
        }
    }
}

/**
 * Панель «+»: главное действие — сфотографировать еду, рядом — галерея, текст,
 * меню кафе, вода, вес и тренировка, ниже — избранное одним тапом.
 * Быстрые действия выполняются прямо из листа, без перехода на экраны.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
    viewModel: QuickAddViewModel = viewModel()
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val c = NutriTheme.colors
    var showWeight by remember { mutableStateOf(false) }

    fun close(then: () -> Unit = {}) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            then()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                "Что добавим?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            val heroInteraction = remember { MutableInteractionSource() }
            Surface(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    close { onNavigate("add/camera") }
                },
                interactionSource = heroInteraction,
                shape = RoundedCornerShape(Radius.Card),
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(heroInteraction, 0.98f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(c.brandGradient)
                        .padding(18.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = c.onBrand, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Сфотографировать", style = MaterialTheme.typography.titleLarge, color = c.onBrand)
                        Text(
                            "ИИ посчитает КБЖУ в фоне",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onBrand.copy(alpha = 0.85f)
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.onBrand)
                }
            }

            val palette = macroPalette()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetAction(Icons.Rounded.PhotoLibrary, "Галерея", palette.protein, Modifier.weight(1f)) {
                    close { onNavigate("add/gallery") }
                }
                SheetAction(Icons.AutoMirrored.Rounded.Notes, "Текстом", palette.carbs, Modifier.weight(1f)) {
                    close { onNavigate("add/text") }
                }
                SheetAction(Icons.AutoMirrored.Rounded.MenuBook, "Меню кафе", MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) {
                    close { onNavigate("menu") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetAction(Icons.Rounded.WaterDrop, "+250 мл", c.water, Modifier.weight(1f)) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.addWater { total -> onMessage("Вода: $total мл за сегодня") }
                    close()
                }
                SheetAction(Icons.Rounded.MonitorWeight, "Вес", c.warnTo, Modifier.weight(1f)) {
                    showWeight = true
                }
                SheetAction(Icons.Rounded.FitnessCenter, "Тренировка", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) {
                    viewModel.toggleWorkout { done ->
                        onMessage(if (done) "Тренировка отмечена: +500 мл к норме воды" else "Отметка тренировки снята")
                    }
                    close()
                }
            }

            if (favorites.isNotEmpty()) {
                TinyLabel("Избранное — одним тапом")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    favorites.take(8).forEach { fav ->
                        val chipInteraction = remember(fav.id) { MutableInteractionSource() }
                        Surface(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.addFavorite(fav) { onMessage("«${fav.name}» записано") }
                                close()
                            },
                            interactionSource = chipInteraction,
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.pressScale(chipInteraction)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    fav.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${fav.calories.roundToInt()}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showWeight) {
        WeightEntryDialog(
            onConfirm = { kg ->
                viewModel.setWeight(kg) { onMessage("Вес %.1f кг записан".format(kg)) }
                showWeight = false
                close()
            },
            onDismiss = { showWeight = false }
        )
    }
}

/** Плитка действия в листе «+»: тонированная иконка и подпись. */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(Radius.Tile),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, NutriTheme.colors.hairline),
        modifier = modifier.pressScale(interaction)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp)
        ) {
            IconBadge(icon, tint, size = 42)
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

/** Ввод веса — общий для «+» и экрана «Сегодня». */
@Composable
fun WeightEntryDialog(onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val parsed = input.trim().replace(",", ".").toDoubleOrNull()?.takeIf { it in 20.0..400.0 }
    NutriDialog(
        onDismiss = onDismiss,
        title = "Записать вес",
        confirmText = "Сохранить",
        confirmEnabled = parsed != null,
        onConfirm = { parsed?.let(onConfirm) }
    ) {
        NutriTextField(
            value = input,
            onValueChange = { input = it },
            label = "Вес",
            suffix = "кг",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
