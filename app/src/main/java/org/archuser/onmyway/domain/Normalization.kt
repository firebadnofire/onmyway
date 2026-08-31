package org.archuser.onmyway.domain

private val bssidPattern = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

fun normalizeSsid(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.equals("<unknown ssid>", ignoreCase = true)) return null
    return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        trimmed.substring(1, trimmed.lastIndex).takeIf { it.isNotEmpty() }
    } else {
        trimmed
    }
}

fun normalizeBssid(value: String?): String? {
    val normalized = value?.trim()?.replace('-', ':')?.lowercase() ?: return null
    return normalized.takeIf { bssidPattern.matches(it) && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
}

fun isValidBssid(value: String): Boolean = normalizeBssid(value) != null
