package org.archuser.onmyway.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.SupplicantState
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.archuser.onmyway.domain.ScanMode
import org.archuser.onmyway.domain.TriggerObservation
import org.archuser.onmyway.domain.normalizeBssid
import org.archuser.onmyway.domain.normalizeSsid
import java.util.concurrent.atomic.AtomicReference

class WifiMonitor(private val context: Context) {
    val scanStatus = MutableStateFlow("No nearby scan requested")
    private val scanSchedule = ScanSchedule(android.os.SystemClock::elapsedRealtime)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    fun currentConnection(): Pair<String?, String?> {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val info = capabilities?.transportInfo as? WifiInfo
        val current = legacyInfo()
        return selectWifiIdentity(info?.ssid, info?.bssid, current?.ssid, current?.bssid)
    }

    /** Requests a fresh, location-aware identity for editor autofill. */
    suspend fun requestCurrentConnection(): Pair<String?, String?> {
        val fresh = runCatching {
            withTimeoutOrNull(3_000L) {
                callbackFlow<Pair<String?, String?>> {
                fun emit(capabilities: NetworkCapabilities?) {
                    val info = capabilities?.transportInfo as? WifiInfo
                    trySend(sanitizeConnection(info?.ssid, info?.bssid))
                }
                val callback = if (Build.VERSION.SDK_INT >= 31) {
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emit(capabilities)
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emit(capabilities)
                    }
                }
                try {
                    connectivity.registerDefaultNetworkCallback(callback)
                } catch (error: SecurityException) {
                    close(error)
                }
                awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
                }.firstOrNull { !it.first.isNullOrBlank() && !it.second.isNullOrBlank() }
            }
        }.getOrNull()
        return fresh ?: currentConnection()
    }

    fun connections(): Flow<TriggerObservation.WifiConnection> = callbackFlow {
        val pendingLoss = AtomicReference<kotlinx.coroutines.Job?>(null)
        fun emitCurrent(capabilities: NetworkCapabilities?) {
            val info = capabilities?.transportInfo as? WifiInfo
            val current = legacyInfo()
            val identity = selectWifiIdentity(info?.ssid, info?.bssid, current?.ssid, current?.bssid)
            trySend(
                TriggerObservation.WifiConnection(
                    connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || current != null,
                    ssid = identity.first,
                    bssid = identity.second,
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
        }
        fun emitAvailable(capabilities: NetworkCapabilities?) {
            pendingLoss.getAndSet(null)?.cancel()
            emitCurrent(capabilities)
        }
        fun scheduleLossRecheck() {
            val recheck = launch {
                delay(CONNECTION_LOSS_GRACE_MILLIS)
                emitCurrent(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
            }
            pendingLoss.getAndSet(recheck)?.cancel()
        }
        val callback = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) { pendingLoss.getAndSet(null)?.cancel() }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emitAvailable(capabilities)
                override fun onLost(network: Network) = scheduleLossRecheck()
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { pendingLoss.getAndSet(null)?.cancel() }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emitAvailable(capabilities)
                override fun onLost(network: Network) = scheduleLossRecheck()
            }
        }
        // Only this protected system action is exported: Wi-Fi can broadcast from a
        // privileged process outside the system UID. No broadcast extras are trusted.
        val connectionChanges = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
                if (legacyInfo() != null) {
                    emitAvailable(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
                } else scheduleLossRecheck()
            }
        }
        var receiverRegistered = false
        try {
            ContextCompat.registerReceiver(context, connectionChanges,
                IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            if (Build.VERSION.SDK_INT >= 31) {
                connectivity.registerDefaultNetworkCallback(callback, android.os.Handler(context.mainLooper))
            } else {
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback,
                )
            }
            emitCurrent(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
        } catch (error: SecurityException) {
            close(error)
        }
        awaitClose {
            pendingLoss.getAndSet(null)?.cancel()
            if (receiverRegistered) context.unregisterReceiver(connectionChanges)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // Android exposes scan broadcasts but no non-deprecated start request API.
    fun scans(mode: ScanMode): Flow<TriggerObservation.WifiScan> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                val points = try {
                    wifi.scanResults.map { result ->
                        TriggerObservation.WifiScan.AccessPoint(result.SSID, result.BSSID, result.level)
                    }
                } catch (_: SecurityException) {
                    null
                }
                scanStatus.value = when {
                    points == null -> "Nearby scan results inaccessible; check location permissions"
                    !fresh -> "Nearby scan was not refreshed; waiting for a successful scan"
                    else -> "Fresh nearby scan: ${points.size} networks"
                }
                trySend(
                    TriggerObservation.WifiScan(
                        accessPoints = points.orEmpty(),
                        fresh = fresh && points != null,
                        timestampMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            // SCAN_RESULTS_AVAILABLE_ACTION is a protected system broadcast.
            // Exporting permits delivery from privileged Wi-Fi components.
            ContextCompat.RECEIVER_EXPORTED,
        )
        val requester = launch {
            while (isActive) {
                delay(scanSchedule.delayMillis(mode))
                scanSchedule.requested()
                try {
                    scanStatus.value = if (wifi.startScan()) "Nearby scan requested; awaiting results"
                        else "Nearby scan request rejected by Android; waiting for the next scheduled request"
                } catch (error: SecurityException) {
                    close(error)
                    break
                }
            }
        }
        awaitClose {
            requester.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyInfo(): WifiInfo? = try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Snapshot of the current association, including AP roaming. This never scans.
            wifi.connectionInfo?.takeIf { it.supplicantState == SupplicantState.COMPLETED }
        } else null
    } catch (_: SecurityException) {
        null
    }

    private fun sanitizeConnection(ssid: String?, bssid: String?): Pair<String?, String?> =
        normalizeSsid(ssid) to normalizeBssid(bssid)

    private companion object {
        const val CONNECTION_LOSS_GRACE_MILLIS = 2_000L
    }
}
