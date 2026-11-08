package com.jamina.wifi

import com.jamina.core.model.Band

/** A single access point (BSSID) as seen in one scan. Never synthesised. */
data class ApObservation(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val band: Band?,
    val widthMhz: Int,
    val centerFreq0: Int,
    val standard: String,
    val security: String,
    val capabilities: String,
    val vendor: String,
    /** Age of the reading when captured (ms), from the scan result's own timestamp. */
    val ageMs: Long,
) {
    val displaySsid: String get() = ssid.ifBlank { "(hidden network)" }

    /** Occupied spectrum in MHz [low, high]. */
    fun span(): Pair<Int, Int> = WifiMath.span(frequency, centerFreq0, widthMhz)
}

object WifiMath {
    fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5000..5895 -> (freq - 5000) / 5
        freq == 5935 -> 2
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> -1
    }

    /** Centre frequency (MHz) for a primary channel in a band. */
    fun freqOf(channel: Int, band: Band): Int = when (band) {
        Band.B24 -> if (channel == 14) 2484 else 2407 + channel * 5
        Band.B5 -> 5000 + channel * 5
        Band.B6 -> 5950 + channel * 5
    }

    /** Converts a ScanResult.channelWidth constant into MHz. */
    fun widthFromCode(code: Int): Int = when (code) {
        0 -> 20
        1 -> 40
        2 -> 80
        3 -> 160
        4 -> 160 // 80+80
        5 -> 320
        else -> 20
    }

    fun span(frequency: Int, centerFreq0: Int, widthMhz: Int): Pair<Int, Int> {
        val band = Band.fromFreq(frequency)
        val w = if (band == Band.B24 && widthMhz <= 20) 22 else widthMhz
        val centre = if (widthMhz > 20 && centerFreq0 > 0) centerFreq0 else frequency
        return (centre - w / 2) to (centre + w / 2)
    }

    fun overlaps(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean = a.first < b.second && b.first < a.second

    fun securityOf(capabilities: String): String {
        val c = capabilities.uppercase()
        val enterprise = c.contains("EAP")
        val base = when {
            c.contains("SAE") && c.contains("PSK") -> "WPA2/WPA3"
            c.contains("SAE") -> "WPA3"
            c.contains("OWE") -> "OWE (enhanced open)"
            c.contains("RSN") || c.contains("WPA2") -> "WPA2"
            c.contains("WPA") -> "WPA"
            c.contains("WEP") -> "WEP"
            else -> "Open"
        }
        return if (enterprise) "$base Enterprise" else base
    }

    /** ScanResult.WIFI_STANDARD_* constants. */
    fun standardLabel(code: Int): String = when (code) {
        1 -> "802.11a/b/g"
        4 -> "Wi-Fi 4 (11n)"
        5 -> "Wi-Fi 5 (11ac)"
        6 -> "Wi-Fi 6/6E (11ax)"
        7 -> "11ad"
        8 -> "Wi-Fi 7 (11be)"
        else -> "Unknown"
    }

    private val oui = mapOf(
        "50:C7:BF" to "TP-Link", "98:DA:C4" to "TP-Link", "14:EB:B6" to "TP-Link", "60:32:B1" to "TP-Link",
        "B0:4E:26" to "TP-Link", "C0:06:C3" to "TP-Link", "E4:C3:2A" to "TP-Link", "3C:52:A1" to "TP-Link",
        "F4:F2:6D" to "TP-Link", "AC:84:C6" to "TP-Link", "70:4F:57" to "TP-Link", "A4:2B:B0" to "TP-Link",
        "54:AF:97" to "TP-Link", "F8:1A:67" to "TP-Link", "C4:E9:84" to "TP-Link", "30:B5:C2" to "TP-Link",
        "E8:48:B8" to "TP-Link", "9C:A2:F4" to "TP-Link", "5C:A6:E6" to "TP-Link",
        "24:5A:4C" to "Ubiquiti", "74:83:C2" to "Ubiquiti", "F0:9F:C2" to "Ubiquiti", "78:8A:20" to "Ubiquiti",
        "E0:63:DA" to "Ubiquiti", "68:D7:9A" to "Ubiquiti", "FC:EC:DA" to "Ubiquiti", "AC:8B:A9" to "Ubiquiti",
        "18:E8:29" to "Ubiquiti", "B4:FB:E4" to "Ubiquiti", "D0:21:F9" to "Ubiquiti", "74:AC:B9" to "Ubiquiti",
        "A0:40:A0" to "Netgear", "9C:3D:CF" to "Netgear", "28:80:88" to "Netgear", "B0:7F:B9" to "Netgear",
        "2C:30:33" to "Netgear", "00:26:F2" to "Netgear", "08:36:C9" to "Netgear", "3C:37:86" to "Netgear",
        "8C:3B:AD" to "Netgear", "94:A6:7E" to "Netgear", "6C:CD:D6" to "Netgear", "00:1F:33" to "Netgear",
        "E4:F4:C6" to "Netgear", "10:0C:6B" to "Netgear", "BC:A5:11" to "Netgear", "9C:C9:EB" to "Netgear",
        "44:A5:6E" to "Netgear", "78:D2:94" to "Netgear", "80:CC:9C" to "Netgear", "C4:04:15" to "Netgear",
        "00:1A:A1" to "Cisco", "70:DB:98" to "Cisco", "F4:DB:E6" to "Cisco", "00:3A:7D" to "Cisco",
        "E0:CB:BC" to "Cisco Meraki", "88:15:44" to "Cisco Meraki", "0C:8D:DB" to "Cisco Meraki",
        "34:56:FE" to "Cisco Meraki", "A8:46:9D" to "Cisco Meraki", "00:18:0A" to "Cisco Meraki",
        "04:D9:F5" to "ASUS", "2C:FD:A1" to "ASUS", "1C:87:2C" to "ASUS", "D8:50:E6" to "ASUS", "4C:ED:FB" to "ASUS",
        "38:10:D5" to "AVM (FRITZ!Box)", "C8:0E:14" to "AVM (FRITZ!Box)",
        "F8:AB:05" to "Sagemcom", "34:8A:AE" to "Sagemcom", "E8:AD:A6" to "Sagemcom",
        "C0:3C:04" to "Sagemcom", "84:A1:D1" to "Sagemcom",
        "00:1D:7E" to "Linksys", "00:23:69" to "Linksys", "C0:C1:C0" to "Linksys", "58:6D:8F" to "Linksys",
        "20:AA:4B" to "Linksys", "C0:56:27" to "Belkin", "D8:EC:5E" to "Belkin", "60:38:E0" to "Belkin",
        "14:91:82" to "Belkin", "30:23:03" to "Belkin",
        "B8:D5:26" to "Zyxel", "5C:E2:8C" to "Zyxel", "40:4A:03" to "Zyxel",
        "AC:22:05" to "Google", "F0:72:EA" to "Google", "18:B4:30" to "Google Nest", "D4:F5:47" to "Google",
        "84:D4:7E" to "Aruba", "94:B4:0F" to "Aruba", "20:4C:03" to "Aruba", "00:0B:86" to "Aruba", "6C:F3:7F" to "Aruba",
        "88:71:B1" to "Arris", "AC:F8:CC" to "Arris", "C8:3A:35" to "Tenda", "E8:65:D4" to "Tenda",
        "E8:F7:24" to "Huawei", "00:E0:FC" to "Huawei", "48:46:FB" to "Huawei", "9C:28:EF" to "Huawei",
        "64:D1:54" to "MikroTik", "4C:5E:0C" to "MikroTik", "DC:2C:6E" to "MikroTik", "B8:69:F4" to "MikroTik",
        "C4:AD:34" to "MikroTik", "48:8F:5A" to "MikroTik", "2C:C8:1B" to "MikroTik", "18:FD:74" to "MikroTik",
        "B8:27:EB" to "Raspberry Pi", "DC:A6:32" to "Raspberry Pi", "00:24:A5" to "Buffalo",
    )

    fun vendorOf(bssid: String): String {
        val up = bssid.uppercase()
        if (up.length < 8) return "Unknown"
        val firstOctet = up.substring(0, 2).toIntOrNull(16) ?: return "Unknown"
        val known = oui[up.substring(0, 8)]
        if (known != null) return known
        return if (firstOctet and 0x02 != 0) "Locally administered (virtual/mesh BSSID)" else "Unknown"
    }
}
