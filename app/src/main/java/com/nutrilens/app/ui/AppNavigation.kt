package com.nutrilens.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.ui.theme.LocalReducedMotion
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriGlowBackground
import com.nutrilens.app.ui.theme.NutriTheme
import java.time.LocalDate

private data class DockTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

private val leftTabs = listOf(
    DockTab("dashboard", "Сегодня", Icons.Outlined.Home, Icons.Rounded.Home),
    DockTab("report", "Прогресс", Icons.Outlined.Insights, Icons.Rounded.Insights)
)

private val rightTabs = listOf(
    DockTab("coach", "Коуч", Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
    DockTab("profile", "Профиль", Icons.Outlined.Person, Icons.Rounded.Person)
)

/** Маршруты верхнего уровня: на них виден док, между ними — «fade through». */
private val TAB_ROUTES = (leftTabs + rightTabs).map { it.route }

private fun tabIndex(entry: NavBackStackEntry): Int =
    TAB_ROUTES.indexOf(entry.destination.route)

// --- Переходы (skill: continuity, navigation-direction, exit-faster-than-enter) ---

/** Вход: вкладка → вкладка — сдвиг по направлению вкладки; вглубь — справа; «+» — снизу. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition {
    val from = tabIndex(initialState)
    val to = tabIndex(targetState)
    return when {
        targetState.destination.route?.startsWith("add") == true ->
            slideInVertically(tween(Motion.Long, easing = Motion.Emphasized)) { it / 6 } +
                fadeIn(tween(Motion.Medium))
        from >= 0 && to >= 0 -> {
            val dir = if (to > from) 1 else -1
            slideInHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { dir * it / 10 } +
                fadeIn(tween(Motion.Medium, delayMillis = 60))
        }
        else ->
            slideInHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { it / 4 } +
                fadeIn(tween(Motion.Medium))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition {
    val from = tabIndex(initialState)
    val to = tabIndex(targetState)
    return if (from >= 0 && to >= 0) {
        val dir = if (to > from) -1 else 1
        slideOutHorizontally(tween(Motion.Medium, easing = Motion.Exit)) { dir * it / 14 } +
            fadeOut(tween(Motion.Short))
    } else {
        // Уходим вглубь: прежний экран слегка отступает и тускнеет под новым.
        scaleOut(tween(Motion.Medium, easing = Motion.Exit), targetScale = 0.96f) +
            fadeOut(tween(Motion.Medium))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    scaleIn(tween(Motion.Long, easing = Motion.Emphasized), initialScale = 0.96f) +
        fadeIn(tween(Motion.Medium))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (initialState.destination.route?.startsWith("add") == true) {
        slideOutVertically(tween(Motion.Medium, easing = Motion.Exit)) { it / 6 } +
            fadeOut(tween(Motion.Short))
    } else {
        slideOutHorizontally(tween(Motion.Medium, easing = Motion.Exit)) { it / 4 } +
            fadeOut(tween(Motion.Short))
    }

/**
 * Корневой композабл: edge-to-edge фон «аврора», NavHost с направленными
 * переходами и плавающий стеклянный док — только на экранах верхнего уровня.
 * Вложенные экраны (чат, инструменты, настройки) открываются поверх с
 * кнопкой «назад», а «назад» возвращает туда, откуда пришли.
 */
@Composable
fun NutriLensAppRoot(initialDate: String? = null, navigateTo: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }
    val navigate: (String) -> Unit = { route -> navController.navigateTo(route) }
    val back: () -> Unit = { navController.popBackStack() }

    // Глубокие ссылки из уведомлений: "settings" — сразу на экран настроек обновлений.
    LaunchedEffect(navigateTo) {
        if (navigateTo != null && navigateTo != "dashboard") {
            navigate(navigateTo)
        }
    }

    // FAB «дышит», пока сегодня нет ни одной записи — мягкий призыв добавить еду.
    // Пересчитываем при каждом возврате на вкладку: после добавления пульс гаснет.
    val appContext = LocalContext.current.applicationContext
    var hasMealsToday by remember { mutableStateOf(true) }
    LaunchedEffect(currentRoute) {
        if (currentRoute in TAB_ROUTES) {
            val today = LocalDate.now().toString()
            hasMealsToday = NutriLensDatabase.getInstance(appContext)
                .mealDao()
                .mealsBetween(today, today)
                .isNotEmpty()
        }
    }

    // Красная индикация на «+», пока есть анализы, завершившиеся ошибкой.
    var failedJobCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        NutriLensDatabase.getInstance(appContext)
            .analysisJobDao()
            .observeFailed()
            .collect { failedJobCount = it.size }
    }

    val showDock = currentRoute == null || currentRoute in TAB_ROUTES
    var showAddSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    NutriGlowBackground {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "dashboard",
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .imePadding(),
                enterTransition = { enter() },
                exitTransition = { exit() },
                popEnterTransition = { popEnter() },
                popExitTransition = { popExit() }
            ) {
                composable("dashboard") {
                    DashboardScreen(
                        initialDate = initialDate,
                        snackbarHostState = snackbarHostState,
                        onOpenLeaderboard = { navigate("leaderboard") },
                        onOpenCoach = { navigate("coach") },
                        onAddMeal = { navigate("add/camera") }
                    )
                }
                composable("report") { ReportScreen(onOpenLeaderboard = { navigate("leaderboard") }) }
                composable("coach") { CoachScreen(onNavigate = navigate) }
                composable("profile") { ProfileScreen(onNavigate = navigate) }
                composable("add") {
                    AddMealScreen(
                        snackbarHostState = snackbarHostState,
                        onDone = { navigate("dashboard") },
                        onGoSettings = { navigate("settings/ai") },
                        onBack = back
                    )
                }
                composable("add/{mode}") { entry ->
                    AddMealScreen(
                        mode = entry.arguments?.getString("mode"),
                        snackbarHostState = snackbarHostState,
                        onDone = { navigate("dashboard") },
                        onGoSettings = { navigate("settings/ai") },
                        onBack = back
                    )
                }
                composable("settings") { SettingsScreen(onBack = back) }
                composable("settings/{section}") { entry ->
                    SettingsScreen(onBack = back, section = entry.arguments?.getString("section"))
                }
                composable("chat") { ChatScreen(onBack = back) }
                composable("chat?q={q}") { entry ->
                    ChatScreen(onBack = back, initialMessage = entry.arguments?.getString("q"))
                }
                composable("ideas") { IdeasScreen(onBack = back) }
                composable("fridge") { FridgeScreen(onBack = back) }
                composable("menu") { MenuScreen(onBack = back) }
                composable("habitTool") { HabitToolScreen(onBack = back) }
                composable("waterTool") { WaterToolScreen(onBack = back) }
                composable("leaderboard") { LeaderboardScreen(onBack = back) }
                composable("grocery") { GroceryScreen(onBack = back) }
            }

            if (showAddSheet) {
                QuickAddSheet(
                    onDismiss = { showAddSheet = false },
                    onNavigate = navigate,
                    onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                )
            }

            // После обновления — один раз «Что нового».
            WhatsNewOnUpdate()

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                SnackbarHost(
                    snackbarHostState,
                    modifier = if (showDock) Modifier else Modifier.navigationBarsPadding()
                )
                AnimatedVisibility(
                    visible = showDock,
                    enter = slideInVertically(tween(Motion.Long, easing = Motion.Emphasized)) { it } +
                        fadeIn(tween(Motion.Medium)),
                    exit = slideOutVertically(tween(Motion.Medium, easing = Motion.Exit)) { it } +
                        fadeOut(tween(Motion.Short))
                ) {
                    FloatingDock(
                        currentRoute = currentRoute,
                        shouldPulseFab = !hasMealsToday,
                        failedJobCount = failedJobCount,
                        onTab = navigate,
                        onAdd = { showAddSheet = true }
                    )
                }
            }
        }
    }
}

/**
 * Плавающий док: «стеклянная» поверхность, волосяная граница, мягкая тень.
 * Индикатор-пилюля перетекает между вкладками на пружине, активная иконка
 * «подпрыгивает» и меняется с контурной на залитую.
 */
@Composable
private fun FloatingDock(
    currentRoute: String?,
    shouldPulseFab: Boolean,
    failedJobCount: Int,
    onTab: (String) -> Unit,
    onAdd: () -> Unit
) {
    val allTabs = leftTabs + rightTabs
    val selectedIndex = allTabs.indexOfFirst { it.route == currentRoute }
    // Сплошная панель во всю ширину (контент не просвечивает) с мягким
    // затуханием над ней; FAB «+» приподнят над центром.
    val dockShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val c = NutriTheme.colors
    val bg = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Затухание контента над панелью: рисуется выше собственных границ.
                val fade = 56.dp.toPx()
                val top = 22.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(bg.copy(alpha = 0f), bg.copy(alpha = 0.92f)),
                        startY = top - fade,
                        endY = top + 8.dp.toPx()
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, top - fade),
                    size = androidx.compose.ui.geometry.Size(size.width, fade + 8.dp.toPx())
                )
            }
    ) {
        Surface(
            shape = dockShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, c.hairline),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(top = 22.dp)
                .softShadow(dockShape, 16)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .height(60.dp)
            ) {
                val spacer = 76.dp
                val slot = (maxWidth - spacer) / 4
                val pillX = if (selectedIndex >= 0) {
                    slot * selectedIndex + (if (selectedIndex >= 2) spacer else 0.dp)
                } else {
                    0.dp
                }
                val animatedX by animateDpAsState(
                    targetValue = pillX,
                    animationSpec = Motion.slide(),
                    label = "pill"
                )
                val pillAlpha by animateFloatAsState(
                    if (selectedIndex >= 0) 1f else 0f, tween(Motion.Medium), label = "pillAlpha"
                )
                Box(
                    Modifier
                        .offset(x = animatedX)
                        .width(slot)
                        .height(60.dp)
                        .graphicsLayer { alpha = pillAlpha }
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(24.dp)
                        )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leftTabs.forEach { tab ->
                        DockItem(tab, selected = currentRoute == tab.route,
                            onClick = { onTab(tab.route) },
                            modifier = Modifier.width(slot))
                    }
                    Spacer(Modifier.width(spacer))
                    rightTabs.forEach { tab ->
                        DockItem(tab, selected = currentRoute == tab.route,
                            onClick = { onTab(tab.route) },
                            modifier = Modifier.width(slot))
                    }
                }
            }
        }
        PulseFab(
            shouldPulse = shouldPulseFab,
            failedCount = failedJobCount,
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 0.dp)
        )
    }
}

/** FAB: градиент бренда, цветное свечение, мягкое «дыхание» и кольцо-пульс. */
@Composable
private fun PulseFab(
    shouldPulse: Boolean,
    failedCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = NutriTheme.colors
    val reduced = LocalReducedMotion.current
    val pulse = shouldPulse && !reduced
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val failed = failedCount > 0
    val glow by animateColorAsState(
        if (failed) c.dangerFrom else c.brandFrom, tween(Motion.Long), label = "fabGlow"
    )

    Box(modifier.size(70.dp), contentAlignment = Alignment.Center) {
        if (pulse) {
            val infinite = rememberInfiniteTransition(label = "fabPulse")
            val ring by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2400, easing = Motion.Standard)),
                label = "ring"
            )
            val breathe by infinite.animateFloat(
                initialValue = 1f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(tween(1200, easing = Motion.Standard), RepeatMode.Reverse),
                label = "breathe"
            )
            Box(
                Modifier
                    .size(62.dp)
                    .graphicsLayer {
                        val s = 1f + 0.45f * ring
                        scaleX = s
                        scaleY = s
                        alpha = 0.5f * (1f - ring)
                    }
                    .border(2.dp, glow, CircleShape)
            )
            FabBody(failed, glow, interaction, breathe, onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            })
        } else {
            FabBody(failed, glow, interaction, 1f, onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            })
        }
        if (failed) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .background(c.dangerTo, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            ) {
                Text(
                    text = failedCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun FabBody(
    failed: Boolean,
    glow: Color,
    interaction: MutableInteractionSource,
    breathe: Float,
    onClick: () -> Unit
) {
    val c = NutriTheme.colors
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = breathe
                scaleY = breathe
            }
            .pressScale(interaction, 0.9f)
            .androidShadow(glow)
            .clip(CircleShape)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                // Есть неудавшиеся анализы — кнопка «+» краснеет, чтобы
                // пользователь заметил и разобрал их на экране добавления.
                .background(if (failed) c.dangerGradient else c.brandGradient)
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = if (failed) "Добавить еду, есть ошибки анализа" else "Добавить еду",
                tint = if (failed) Color.White else c.onBrand,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

private fun Modifier.androidShadow(glow: Color): Modifier =
    this.then(
        Modifier.graphicsLayer {
            shadowElevation = 16.dp.toPx()
            shape = CircleShape
            clip = false
            ambientShadowColor = glow.copy(alpha = 0.6f)
            spotShadowColor = glow.copy(alpha = 0.6f)
        }
    )

@Composable
private fun DockItem(
    tab: DockTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(Motion.Medium),
        label = "dockTint"
    )
    val iconScale by animateFloatAsState(
        if (selected) 1.08f else 1f, Motion.press(), label = "dockIcon"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(24.dp))
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .clickableNoRipple {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    ) {
        Icon(
            if (selected) tab.selectedIcon else tab.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = tint,
            maxLines = 1
        )
    }
}

/**
 * Вкладки дока — с сохранением состояния (как раньше); остальные экраны —
 * обычный push, чтобы «назад» возвращал на экран, откуда пришли.
 */
private fun NavHostController.navigateTo(route: String) {
    if (route in TAB_ROUTES) {
        navigate(route) {
            popUpTo(graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    } else {
        navigate(route) { launchSingleTop = true }
    }
}
