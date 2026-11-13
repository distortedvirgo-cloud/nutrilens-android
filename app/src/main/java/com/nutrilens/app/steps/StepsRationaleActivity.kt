package com.nutrilens.app.steps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nutrilens.app.ui.GlowButton
import com.nutrilens.app.ui.theme.NutriLensTheme

/**
 * Экран «зачем приложению шаги» для Health Connect: его открывает система из окна
 * разрешений (ACTION_SHOW_PERMISSIONS_RATIONALE, а на Android 14+ — через
 * activity-alias с VIEW_PERMISSION_USAGE). Без такого экрана Health Connect
 * может вообще не показать запрос разрешения.
 */
class StepsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NutriLensTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp)
                    ) {
                        Text(
                            "Зачем NutriLens шаги",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "NutriLens читает только количество шагов за сегодня — чтобы показать их на экране «Сегодня» " +
                                "и учесть в оценке дня. Шаги не прибавляют калорий к бюджету и никуда не отправляются: " +
                                "всё хранится на телефоне.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Отключить доступ можно в любой момент в настройках Health Connect — тогда шаги будут считаться датчиком телефона или вводиться вручную.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(24.dp))
                        GlowButton("Понятно", onClick = { finish() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
