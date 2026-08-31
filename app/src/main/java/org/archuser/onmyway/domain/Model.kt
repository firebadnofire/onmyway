package org.archuser.onmyway.domain

enum class TriggerType(val displayName: String) {
    CONNECTED_SSID("Connected Wi-Fi name"),
    CONNECTED_BSSID("Connected Wi-Fi access point"),
    NEARBY_SSID("Nearby Wi-Fi name"),
    NEARBY_BSSID("Nearby Wi-Fi access point"),
    GPS_CIRCLE("GPS region"),
    DISTANCE_TRAVELED("Distance traveled"),
}

enum class TriggerState { UNKNOWN, UNSATISFIED, SATISFIED }
enum class DistanceUnit { FEET, METERS }
enum class ScanMode { BATTERY_SAVER, BALANCED, FREQUENT }

sealed interface TriggerConfig {
    val type: TriggerType

    data class ConnectedSsid(val ssid: String) : TriggerConfig {
        override val type = TriggerType.CONNECTED_SSID
    }

    data class ConnectedBssid(val bssid: String) : TriggerConfig {
        override val type = TriggerType.CONNECTED_BSSID
    }

    data class NearbySsid(val ssid: String, val scanMode: ScanMode) : TriggerConfig {
        override val type = TriggerType.NEARBY_SSID
    }

    data class NearbyBssid(val bssid: String, val scanMode: ScanMode) : TriggerConfig {
        override val type = TriggerType.NEARBY_BSSID
    }

    data class GpsCircle(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double,
    ) : TriggerConfig {
        override val type = TriggerType.GPS_CIRCLE
    }

    data class DistanceTraveled(
        val distance: Double,
        val unit: DistanceUnit,
        val includeElevation: Boolean,
    ) : TriggerConfig {
        override val type = TriggerType.DISTANCE_TRAVELED
        val distanceMeters: Double get() = if (unit == DistanceUnit.FEET) distance * 0.3048 else distance
    }
}

data class LocationObservation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val horizontalAccuracyMeters: Float? = null,
    val verticalAccuracyMeters: Float? = null,
    val timestampMillis: Long,
)

sealed interface TriggerObservation {
    val timestampMillis: Long

    data class WifiConnection(
        val connected: Boolean,
        val ssid: String?,
        val bssid: String?,
        override val timestampMillis: Long,
    ) : TriggerObservation

    data class WifiScan(
        val accessPoints: List<AccessPoint>,
        val fresh: Boolean,
        override val timestampMillis: Long,
    ) : TriggerObservation {
        data class AccessPoint(val ssid: String?, val bssid: String?, val rssi: Int)
    }

    data class Location(val value: LocationObservation) : TriggerObservation {
        override val timestampMillis: Long = value.timestampMillis
    }
}

data class NotificationEvent(
    val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val config: TriggerConfig,
    val notificationTitle: String? = null,
    val notificationBody: String,
    val oneTime: Boolean = false,
    val invert: Boolean = false,
    val customSoundEnabled: Boolean = false,
    val customSoundUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null,
    val triggerState: TriggerState = TriggerState.UNKNOWN,
    val baseline: LocationObservation? = null,
)

data class TriggerEvaluation(
    val event: NotificationEvent,
    val fired: Boolean,
    val acceptedObservation: Boolean = true,
)
