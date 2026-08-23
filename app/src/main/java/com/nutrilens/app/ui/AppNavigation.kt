package com.nutrilens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class DockTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val leftTabs = listOf(
    DockTab(route = "dashboard", label = "Дневник", icon = Icons.Filled.Home)
)

private val rightTabs = listOf(
    DockTab(route = "report", label = "Отчёт", icon = Icons.Filled.BarChart),
    DockTab(route = "settings", label = "Ещё", icon = Icons.Filled.Settings)
)

/**
 * Корневой композабл: плавающий нижний док с центральной кнопкой
 * добавления еды + NavHost.
 *
 * @param initialDate начальная выбранная дата дашборда в формате "yyyy-MM-dd"
 *                    (null — сегодня); передаёт MainActivity из extra "date".
 */
@Composable
fun NutriLensAppRoot(initialDate: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            FloatingDock(
                currentRoute = currentRoute,
                onTab = { route -> navController.navigateDock(route) },
                onAdd = { navController.navigateDock("add") }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                DashboardScreen(initialDate = initialDate)
            }
            composable("report") {
                ReportScreen()
            }
            composable("add") {
                AddMealScreen(
                    snackbarHostState = snackbarHostState,
                    onDone = { navController.navigateDock("dashboard") },
                    onGoSettings = { navController.navigateDock("settings") }
                )
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}

/** Плавающий док: скруглённая панель с вкладками и FAB посередине. */
@Composable
private fun FloatingDock(
    currentRoute: String?,
    onTab: (String) -> Unit,
    onAdd: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 0.dp)
            .padding(bottom = 14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 10.dp, top = 10.dp)
                    .height(56.dp)
            ) {
                leftTabs.forEach { tab ->
                    DockItem(
                        tab = tab,
                        selected = currentRoute == tab.route,
                        onClick = { onTab(tab.route) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.width(72.dp))
                rightTabs.forEach { tab ->
                    DockItem(
                        tab = tab,
                        selected = currentRoute == tab.route,
                        onClick = { onTab(tab.route) },
                        modifier = Modifier.weight(1f)
                    )
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
                .size(58.dp)
                .offset(y = (-6).dp)
        ) {
            Icon(
                if (currentRoute == "add") Icons.Filled.Add else Icons.Filled.Add,
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
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                shape = RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
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
