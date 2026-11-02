package com.nutrilens.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.nutrilens.app.ui.NutriLensAppRoot
import com.nutrilens.app.ui.Onboarding
import com.nutrilens.app.ui.OnboardingScreen
import com.nutrilens.app.ui.theme.NutriLensTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge на всех версиях (на Android 15 при targetSdk 35 он и так
        // принудительный): фон-«аврора» уходит под системные бары, отступы
        // считаются в Compose через WindowInsets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // "navigate" = "dashboard" — дашборд, который и так является стартовой
        // точкой навигации; также приходит необязательный "date" ("yyyy-MM-dd").
        val navigateTo = intent.getStringExtra("navigate")
        val dateExtra = intent.getStringExtra("date")

        setContent {
            NutriLensTheme {
                if (Build.VERSION.SDK_INT >= 33) {
                    RequestNotificationPermissionOnce()
                }
                // Первый запуск — онбординг (данные о себе + ключ ИИ), затем основное приложение.
                val context = LocalContext.current
                var onboarding by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) { onboarding = Onboarding.needed(context) }
                when (onboarding) {
                    null -> Unit
                    true -> OnboardingScreen(onFinish = { onboarding = false })
                    false -> NutriLensAppRoot(
                        initialDate = dateExtra?.takeIf { it.isNotBlank() },
                        navigateTo = navigateTo?.takeIf { it in DEEP_LINK_ROUTES }
                    )
                }
            }
        }

    // Свой день в лидерборд — один пуш при старте приложения (fire-and-forget).
    com.nutrilens.app.leaderboard.LeaderboardSync.pushAsync(this)
}

/** Маршруты, на которые разрешено вести по уведомлениям (dashboard=settings/add + инструменты «Ещё» + лидерборд). */
private val DEEP_LINK_ROUTES = setOf(
    "settings", "settings/updates", "dashboard", "add",
    "chat", "ideas", "fridge", "menu", "grocery", "waterTool", "habitTool",
    "leaderboard", "coach", "profile", "report", "add/camera"
)

}

/**
 * Запрашивает POST_NOTIFICATIONS один раз при старте (Android 13+),
 * если разрешение ещё не выдано. Повторного запроса не будет — только первый вход.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}