package com.nutrilens.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
                enterTransition = {
                    fadeIn(tween(240)) + slideInHorizontally(tween(240)) { it / 14 }
                },
                exitTransition = {
                    fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 10 }
                },
                popEnterTransition = {
                    fadeIn(tween(240)) + slideInHorizontally(tween(240)) { -it / 14 }
                },
                popExitTransition = {
                    fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 10 }
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

/** Плавающий док: пилюля активного таба «перелетает» spring-анимацией. */
@Composable
private fun FloatingDock(
    currentRoute: String?,
    shouldPulseFab: Boolean,
    onTab: (String) -> Unit,
    onAdd: () -> Unit
) {
    val allTabs = leftTabs + rightTabs
    val selectedIndex = allTabs.indexOfFirst { it.route == currentRoute }

    val infinite = rememberInfiniteTransition(label = "fab")
    val fabScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(1100), RepeatMode.Reverse
        ),
        label = "fabPulse"
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .padding(vertical = 8.dp)
                    .height(56.dp)
            ) {
                val spacer = 68.dp
                val slot = (maxWidth - spacer) / 4
                val pillX = when (selectedIndex) {
                    0, 1 -> slot * selectedIndex
                    2, 3 -> spacer + slot * selectedIndex
                    else -> (-1000).dp
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
                            .height(48.dp)
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
        FloatingActionButton(
            onClick = onAdd,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(8.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(60.dp)
                .offset(y = (-8).dp)
                .scale(if (shouldPulseFab) fabScale else 1f)
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Добавить еду",
                modifier = Modifier.size(26.dp)
            )
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
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
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
