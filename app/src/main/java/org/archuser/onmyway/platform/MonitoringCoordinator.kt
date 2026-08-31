package org.archuser.onmyway.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.archuser.onmyway.data.EventRepository
import org.archuser.onmyway.domain.ScanMode
import org.archuser.onmyway.domain.TriggerConfig
import org.archuser.onmyway.domain.TriggerEngine
import org.archuser.onmyway.domain.TriggerObservation

class MonitoringCoordinator(
    private val context: Context,
    private val repository: EventRepository,
    private val wifiMonitor: WifiMonitor,
    private val notifications: NotificationDispatcher,
    private val scope: CoroutineScope,
) {
    val status = MutableStateFlow("Monitoring starting")
    val currentWifi = MutableStateFlow<Pair<String?, String?>>(null to null)
    val lastScan = MutableStateFlow<Pair<Long, Int>?>(null)
    private val engine = TriggerEngine()
    private val mutex = Mutex()
    private var connectionJob: Job? = null
    private var scanJob: Job? = null

    fun start() {
        scope.launch {
            repository.events.collectLatest { events ->
                val enabled = events.filter { it.enabled }
                configureWifi(enabled.any { it.config is TriggerConfig.ConnectedSsid || it.config is TriggerConfig.ConnectedBssid })
                configureScan(enabled.mapNotNull { configScanMode(it.config) }.minByOrNull(ScanMode::ordinal))
                configureLocation(
                    required = enabled.any { it.config is TriggerConfig.GpsCircle || it.config is TriggerConfig.DistanceTraveled },
                    highPrecision = enabled.any { it.config is TriggerConfig.DistanceTraveled },
                )
                status.value = if (enabled.isEmpty()) "No events armed" else "Monitoring active • ${enabled.size} armed"
            }
        }
    }

    fun refresh() { /* Room Flow is authoritative and already replays current state. */ }

    fun accept(observation: TriggerObservation) {
        when (observation) {
            is TriggerObservation.WifiConnection -> currentWifi.value = observation.ssid to observation.bssid
            is TriggerObservation.WifiScan -> if (observation.fresh) lastScan.value = observation.timestampMillis to observation.accessPoints.size
            else -> Unit
        }
        scope.launch { evaluate(observation) }
    }

    private suspend fun evaluate(observation: TriggerObservation) = mutex.withLock {
        repository.enabledEvents().forEach { event ->
            val result = engine.evaluate(event, observation)
            if (result.acceptedObservation && result.event != event) {
                repository.persistEvaluation(result.event, result.fired)
                if (result.fired && !notifications.post(result.event)) {
                    status.value = "Notification permission required"
                }
            }
        }
    }

    private fun configureWifi(required: Boolean) {
        if (!required) {
            connectionJob?.cancel()
            connectionJob = null
        } else if (connectionJob == null) {
            connectionJob = scope.launch {
                wifiMonitor.connections().catch { status.value = "Wi-Fi permission or state unavailable" }.collect(::accept)
            }
        }
    }

    private fun configureScan(mode: ScanMode?) {
        scanJob?.cancel()
        scanJob = mode?.let {
            scope.launch {
                wifiMonitor.scans(it).catch { status.value = "Nearby Wi-Fi permission required" }.collect(::accept)
            }
        }
    }

    private fun configureLocation(required: Boolean, highPrecision: Boolean) {
        val intent = Intent(context, LocationMonitoringService::class.java)
            .putExtra(LocationMonitoringService.EXTRA_HIGH_PRECISION, highPrecision)
        if (!required) {
            context.stopService(intent)
            return
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: RuntimeException) {
            status.value = "Open OnMyWay to start location monitoring"
        }
    }

    private fun configScanMode(config: TriggerConfig): ScanMode? = when (config) {
        is TriggerConfig.NearbySsid -> config.scanMode
        is TriggerConfig.NearbyBssid -> config.scanMode
        else -> null
    }
}
