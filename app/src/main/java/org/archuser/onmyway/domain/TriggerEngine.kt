package org.archuser.onmyway.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class TriggerEngine {
    fun evaluate(event: NotificationEvent, observation: TriggerObservation): TriggerEvaluation {
        if (!event.enabled) return TriggerEvaluation(event, fired = false, acceptedObservation = false)

        return when (val config = event.config) {
            is TriggerConfig.ConnectedSsid -> evaluateCondition(
                event,
                connectedIdentityCondition(
                    observation = observation,
                    configuredIdentities = config.wifiTargets(),
                    observedIdentity = TriggerObservation.WifiConnection::ssid,
                    normalize = ::normalizeSsid,
                ),
                observation.timestampMillis,
            )
            is TriggerConfig.ConnectedBssid -> evaluateCondition(
                event,
                connectedIdentityCondition(
                    observation = observation,
                    configuredIdentities = config.wifiTargets(),
                    observedIdentity = TriggerObservation.WifiConnection::bssid,
                    normalize = ::normalizeBssid,
                ),
                observation.timestampMillis,
            )
            is TriggerConfig.NearbySsid -> evaluateScan(event, observation, observation.timestampMillis) { point ->
                normalizeSsid(point.ssid)?.let { actual -> config.wifiTargets().any { normalizeSsid(it) == actual } } == true
            }
            is TriggerConfig.NearbyBssid -> evaluateScan(event, observation, observation.timestampMillis) { point ->
                normalizeBssid(point.bssid)?.let { actual -> config.wifiTargets().any { normalizeBssid(it) == actual } } == true
            }
            is TriggerConfig.GpsCircle -> evaluateGps(event, config, observation)
            is TriggerConfig.DistanceTraveled -> evaluateDistance(event, config, observation)
        }
    }

    private fun connectedIdentityCondition(
        observation: TriggerObservation,
        configuredIdentities: List<String>,
        observedIdentity: (TriggerObservation.WifiConnection) -> String?,
        normalize: (String?) -> String?,
    ): Boolean? {
        val connection = observation as? TriggerObservation.WifiConnection ?: return null
        if (!connection.connected) return false
        val actual = normalize(observedIdentity(connection)) ?: return null
        val expected = configuredIdentities.map { normalize(it) ?: return null }
        return actual in expected
    }

    private fun evaluateScan(
        event: NotificationEvent,
        observation: TriggerObservation,
        timestamp: Long,
        predicate: (TriggerObservation.WifiScan.AccessPoint) -> Boolean,
    ): TriggerEvaluation {
        val scan = observation as? TriggerObservation.WifiScan
            ?: return TriggerEvaluation(event, false, acceptedObservation = false)
        if (!scan.fresh) return TriggerEvaluation(event, false, acceptedObservation = false)
        return evaluateCondition(event, scan.accessPoints.any(predicate), timestamp)
    }

    private fun evaluateGps(
        event: NotificationEvent,
        config: TriggerConfig.GpsCircle,
        observation: TriggerObservation,
    ): TriggerEvaluation {
        val location = (observation as? TriggerObservation.Location)?.value
            ?: return TriggerEvaluation(event, false, acceptedObservation = false)
        if (!location.isUsable()) return TriggerEvaluation(event, false, acceptedObservation = false)
        val distance = distanceMeters(location.latitude, location.longitude, config.latitude, config.longitude)
        val accuracy = (location.horizontalAccuracyMeters ?: 0f).toDouble()
        val condition = when {
            distance + accuracy <= config.radiusMeters -> true
            max(0.0, distance - accuracy) > config.radiusMeters -> false
            else -> null // Accuracy overlaps the boundary; wait rather than guess.
        }
        return evaluateCondition(event, condition, location.timestampMillis)
    }

    private fun evaluateDistance(
        event: NotificationEvent,
        config: TriggerConfig.DistanceTraveled,
        observation: TriggerObservation,
    ): TriggerEvaluation {
        val current = (observation as? TriggerObservation.Location)?.value
            ?: return TriggerEvaluation(event, false, acceptedObservation = false)
        if (!current.isUsable()) return TriggerEvaluation(event, false, acceptedObservation = false)
        val baseline = event.baseline
        if (baseline == null) {
            return TriggerEvaluation(event.copy(baseline = current, triggerState = TriggerState.UNSATISFIED), false)
        }

        val horizontal = distanceMeters(
            baseline.latitude,
            baseline.longitude,
            current.latitude,
            current.longitude,
        )
        val horizontalUncertainty = max(
            baseline.horizontalAccuracyMeters ?: 0f,
            current.horizontalAccuracyMeters ?: 0f,
        ).toDouble()
        val conservativeHorizontal = max(0.0, horizontal - horizontalUncertainty)
        val vertical = if (config.includeElevation) usableVerticalDelta(baseline, current) else 0.0
        val displacement = sqrt(conservativeHorizontal * conservativeHorizontal + vertical * vertical)
        if (displacement < config.distanceMeters) {
            return TriggerEvaluation(event.copy(triggerState = TriggerState.UNSATISFIED), false)
        }

        val firedEvent = event.copy(
            enabled = !event.oneTime,
            lastTriggeredAt = current.timestampMillis,
            triggerState = if (event.oneTime) TriggerState.SATISFIED else TriggerState.UNSATISFIED,
            baseline = if (event.oneTime) baseline else current,
        )
        return TriggerEvaluation(firedEvent, true)
    }

    private fun usableVerticalDelta(baseline: LocationObservation, current: LocationObservation): Double {
        val first = baseline.altitudeMeters ?: return 0.0
        val second = current.altitudeMeters ?: return 0.0
        val firstAccuracy = baseline.verticalAccuracyMeters ?: return 0.0
        val secondAccuracy = current.verticalAccuracyMeters ?: return 0.0
        if (!first.isFinite() || !second.isFinite() || !firstAccuracy.isFinite() || !secondAccuracy.isFinite()) return 0.0
        if (firstAccuracy <= 0f || secondAccuracy <= 0f || firstAccuracy > 20f || secondAccuracy > 20f) return 0.0
        return max(0.0, kotlin.math.abs(second - first) - max(firstAccuracy, secondAccuracy))
    }

    private fun evaluateCondition(
        event: NotificationEvent,
        condition: Boolean?,
        timestamp: Long,
    ): TriggerEvaluation {
        if (condition == null) return TriggerEvaluation(event, false, acceptedObservation = false)
        val effectiveCondition = if (event.invert) !condition else condition
        val nextState = if (effectiveCondition) TriggerState.SATISFIED else TriggerState.UNSATISFIED
        val fired = effectiveCondition && event.triggerState == TriggerState.UNSATISFIED
        val updated = event.copy(
            enabled = if (fired && event.oneTime) false else event.enabled,
            lastTriggeredAt = if (fired) timestamp else event.lastTriggeredAt,
            triggerState = nextState,
        )
        return TriggerEvaluation(updated, fired)
    }
}

private fun LocationObservation.isUsable(): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        (horizontalAccuracyMeters == null || horizontalAccuracyMeters in 0f..100f)

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Radians = lat1 * PI / 180.0
    val lat2Radians = lat2 * PI / 180.0
    val deltaLat = (lat2 - lat1) * PI / 180.0
    val deltaLon = (lon2 - lon1) * PI / 180.0
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1Radians) * cos(lat2Radians) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 6_371_000.0 * 2.0 * asin(min(1.0, sqrt(a)))
}
