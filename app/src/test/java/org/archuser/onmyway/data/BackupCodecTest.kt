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
}
