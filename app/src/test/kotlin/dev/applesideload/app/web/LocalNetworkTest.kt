package dev.applesideload.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalNetworkTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun privateAndLinkLocalAddressesAreLocal() {
        listOf(
            "192.168.1.20", "10.0.0.7", "172.16.4.1", "172.31.255.1", "169.254.10.2",
            "127.0.0.1", "::1", "fe80::1", "fd12:3456::1", "::ffff:192.168.43.5"
        ).forEach { assertTrue(it, LocalNetwork.isPrivate(ip(it))) }
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "2001:4860:4860::8888", "::ffff:8.8.8.8")
            .forEach { assertFalse(it, LocalNetwork.isPrivate(ip(it))) }
    }

    @Test
    fun subnetsMatchByPrefixAndTooBroadOnesNever() {
        assertTrue(LocalNetwork.sameSubnet(ip("130.89.4.20"), ip("130.89.7.1"), 22))
        assertFalse(LocalNetwork.sameSubnet(ip("130.89.4.20"), ip("130.89.8.1"), 22))
        assertFalse(LocalNetwork.sameSubnet(ip("130.89.4.20"), ip("130.1.1.1"), 8))
        assertTrue(LocalNetwork.sameSubnet(ip("2001:db8:1:2::10"), ip("2001:db8:1:2::99"), 64))
        assertFalse(LocalNetwork.sameSubnet(ip("2001:db8:1:2::10"), ip("2001:db8:1:3::99"), 64))
        assertFalse(LocalNetwork.sameSubnet(ip("2001:db8:1:2::10"), ip("2001:db8:1:2::99"), 16))
        assertFalse(LocalNetwork.sameSubnet(ip("130.89.4.20"), ip("2001:db8::1"), 64))
    }

    @Test
    fun onlyAddressesAreAcceptedAsTheHost() {
        listOf("192.168.1.20:8686", "192.168.1.20", "localhost:8686", "LOCALHOST", "[fe80::1]:8686", "[::1]")
            .forEach { assertTrue(it, WebApi.isDirectHost(it)) }
        listOf(
            "", "attacker.example", "attacker.example:8686", "192.168.1.20.nip.io:8686", "phone.local:8686",
            "[::1]x", "[fe80::1", "192.168.1.20:80:80", "192.168.1.20:abc", "[127.0.0.1]:8686"
        ).forEach { assertFalse(it, WebApi.isDirectHost(it)) }
    }
}
