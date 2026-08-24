package com.nutrilens.app.ui

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.BuildConfig
import com.nutrilens.app.bg.ReminderSync
import com.nutrilens.app.data.Backup
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.notifications.NotificationHelper
import com.nutrilens.app.update.ReleaseInfo
import com.nutrilens.app.update.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class Available(val release: ReleaseInfo) : UpdateUiState()
    data class Downloading(val percent: Int) : UpdateUiState()
    data class Done(val message: String) : UpdateUiState()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NutriLensDatabase.getInstance(application)
    private val settingsRepository = SettingsRepository(database.settingsDao())
    private val appContext = getApplication<Application>().applicationContext

    val settings: StateFlow<SettingsEntity> = settingsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _dataMessage = MutableStateFlow<String?>(null)
    val dataMessage: StateFlow<String?> = _dataMessage.asStateFlow()

    // ---- Резервные копии ----
    suspend fun buildExport(): String = Backup.exportJson(appContext, database)

    fun reportExportResult(ok: Boolean) {
        _dataMessage.value = if (ok) "Резервная копия сохранена ✅"
        else "Не удалось сохранить файл"
    }

    fun importFrom(json: String) {
        viewModelScope.launch {
            try {
                Backup.importJson(appContext, database, json)
                _dataMessage.value = "Данные импортированы ✅"
            } catch (e: Exception) {
                _dataMessage.value = "Ошибка импорта: ${e.message?.take(120)}"
            }
        }
    }

    // ---- ИИ ----
    fun setApiKey(value: String) = update { it.copy(apiKey = value) }

    // ---- NanoGPT ----
    fun setNanoApiKey(value: String) = update { it.copy(nanoApiKey = value) }
    fun setNanoApiEndpoint(value: String) = update { it.copy(nanoApiEndpoint = value) }
    fun setAnalysisMode(value: String) = update { it.copy(analysisMode = value) }

    // ---- Цели ----
    fun setDailyGoal(value: Double) = update { it.copy(dailyGoal = value) }
    fun setProteinGoal(value: Double?) = update { it.copy(proteinGoal = value) }
    fun setFatGoal(value: Double?) = update { it.copy(fatGoal = value) }
    fun setCarbsGoal(value: Double?) = update { it.copy(carbsGoal = value) }

    // ---- Профиль ----
    fun setUserContext(value: String) = update { it.copy(userContext = value) }

    // ---- Напоминания ----
    fun setBreakfastReminderEnabled(value: Boolean) = update { it.copy(breakfastReminderEnabled = value) }
    fun setLunchReminderEnabled(value: Boolean) = update { it.copy(lunchReminderEnabled = value) }
    fun setDinnerReminderEnabled(value: Boolean) = update { it.copy(dinnerReminderEnabled = value) }
    fun setWaterReminderEnabled(value: Boolean) = update { it.copy(waterReminderEnabled = value) }
    fun setWeighInReminderEnabled(value: Boolean) = update { it.copy(weighInReminderEnabled = value) }

    fun setReminderTime(kind: ReminderKind, time: String) {
        update {
            when (kind) {
                ReminderKind.BREAKFAST -> it.copy(breakfastTime = time)
                ReminderKind.LUNCH -> it.copy(lunchTime = time)
                ReminderKind.DINNER -> it.copy(dinnerTime = time)
            }
        }
    }

    fun setWaterIntervalMinutes(value: Int) = update { it.copy(waterIntervalMinutes = value) }

    fun setUpdateRepo(value: String) = update { it.copy(updateRepo = value) }

    fun checkUpdates() {
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Checking
            try {
                val repo = settingsRepository.get().updateRepo
                val release = UpdateChecker.checkLatest(repo)
                _updateState.value = when {
                    release == null || release.apkUrl.isEmpty() ->
                        UpdateUiState.Done("Релизы в этом репозитории пока не опубликованы")
                    UpdateChecker.isNewerVersion(release.version, BuildConfig.VERSION_NAME) ->
                        UpdateUiState.Available(release)
                    else ->
                        UpdateUiState.Done("У вас последняя версия (${BuildConfig.VERSION_NAME})")
                }
            } catch (e: Exception) {
                _updateState.value =
                    UpdateUiState.Done("Ошибка проверки обновлений: ${e.message?.take(80)}")
            }
        }
    }

    fun downloadAndInstall() {
        val release = (_updateState.value as? UpdateUiState.Available)?.release ?: return
        viewModelScope.launch {
            try {
                if (!UpdateChecker.canInstall(appContext)) {
                    UpdateChecker.openInstallUnknownAppsSettings(appContext)
                    _updateState.value = UpdateUiState.Done(
                        "Разрешите установку из этого источника и нажмите кнопку ещё раз"
                    )
                    return@launch
                }
                _updateState.value = UpdateUiState.Downloading(0)
                val file = UpdateChecker.downloadApk(appContext, release.apkUrl) { percent ->
                    _updateState.value = UpdateUiState.Downloading(percent)
                }
                UpdateChecker.installApk(appContext, file)
                _updateState.value = UpdateUiState.Idle
            } catch (e: Exception) {
                _updateState.value =
                    UpdateUiState.Done("Ошибка загрузки: ${e.message?.take(80)}")
            }
        }
    }

    fun testNotification() {
        viewModelScope.launch {
            NotificationHelper.ensureChannels(appContext)
            NotificationHelper.postSample(appContext, "NutriLens", "Так выглядят ваши напоминания")
        }
    }

    /** Сохраняет настройки и синхронизирует напоминания после каждого изменения. */
    private fun update(transform: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch {
            settingsRepository.update(transform)
            ReminderSync.sync(appContext)
        }
    }
}

enum class ReminderKind { BREAKFAST, LUNCH, DINNER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val dataMessage by viewModel.dataMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var timePickerFor by remember { mutableStateOf<ReminderKind?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = runCatching {
                    val json = viewModel.buildExport()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("нет доступа к файлу")
                }.isSuccess
                viewModel.reportExportResult(ok)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().decodeToString()
                    }
                }.getOrNull()
                if (json == null) {
                    viewModel.reportExportResult(false)
                } else {
                    viewModel.importFrom(json)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenHeader(title = "Настройки")

        // ---- ИИ ----
        FreshCard(modifier = Modifier.fillMaxWidth().staggeredIn(0)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("ИИ", style = MaterialTheme.typography.titleMedium)
                SettingsTextField(
                    initial = settings.apiKey,
                    label = "Ключ Gemini",
                    onCommit = viewModel::setApiKey,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password
                )
                Text(
                    text = "Хранится только на этом устройстве",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    "Режим анализа",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                SettingsModeRow(
                    selected = settings.analysisMode,
                    onSelect = viewModel::setAnalysisMode
                )
                SettingsTextField(
                    initial = settings.nanoApiKey,
                    label = "Ключ NanoGPT (для Простой/Продвинутый)",
                    onCommit = viewModel::setNanoApiKey,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password
                )
                SettingsTextField(
                    initial = settings.nanoApiEndpoint,
                    label = "Адрес NanoGPT (необязательно)",
                    onCommit = viewModel::setNanoApiEndpoint
                )
                Text(
                    text = "«Бесплатно» — ваш ключ Gemini (при сбое — NanoGPT, если ключ задан). «Простой» и «Продвинутый» идут через nano-gpt.com; в «Продвинутом» сложные фото эскалируются на thinking-модель.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Цели ----
        FreshCard(modifier = Modifier.fillMaxWidth().staggeredIn(1)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Цели", style = MaterialTheme.typography.titleMedium)
                SettingsNumberField(
                    initial = formatNumber(settings.dailyGoal),
                    label = "Дневная норма калорий, ккал",
                    onValid = viewModel::setDailyGoal,
                    onEmpty = null
                )
                SettingsNumberField(
                    initial = settings.proteinGoal?.let(::formatNumber).orEmpty(),
                    label = "Белки, г (пусто — автоматически)",
                    onValid = viewModel::setProteinGoal,
                    onEmpty = { viewModel.setProteinGoal(null) }
                )
                SettingsNumberField(
                    initial = settings.fatGoal?.let(::formatNumber).orEmpty(),
                    label = "Жиры, г (пусто — автоматически)",
                    onValid = viewModel::setFatGoal,
                    onEmpty = { viewModel.setFatGoal(null) }
                )
                SettingsNumberField(
                    initial = settings.carbsGoal?.let(::formatNumber).orEmpty(),
                    label = "Углеводы, г (пусто — автоматически)",
                    onValid = viewModel::setCarbsGoal,
                    onEmpty = { viewModel.setCarbsGoal(null) }
                )
            }
        }

        // ---- Профиль ----
        FreshCard(modifier = Modifier.fillMaxWidth().staggeredIn(2)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Профиль", style = MaterialTheme.typography.titleMedium)
                SettingsTextField(
                    initial = settings.userContext,
                    label = "О вас (контекст для ИИ)",
                    onCommit = viewModel::setUserContext,
                    minLines = 3
                )
            }
        }

        // ---- Напоминания ----
        FreshCard(modifier = Modifier.fillMaxWidth().staggeredIn(3)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Напоминания", style = MaterialTheme.typography.titleMedium)

                ReminderTimeRow(
                    label = "Завтрак",
                    enabled = settings.breakfastReminderEnabled,
                    time = settings.breakfastTime,
                    onEnabledChange = viewModel::setBreakfastReminderEnabled,
                    onTimeClick = { timePickerFor = ReminderKind.BREAKFAST }
                )
                ReminderTimeRow(
                    label = "Обед",
                    enabled = settings.lunchReminderEnabled,
                    time = settings.lunchTime,
                    onEnabledChange = viewModel::setLunchReminderEnabled,
                    onTimeClick = { timePickerFor = ReminderKind.LUNCH }
                )
                ReminderTimeRow(
                    label = "Ужин",
                    enabled = settings.dinnerReminderEnabled,
                    time = settings.dinnerTime,
                    onEnabledChange = viewModel::setDinnerReminderEnabled,
                    onTimeClick = { timePickerFor = ReminderKind.DINNER }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Вода", modifier = Modifier.weight(1f))
                    SettingsNumberField(
                        initial = settings.waterIntervalMinutes.toString(),
                        label = null,
                        onValid = { viewModel.setWaterIntervalMinutes(it.toInt()) },
                        onEmpty = null,
                        compact = true
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.waterReminderEnabled,
                        onCheckedChange = viewModel::setWaterReminderEnabled
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Взвешивание")
                        Text(
                            text = "Напоминание раз в 7 дней",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.weighInReminderEnabled,
                        onCheckedChange = viewModel::setWeighInReminderEnabled
                    )
                }
            }
        }

        FreshCard(modifier = Modifier.fillMaxWidth().staggeredIn(4)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Обновления",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Приложение само скачивает новые версии с GitHub",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                SettingsTextField(
                    initial = settings.updateRepo,
                    label = "Репозиторий (владелец/имя)",
                    onCommit = viewModel::setUpdateRepo
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::checkUpdates,
                    enabled = updateState !is UpdateUiState.Checking &&
                        updateState !is UpdateUiState.Downloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (updateState is UpdateUiState.Checking) "Проверяем…"
                        else "Проверить обновления"
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (val u = updateState) {
                    is UpdateUiState.Available -> {
                        Text(
                            "Доступна версия ${u.release.version} 🎉",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (u.release.notes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                u.release.notes.take(300),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = viewModel::downloadAndInstall,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Скачать и установить") }
                    }
                    is UpdateUiState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { (u.percent.coerceAtLeast(0)) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (u.percent >= 0) "Загрузка: ${u.percent}%" else "Загрузка…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateUiState.Done -> {
                        Text(
                            u.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {}
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Текущая версия: ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Данные ----
        FreshCard(modifier = Modifier.fillMaxWidth().staggeredIn(5)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Данные", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Резервная копия в JSON: дневник, вес, вода, настройки, избранное",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            exportLauncher.launch("nutrilens_backup_${LocalDate.now()}.json")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Экспорт") }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Импорт") }
                }
                dataMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Button(onClick = viewModel::testNotification, modifier = Modifier.fillMaxWidth()) {
            Text("🔔 Тестовое уведомление")
        }

        Spacer(Modifier.height(24.dp))
    }

    // Диалог выбора времени.
    timePickerFor?.let { kind ->
        val current = when (kind) {
            ReminderKind.BREAKFAST -> settings.breakfastTime
            ReminderKind.LUNCH -> settings.lunchTime
            ReminderKind.DINNER -> settings.dinnerTime
        }
        val state = rememberTimePickerState(
            initialHour = current.substringBefore(":").toIntOrNull() ?: 0,
            initialMinute = current.substringAfter(":", "").toIntOrNull() ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { timePickerFor = null },
            title = { Text("Выберите время") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val time = "%02d:%02d".format(state.hour, state.minute)
                        viewModel.setReminderTime(kind, time)
                        timePickerFor = null
                    }
                ) { Text("ОК") }
            },
            dismissButton = {
                TextButton(onClick = { timePickerFor = null }) { Text("Отмена") }
            }
        )
    }
}

// ---------- Готовые поля ----------

@Composable
private fun ReminderTimeRow(
    label: String,
    enabled: Boolean,
    time: String,
    onEnabledChange: (Boolean) -> Unit,
    onTimeClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onTimeClick, enabled = enabled) { Text(time) }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun SettingsTextField(
    initial: String,
    label: String,
    onCommit: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1
) {
    var text by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(initial) {
        if (!focused) text = initial
    }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            onCommit(new)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusEvent { focused = it.isFocused },
        label = { Text(label) },
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        minLines = minLines
    )
}

/**
 * Числовое поле настроек. Сохраняет значение при валидном вводе; при пустоте
 * вызывает [onEmpty] (для целей макросов — авто-расчёт, null), невалидный ввод
 * не сохраняется и остаётся в поле для исправления.
 */
@Composable
private fun SettingsNumberField(
    initial: String,
    label: String?,
    onValid: (Double) -> Unit,
    onEmpty: (() -> Unit)?,
    compact: Boolean = false
) {
    var text by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(initial) {
        if (!focused) text = initial
    }
    val modifier = if (compact) {
        Modifier.width(72.dp).onFocusEvent { focused = it.isFocused }
    } else {
        Modifier.fillMaxWidth().onFocusEvent { focused = it.isFocused }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            val normalized = new.trim().replace(",", ".")
            when {
                normalized.isEmpty() -> onEmpty?.invoke()
                normalized.toDoubleOrNull() != null -> onValid(normalized.toDouble())
                else -> Unit // невалидный ввод — просто держим в поле, не сохраняем
            }
        },
        modifier = modifier,
        label = label?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
/** Переключатель режима анализа: три варианта, как в веб-версии. */
@Composable
private fun SettingsModeRow(selected: String, onSelect: (String) -> Unit) {
    val modes = listOf(
        "free" to "Бесплатно",
        "simple" to "Простой",
        "advanced" to "Продвинутый"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { (key, label) ->
            val isSelected = selected == key
            if (isSelected) {
                Button(onClick = { onSelect(key) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { onSelect(key) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            }
        }
    }
}
