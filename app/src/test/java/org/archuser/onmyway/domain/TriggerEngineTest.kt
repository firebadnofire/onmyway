package org.archuser.onmyway.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerEngineTest {
    private val engine = TriggerEngine()

    @Test fun connectedWifiFiresOnlyOnEdgesAndRearms() {
        var event = event(TriggerConfig.ConnectedSsid("Home"))
        event = evaluate(event, wifi(false)).event
        assertTrue(evaluate(event, wifi(true, "Home")).also { event = it.event }.fired)
        assertFalse(evaluate(event, wifi(true, "Home")).also { event = it.event }.fired)
        assertFalse(evaluate(event, wifi(false)).also { event = it.event }.fired)
        assertTrue(evaluate(event, wifi(true, "Home")).fired)
    }

    @Test fun wrongSsidDoesNotFire() {
        val armed = evaluate(event(TriggerConfig.ConnectedSsid("Home")), wifi(false)).event
        assertFalse(evaluate(armed, wifi(true, "Work")).fired)
    }

    @Test fun bssidNormalizationIsCaseInsensitive() {
        val config = TriggerConfig.ConnectedBssid("AA-BB-CC-DD-EE-FF")
        val armed = evaluate(event(config), wifi(false)).event
        assertTrue(evaluate(armed, wifi(true, bssid = "aa:bb:cc:dd:ee:ff")).fired)
    }

    @Test fun redactedWifiIdentitiesAreRejected() {
        assertTrue(normalizeSsid("<unknown ssid>") == null)
        assertTrue(normalizeBssid("02:00:00:00:00:00") == null)
        assertTrue(normalizeBssid("00:00:00:00:00:00") == null)
    }

    @Test fun nearbyWifiRearmsButStaleScanCannotChangeState() {
        var event = event(TriggerConfig.NearbySsid("Home", ScanMode.BALANCED))
        event = evaluate(event, scan()).event
        assertTrue(evaluate(event, scan("Home")).also { event = it.event }.fired)
        assertFalse(evaluate(event, scan("Home")).also { event = it.event }.fired)
        assertFalse(evaluate(event, scan(fresh = false)).also { event = it.event }.fired)
        assertTrue(event.triggerState == TriggerState.SATISFIED)
        event = evaluate(event, scan()).event
        assertTrue(evaluate(event, scan("Home")).fired)
    }

    @Test fun gpsRequiresOutsideBeforeInside() {
        var event = event(TriggerConfig.GpsCircle(0.0, 0.0, 100.0))
        assertFalse(evaluate(event, location(0.0, 0.0)).also { event = it.event }.fired)
        assertFalse(evaluate(event, location(0.0, 0.002)).also { event = it.event }.fired)
        assertTrue(evaluate(event, location(0.0, 0.0)).also { event = it.event }.fired)
        assertFalse(evaluate(event, location(0.0, 0.0)).fired)
    }

    @Test fun oneTimeEventDisablesAfterFire() {
        var event = event(TriggerConfig.ConnectedSsid("Home"), oneTime = true)
        event = evaluate(event, wifi(false)).event
        val result = evaluate(event, wifi(true, "Home"))
        assertTrue(result.fired)
        assertFalse(result.event.enabled)
        assertFalse(evaluate(result.event, wifi(false)).fired)
    }

    @Test fun invertedConnectedWifiFiresOnLeave() {
        var event = event(TriggerConfig.ConnectedSsid("Home")).copy(invert = true)
        event = evaluate(event, wifi(true, "Home")).event
        assertFalse(evaluate(event, wifi(true, "Home")).fired)
        assertTrue(evaluate(event, wifi(false)).fired)
    }

    @Test fun invertedConnectedBssidIgnoresUnavailableIdentityUntilDefinitiveLeave() {
        var event = event(TriggerConfig.ConnectedBssid("aa:bb:cc:dd:ee:ff")).copy(invert = true)
        event = evaluate(event, wifi(true, bssid = "aa:bb:cc:dd:ee:ff")).event

        val unavailable = evaluate(event, wifi(true, bssid = "02:00:00:00:00:00"))
        assertFalse(unavailable.fired)
        assertFalse(unavailable.acceptedObservation)
        assertTrue(unavailable.event.triggerState == TriggerState.UNSATISFIED)

        assertTrue(evaluate(unavailable.event, wifi(false)).fired)
    }

    @Test fun unavailableConnectedSsidDoesNotFireOrRearm() {
        var event = event(TriggerConfig.ConnectedSsid("Home"))
        event = evaluate(event, wifi(false)).event
        event = evaluate(event, wifi(true, ssid = "Home")).event

        val unavailable = evaluate(event, wifi(true, ssid = "<unknown ssid>"))
        assertFalse(unavailable.fired)
        assertFalse(unavailable.acceptedObservation)
        assertTrue(unavailable.event.triggerState == TriggerState.SATISFIED)

        assertFalse(evaluate(unavailable.event, wifi(true, ssid = "Home")).fired)
    }

    @Test fun invertedConnectedWifiIgnoresUnrelatedObservations() {
        var event = event(TriggerConfig.ConnectedBssid("aa:bb:cc:dd:ee:ff")).copy(invert = true)
        event = evaluate(event, wifi(true, bssid = "aa:bb:cc:dd:ee:ff")).event

        val unrelated = evaluate(event, location(0.0, 0.0))
        assertFalse(unrelated.fired)
        assertFalse(unrelated.acceptedObservation)
        assertTrue(unrelated.event.triggerState == TriggerState.UNSATISFIED)
    }

    @Test fun invertedGpsFiresWhenLeavingCircle() {
        var event = event(TriggerConfig.GpsCircle(0.0, 0.0, 100.0)).copy(invert = true)
        assertFalse(evaluate(event, location(0.0, 0.0)).also { event = it.event }.fired)
        assertTrue(evaluate(event, location(0.0, 0.002)).fired)
    }

    @Test fun invertedGpsIgnoresAccuracyThatOverlapsBoundary() {
        var event = event(TriggerConfig.GpsCircle(0.0, 0.0, 100.0)).copy(invert = true)
        event = evaluate(event, location(0.0, 0.0)).event
        val uncertain = TriggerObservation.Location(
            LocationObservation(0.0, 100.0 / 111_195.0, horizontalAccuracyMeters = 20f, timestampMillis = 1L),
        )

        val result = evaluate(event, uncertain)
        assertFalse(result.fired)
        assertFalse(result.acceptedObservation)
        assertTrue(result.event.triggerState == TriggerState.UNSATISFIED)
    }

    @Test fun invertedNearbyWifiIgnoresStaleAbsence() {
        var event = event(TriggerConfig.NearbyBssid("aa:bb:cc:dd:ee:ff", ScanMode.BALANCED)).copy(invert = true)
        event = evaluate(event, bssidScan("aa:bb:cc:dd:ee:ff")).event

        val stale = evaluate(event, bssidScan(fresh = false))
        assertFalse(stale.fired)
        assertFalse(stale.acceptedObservation)
        assertTrue(stale.event.triggerState == TriggerState.UNSATISFIED)
    }

    @Test fun distanceThresholdAndRecurringBaseline() {
        val config = TriggerConfig.DistanceTraveled(25.0, DistanceUnit.FEET, false)
        var event = evaluate(event(config), location(0.0, 0.0)).event
        assertFalse(evaluate(event, location(0.0, feetToLongitude(24.0))).also { event = it.event }.fired)
        val fired = evaluate(event, location(0.0, feetToLongitude(26.0)))
        assertTrue(fired.fired)
        assertTrue(fired.event.enabled)
        assertTrue(fired.event.baseline?.longitude == feetToLongitude(26.0))
    }

    @Test fun elevationContributesConservatively() {
        val config = TriggerConfig.DistanceTraveled(25.0, DistanceUnit.FEET, true)
        val baseline = location(0.0, 0.0, altitude = 0.0, verticalAccuracy = 0.1f)
        var event = evaluate(event(config), baseline).event
        val moved = location(0.0, feetToLongitude(15.0), altitude = 6.2, verticalAccuracy = 0.1f)
        assertTrue(evaluate(event, moved).fired)
    }

    @Test fun invalidElevationCannotFireStationaryDistanceRule() {
        val config = TriggerConfig.DistanceTraveled(25.0, DistanceUnit.FEET, true)
        val armed = evaluate(event(config), location(0.0, 0.0, 0.0, 0.1f)).event
        for (altitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(evaluate(armed, location(0.0, 0.0, altitude, 0.1f)).fired)
        }
        assertFalse(evaluate(armed, location(0.0, 0.0, 0.0, Float.NaN)).fired)
        assertTrue(evaluate(armed, location(0.0, 0.001, Double.NaN, 0.1f)).fired)
    }

    private fun evaluate(event: NotificationEvent, observation: TriggerObservation) = engine.evaluate(event, observation)

    @Test fun multipleApsAreOneConditionIncludingInvert() {
        val first = "aa:bb:cc:dd:ee:ff"
        val second = "11:22:33:44:55:66"
        for (invert in listOf(false, true)) {
            val config = TriggerConfig.ConnectedBssid(first, listOf(second))
            var rule = event(config).copy(invert = invert)
            rule = evaluate(rule, wifi(false)).event
            val joined = evaluate(rule, wifi(true, bssid = first))
            assertTrue(joined.fired == !invert)
            val roamed = evaluate(joined.event, wifi(true, bssid = second))
            assertFalse(roamed.fired)
            val redacted = evaluate(roamed.event, wifi(true, bssid = "02:00:00:00:00:00"))
            assertFalse(redacted.acceptedObservation)
            assertTrue(evaluate(redacted.event, wifi(false)).fired == invert)
        }
    }

    @Test fun nearbyNamesMatchAnyAndOnlyRearmWhenAllAbsent() {
        var rule = event(TriggerConfig.NearbySsid("Home", ScanMode.FREQUENT, listOf("Home5G")))
        rule = evaluate(rule, scan()).event
        val joined = evaluate(rule, scan("Home5G"))
        assertTrue(joined.fired)
        val switched = evaluate(joined.event, scan("Home"))
        assertFalse(switched.fired)
        val stale = evaluate(switched.event, scan(fresh = false))
        assertFalse(stale.acceptedObservation)
        val absent = evaluate(stale.event, scan()).event
        assertTrue(evaluate(absent, scan("Home", "Home5G")).fired)
    }
    private fun event(config: TriggerConfig, oneTime: Boolean = false) = NotificationEvent(
        config = config,
        notificationBody = "Remember",
        oneTime = oneTime,
    )
    private fun wifi(connected: Boolean, ssid: String? = null, bssid: String? = null) =
        TriggerObservation.WifiConnection(connected, ssid, bssid, 1L)
    private fun scan(vararg ssids: String, fresh: Boolean = true) = TriggerObservation.WifiScan(
        ssids.map { TriggerObservation.WifiScan.AccessPoint(it, null, -50) }, fresh, 1L,
    )
    private fun bssidScan(vararg bssids: String, fresh: Boolean = true) = TriggerObservation.WifiScan(
        bssids.map { TriggerObservation.WifiScan.AccessPoint(null, it, -50) }, fresh, 1L,
    )
    private fun location(
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        verticalAccuracy: Float? = null,
    ) = TriggerObservation.Location(
        LocationObservation(latitude, longitude, altitude, null, verticalAccuracy, 1L),
    )
    private fun feetToLongitude(feet: Double): Double = feet * 0.3048 / 111_195.0
}
