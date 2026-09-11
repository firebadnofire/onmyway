package org.archuser.onmyway

import org.archuser.onmyway.domain.NotificationEvent
import org.archuser.onmyway.domain.TriggerConfig
import org.archuser.onmyway.domain.TriggerEngine
import org.archuser.onmyway.domain.TriggerObservation
import org.archuser.onmyway.domain.TriggerState
import org.archuser.onmyway.domain.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDraftTest {
    @Test fun multipleTargetsRoundTripNormalizeAndRejectEmptyRows() {
        val draft = EventDraft(type = TriggerType.CONNECTED_BSSID, body = "Remember", textValue = "AA-BB-CC-DD-EE-FF",
            additionalTextValues = listOf("11:22:33:44:55:66", "aa:bb:cc:dd:ee:ff"))
        val event = draft.toEvent()
        assertEquals(TriggerConfig.ConnectedBssid("aa:bb:cc:dd:ee:ff", listOf("11:22:33:44:55:66")), event.config)
        assertEquals(event.config, event.toDraft().toEvent().config)
        assertThrows(IllegalArgumentException::class.java) { draft.copy(additionalTextValues = listOf("")).toEvent() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(additionalTextValues = listOf("not-a-bssid")).toEvent() }
    }
    @Test fun changingInvertEstablishesNewStateBeforeFiring() {
        val existing = NotificationEvent(
            id = 1, config = TriggerConfig.ConnectedSsid("Home"), notificationBody = "Remember",
            triggerState = TriggerState.UNSATISFIED, createdAt = 10, lastTriggeredAt = 20,
        )
        val edited = existing.copy(invert = true).preserveRuntimeFrom(existing)
        assertEquals(TriggerState.UNKNOWN, edited.triggerState)
        assertEquals(existing.createdAt, edited.createdAt)
        assertEquals(existing.lastTriggeredAt, edited.lastTriggeredAt)
        val engine = TriggerEngine()
        val first = engine.evaluate(edited, TriggerObservation.WifiConnection(false, null, null, 30))
        assertFalse(first.fired)
        val home = engine.evaluate(first.event, TriggerObservation.WifiConnection(true, "Home", null, 40))
        assertTrue(engine.evaluate(home.event, TriggerObservation.WifiConnection(false, null, null, 50)).fired)
    }

    @Test fun editingTextPreservesArmedStateAndDisabledFlag() {
        val existing = NotificationEvent(
            config = TriggerConfig.ConnectedSsid("Home"), notificationBody = "Before",
            enabled = false, triggerState = TriggerState.SATISFIED,
        )
        val edited = existing.copy(notificationBody = "After", enabled = true).preserveRuntimeFrom(existing)
        assertEquals("After", edited.notificationBody)
        assertEquals(existing.triggerState, edited.triggerState)
        assertFalse(edited.enabled)
    }

    @Test fun editorRejectsNonFiniteDistancesAndRadii() {
        for (value in listOf("Infinity", "1e309", "NaN", "-Infinity")) {
            assertThrows(IllegalArgumentException::class.java) {
                EventDraft(body = "Remember", number1 = value).toEvent()
            }
            assertThrows(IllegalArgumentException::class.java) {
                EventDraft(type = TriggerType.GPS_CIRCLE, body = "Remember", number1 = "0", number2 = "0", number3 = value).toEvent()
            }
        }
        assertEquals(TriggerConfig.GpsCircle(0.0, 0.0, 150.0),
            EventDraft(type = TriggerType.GPS_CIRCLE, body = "Remember", number1 = "0", number2 = "0").toEvent().config)
    }
}
