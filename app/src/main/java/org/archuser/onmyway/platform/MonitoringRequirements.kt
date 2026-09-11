package org.archuser.onmyway.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.archuser.onmyway.domain.*

internal fun ScanMode.intervalMillis(): Long = when (this) {
    ScanMode.BATTERY_SAVER -> 30 * 60_000L
    ScanMode.BALANCED -> 15 * 60_000L
    ScanMode.FREQUENT -> 30_000L
}

internal enum class LocationRequestSpec(val intervalMillis: Long, val distanceMeters: Float) {
    REGION(30_000L, 10f), DISTANCE(3_000L, 1f)
}

internal data class MonitoringRequirements(
    val observeConnectedWifi: Boolean,
    val nearbyWifiScanMode: ScanMode?,
    val locationRequest: LocationRequestSpec?,
) {
    companion object {
        fun from(events: List<NotificationEvent>): MonitoringRequirements {
            val configs = events.filter { it.enabled }.map { it.config }
            return MonitoringRequirements(
                configs.any { it is TriggerConfig.ConnectedSsid || it is TriggerConfig.ConnectedBssid },
                configs.mapNotNull {
                    when (it) {
                        is TriggerConfig.NearbySsid -> it.scanMode
                        is TriggerConfig.NearbyBssid -> it.scanMode
                        else -> null
                    }
                }.minByOrNull { it.intervalMillis() },
                when {
                    configs.any { it is TriggerConfig.DistanceTraveled } -> LocationRequestSpec.DISTANCE
                    configs.any { it is TriggerConfig.GpsCircle } -> LocationRequestSpec.REGION
                    else -> null
                },
            )
        }
    }
}

/** Owns independent subscriptions; database state updates do not restart them. */
internal class MonitoringSubscriptions(
    private val scope: CoroutineScope,
    private val connections: () -> Flow<TriggerObservation.WifiConnection>,
    private val scans: (ScanMode) -> Flow<TriggerObservation.WifiScan>,
    private val accept: suspend (TriggerObservation) -> Unit,
    private val failure: (String, Throwable?) -> Unit,
) {
    private var connectionJob: Job? = null
    private var scanJob: Job? = null
    private var scanMode: ScanMode? = null

    fun apply(requirements: MonitoringRequirements) {
        if (!requirements.observeConnectedWifi) {
            connectionJob?.cancel()
            connectionJob = null
            failure("connection", null)
        } else if (connectionJob?.isActive != true) {
            failure("connection", null)
            connectionJob = scope.launch {
                connections().catch { failure("connection", it) }.collect { accept(it) }
            }
        }
        val mode = requirements.nearbyWifiScanMode
        if (mode != scanMode || (mode != null && scanJob?.isActive != true)) {
            scanJob?.cancel()
            scanMode = mode
            failure("scan", null)
            scanJob = mode?.let {
                scope.launch { scans(it).catch { failure("scan", it) }.collect { accept(it) } }
            }
        }
    }
}

/** Retained across scan-loop replacements; elapsed time never causes catch-up bursts. */
internal class ScanSchedule(private val now: () -> Long) {
    private var lastRequest: Long? = null
    fun delayMillis(mode: ScanMode): Long = lastRequest?.let {
        (mode.intervalMillis() - (now() - it)).coerceAtLeast(0L)
    } ?: 0L
    fun requested() { lastRequest = now() }
}
