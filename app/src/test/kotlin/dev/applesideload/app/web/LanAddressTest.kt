package dev.applesideload.app.web

import org.junit.Assert.assertEquals
import org.junit.Test

class LanAddressTest {
    @Test
    fun interfacesAreNamedForTheLinkTheyAreOn() {
        assertEquals("Wi-Fi", WebControl.label("wlan0"))
        assertEquals("Hotspot", WebControl.label("ap0"))
        assertEquals("Hotspot", WebControl.label("swlan0"))
        assertEquals("USB tethering (wired)", WebControl.label("rndis0"))
        assertEquals("USB tethering (wired)", WebControl.label("ncm0"))
        assertEquals("Ethernet (wired)", WebControl.label("eth0"))
        assertEquals("Bluetooth tethering", WebControl.label("bt-pan"))
    }
}
