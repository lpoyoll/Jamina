package com.jamina.planner

/**
 * Small built-in equipment database. Transmit powers are typical regulatory-limited EIRP-ish
 * figures for UK/EU use and should be adjusted to match the actual device configuration.
 */
data class ApProfile(
    val vendor: String,
    val model: String,
    val standard: String,
    val tx24: Float,
    val tx5: Float,
    val tx6: Float?,
    val gain24: Float,
    val gain5: Float,
    val gain6: Float?,
    val maxWidth5: Int,
) {
    val label: String get() = if (vendor == "Generic") model else "$vendor $model"
}

object ApCatalog {
    val profiles = listOf(
        ApProfile("Generic", "Generic router", "Wi-Fi 6", 17f, 20f, null, 3f, 4f, null, 80),
        ApProfile("Generic", "ISP hub (low power)", "Wi-Fi 5", 15f, 18f, null, 2f, 3f, null, 80),
        ApProfile("Generic", "Mesh node", "Wi-Fi 6", 17f, 20f, null, 3f, 4f, null, 80),
        ApProfile("Generic", "Wi-Fi 6E mesh node", "Wi-Fi 6E", 17f, 20f, 18f, 3f, 4f, 4f, 160),
        ApProfile("Generic", "Wi-Fi 7 router", "Wi-Fi 7", 18f, 20f, 18f, 4f, 5f, 5f, 160),
        ApProfile("Ubiquiti", "U6 Lite", "Wi-Fi 6", 20f, 23f, null, 2.8f, 3f, null, 80),
        ApProfile("Ubiquiti", "U6 Pro", "Wi-Fi 6", 22f, 26f, null, 4f, 6f, null, 160),
        ApProfile("Ubiquiti", "U6 Enterprise", "Wi-Fi 6E", 22f, 26f, 23f, 4f, 6f, 6f, 160),
        ApProfile("Ubiquiti", "U7 Pro", "Wi-Fi 7", 22f, 26f, 23f, 4f, 6f, 6f, 160),
        ApProfile("TP-Link", "Deco X20", "Wi-Fi 6", 18f, 20f, null, 3f, 4f, null, 80),
        ApProfile("TP-Link", "Deco XE75", "Wi-Fi 6E", 18f, 20f, 18f, 3f, 4f, 4f, 160),
        ApProfile("TP-Link", "Omada EAP650", "Wi-Fi 6", 22f, 23f, null, 4f, 5f, null, 160),
        ApProfile("TP-Link", "Omada EAP773", "Wi-Fi 7", 22f, 23f, 23f, 4f, 5f, 5f, 160),
        ApProfile("Netgear", "Orbi RBK752", "Wi-Fi 6", 18f, 20f, null, 3f, 4f, null, 80),
        ApProfile("Netgear", "WAX620", "Wi-Fi 6", 21f, 23f, null, 4f, 5f, null, 160),
        ApProfile("Cisco", "Catalyst CW9166", "Wi-Fi 6E", 21f, 23f, 23f, 4f, 5f, 5f, 160),
        ApProfile("Cisco", "Meraki MR36", "Wi-Fi 6", 21f, 23f, null, 4f, 6f, null, 80),
        ApProfile("ASUS", "ZenWiFi XT8", "Wi-Fi 6", 18f, 20f, null, 3f, 4f, null, 80),
        ApProfile("AVM", "FRITZ!Box 7590 AX", "Wi-Fi 6", 18f, 20f, null, 3f, 4f, null, 80),
    )

    fun find(model: String): ApProfile? = profiles.firstOrNull { it.model == model }
}
