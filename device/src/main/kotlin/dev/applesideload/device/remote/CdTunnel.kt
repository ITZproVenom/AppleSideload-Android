package dev.applesideload.device.remote

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.Transport
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress

/**
 * The CDTunnel handshake, the first thing said inside the TLS-PSK channel:
 * the host asks for an MTU and the device answers with both ends' IPv6
 * addresses, the MTU it settled on and the port of its RemoteServiceDiscovery
 * (RSD) service. Each message is the ASCII magic "CDTunnel", a two-byte
 * big-endian length and a JSON body. After it, the channel carries raw IPv6
 * packets for [TunnelStack].
 */
object CdTunnel {
    private val MAGIC = "CDTunnel".toByteArray(Charsets.US_ASCII)
    const val REQUESTED_MTU = 16000

    data class Parameters(
        val clientAddress: ByteArray,
        val serverAddress: ByteArray,
        val mtu: Int,
        val rsdPort: Int
    ) {
        val clientText: String get() = InetAddress.getByAddress(clientAddress).hostAddress ?: "?"
        val serverText: String get() = InetAddress.getByAddress(serverAddress).hostAddress ?: "?"

        override fun equals(other: Any?): Boolean = other is Parameters &&
            clientAddress.contentEquals(other.clientAddress) &&
            serverAddress.contentEquals(other.serverAddress) && mtu == other.mtu && rsdPort == other.rsdPort

        override fun hashCode(): Int = clientAddress.contentHashCode() * 31 + rsdPort
    }

    fun encode(json: JSONObject): ByteArray {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 0xFFFF) { "CDTunnel message too large" }
        return MAGIC + byteArrayOf((body.size ushr 8).toByte(), body.size.toByte()) + body
    }

    fun readMessage(transport: Transport, timeoutMs: Int): JSONObject {
        val magic = transport.readFully(MAGIC.size, timeoutMs)
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("the tunnel answered without the CDTunnel header (got ${magic.toHex()})")
        }
        val length = transport.readFully(2, timeoutMs).let {
            ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)
        }
        val body = transport.readFully(length, timeoutMs)
        return JSONObject(String(body, Charsets.UTF_8))
    }

    fun handshake(transport: Transport, mtu: Int = REQUESTED_MTU, timeoutMs: Int = 15_000): Parameters {
        transport.write(
            encode(JSONObject().put("type", "clientHandshakeRequest").put("mtu", mtu)),
            timeoutMs
        )
        val reply = readMessage(transport, timeoutMs)
        return parse(reply).also {
            Log.i(
                LogTag.TUNNEL,
                "CDTunnel up: this side ${it.clientText}, device ${it.serverText}, " +
                    "MTU ${it.mtu}, RSD port ${it.rsdPort}"
            )
        }
    }

    fun parse(reply: JSONObject): Parameters {
        val client = reply.optJSONObject("clientParameters")
            ?: throw IOException("CDTunnel reply has no clientParameters: $reply")
        val address = client.optString("address").takeIf { it.isNotEmpty() }
            ?: throw IOException("CDTunnel reply has no client address: $reply")
        val server = reply.optString("serverAddress").takeIf { it.isNotEmpty() }
            ?: throw IOException("CDTunnel reply has no serverAddress: $reply")
        val port = reply.optInt("serverRSDPort", -1)
        if (port !in 1..65535) throw IOException("CDTunnel reply has no usable serverRSDPort: $reply")
        val mtu = client.optInt("mtu", REQUESTED_MTU).takeIf { it in 1280..65535 } ?: 1280
        return Parameters(ipv6(address), ipv6(server), mtu, port)
    }

    /** Parses a literal IPv6 address without any DNS lookup. */
    fun ipv6(text: String): ByteArray {
        val trimmed = text.substringBefore('%').trim()
        require(trimmed.contains(':')) { "not an IPv6 address: $text" }
        val halves = trimmed.split("::")
        require(halves.size <= 2) { "not an IPv6 address: $text" }
        fun groups(part: String): List<Int> =
            if (part.isEmpty()) emptyList() else part.split(':').map {
                require(it.length in 1..4) { "not an IPv6 address: $text" }
                it.toInt(16)
            }
        val head = groups(halves[0])
        val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
        val all = if (halves.size == 2) {
            require(head.size + tail.size < 8) { "not an IPv6 address: $text" }
            head + List(8 - head.size - tail.size) { 0 } + tail
        } else head
        require(all.size == 8) { "not an IPv6 address: $text" }
        val out = ByteArray(16)
        all.forEachIndexed { i, g -> out[2 * i] = (g ushr 8).toByte(); out[2 * i + 1] = g.toByte() }
        return out
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
