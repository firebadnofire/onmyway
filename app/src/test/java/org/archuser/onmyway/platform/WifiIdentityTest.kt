package org.archuser.onmyway.platform

import org.archuser.onmyway.domain.NotificationEvent
import org.archuser.onmyway.domain.TriggerConfig
import org.archuser.onmyway.domain.TriggerEngine
import org.archuser.onmyway.domain.TriggerObservation
import org.junit.Assert.*
import org.junit.Test

class WifiIdentityTest {
    @Test fun redactedCallbackBssidUsesCurrentAssociation() {
        assertEquals("Home" to "aa:bb:cc:dd:ee:ff", selectWifiIdentity(
            "Home", "02:00:00:00:00:00", "\"Home\"", "AA-BB-CC-DD-EE-FF"))
    }

    @Test fun neverCombinesDifferentNetworksOrInventsRedactedIdentity() {
        assertEquals("Home" to null, selectWifiIdentity("Home", null, "Work", "aa:bb:cc:dd:ee:ff"))
        assertEquals("Home" to null, selectWifiIdentity("Home", null, "Home", "02:00:00:00:00:00"))
        assertEquals("Home" to "aa:bb:cc:dd:ee:ff",
            selectWifiIdentity("Home", "aa:bb:cc:dd:ee:ff", "Home", "11:22:33:44:55:66"))
    }

    @Test fun roamingWithinSameSsidTriggersConnectedApOnly() {
        val engine = TriggerEngine()
        fun observation(bssid: String): TriggerObservation.WifiConnection {
            val identity = selectWifiIdentity("Home", null, "Home", bssid)
            return TriggerObservation.WifiConnection(true, identity.first, identity.second, 1)
        }
        val home = NotificationEvent(config = TriggerConfig.ConnectedSsid("Home"), notificationBody = "SSID")
        val ap = NotificationEvent(config = TriggerConfig.ConnectedBssid("aa:bb:cc:dd:ee:ff"), notificationBody = "AP")
        val before = observation("11:22:33:44:55:66")
        val after = observation("aa:bb:cc:dd:ee:ff")
        assertFalse(engine.evaluate(engine.evaluate(home, before).event, after).fired)
        assertTrue(engine.evaluate(engine.evaluate(ap, before).event, after).fired)
    }
}
