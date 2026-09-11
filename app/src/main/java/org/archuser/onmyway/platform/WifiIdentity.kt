package org.archuser.onmyway.platform

import org.archuser.onmyway.domain.normalizeBssid
import org.archuser.onmyway.domain.normalizeSsid

/** Keep identity pairs together; never attach another network's AP to an SSID. */
internal fun selectWifiIdentity(
    callbackSsid: String?, callbackBssid: String?, currentSsid: String?, currentBssid: String?,
): Pair<String?, String?> {
    val callback = normalizeSsid(callbackSsid) to normalizeBssid(callbackBssid)
    val current = normalizeSsid(currentSsid) to normalizeBssid(currentBssid)
    return if (callback.second == null && current.second != null &&
        (callback.first == null || callback.first == current.first)) current else callback
}
