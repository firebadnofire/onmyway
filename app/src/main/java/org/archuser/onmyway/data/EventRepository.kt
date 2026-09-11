package org.archuser.onmyway.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.archuser.onmyway.domain.DistanceUnit
import org.archuser.onmyway.domain.LocationObservation
import org.archuser.onmyway.domain.NotificationEvent
import org.archuser.onmyway.domain.ScanMode
import org.archuser.onmyway.domain.TriggerConfig
import org.archuser.onmyway.domain.TriggerState
import org.archuser.onmyway.domain.TriggerType
import org.archuser.onmyway.domain.wifiTargets
import org.json.JSONArray

class EventRepository(private val database: AppDatabase) {
    private val dao = database.eventDao()

    val events: Flow<List<NotificationEvent>> = dao.observeEvents().map { rows -> rows.map(::toDomain) }
    val history: Flow<List<HistoryEntity>> = dao.observeHistory()

    suspend fun enabledEvents(): List<NotificationEvent> = dao.enabledEvents().map(::toDomain)
    suspend fun event(id: Long): NotificationEvent? = dao.event(id)?.let(::toDomain)
    suspend fun save(event: NotificationEvent): Long = dao.save(event.toEntity(), event.config.toEntity(event.id))
    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clearHistory() = dao.clearHistory()

    suspend fun snapshot(): StoredData = database.withTransaction {
        StoredData(
            events = (dao.enabledEvents() + dao.disabledEvents()).map(::toDomain),
            history = dao.historySnapshot(),
        )
    }

    suspend fun replaceAll(data: StoredData) = database.withTransaction {
        dao.clearHistory()
        dao.clearEvents()
        data.events.forEach { event ->
            dao.insertEvent(event.toEntity())
            dao.upsertConfig(event.config.toEntity(event.id))
        }
        data.history.forEach { history -> dao.insertHistory(history) }
    }

    suspend fun persistEvaluation(event: NotificationEvent, fired: Boolean) {
        database.withTransaction {
            dao.save(event.toEntity(), event.config.toEntity(event.id))
            if (fired) {
                dao.insertHistory(
                    HistoryEntity(
                        eventId = event.id,
                        eventLabel = event.name.ifBlank { event.notificationBody },
                        triggerSummary = event.config.summary(),
                        triggeredAt = requireNotNull(event.lastTriggeredAt),
                    ),
                )
                dao.pruneHistory()
            }
        }
    }

    private fun toDomain(row: EventWithConfig): NotificationEvent {
        val event = row.event
        val type = TriggerType.valueOf(event.triggerType)
        val config = row.config.toDomain(type)
        val baseline = if (event.baselineLatitude != null && event.baselineLongitude != null && event.baselineTimestampMillis != null) {
            LocationObservation(
                event.baselineLatitude,
                event.baselineLongitude,
                event.baselineAltitudeMeters,
                event.baselineHorizontalAccuracyMeters,
                event.baselineVerticalAccuracyMeters,
                event.baselineTimestampMillis,
            )
        } else null
        return NotificationEvent(
            event.id, event.name, event.enabled, config, event.notificationTitle,
            event.notificationBody, event.oneTime, event.invert, event.customSoundEnabled, event.customSoundUri,
            event.createdAt, event.lastTriggeredAt,
            TriggerState.valueOf(event.triggerState), baseline,
        )
    }
}

private fun NotificationEvent.toEntity() = EventEntity(
    id, name, enabled, config.type.name, notificationTitle, notificationBody, oneTime,
    invert, customSoundEnabled, customSoundUri, createdAt, lastTriggeredAt, triggerState.name,
    baseline?.latitude, baseline?.longitude,
    baseline?.altitudeMeters, baseline?.horizontalAccuracyMeters, baseline?.verticalAccuracyMeters,
    baseline?.timestampMillis,
)

data class StoredData(val events: List<NotificationEvent>, val history: List<HistoryEntity>)

internal fun TriggerConfig.toEntity(eventId: Long): TriggerConfigEntity = when (this) {
    is TriggerConfig.ConnectedSsid -> TriggerConfigEntity(eventId, ssid, null, null, null, null, null, JSONArray(additionalSsids).toString())
    is TriggerConfig.ConnectedBssid -> TriggerConfigEntity(eventId, bssid, null, null, null, null, null, JSONArray(additionalBssids).toString())
    is TriggerConfig.NearbySsid -> TriggerConfigEntity(eventId, ssid, null, null, null, scanMode.name, null, JSONArray(additionalSsids).toString())
    is TriggerConfig.NearbyBssid -> TriggerConfigEntity(eventId, bssid, null, null, null, scanMode.name, null, JSONArray(additionalBssids).toString())
    is TriggerConfig.GpsCircle -> TriggerConfigEntity(eventId, null, latitude, longitude, radiusMeters, null, null)
    is TriggerConfig.DistanceTraveled -> TriggerConfigEntity(eventId, null, distance, null, null, unit.name, includeElevation)
}

private fun TriggerConfigEntity.extraTargets(): List<String> = additionalTargets?.let { text ->
    val values = JSONArray(text)
    (0 until values.length()).map { values.getString(it) }
} ?: emptyList()

internal fun TriggerConfigEntity.toDomain(type: TriggerType): TriggerConfig = when (type) {
    TriggerType.CONNECTED_SSID -> TriggerConfig.ConnectedSsid(requireNotNull(textValue), extraTargets())
    TriggerType.CONNECTED_BSSID -> TriggerConfig.ConnectedBssid(requireNotNull(textValue), extraTargets())
    TriggerType.NEARBY_SSID -> TriggerConfig.NearbySsid(requireNotNull(textValue), ScanMode.valueOf(requireNotNull(optionValue)), extraTargets())
    TriggerType.NEARBY_BSSID -> TriggerConfig.NearbyBssid(requireNotNull(textValue), ScanMode.valueOf(requireNotNull(optionValue)), extraTargets())
    TriggerType.GPS_CIRCLE -> TriggerConfig.GpsCircle(requireNotNull(numberValue1), requireNotNull(numberValue2), requireNotNull(numberValue3))
    TriggerType.DISTANCE_TRAVELED -> TriggerConfig.DistanceTraveled(requireNotNull(numberValue1), DistanceUnit.valueOf(requireNotNull(optionValue)), booleanValue == true)
}

fun TriggerConfig.summary(): String = when (this) {
    is TriggerConfig.ConnectedSsid -> "Connected Wi-Fi • ${wifiTargets().joinToString()}"
    is TriggerConfig.ConnectedBssid -> "Connected access point • ${wifiTargets().joinToString()}"
    is TriggerConfig.NearbySsid -> "Nearby Wi-Fi • ${wifiTargets().joinToString()}"
    is TriggerConfig.NearbyBssid -> "Nearby access point • ${wifiTargets().joinToString()}"
    is TriggerConfig.GpsCircle -> "GPS region • ${radiusMeters.toInt()} m"
    is TriggerConfig.DistanceTraveled -> "Distance traveled • ${distance.toString().trimEnd('0').trimEnd('.')} ${if (unit == DistanceUnit.FEET) "ft" else "m"}"
}
