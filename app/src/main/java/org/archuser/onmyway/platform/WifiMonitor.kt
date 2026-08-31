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
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.archuser.onmyway.domain.ScanMode
import org.archuser.onmyway.domain.TriggerObservation
import org.archuser.onmyway.domain.normalizeBssid
import org.archuser.onmyway.domain.normalizeSsid

class WifiMonitor(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    fun currentConnection(): Pair<String?, String?> {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val info = capabilities?.transportInfo as? WifiInfo ?: legacyInfo()
        return sanitizeConnection(info?.ssid, info?.bssid)
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
                        override fun onAvailable(network: Network) = emit(connectivity.getNetworkCapabilities(network))
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emit(capabilities)
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = emit(connectivity.getNetworkCapabilities(network))
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emit(capabilities)
                    }
                }
                try {
                    connectivity.registerDefaultNetworkCallback(callback)
                } catch (error: SecurityException) {
                    close(error)
                }
                awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
                }.firstOrNull { !it.first.isNullOrBlank() || !it.second.isNullOrBlank() }
            }
        }.getOrNull()
        return fresh ?: currentConnection()
    }

    fun connections(): Flow<TriggerObservation.WifiConnection> = callbackFlow {
        fun emit(capabilities: NetworkCapabilities?) {
            val info = capabilities?.transportInfo as? WifiInfo
            val identity = sanitizeConnection(info?.ssid ?: legacyInfo()?.ssid, info?.bssid ?: legacyInfo()?.bssid)
            trySend(
                TriggerObservation.WifiConnection(
                    connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                    ssid = identity.first,
                    bssid = identity.second,
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
        }
        val callback = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = emit(connectivity.getNetworkCapabilities(network))
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emit(capabilities)
                override fun onLost(network: Network) = emit(null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = emit(connectivity.getNetworkCapabilities(network))
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emit(capabilities)
                override fun onLost(network: Network) = emit(null)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                connectivity.registerDefaultNetworkCallback(callback, android.os.Handler(context.mainLooper))
            } else {
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback,
                )
            }
            emit(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
        } catch (error: SecurityException) {
            close(error)
        }
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // Android exposes scan broadcasts but no non-deprecated start request API.
    fun scans(mode: ScanMode): Flow<TriggerObservation.WifiScan> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                val points = try {
                    wifi.scanResults.map { result ->
                        TriggerObservation.WifiScan.AccessPoint(result.SSID, result.BSSID, result.level)
                    }
                } catch (_: SecurityException) {
                    emptyList()
                }
                trySend(TriggerObservation.WifiScan(points, fresh, System.currentTimeMillis()))
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val interval = when (mode) {
            ScanMode.BATTERY_SAVER -> 30 * 60_000L
            ScanMode.BALANCED -> 15 * 60_000L
            ScanMode.FREQUENT -> 5 * 60_000L
        }
        val requester = launch {
            while (isActive) {
                try {
                    wifi.startScan() // false means throttled; no synthetic observation is emitted.
                } catch (_: SecurityException) {
                    // Permission state is exposed by diagnostics; wait instead of busy-looping.
                }
                delay(interval)
            }
        }
        awaitClose {
            requester.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyInfo(): WifiInfo? = try {
        if (Build.VERSION.SDK_INT <= 30 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            wifi.connectionInfo
        } else null
    } catch (_: SecurityException) {
        null
    }

    private fun sanitizeConnection(ssid: String?, bssid: String?): Pair<String?, String?> =
        normalizeSsid(ssid) to normalizeBssid(bssid)
}
