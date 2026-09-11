package org.archuser.onmyway.platform

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.archuser.onmyway.data.EventRepository
import org.archuser.onmyway.domain.*

class MonitoringCoordinator(
    private val context: Context,
    private val repository: EventRepository,
    private val wifiMonitor: WifiMonitor,
    private val notifications: NotificationDispatcher,
    private val scope: CoroutineScope,
) {
    val status = MutableStateFlow("Monitoring stopped")
    val currentWifi = MutableStateFlow<Pair<String?, String?>>(null to null)
    val lastScan = MutableStateFlow<Pair<Long, Int>?>(null)
    private val engine = TriggerEngine()
    private val mutex = Mutex()
    private val refreshRequests = MutableStateFlow(0L)
    private val failures = mutableMapOf<String, String>()
    private var visibleJob: Job? = null
    private var monitoringJob: Job? = null
    private var serviceScope: CoroutineScope? = null
    private var lastConnection: TriggerObservation.WifiConnection? = null
    private var armedCount = 0

    /** The Activity watches only rule requirements; it never owns sensor subscriptions. */
    fun onVisible() {
        if (visibleJob?.isActive != true) {
            visibleJob = scope.launch {
                repository.events.map { events ->
                    events.filter { it.enabled }.map { Triple(it.id, it.config, it.invert) }
                }.distinctUntilChanged().catch { reportFailure("storage", it) }.collect { refreshSafely() }
            }
        } else scope.launch { refreshSafely() }
    }

    private suspend fun refreshSafely() {
        try { refresh() } catch (error: CancellationException) { throw error }
        catch (error: Exception) { reportFailure("startup", error) }
    }

    fun onHidden() { visibleJob?.cancel(); visibleJob = null }

    suspend fun refresh() {
        if (repository.enabledEvents().isEmpty()) {
            if (monitoringJob?.isActive != true) status.value = "No events armed"
            return
        }
        val issue = PermissionStatusManager(context).monitoringIssue()
        if (issue != null) {
            status.value = issue
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, LocationMonitoringService::class.java))
        } catch (error: RuntimeException) {
            Log.w("OnMyWay", "Foreground monitoring start refused", error)
            status.value = "Open OnMyWay to resume monitoring • ${error.javaClass.simpleName}"
        }
    }

    internal fun startMonitoring(
        owner: CoroutineScope,
        configureLocation: (LocationRequestSpec?) -> Unit,
        updateNotification: (Int) -> Unit,
        stop: () -> Unit,
    ) {
        if (monitoringJob?.isActive == true) {
            refreshRequests.value++
            return
        }
        serviceScope = owner
        failures.clear()
        val subscriptions = MonitoringSubscriptions(owner, wifiMonitor::connections, wifiMonitor::scans,
            ::evaluate, ::reportFailure)
        monitoringJob = owner.launch {
            combine(repository.events, refreshRequests) { events, _ -> events }
                .catch { reportFailure("storage", it) }
                .collect {
                    mutex.withLock {
                        // A pending Room emission may predate the evaluation that just finished.
                        val enabled = repository.enabledEvents()
                        armedCount = enabled.size
                        if (enabled.isEmpty()) {
                            if (failures.isEmpty()) status.value = "No events armed" else publishStatus()
                            stop()
                            return@withLock
                        }
                        val issue = PermissionStatusManager(context).monitoringIssue()
                        if (issue != null) {
                            status.value = issue
                            stop()
                            return@withLock
                        }
                        val requirements = MonitoringRequirements.from(enabled)
                        if (!requirements.observeConnectedWifi) lastConnection = null
                        subscriptions.apply(requirements)
                        configureLocation(requirements.locationRequest)
                        updateNotification(enabled.size)
                        // New or edited connected rules need an initial observation even when
                        // an existing callback subscription does not need to be replaced.
                        lastConnection?.let { connection ->
                            enabled.filter { it.triggerState == TriggerState.UNKNOWN &&
                                (it.config is TriggerConfig.ConnectedSsid || it.config is TriggerConfig.ConnectedBssid)
                            }.forEach { evaluateEvent(it, connection) }
                        }
                        publishStatus()
                    }
                }
        }
    }

    fun accept(observation: TriggerObservation) {
        serviceScope?.launch { evaluate(observation) }
    }

    private suspend fun evaluate(observation: TriggerObservation) = mutex.withLock {
        when (observation) {
            is TriggerObservation.WifiConnection -> {
                lastConnection = observation
                currentWifi.value = observation.ssid to observation.bssid
            }
            is TriggerObservation.WifiScan -> if (observation.fresh) {
                lastScan.value = observation.timestampMillis to observation.accessPoints.size
            }
            else -> Unit
        }
        repository.enabledEvents().forEach { evaluateEvent(it, observation) }
    }

    private suspend fun evaluateEvent(event: NotificationEvent, observation: TriggerObservation) {
        val result = engine.evaluate(event, observation)
        if (result.acceptedObservation && result.event != event) {
            repository.persistEvaluation(result.event, result.fired)
            if (result.fired && !notifications.post(result.event)) {
                reportFailure("notification", IllegalStateException("Reminder notification blocked; check notification settings"))
            }
        }
    }

    internal fun reportFailure(source: String, error: Throwable?) {
        if (error == null) failures.remove(source) else {
            Log.w("OnMyWay", "Monitoring $source failed", error)
            failures[source] = "$source: ${error.message ?: error.javaClass.simpleName}"
        }
        publishStatus()
    }

    private fun publishStatus() {
        status.value = if (failures.isEmpty()) "Monitoring active • $armedCount armed"
            else "Monitoring needs attention • ${failures.values.joinToString()}"
    }

    internal fun stopped() {
        monitoringJob?.cancel()
        monitoringJob = null
        serviceScope = null
        lastConnection = null
        if (status.value.startsWith("Monitoring active")) status.value = "Monitoring stopped • open OnMyWay to resume"
    }
}
