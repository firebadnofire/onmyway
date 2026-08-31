package org.archuser.onmyway

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.archuser.onmyway.data.HistoryEntity
import org.archuser.onmyway.data.summary
import org.archuser.onmyway.domain.DistanceUnit
import org.archuser.onmyway.domain.NotificationEvent
import org.archuser.onmyway.domain.ScanMode
import org.archuser.onmyway.domain.TriggerType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val firstInstallPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        maybeOpenBackgroundLocationSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val colors = when {
                settings.materialYouEnabled && Build.VERSION.SDK_INT >= 31 && settings.darkThemeEnabled -> dynamicDarkColorScheme(context)
                settings.materialYouEnabled && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
                settings.darkThemeEnabled -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize()) { OnMyWayApp(viewModel) } }
        }
        if (!getPreferences(MODE_PRIVATE).getBoolean(PERMISSION_PROMPT_SHOWN, false)) {
            getPreferences(MODE_PRIVATE).edit { putBoolean(PERMISSION_PROMPT_SHOWN, true) }
            window.decorView.post {
                val permissions = viewModel.permissions.initialInstallPermissions()
                if (permissions.isNotEmpty()) firstInstallPermissionLauncher.launch(permissions)
                else maybeOpenBackgroundLocationSettings()
            }
        }
    }

    private fun maybeOpenBackgroundLocationSettings() {
        if (
            Build.VERSION.SDK_INT >= 30 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }

    companion object { private const val PERMISSION_PROMPT_SHOWN = "permission_prompt_shown" }
}

private enum class Screen { HOME, EDITOR, HISTORY, DIAGNOSTICS }

@Composable
private fun OnMyWayApp(viewModel: AppViewModel) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var editingId by remember { mutableLongStateOf(0L) }
    when (screen) {
        Screen.HOME -> HomeScreen(viewModel, { editingId = it; screen = Screen.EDITOR }, { screen = Screen.HISTORY }, { screen = Screen.DIAGNOSTICS })
        Screen.EDITOR -> EditorScreen(viewModel, editingId) { screen = Screen.HOME }
        Screen.HISTORY -> HistoryScreen(viewModel) { screen = Screen.HOME }
        Screen.DIAGNOSTICS -> DiagnosticsScreen(viewModel) { screen = Screen.HOME }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(viewModel: AppViewModel, edit: (Long) -> Unit, history: () -> Unit, diagnostics: () -> Unit) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val status by viewModel.monitoringStatus.collectAsState()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val globalSoundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectSound(uri) { selected, error ->
            if (selected != null) viewModel.setGlobalSoundUri(selected)
            scope.launch { snackbar.showSnackbar(error ?: "Custom notification sound selected") }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportBackup(uri) { error -> scope.launch { snackbar.showSnackbar(error ?: "Backup exported") } }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                settings = settings,
                close = { scope.launch { drawerState.close() } },
                history = history,
                diagnostics = diagnostics,
                setMaterialYou = viewModel::setMaterialYou,
                setDarkTheme = viewModel::setDarkTheme,
                setCustomSound = viewModel::setGlobalCustomSoundEnabled,
                pickSound = { globalSoundPicker.launch(arrayOf("audio/*")) },
                export = { exportPicker.launch("onmyway-backup.json") },
                import = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) },
            )
        },
    ) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("OnMyWay") }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", style = MaterialTheme.typography.headlineMedium) } }) },
            floatingActionButton = { FloatingActionButton(onClick = { edit(0) }) { Text("+") } },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Card(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) } }
                val enabled = events.filter { it.enabled }
                val disabled = events.filterNot { it.enabled }
                if (enabled.isNotEmpty()) item { Text("Enabled", style = MaterialTheme.typography.titleLarge) }
                items(enabled, key = { it.id }) { EventCard(it, viewModel, edit) }
                if (disabled.isNotEmpty()) item { Text("Disabled", style = MaterialTheme.typography.titleLarge) }
                items(disabled, key = { it.id }) { EventCard(it, viewModel, edit) }
                if (events.isEmpty()) item { Text("Create a reminder tied to movement, Wi-Fi, or a place.") }
            }
        }
    }
    if (pendingImport != null) AlertDialog(
        onDismissRequest = { pendingImport = null },
        title = { Text("Replace all OnMyWay data?") },
        text = { Text("Import replaces every reminder, trigger state, history entry, and global setting. The file is validated before current data is changed.") },
        confirmButton = { Button(onClick = {
            val uri = pendingImport ?: return@Button
            pendingImport = null
            viewModel.importBackup(uri) { error -> scope.launch { snackbar.showSnackbar(error ?: "Backup imported") } }
        }) { Text("Import") } },
        dismissButton = { OutlinedButton(onClick = { pendingImport = null }) { Text("Cancel") } },
    )
}

@Composable
private fun AppDrawer(
    settings: org.archuser.onmyway.data.AppSettings,
    close: () -> Unit,
    history: () -> Unit,
    diagnostics: () -> Unit,
    setMaterialYou: (Boolean) -> Unit,
    setDarkTheme: (Boolean) -> Unit,
    setCustomSound: (Boolean) -> Unit,
    pickSound: () -> Unit,
    export: () -> Unit,
    import: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("OnMyWay", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
            NavigationDrawerItem(label = { Text("Reminders") }, selected = true, onClick = close)
            NavigationDrawerItem(label = { Text("History") }, selected = false, onClick = { close(); history() })
            NavigationDrawerItem(label = { Text("Diagnostics") }, selected = false, onClick = { close(); diagnostics() })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Appearance", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            SwitchRow("Material You", settings.materialYouEnabled, setMaterialYou)
            SwitchRow("Dark theme", settings.darkThemeEnabled, setDarkTheme)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Notifications", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            SwitchRow("Custom notification sound", settings.customSoundEnabled, setCustomSound)
            if (settings.customSoundEnabled) {
                TextButton(onClick = pickSound, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(if (settings.customSoundUri == null) "Choose audio file" else "Change audio file")
                }
                settings.customSoundUri?.let { Text(it.toUri().lastPathSegment ?: "Selected audio", Modifier.padding(horizontal = 24.dp)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Backup", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = export, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Export all data") }
            TextButton(onClick = import, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Import all data") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = changed)
    }
}

@Composable
private fun EventCard(event: NotificationEvent, viewModel: AppViewModel, edit: (Long) -> Unit) {
    val issue = viewModel.permissions.issueFor(event.config.type)
    Card(Modifier.fillMaxWidth().clickable { edit(event.id) }) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(event.name.ifBlank { event.notificationBody }, style = MaterialTheme.typography.titleMedium)
                Text(event.config.summary())
                if (event.oneTime) Text(if (event.enabled) "One time" else "Triggered once")
                if (issue != null) Text(issue, color = MaterialTheme.colorScheme.error)
            }
            Switch(checked = event.enabled, onCheckedChange = { viewModel.setEnabled(event.id, it) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(viewModel: AppViewModel, id: Long, close: () -> Unit) {
    var draft by remember { mutableStateOf(EventDraft(id = id)) }
    var loaded by remember { mutableStateOf(id == 0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectSound(uri) { selected, pickerError ->
            if (selected != null) draft = draft.copy(customSoundEnabled = true, customSoundUri = selected)
            error = pickerError
        }
    }
    LaunchedEffect(id) { if (id != 0L) { draft = viewModel.draft(id); loaded = true } }
    if (!loaded) return
    Scaffold(topBar = { TopAppBar(title = { Text(if (id == 0L) "New reminder" else "Edit reminder") }, navigationIcon = { Button(onClick = close) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TriggerPicker(draft.type) { draft = draft.copy(type = it, textValue = "", number1 = "25", number2 = "", number3 = "150", unit = DistanceUnit.FEET, scanMode = ScanMode.BALANCED, includeElevation = false, invert = false) } }
            item { OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Event name (optional)") }, modifier = Modifier.fillMaxWidth()) }
            when (draft.type) {
                TriggerType.CONNECTED_SSID, TriggerType.NEARBY_SSID -> item { OutlinedTextField(draft.textValue, { draft = draft.copy(textValue = it) }, label = { Text("Wi-Fi name (SSID)") }, modifier = Modifier.fillMaxWidth()) }
                TriggerType.CONNECTED_BSSID, TriggerType.NEARBY_BSSID -> item { OutlinedTextField(draft.textValue, { draft = draft.copy(textValue = it) }, label = { Text("Access point (BSSID)") }, modifier = Modifier.fillMaxWidth()) }
                TriggerType.GPS_CIRCLE -> {
                    item { OutlinedTextField(draft.number1, { draft = draft.copy(number1 = it) }, label = { Text("Latitude") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(draft.number2, { draft = draft.copy(number2 = it) }, label = { Text("Longitude") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(draft.number3, { draft = draft.copy(number3 = it) }, label = { Text("Radius (meters)") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedButton(onClick = { viewModel.currentLocation()?.let { draft = draft.copy(number1 = it.first.toString(), number2 = it.second.toString()) } ?: run { error = "Grant location permission and wait for a location fix" } }) { Text("Use current location") } }
                }
                TriggerType.DISTANCE_TRAVELED -> {
                    item { OutlinedTextField(draft.number1, { draft = draft.copy(number1 = it) }, label = { Text("Distance") }, modifier = Modifier.fillMaxWidth()) }
                    item { ChoiceRow("Units", DistanceUnit.entries, draft.unit) { draft = draft.copy(unit = it) } }
                    item { CheckRow("Include elevation (altitude can be noisy)", draft.includeElevation) { draft = draft.copy(includeElevation = it) } }
                }
            }
            if (draft.type == TriggerType.CONNECTED_SSID || draft.type == TriggerType.CONNECTED_BSSID) {
                item { OutlinedButton(onClick = { viewModel.currentWifi { current -> val value = if (draft.type == TriggerType.CONNECTED_SSID) current.first else current.second; if (value.isNullOrBlank()) error = "Current Wi-Fi identity is unavailable; grant permission or enter it manually" else draft = draft.copy(textValue = value) } }) { Text("Use current Wi-Fi") } }
            }
            if (draft.type == TriggerType.NEARBY_SSID || draft.type == TriggerType.NEARBY_BSSID) {
                item { ChoiceRow("Best-effort scan mode", ScanMode.entries, draft.scanMode) { draft = draft.copy(scanMode = it) } }
                item { Text("Android may scan less often than requested. Failed or cached scans do not rearm reminders.") }
            }
            if (draft.type != TriggerType.DISTANCE_TRAVELED) {
                item { CheckRow("Invert: trigger when leaving this Wi-Fi or GPS region", draft.invert) { draft = draft.copy(invert = it) } }
            }
            item { Text("Battery use: ${when (draft.type) { TriggerType.DISTANCE_TRAVELED -> "Higher"; TriggerType.GPS_CIRCLE, TriggerType.NEARBY_SSID, TriggerType.NEARBY_BSSID -> "Moderate"; else -> "Very low" }}") }
            item { OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text("Notification title (optional)") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(draft.body, { draft = draft.copy(body = it) }, label = { Text("Notification text") }, modifier = Modifier.fillMaxWidth()) }
            item { SwitchRow("Use a custom sound for this reminder", draft.customSoundEnabled) { draft = draft.copy(customSoundEnabled = it) } }
            if (draft.customSoundEnabled) {
                item {
                    OutlinedButton(onClick = { soundPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (draft.customSoundUri == null) "Choose audio file" else "Change audio file")
                    }
                }
                draft.customSoundUri?.let { value -> item { Text("Selected: ${value.toUri().lastPathSegment ?: "audio file"}") } }
                item { Text("This overrides the default sound in the hamburger menu.") }
            }
            item { CheckRow("One time", draft.oneTime) { draft = draft.copy(oneTime = it) } }
            if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            item { Button(onClick = { viewModel.save(draft) { message -> error = message; if (message == null) close() } }, modifier = Modifier.fillMaxWidth()) { Text("Save") } }
            if (id != 0L) item { OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete event") } }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this event?") }, text = { Text("This permanently removes the rule. Trigger history is retained.") }, confirmButton = { Button(onClick = { viewModel.delete(id, close) }) { Text("Delete") } }, dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun TriggerPicker(selected: TriggerType, changed: (TriggerType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column { Text("Trigger"); OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected.displayName) }; DropdownMenu(expanded, { expanded = false }) { TriggerType.entries.forEach { DropdownMenuItem(text = { Text(it.displayName) }, onClick = { changed(it); expanded = false }) } } }
}

@Composable
private fun <T : Enum<T>> ChoiceRow(label: String, values: List<T>, selected: T, changed: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                OutlinedButton(
                    onClick = { changed(value) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (value == selected) "✓ ${value.name.replace('_', ' ').lowercase()}"
                        else value.name.replace('_', ' ').lowercase(),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable private fun CheckRow(label: String, checked: Boolean, changed: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth()) { Checkbox(checked, changed); Text(label, Modifier.padding(top = 12.dp)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HistoryScreen(viewModel: AppViewModel, close: () -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("History") }, navigationIcon = { Button(onClick = close) { Text("Back") } }, actions = { OutlinedButton(onClick = { confirmClear = true }, enabled = history.isNotEmpty()) { Text("Clear") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(history, key = HistoryEntity::id) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(it.eventLabel, style = MaterialTheme.typography.titleMedium); Text(it.triggerSummary); Text(DateFormat.getDateTimeInstance().format(Date(it.triggeredAt))) } } }; if (history.isEmpty()) item { Text("No reminders have triggered yet.") } }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear trigger history?") }, text = { Text("This cannot be undone. Notification events are not deleted.") }, confirmButton = { Button(onClick = { viewModel.clearHistory(); confirmClear = false }) { Text("Clear") } }, dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DiagnosticsScreen(viewModel: AppViewModel, close: () -> Unit) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val status by viewModel.monitoringStatus.collectAsState()
    val currentWifi by viewModel.currentWifi.collectAsState()
    val lastScan by viewModel.lastScan.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Diagnostics") }, navigationIcon = { Button(onClick = close) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text(status, style = MaterialTheme.typography.titleMedium) }; items(viewModel.permissions.diagnostics()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(it.first); Text(it.second) } }; item { Text("Current Wi-Fi SSID: ${currentWifi.first ?: "Unavailable"}") }; item { Text("Current Wi-Fi BSSID: ${currentWifi.second ?: "Unavailable"}") }; item { Text(lastScan?.let { "Last successful Wi-Fi scan: ${DateFormat.getTimeInstance().format(Date(it.first))} • ${it.second} networks" } ?: "Last successful Wi-Fi scan: None") }; item { Text("Enabled events: ${events.count(NotificationEvent::enabled)}") }; item { Text("Wi-Fi scans are best effort. GPS accuracy and altitude can vary; distance triggering favors avoiding false alarms.") } }
    }
}
