package org.archuser.onmyway.data

import org.archuser.onmyway.domain.DistanceUnit
import org.archuser.onmyway.domain.LocationObservation
import org.archuser.onmyway.domain.NotificationEvent
import org.archuser.onmyway.domain.ScanMode
import org.archuser.onmyway.domain.TriggerConfig
import org.archuser.onmyway.domain.TriggerState
import org.archuser.onmyway.domain.TriggerType
import org.archuser.onmyway.domain.isValidBssid
import org.json.JSONArray
import org.json.JSONObject

data class AppBackup(val settings: AppSettings, val storedData: StoredData)

object BackupCodec {
    private const val VERSION = 1

    fun encode(backup: AppBackup): String = JSONObject()
        .put("format", "onmyway-backup")
        .put("version", VERSION)
        .put("settings", backup.settings.toJson())
        .put("events", JSONArray().apply { backup.storedData.events.forEach { put(it.toJson()) } })
        .put("history", JSONArray().apply { backup.storedData.history.forEach { put(it.toJson()) } })
        .toString(2)

    fun decode(text: String): AppBackup {
        val root = JSONObject(text)
        require(root.getString("format") == "onmyway-backup") { "Not an OnMyWay backup" }
        require(root.getInt("version") == VERSION) { "Unsupported backup version" }
        val events = root.getJSONArray("events").objects().map(::eventFromJson).onEach(::validateEvent)
        require(events.map(NotificationEvent::id).all { it > 0 } && events.map(NotificationEvent::id).distinct().size == events.size) {
            "Backup contains invalid or duplicate reminder IDs"
        }
        val history = root.getJSONArray("history").objects().map(::historyFromJson)
        require(history.size <= 200) { "Backup contains more than 200 history entries" }
        require(history.map(HistoryEntity::id).all { it > 0 } && history.map(HistoryEntity::id).distinct().size == history.size) {
            "Backup contains invalid or duplicate history IDs"
        }
        return AppBackup(settingsFromJson(root.getJSONObject("settings")), StoredData(events, history))
    }

    private fun AppSettings.toJson() = JSONObject()
        .put("materialYouEnabled", materialYouEnabled)
        .put("darkThemeEnabled", darkThemeEnabled)
        .put("customSoundEnabled", customSoundEnabled)
        .putNullable("customSoundUri", customSoundUri)

    private fun settingsFromJson(value: JSONObject) = AppSettings(
        materialYouEnabled = value.getBoolean("materialYouEnabled"),
        darkThemeEnabled = value.getBoolean("darkThemeEnabled"),
        customSoundEnabled = value.getBoolean("customSoundEnabled"),
        customSoundUri = value.nullableString("customSoundUri"),
    )

    private fun NotificationEvent.toJson() = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled)
        .put("triggerType", config.type.name).put("config", config.toJson())
        .putNullable("notificationTitle", notificationTitle).put("notificationBody", notificationBody)
        .put("oneTime", oneTime).put("invert", invert)
        .put("customSoundEnabled", customSoundEnabled).putNullable("customSoundUri", customSoundUri)
        .put("createdAt", createdAt).putNullable("lastTriggeredAt", lastTriggeredAt)
        .put("triggerState", triggerState.name).putNullable("baseline", baseline?.toJson())

    private fun eventFromJson(value: JSONObject): NotificationEvent {
        val type = TriggerType.valueOf(value.getString("triggerType"))
        return NotificationEvent(
            id = value.getLong("id"), name = value.getString("name"), enabled = value.getBoolean("enabled"),
            config = configFromJson(type, value.getJSONObject("config")),
            notificationTitle = value.nullableString("notificationTitle"),
            notificationBody = value.getString("notificationBody"), oneTime = value.getBoolean("oneTime"),
            invert = value.getBoolean("invert"), customSoundEnabled = value.getBoolean("customSoundEnabled"),
            customSoundUri = value.nullableString("customSoundUri"), createdAt = value.getLong("createdAt"),
            lastTriggeredAt = value.nullableLong("lastTriggeredAt"),
            triggerState = TriggerState.valueOf(value.getString("triggerState")),
            baseline = value.nullableObject("baseline")?.let(::locationFromJson),
        )
    }

    private fun validateEvent(event: NotificationEvent) {
        require(event.notificationBody.isNotBlank()) { "A reminder has empty notification text" }
        require(!event.customSoundEnabled || !event.customSoundUri.isNullOrBlank()) { "A reminder has no custom sound file" }
        when (val config = event.config) {
            is TriggerConfig.ConnectedSsid -> require(config.ssid.isNotBlank()) { "A reminder has an empty Wi-Fi name" }
            is TriggerConfig.ConnectedBssid -> require(isValidBssid(config.bssid)) { "A reminder has an invalid BSSID" }
            is TriggerConfig.NearbySsid -> require(config.ssid.isNotBlank()) { "A reminder has an empty Wi-Fi name" }
            is TriggerConfig.NearbyBssid -> require(isValidBssid(config.bssid)) { "A reminder has an invalid BSSID" }
            is TriggerConfig.GpsCircle -> require(
                config.latitude.isFinite() && config.latitude in -90.0..90.0 &&
                    config.longitude.isFinite() && config.longitude in -180.0..180.0 &&
                    config.radiusMeters.isFinite() && config.radiusMeters > 0,
            ) { "A reminder has an invalid GPS region" }
            is TriggerConfig.DistanceTraveled -> require(config.distance.isFinite() && config.distance > 0) {
                "A reminder has an invalid distance"
            }
        }
    }

    private fun TriggerConfig.toJson(): JSONObject = when (this) {
        is TriggerConfig.ConnectedSsid -> JSONObject().put("text", ssid)
        is TriggerConfig.ConnectedBssid -> JSONObject().put("text", bssid)
        is TriggerConfig.NearbySsid -> JSONObject().put("text", ssid).put("scanMode", scanMode.name)
        is TriggerConfig.NearbyBssid -> JSONObject().put("text", bssid).put("scanMode", scanMode.name)
        is TriggerConfig.GpsCircle -> JSONObject().put("latitude", latitude).put("longitude", longitude).put("radiusMeters", radiusMeters)
        is TriggerConfig.DistanceTraveled -> JSONObject().put("distance", distance).put("unit", unit.name).put("includeElevation", includeElevation)
    }

    private fun configFromJson(type: TriggerType, value: JSONObject): TriggerConfig = when (type) {
        TriggerType.CONNECTED_SSID -> TriggerConfig.ConnectedSsid(value.getString("text"))
        TriggerType.CONNECTED_BSSID -> TriggerConfig.ConnectedBssid(value.getString("text"))
        TriggerType.NEARBY_SSID -> TriggerConfig.NearbySsid(value.getString("text"), ScanMode.valueOf(value.getString("scanMode")))
        TriggerType.NEARBY_BSSID -> TriggerConfig.NearbyBssid(value.getString("text"), ScanMode.valueOf(value.getString("scanMode")))
        TriggerType.GPS_CIRCLE -> TriggerConfig.GpsCircle(value.getDouble("latitude"), value.getDouble("longitude"), value.getDouble("radiusMeters"))
        TriggerType.DISTANCE_TRAVELED -> TriggerConfig.DistanceTraveled(value.getDouble("distance"), DistanceUnit.valueOf(value.getString("unit")), value.getBoolean("includeElevation"))
    }

    private fun LocationObservation.toJson() = JSONObject()
        .put("latitude", latitude).put("longitude", longitude).putNullable("altitudeMeters", altitudeMeters)
        .putNullable("horizontalAccuracyMeters", horizontalAccuracyMeters?.toDouble())
        .putNullable("verticalAccuracyMeters", verticalAccuracyMeters?.toDouble()).put("timestampMillis", timestampMillis)

    private fun locationFromJson(value: JSONObject) = LocationObservation(
        value.getDouble("latitude"), value.getDouble("longitude"), value.nullableDouble("altitudeMeters"),
        value.nullableDouble("horizontalAccuracyMeters")?.toFloat(), value.nullableDouble("verticalAccuracyMeters")?.toFloat(),
        value.getLong("timestampMillis"),
    )

    private fun HistoryEntity.toJson() = JSONObject().put("id", id).put("eventId", eventId)
        .put("eventLabel", eventLabel).put("triggerSummary", triggerSummary).put("triggeredAt", triggeredAt)

    private fun historyFromJson(value: JSONObject) = HistoryEntity(
        value.getLong("id"), value.getLong("eventId"), value.getString("eventLabel"),
        value.getString("triggerSummary"), value.getLong("triggeredAt"),
    )

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.putNullable(name: String, value: Any?) = put(name, value ?: JSONObject.NULL)
    private fun JSONObject.nullableString(name: String) = if (isNull(name)) null else getString(name)
    private fun JSONObject.nullableLong(name: String) = if (isNull(name)) null else getLong(name)
    private fun JSONObject.nullableDouble(name: String) = if (isNull(name)) null else getDouble(name)
    private fun JSONObject.nullableObject(name: String) = if (isNull(name)) null else getJSONObject(name)
}
