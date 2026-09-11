package org.archuser.onmyway.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.archuser.onmyway.domain.TriggerType

class PermissionStatusManager(private val context: Context) {
    fun monitoringIssue(): String? = when {
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "Grant precise location to monitor reminders"
        !context.getSystemService(LocationManager::class.java).isLocationEnabled -> "Enable location services to monitor reminders"
        !NotificationDispatcher(context).canNotify() -> "Allow notifications to receive reminders"
        else -> null
    }

    fun initialInstallPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.filterNot(::granted).toTypedArray()

    fun requestedPermissions(type: TriggerType): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        when (type) {
            TriggerType.CONNECTED_SSID, TriggerType.CONNECTED_BSSID -> add(Manifest.permission.ACCESS_FINE_LOCATION)
            TriggerType.NEARBY_SSID, TriggerType.NEARBY_BSSID -> {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            TriggerType.GPS_CIRCLE, TriggerType.DISTANCE_TRAVELED -> add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.filterNot(::granted).toTypedArray()

    fun issueFor(type: TriggerType): String? {
        if (!NotificationDispatcher(context).canNotify()) return "Notification permission required"
        if (!context.getSystemService(LocationManager::class.java).isLocationEnabled) return "Location services disabled"
        return when (type) {
            TriggerType.CONNECTED_SSID, TriggerType.CONNECTED_BSSID ->
                if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) "Location permission is required to read Wi-Fi identity" else null
            TriggerType.NEARBY_SSID, TriggerType.NEARBY_BSSID -> when {
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "Location permission required for Wi-Fi scans"
                Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.NEARBY_WIFI_DEVICES) -> "Nearby Wi-Fi permission required"
                !context.getSystemService(WifiManager::class.java).isWifiEnabled -> "Wi-Fi is disabled"
                else -> null
            }
            TriggerType.GPS_CIRCLE, TriggerType.DISTANCE_TRAVELED -> when {
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "Location permission required"
                !context.getSystemService(LocationManager::class.java).isLocationEnabled -> "Location services disabled"
                else -> null
            }
        }
    }

    fun diagnostics(): List<Pair<String, String>> = listOf(
        "Notifications" to if (NotificationDispatcher(context).canNotify()) "Granted" else "Required",
        "Precise location" to permissionLabel(Manifest.permission.ACCESS_FINE_LOCATION),
        "Background location" to if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) "Granted" else "Needed for background restart",
        "Nearby Wi-Fi" to if (Build.VERSION.SDK_INT >= 33) permissionLabel(Manifest.permission.NEARBY_WIFI_DEVICES) else "Not required on this Android version",
        "Location services" to if (context.getSystemService(LocationManager::class.java).isLocationEnabled) "Enabled" else "Disabled",
        "Wi-Fi" to if (context.getSystemService(WifiManager::class.java).isWifiEnabled) "Enabled" else "Disabled",
    )

    private fun permissionLabel(permission: String) = if (granted(permission)) "Granted" else "Required"
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
