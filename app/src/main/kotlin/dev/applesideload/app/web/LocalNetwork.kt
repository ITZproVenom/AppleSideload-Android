package dev.applesideload.app.web

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Which devices count as "on the same network" for the web controller,
 * which has no login.
 *
 * A request is answered when it comes from this phone, from a private or
 * link-local address (home Wi-Fi, a hotspot, USB tethering), or from inside
 * the subnet of one of this phone's own non-mobile interfaces, for networks
 * that hand their devices public addresses. Anything else, such as a request
 * arriving over mobile data, is refused.
 */
object LocalNetwork {

    fun contains(address: InetAddress): Boolean = isPrivate(address) || onOwnSubnet(address)

    /** Loopback, RFC 1918, link-local and IPv6 unique local addresses. */
    fun isPrivate(address: InetAddress): Boolean {
        val plain = unmapped(address)
        if (plain.isLoopbackAddress || plain.isSiteLocalAddress || plain.isLinkLocalAddress) return true
        // fc00::/7, the IPv6 equivalent of the RFC 1918 ranges.
        return plain is Inet6Address && (plain.address[0].toInt() and 0xFE) == 0xFC
    }

    /** Whether [other] is inside [own]/[prefix]. Too broad a prefix never matches. */
    fun sameSubnet(own: InetAddress, other: InetAddress, prefix: Int): Boolean {
        val a = unmapped(own).address
        val b = unmapped(other).address
        if (a.size != b.size) return false
        val narrowest = if (a.size == 4) 16 else 48
        if (prefix < narrowest || prefix > a.size * 8) return false
        val whole = prefix / 8
        for (i in 0 until whole) if (a[i] != b[i]) return false
        val rest = prefix % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (a[whole].toInt() and mask) == (b[whole].toInt() and mask)
    }

    private fun onOwnSubnet(address: InetAddress): Boolean {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return false
        return interfaces.any { network ->
            val usable = runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) &&
                !WebControl.label(network.name).startsWith("Mobile")
            usable && network.interfaceAddresses.any { entry ->
                val own = entry.address ?: return@any false
                sameSubnet(own, address, entry.networkPrefixLength.toInt())
            }
        }
    }

    /** ::ffff:a.b.c.d is an IPv4 client on a dual-stack socket. */
    private fun unmapped(address: InetAddress): InetAddress {
        if (address !is Inet6Address) return address
        val bytes = address.address
        val mapped = (0 until 10).all { bytes[it].toInt() == 0 } &&
            bytes[10].toInt() == -1 && bytes[11].toInt() == -1
        return if (mapped) InetAddress.getByAddress(bytes.copyOfRange(12, 16)) else address
    }
}
