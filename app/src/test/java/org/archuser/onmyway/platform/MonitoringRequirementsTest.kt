package org.archuser.onmyway.platform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.archuser.onmyway.domain.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringRequirementsTest {
    private fun event(config: TriggerConfig) = NotificationEvent(config = config, notificationBody = "Test")
    @Test fun connectedOnlyNeverSchedulesScans() = runTest {
        var scans = 0
        var subscriptions = 0
        var observations = 0
        val connection = MutableSharedFlow<TriggerObservation.WifiConnection>()
        val monitors = MonitoringSubscriptions(backgroundScope,
            { flow { subscriptions++; emit(TriggerObservation.WifiConnection(false, null, null, 0)); connection.collect { emit(it) } } },
            { flow { while (true) { scans++; delay(30_000) } } },
            { observations++ }, { _, error -> assertNull(error) })
        val required = MonitoringRequirements.from(listOf(event(TriggerConfig.ConnectedSsid("Home"))))
        repeat(3) { monitors.apply(required); runCurrent() }
        connection.emit(TriggerObservation.WifiConnection(true, "Home", null, 1)); runCurrent()
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(0, scans); assertEquals(1, subscriptions); assertEquals(2, observations)
    }
    @Test fun nearbyUsesFastestModeAndLeavesConnectedMonitorRunning() = runTest {
        var connections = 0
        var scans = 0
        val monitors = MonitoringSubscriptions(backgroundScope,
            { flow { connections++; awaitCancellation() } },
            { mode -> flow { while (true) { scans++; delay(mode.intervalMillis()) } } }, {}, { _, error -> assertNull(error) })
        val connected = event(TriggerConfig.ConnectedBssid("aa:bb:cc:dd:ee:ff"))
        for (a in ScanMode.entries) for (b in ScanMode.entries) {
            val required = MonitoringRequirements.from(listOf(connected,
                event(TriggerConfig.NearbySsid("A", a)), event(TriggerConfig.NearbySsid("B", b))))
            assertEquals(minOf(a.intervalMillis(), b.intervalMillis()), required.nearbyWifiScanMode!!.intervalMillis())
        }
        val mixed = MonitoringRequirements.from(listOf(connected, event(TriggerConfig.NearbySsid("A", ScanMode.FREQUENT))))
        monitors.apply(mixed); runCurrent(); monitors.apply(mixed); runCurrent()
        assertEquals(1, scans)
        advanceTimeBy(30_000); runCurrent(); assertEquals(2, scans)
        monitors.apply(MonitoringRequirements.from(listOf(connected))); runCurrent()
        advanceTimeBy(90_000); runCurrent()
        assertEquals(2, scans); assertEquals(1, connections)
    }
    @Test fun schedulePreservesLastRequestAndNeverCatchesUp() {
        var now = 0L
        val schedule = ScanSchedule { now }
        assertEquals(0L, schedule.delayMillis(ScanMode.FREQUENT))
        schedule.requested(); now = 10_000
        assertEquals(20_000L, schedule.delayMillis(ScanMode.FREQUENT))
        assertEquals(890_000L, schedule.delayMillis(ScanMode.BALANCED))
        now = 4_000_000
        assertEquals(0L, schedule.delayMillis(ScanMode.FREQUENT))
        schedule.requested(); assertEquals(30_000L, schedule.delayMillis(ScanMode.FREQUENT))
        assertEquals(1_800_000L, ScanMode.BATTERY_SAVER.intervalMillis())
    }
    @Test fun locationRequirementsRestorePrecision() {
        val region = event(TriggerConfig.GpsCircle(0.0, 0.0, 100.0))
        val distance = event(TriggerConfig.DistanceTraveled(10.0, DistanceUnit.METERS, false))
        assertEquals(LocationRequestSpec.DISTANCE, MonitoringRequirements.from(listOf(region, distance)).locationRequest)
        assertEquals(LocationRequestSpec.REGION, MonitoringRequirements.from(listOf(region, distance.copy(enabled = false))).locationRequest)
        assertNull(MonitoringRequirements.from(emptyList()).locationRequest)
    }

    @Test fun connectedEventsContinueWhileNearbyResultsAreUnavailable() = runTest {
        val changes = MutableSharedFlow<TriggerObservation.WifiConnection>()
        var connectedEvents = 0
        val monitors = MonitoringSubscriptions(backgroundScope,
            { changes }, { flow { awaitCancellation() } },
            { if (it is TriggerObservation.WifiConnection) connectedEvents++ }, { _, error -> assertNull(error) })
        monitors.apply(MonitoringRequirements(true, ScanMode.FREQUENT, null)); runCurrent()
        changes.emit(TriggerObservation.WifiConnection(true, "Home", null, 1)); runCurrent()
        changes.emit(TriggerObservation.WifiConnection(false, null, null, 2)); runCurrent()
        assertEquals(2, connectedEvents)
    }

    @Test fun failedSubscriptionCanRecoverWithoutDuplicatingHealthyOne() = runTest {
        var connectionStarts = 0
        var scanStarts = 0
        var failure: Throwable? = null
        val monitors = MonitoringSubscriptions(backgroundScope,
            { flow { connectionStarts++; awaitCancellation() } },
            { flow {
                scanStarts++
                if (scanStarts == 1) throw SecurityException("Permission revoked")
                awaitCancellation()
            } }, {}, { source, error -> if (source == "scan") failure = error })
        val requirements = MonitoringRequirements(true, ScanMode.FREQUENT, null)
        monitors.apply(requirements); runCurrent()
        assertTrue(failure is SecurityException)
        monitors.apply(requirements); runCurrent()
        assertNull(failure)
        monitors.apply(requirements); runCurrent()
        assertEquals(2, scanStarts); assertEquals(1, connectionStarts)
    }
}
