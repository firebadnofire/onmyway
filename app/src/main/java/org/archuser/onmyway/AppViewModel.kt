package org.archuser.onmyway

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.archuser.onmyway.data.AppBackup
import org.archuser.onmyway.data.BackupCodec
import org.archuser.onmyway.data.EventRepository
import org.archuser.onmyway.domain.DistanceUnit
import org.archuser.onmyway.domain.NotificationEvent
import org.archuser.onmyway.domain.ScanMode
import org.archuser.onmyway.domain.TriggerConfig
import org.archuser.onmyway.domain.TriggerState
import org.archuser.onmyway.domain.TriggerType
import org.archuser.onmyway.domain.isValidBssid
import org.archuser.onmyway.platform.PermissionStatusManager
import org.archuser.onmyway.platform.WifiMonitor
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class EventDraft(
    val id: Long = 0,
    val type: TriggerType = TriggerType.DISTANCE_TRAVELED,
    val name: String = "",
    val body: String = "",
    val title: String = "",
    val oneTime: Boolean = true,
    val invert: Boolean = false,
    val customSoundEnabled: Boolean = false,
    val customSoundUri: String? = null,
    val textValue: String = "",
    val number1: String = "25",
    val number2: String = "",
    val number3: String = "150",
    val unit: DistanceUnit = DistanceUnit.FEET,
    val scanMode: ScanMode = ScanMode.BALANCED,
    val includeElevation: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OnMyWayApplication
    private val repository: EventRepository = app.repository
    val events = repository.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = repository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val monitoringStatus = app.coordinator.status
    val currentWifi = app.coordinator.currentWifi
    val lastScan = app.coordinator.lastScan
    val permissions = PermissionStatusManager(application)
    val settings = app.settingsStore.settings

    fun setMaterialYou(enabled: Boolean) = app.settingsStore.update { it.copy(materialYouEnabled = enabled) }
    fun setDarkTheme(enabled: Boolean) = app.settingsStore.update { it.copy(darkThemeEnabled = enabled) }
    fun setGlobalCustomSoundEnabled(enabled: Boolean) = app.settingsStore.update { it.copy(customSoundEnabled = enabled) }

    fun selectSound(uri: Uri, done: (String?, String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val resolver = getApplication<Application>().contentResolver
                val mimeType = resolver.getType(uri)
                require(mimeType?.startsWith("audio/") == true) { "Choose an audio file" }
                resolver.openAssetFileDescriptor(uri, "r")?.use { require(it.length != 0L) { "The audio file is empty" } }
                    ?: error("The audio file cannot be opened")
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                uri.toString()
            }
            result.fold({ done(it, null) }, { done(null, it.message ?: "Could not open audio file") })
        }
    }

    fun setGlobalSoundUri(value: String) = app.settingsStore.update {
        it.copy(customSoundEnabled = true, customSoundUri = value)
    }

    fun exportBackup(uri: Uri, done: (String?) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            val json = BackupCodec.encode(AppBackup(app.settingsStore.settings.value, repository.snapshot()))
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                    ?: error("The export file cannot be opened")
            }
        }
        done(result.exceptionOrNull()?.message)
    }

    fun importBackup(uri: Uri, done: (String?) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            val backup = BackupCodec.decode(withContext(Dispatchers.IO) { readBoundedText(uri) })
            repository.replaceAll(backup.storedData)
            app.settingsStore.replace(backup.settings)
        }
        done(result.exceptionOrNull()?.message)
    }

    private fun readBoundedText(uri: Uri): String {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: error("The import file cannot be opened")
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BACKUP_BYTES) { "Backup is larger than 5 MB" }
                output.write(buffer, 0, count)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { repository.setEnabled(id, enabled) }
    fun delete(id: Long, done: () -> Unit) = viewModelScope.launch { repository.delete(id); done() }
    fun clearHistory() = viewModelScope.launch { repository.clearHistory() }

    suspend fun draft(id: Long): EventDraft = repository.event(id)?.toDraft() ?: EventDraft()

    fun save(draft: EventDraft, done: (String?) -> Unit) {
        val candidate = runCatching { draft.toEvent() }.getOrElse { return done(it.message ?: "Invalid event") }
        viewModelScope.launch {
            val existing = candidate.id.takeIf { it != 0L }?.let { repository.event(it) }
            val event = if (existing == null) candidate else candidate.copy(
                enabled = existing.enabled,
                createdAt = existing.createdAt,
                lastTriggeredAt = existing.lastTriggeredAt,
                triggerState = if (existing.config == candidate.config) existing.triggerState else TriggerState.UNKNOWN,
                baseline = if (existing.config == candidate.config) existing.baseline else null,
            )
            runCatching { repository.save(event) }.fold({ done(null) }, { done(it.message ?: "Could not save event") })
        }
    }

    fun currentWifi(onResult: (Pair<String?, String?>) -> Unit) = viewModelScope.launch {
        onResult(app.wifiMonitor.requestCurrentConnection())
    }

    @Suppress("DEPRECATION")
    fun currentLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getApplication<Application>().getSystemService(LocationManager::class.java)
        return runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull(manager::getLastKnownLocation)
                .maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    private companion object { const val MAX_BACKUP_BYTES = 5 * 1024 * 1024 }
}

private fun EventDraft.toEvent(): NotificationEvent {
    require(body.isNotBlank()) { "Notification text is required" }
    require(!customSoundEnabled || !customSoundUri.isNullOrBlank()) { "Choose a custom sound or turn off the override" }
    val config: TriggerConfig = when (type) {
        TriggerType.CONNECTED_SSID -> TriggerConfig.ConnectedSsid(textValue.trim().also { require(it.isNotEmpty()) { "Wi-Fi name is required" } })
        TriggerType.CONNECTED_BSSID -> TriggerConfig.ConnectedBssid(textValue.trim().also { require(isValidBssid(it)) { "Enter a BSSID like aa:bb:cc:dd:ee:ff" } })
        TriggerType.NEARBY_SSID -> TriggerConfig.NearbySsid(textValue.trim().also { require(it.isNotEmpty()) { "Wi-Fi name is required" } }, scanMode)
        TriggerType.NEARBY_BSSID -> TriggerConfig.NearbyBssid(textValue.trim().also { require(isValidBssid(it)) { "Enter a BSSID like aa:bb:cc:dd:ee:ff" } }, scanMode)
        TriggerType.GPS_CIRCLE -> TriggerConfig.GpsCircle(
            number1.toDoubleOrNull()?.also { require(it in -90.0..90.0) } ?: error("Valid latitude required"),
            number2.toDoubleOrNull()?.also { require(it in -180.0..180.0) } ?: error("Valid longitude required"),
            number3.toDoubleOrNull()?.also { require(it > 0) } ?: error("Positive radius required"),
        )
        TriggerType.DISTANCE_TRAVELED -> TriggerConfig.DistanceTraveled(
            number1.toDoubleOrNull()?.also { require(it > 0) } ?: error("Positive distance required"), unit, includeElevation,
        )
    }
    return NotificationEvent(
        id = id,
        name = name.trim(),
        config = config,
        notificationTitle = title.trim().takeIf(String::isNotEmpty),
        notificationBody = body.trim(),
        oneTime = oneTime,
        invert = invert,
        customSoundEnabled = customSoundEnabled,
        customSoundUri = customSoundUri,
        triggerState = TriggerState.UNKNOWN,
    )
}

private fun NotificationEvent.toDraft(): EventDraft {
    val common = EventDraft(
        id = id, type = config.type, name = name, body = notificationBody,
        title = notificationTitle.orEmpty(), oneTime = oneTime, invert = invert,
        customSoundEnabled = customSoundEnabled, customSoundUri = customSoundUri,
    )
    return when (val value = config) {
        is TriggerConfig.ConnectedSsid -> common.copy(textValue = value.ssid)
        is TriggerConfig.ConnectedBssid -> common.copy(textValue = value.bssid)
        is TriggerConfig.NearbySsid -> common.copy(textValue = value.ssid, scanMode = value.scanMode)
        is TriggerConfig.NearbyBssid -> common.copy(textValue = value.bssid, scanMode = value.scanMode)
        is TriggerConfig.GpsCircle -> common.copy(number1 = value.latitude.toString(), number2 = value.longitude.toString(), number3 = value.radiusMeters.toString())
        is TriggerConfig.DistanceTraveled -> common.copy(number1 = value.distance.toString(), unit = value.unit, includeElevation = value.includeElevation)
    }
}
