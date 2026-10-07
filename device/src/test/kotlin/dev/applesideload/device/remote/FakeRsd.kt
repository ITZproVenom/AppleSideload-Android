package dev.applesideload.device.remote

import dev.applesideload.device.Transport
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The iPhone's RemoteServiceDiscovery endpoint for tests: the HTTP/2 server
 * side of the exchange, checking that the host sends exactly the frames
 * idevice sends, in the same order, and answering the way a device can: its
 * own SETTINGS, a PING, a message on the other stream, an empty dictionary,
 * and then the service list split over two DATA frames, the first one padded.
 */
internal class FakeRsd(private val reply: Map<String, Any?>) {
    data class Frame(val type: Int, val flags: Int, val stream: Int, val payload: ByteArray)

    /** The host's handshake dictionary, once it arrived. */
    @Volatile var hello: Map<*, *>? = null
        private set

    /** WINDOW_UPDATE increments the host sent after reading the reply. */
    val windowUpdates = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Long>>()

    fun serve(t: Transport) {
        assertContentEquals(Http2Connection.PREFACE, t.readFully(Http2Connection.PREFACE.size, 10_000), "preface")

        val settings = frame(t)
        assertEquals(Http2Connection.TYPE_SETTINGS to 0, settings.type to settings.stream, "the first frame is SETTINGS")
        assertEquals(0, settings.flags)
        assertEquals(listOf(3 to 100L, 4 to 1_048_576L), settingsOf(settings.payload))

        val update = frame(t)
        assertEquals(Http2Connection.TYPE_WINDOW_UPDATE to 0, update.type to update.stream, "then a connection WINDOW_UPDATE")
        assertEquals(983_041L, u32(update.payload, 0))

        expectHeaders(frame(t), 1)
        val first = message(frame(t), 1)
        assertEquals(XpcMessage.ALWAYS_SET, first.flags)
        assertEquals(1L, first.messageId)
        assertTrue(first.hasBody)
        assertEquals(emptyMap<String, Any?>(), first.body)

        expectHeaders(frame(t), 3)
        val open = message(frame(t), 3)
        assertEquals(XpcMessage.INIT_HANDSHAKE or XpcMessage.ALWAYS_SET, open.flags)
        assertEquals(1L, open.messageId)
        assertTrue(!open.hasBody)

        val second = message(frame(t), 1)
        assertEquals(0x201, second.flags)
        assertEquals(1L, second.messageId)
        assertTrue(!second.hasBody)

        val handshake = message(frame(t), 1)
        assertEquals(XpcMessage.DATA or XpcMessage.ALWAYS_SET, handshake.flags)
        assertEquals(1L, handshake.messageId)
        val body = handshake.body as Map<*, *>
        assertEquals("Handshake", body["MessageType"])
        assertEquals(XpcUInt64(7), body["MessagingProtocolVersion"])
        assertTrue(body["UUID"] is UUID)
        val properties = body["Properties"] as Map<*, *>
        assertEquals(XpcUInt64(0x0100000000000006L), properties["RemoteXPCVersionFlags"])
        assertEquals(true, properties["SensitivePropertiesVisible"])
        assertEquals(emptyMap<String, Any?>(), body["Services"])
        hello = body

        val ping = "pingpong".toByteArray()
        write(t, Http2Connection.TYPE_SETTINGS, 0, 0, settings(3 to 100, 4 to 1_048_576))
        write(t, Http2Connection.TYPE_PING, 0, 0, ping)
        write(t, Http2Connection.TYPE_DATA, 0, 3, XpcMessage(XpcMessage.INIT_HANDSHAKE or XpcMessage.ALWAYS_SET, 0, null, false).encode())
        write(t, Http2Connection.TYPE_DATA, 0, 1, XpcMessage(XpcMessage.ALWAYS_SET, 0, emptyMap<String, Any?>(), true).encode())
        val encoded = XpcMessage(XpcMessage.DATA or XpcMessage.ALWAYS_SET, 1, reply, true).encode()
        val cut = encoded.size / 3
        write(t, Http2Connection.TYPE_DATA, Http2Connection.FLAG_PADDED, 1, byteArrayOf(7) + encoded.copyOfRange(0, cut) + ByteArray(7))
        write(t, Http2Connection.TYPE_DATA, 0, 1, encoded.copyOfRange(cut, encoded.size))

        var settingsAcked = false
        var pingAcked = false
        while (!settingsAcked || !pingAcked) {
            val next = frame(t)
            when {
                next.type == Http2Connection.TYPE_SETTINGS && next.flags == Http2Connection.FLAG_ACK -> {
                    assertEquals(0, next.payload.size)
                    settingsAcked = true
                }
                next.type == Http2Connection.TYPE_PING && next.flags == Http2Connection.FLAG_ACK -> {
                    assertContentEquals(ping, next.payload)
                    pingAcked = true
                }
                next.type == Http2Connection.TYPE_WINDOW_UPDATE -> windowUpdates += next.stream to u32(next.payload, 0)
                else -> fail("unexpected frame from the host: type ${next.type} flags ${next.flags} stream ${next.stream}")
            }
        }
    }

    private fun expectHeaders(f: Frame, stream: Int) {
        assertEquals(Http2Connection.TYPE_HEADERS to stream, f.type to f.stream, "HEADERS opening stream $stream")
        assertEquals(Http2Connection.FLAG_END_HEADERS, f.flags)
        assertEquals(0, f.payload.size)
    }

    private fun message(f: Frame, stream: Int): XpcMessage {
        assertEquals(Http2Connection.TYPE_DATA to stream, f.type to f.stream, "DATA on stream $stream")
        assertEquals(0, f.flags)
        val (message, used) = XpcMessage.decode(f.payload, f.payload.size) ?: fail("a DATA frame with a partial message")
        assertEquals(f.payload.size, used, "one message per frame")
        return message
    }

    companion object {
        fun frame(t: Transport): Frame {
            val header = t.readFully(9, 10_000)
            val length = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
            val stream = (((header[5].toInt() and 0x7F) shl 24) or ((header[6].toInt() and 0xFF) shl 16) or
                ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF))
            val payload = if (length > 0) t.readFully(length, 10_000) else ByteArray(0)
            return Frame(header[3].toInt() and 0xFF, header[4].toInt() and 0xFF, stream, payload)
        }

        fun write(t: Transport, type: Int, flags: Int, stream: Int, payload: ByteArray) {
            val header = byteArrayOf(
                (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(),
                type.toByte(), flags.toByte(),
                (stream ushr 24).toByte(), (stream ushr 16).toByte(), (stream ushr 8).toByte(), stream.toByte()
            )
            t.write(header + payload, 10_000)
        }

        fun settings(vararg pairs: Pair<Int, Int>): ByteArray = pairs.fold(ByteArray(0)) { out, (id, value) ->
            out + byteArrayOf((id ushr 8).toByte(), id.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        }

        fun settingsOf(payload: ByteArray): List<Pair<Int, Long>> = (0 until payload.size / 6).map {
            (((payload[it * 6].toInt() and 0xFF) shl 8) or (payload[it * 6 + 1].toInt() and 0xFF)) to u32(payload, it * 6 + 2)
        }

        fun u32(b: ByteArray, i: Int): Long =
            ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

        /** A service list shaped like an iPhone's, with the lockdown shim and one RemoteXPC service. */
        fun sampleReply(lockdownPort: Int = 50001): Map<String, Any?> = linkedMapOf(
            "MessageType" to "Handshake",
            "MessagingProtocolVersion" to XpcUInt64(7),
            "UUID" to UUID.fromString("2BE6E510-0325-4365-923E-B14C6F57DB3A"),
            "Properties" to linkedMapOf<String, Any?>(
                "ProductType" to "iPhone18,1",
                "OSVersion" to "27.0",
                "UniqueDeviceID" to FakeIphone.UDID,
                "BoardId" to XpcUInt64(12)
            ),
            "Services" to linkedMapOf<String, Any?>(
                RemoteTunnel.LOCKDOWN to linkedMapOf<String, Any?>(
                    "Port" to lockdownPort.toString(),
                    "Entitlement" to "com.apple.mobile.lockdown.remote.trusted",
                    "Properties" to linkedMapOf<String, Any?>("UsesRemoteXPC" to false)
                ),
                "com.apple.coredevice.appservice" to linkedMapOf<String, Any?>(
                    "Port" to "50002",
                    "Entitlement" to "com.apple.private.CoreDevice.canInstallCustomerContent",
                    "Properties" to linkedMapOf<String, Any?>("UsesRemoteXPC" to true, "ServiceVersion" to 1L)
                ),
                "com.apple.broken.service" to linkedMapOf<String, Any?>("Entitlement" to "no port at all")
            )
        )
    }
}
