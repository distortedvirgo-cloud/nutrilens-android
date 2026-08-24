package com.nutrilens.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.ui.theme.NutriGlowBackground
import java.time.LocalDate

private data class DockTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val leftTabs = listOf(
    DockTab(route = "dashboard", label = "Дневник", icon = Icons.Filled.Home),
    DockTab(route = "report", label = "Отчёт", icon = Icons.Filled.BarChart)
)

private val rightTabs = listOf(
    DockTab(route = "assistant", label = "Ассистент", icon = Icons.Filled.SelfImprovement),
    DockTab(route = "hub", label = "Ещё", icon = Icons.Filled.SpaceDashboard)
)

/** Кривая появления из веба: cubic-bezier(0.22, 1, 0.36, 1). */
private val FreshEasing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Корневой композабл: плавающий док с перелетающей пилюлей, пульсирующим FAB
 * и NavHost с анимированными переходами (как fresh-оболочка веб-версии).
 */
@Composable
fun NutriLensAppRoot(initialDate: String? = null, navigateTo: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }
    val navigate: (String) -> Unit = { route -> navController.navigateDock(route) }

    // Глубокие ссылки из уведомлений: "settings" — сразу на экран настроек обновлений.
    LaunchedEffect(navigateTo) {
        if (navigateTo != null && navigateTo != "dashboard") {
            navigate(navigateTo)
        }
    }

    // FAB «дышит», пока сегодня нет ни одной записи — мягкий призыв добавить еду.
    val appContext = LocalContext.current.applicationContext
    var hasMealsToday by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val today = LocalDate.now().toString()
        hasMealsToday = NutriLensDatabase.getInstance(appContext)
            .mealDao()
            .mealsBetween(today, today)
            .isNotEmpty()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute != "add") {
                FloatingDock(
                    currentRoute = currentRoute,
                    shouldPulseFab = !hasMealsToday,
                    onTab = navigate,
                    onAdd = { navigate("add") }
                )
            }
        }
    ) { innerPadding ->
        NutriGlowBackground {
            NavHost(
                navController = navController,
                startDestination = "dashboard",
                modifier = Modifier.padding(innerPadding),
                // Мягкий «fresh-in» как в вебе: fade + лёгкий подъём снизу на decelerate,
                // уход — плавный fade (вызываем после анализа: оверлей уже растворился).
                enterTransition = {
                    fadeIn(tween(380, easing = FreshEasing)) +
                        slideInVertically(tween(380, easing = FreshEasing)) { it / 14 }
                },
                exitTransition = {
                    fadeOut(tween(240))
                },
                popEnterTransition = {
                    fadeIn(tween(380, easing = FreshEasing)) +
                        slideInVertically(tween(380, easing = FreshEasing)) { it / 14 }
                },
                popExitTransition = {
                    fadeOut(tween(240))
                }
            ) {
                composable("dashboard") {
                    DashboardScreen(
                        initialDate = initialDate,
                        snackbarHostState = snackbarHostState
                    )
                }
                composable("report") { ReportScreen() }
                composable("assistant") { AssistantScreen(onNavigate = navigate) }
                composable("hub") { HubScreen(onNavigate = navigate) }
                composable("add") {
                    AddMealScreen(
                        snackbarHostState = snackbarHostState,
                        onDone = { navigate("dashboard") },
                        onGoSettings = { navigate("settings") },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("settings") { SettingsScreen() }
                composable("chat") { ChatScreen(onBack = { navController.popBackStack() }) }
                composable("ideas") { IdeasScreen(onBack = { navController.popBackStack() }) }
                composable("fridge") { FridgeScreen(onBack = { navController.popBackStack() }) }
                composable("menu") { MenuScreen(onBack = { navController.popBackStack() }) }
                composable("habitTool") { HabitToolScreen(onBack = { navController.popBackStack() }) }
                composable("waterTool") { WaterToolScreen(onBack = { navController.popBackStack() }) }
                composable("grocery") { GroceryScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}

/** Плавающий док из веба: rounded-[26px], surface/95, border line/60, lift-тень. */
@Composable
private fun FloatingDock(
    currentRoute: String?,
    shouldPulseFab: Boolean,
    onTab: (String) -> Unit,
    onAdd: () -> Unit
) {
    val allTabs = leftTabs + rightTabs
    val selectedIndex = allTabs.indexOfFirst { it.route == currentRoute }
    val dockShape = RoundedCornerShape(26.dp)

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
    ) {
        Surface(
            shape = dockShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .shadow(18.dp, dockShape, ambientColor = Color(0x290F172A), spotColor = Color(0x290F172A))
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
                    .height(58.dp)
            ) {
                val spacer = 68.dp
                val slot = (maxWidth - spacer) / 4
                val pillX = if (selectedIndex >= 0) {
                    slot * selectedIndex + (if (selectedIndex >= 2) spacer else 0.dp)
                } else {
                    (-1000).dp
                }
                val animatedX by animateDpAsState(
                    targetValue = pillX,
                    animationSpec = spring(stiffness = 420f, dampingRatio = 0.8f),
                    label = "pill"
                )
                if (selectedIndex >= 0) {
                    Box(
                        Modifier
                            .offset(x = animatedX)
                            .width(slot - 6.dp)
                            .height(50.dp)
                            .padding(2.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(18.dp)
                            )
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                ) {
                    leftTabs.forEachIndexed { index, tab ->
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
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-10).dp)
        )
    }
}

/** FAB из веба: градиент accent→strong, glow-тень, пульс-кольцо pulse-glow. */
@Composable
private fun PulseFab(
    shouldPulse: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "fabPulse")
    val ringAlpha by infinite.animateFloat(
        initialValue = 0.40f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2200)),
        label = "ringAlpha"
    )
    val ringScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(tween(2200)),
        label = "ringScale"
    )
    val bobScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "bobScale"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "fabPress")
    val crane = if (shouldPulse) bobScale else 1f
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)

    Box(modifier.size(64.dp), contentAlignment = Alignment.Center) {
        if (shouldPulse) {
            Box(
                Modifier
                    .size(64.dp)
                    .scale(ringScale)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = ringAlpha), CircleShape)
            )
        }
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier
                .size(58.dp)
                .scale(crane * pressScale)
                .shadow(12.dp, CircleShape, ambientColor = glow, spotColor = glow)
                .clip(CircleShape)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    )
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Добавить еду",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    tab: DockTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(
            tab.icon,
            contentDescription = tab.label,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
    }
}

private fun NavHostController.navigateDock(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
