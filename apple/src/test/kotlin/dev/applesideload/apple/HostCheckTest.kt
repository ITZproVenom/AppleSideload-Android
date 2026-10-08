package dev.applesideload.apple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Certificate names as RFC 6125 matches them, for the WebSocket's own check. */
class HostCheckTest {

    @Test
    fun exactNamesMatchWhateverTheCase() {
        assertTrue(HostCheck.dnsMatches("ani.sidestore.io", "ani.sidestore.io"))
        assertTrue(HostCheck.dnsMatches("ani.sidestore.io", "ANI.SideStore.io"))
        assertTrue(HostCheck.dnsMatches("ani.sidestore.io", "ani.sidestore.io."))
        assertFalse(HostCheck.dnsMatches("ani.sidestore.io", "other.sidestore.io"))
    }

    @Test
    fun aWildcardCoversExactlyOneLeftMostLabel() {
        assertTrue(HostCheck.dnsMatches("ani.sidestore.io", "*.sidestore.io"))
        assertFalse("not two labels", HostCheck.dnsMatches("a.b.sidestore.io", "*.sidestore.io"))
        assertFalse("not the bare domain", HostCheck.dnsMatches("sidestore.io", "*.sidestore.io"))
        assertFalse("not a look-alike", HostCheck.dnsMatches("evilsidestore.io", "*.sidestore.io"))
        assertFalse("never a whole top-level domain", HostCheck.dnsMatches("sidestore.io", "*.io"))
        assertFalse("only a whole left-most label", HostCheck.dnsMatches("ani.sidestore.io", "a*.sidestore.io"))
    }

    @Test
    fun ipAddressesAreRecognised() {
        assertTrue(HostCheck.isIpLiteral("192.168.1.13"))
        assertTrue(HostCheck.isIpLiteral("::1"))
        assertFalse(HostCheck.isIpLiteral("ani.sidestore.io"))
    }
}
