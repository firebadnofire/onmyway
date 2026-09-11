package org.archuser.onmyway.data

import org.archuser.onmyway.domain.DistanceUnit
import org.archuser.onmyway.domain.LocationObservation
import org.archuser.onmyway.domain.NotificationEvent
import org.archuser.onmyway.domain.TriggerConfig
import org.archuser.onmyway.domain.TriggerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {
    @Test fun multipleTargetsSurviveStorageAndBackupAndOldRowsStillLoad() {
        val configs = listOf(
            TriggerConfig.ConnectedSsid("Home", listOf("Home5G")),
            TriggerConfig.ConnectedBssid("aa:bb:cc:dd:ee:ff", listOf("11:22:33:44:55:66")),
            TriggerConfig.NearbySsid("Home", org.archuser.onmyway.domain.ScanMode.FREQUENT, listOf("Home5G")),
            TriggerConfig.NearbyBssid("aa:bb:cc:dd:ee:ff", org.archuser.onmyway.domain.ScanMode.BALANCED, listOf("11:22:33:44:55:66")),
        )
        configs.forEach { config -> assertEquals(config, config.toEntity(1).toDomain(config.type)) }
        val backup = AppBackup(AppSettings(), StoredData(configs.mapIndexed { index, config ->
            NotificationEvent(id = index + 1L, config = config, notificationBody = "Remember")
        }, emptyList()))
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
        val oldConfig = configs.first().toEntity(1).copy(additionalTargets = null)
        assertEquals(TriggerConfig.ConnectedSsid("Home"), oldConfig.toDomain(configs.first().type))
        val old = org.json.JSONObject(BackupCodec.encode(backup)).put("version", 1)
        val events = old.getJSONArray("events")
        for (index in 0 until events.length()) events.getJSONObject(index).getJSONObject("config").remove("additionalTargets")
        assertEquals(TriggerConfig.ConnectedSsid("Home"), BackupCodec.decode(old.toString()).storedData.events.first().config)
    }
    @Test
    fun roundTripPreservesSettingsEventsRuntimeStateAndHistory() {
        val backup = AppBackup(
            settings = AppSettings(
                materialYouEnabled = true,
                darkThemeEnabled = true,
                customSoundEnabled = true,
                customSoundUri = "content://audio/global",
            ),
            storedData = StoredData(
                events = listOf(
                    NotificationEvent(
                        id = 7,
                        name = "Leave work",
                        enabled = true,
                        config = TriggerConfig.DistanceTraveled(12.5, DistanceUnit.METERS, true),
                        notificationTitle = "On the way",
                        notificationBody = "Pick up groceries",
                        oneTime = false,
                        invert = false,
                        customSoundEnabled = true,
                        customSoundUri = "content://audio/item",
                        createdAt = 100,
                        lastTriggeredAt = 200,
                        triggerState = TriggerState.SATISFIED,
                        baseline = LocationObservation(1.5, -2.5, 3.0, 4f, 5f, 99),
                    ),
                ),
                history = listOf(HistoryEntity(9, 7, "Leave work", "Distance traveled", 200)),
            ),
        )

        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
    }

    @Test
    fun rejectsUnknownVersionsBeforeImport() {
        val invalid = """{"format":"onmyway-backup","version":99,"settings":{},"events":[],"history":[]}"""
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(invalid) }
    }

    @Test
    fun rejectsInvalidLocationBaselinesBeforeImport() {
        val baseline = LocationObservation(0.0, 0.0, timestampMillis = 1)
        for (invalid in listOf(
            baseline.copy(latitude = 91.0),
            baseline.copy(longitude = -181.0),
            baseline.copy(horizontalAccuracyMeters = -1f),
            baseline.copy(verticalAccuracyMeters = -1f),
        )) {
            val backup = AppBackup(AppSettings(), StoredData(listOf(NotificationEvent(
                id = 1, config = TriggerConfig.DistanceTraveled(10.0, DistanceUnit.METERS, false),
                notificationBody = "Remember", baseline = invalid,
            )), emptyList()))
            assertThrows(IllegalArgumentException::class.java) {
                BackupCodec.decode(BackupCodec.encode(backup))
            }
        }
    }
}
