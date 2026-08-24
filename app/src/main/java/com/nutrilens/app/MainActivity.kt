package com.nutrilens.app

import android.Manifest
import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.notifications.NotificationHelper
import com.nutrilens.app.ui.NutriLensAppRoot
import com.nutrilens.app.ui.theme.NutriLensTheme
import com.nutrilens.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                NutriLensAppRoot(
                    initialDate = dateExtra?.takeIf { it.isNotBlank() },
                    navigateTo = navigateTo?.takeIf { it == "settings" || it == "dashboard" || it == "add" }
                )
            }
        }

        checkForUpdatesInBackground()
    }

    /**
     * Тихая проверка обновлений при старте. Нашли новую версию — push-уведомление
     * (один раз на версию, чтобы не надоедать при каждом запуске). Нет сети — тихо молчим.
     */
    private fun checkForUpdatesInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = NutriLensDatabase.getInstance(this@MainActivity)
                val repo = SettingsRepository(db.settingsDao()).get()?.updateRepo
                    ?: "distortedvirgo-cloud/nutrilens-android"
                val release = UpdateChecker.checkLatest(repo) ?: return@launch
                if (release.apkUrl.isEmpty()) return@launch
                if (!UpdateChecker.isNewerVersion(release.version, BuildConfig.VERSION_NAME)) {
                    return@launch
                }
                val prefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
                if (prefs.getString("notified", null) == release.version) return@launch
                prefs.edit().putString("notified", release.version).apply()

                NotificationHelper.ensureChannels(this@MainActivity)
                NotificationHelper.post(
                    this@MainActivity,
                    NotificationHelper.CHANNEL_UPDATE,
                    9500,
                    "Доступно обновление NutriLens v${release.version} 🎉",
                    "Новая версия уже опубликована. Откройте Настройки → Обновления, чтобы скачать.",
                    NotificationHelper.mainActivityPendingIntent(
                        this@MainActivity, 9500, navigate = "settings"
                    )
                )
            } catch (_: Exception) {
                // Без сети или GitHub недоступен — пропускаем, пользователь проверит вручную.
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