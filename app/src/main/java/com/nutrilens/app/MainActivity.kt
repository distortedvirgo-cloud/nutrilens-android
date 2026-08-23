package com.nutrilens.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import com.nutrilens.app.ui.theme.NutriLensTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
                NutriLensAppRoot(initialDate = dateExtra?.takeIf { it.isNotBlank() })
            }
        }
    }
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