package dev.applesideload.device.remote

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.Transport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** An XPC unsigned 64-bit integer, kept apart from int64 because the wire types differ. */
data class XpcUInt64(val value: Long)

/** An XPC date: nanoseconds since 1970. */
data class XpcDate(val nanos: Long)

/**
 * The XPC object serialisation RemoteXPC uses (little-endian, 4-byte aligned).
 *
 * Kotlin values map as: Map<String, Any?> = dictionary, List = array,
 * String, Long/Int = int64, [XpcUInt64], Double, Boolean, ByteArray = data,
 * UUID, [XpcDate], null.
 */
object XpcCodec {
    const val BODY_MAGIC = 0x42133742
    const val BODY_VERSION = 5

    private const val NULL = 0x1000
    private const val BOOL = 0x2000
    private const val INT64 = 0x3000
    private const val UINT64 = 0x4000
    private const val DOUBLE = 0x5000
    private const val DATE = 0x7000
    private const val DATA = 0x8000
    private const val STRING = 0x9000
    private const val UUID_TYPE = 0xa000
    private const val ARRAY = 0xe000
    private const val DICTIONARY = 0xf000
    private const val FILE_TRANSFER = 0x1a000

    fun encode(value: Any?): ByteArray {
        val out = Writer()
        out.u32(BODY_MAGIC)
        out.u32(BODY_VERSION)
        encodeObject(value, out)
        return out.bytes()
    }

    fun decode(body: ByteArray): Any? {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 8) throw IOException("XPC body is only ${body.size} bytes")
        val magic = buf.int
        if (magic != BODY_MAGIC) throw IOException("XPC body has the wrong magic 0x${Integer.toHexString(magic)}")
        val version = buf.int
        if (version != BODY_VERSION) throw IOException("XPC body version $version is not 5")
        return try {
            decodeObject(buf)
        } catch (error: java.nio.BufferUnderflowException) {
            throw IOException("XPC body ended in the middle of an object", error)
        }
    }

    private fun pad(length: Int) = (4 - length % 4) % 4

    private fun encodeObject(value: Any?, out: Writer) {
        when (value) {
            null -> out.u32(NULL)
            is Boolean -> { out.u32(BOOL); out.raw(byteArrayOf(if (value) 1 else 0, 0, 0, 0)) }
            is Int -> { out.u32(INT64); out.u64(value.toLong()) }
            is Long -> { out.u32(INT64); out.u64(value) }
            is XpcUInt64 -> { out.u32(UINT64); out.u64(value.value) }
            is Double -> { out.u32(DOUBLE); out.u64(java.lang.Double.doubleToRawLongBits(value)) }
            is XpcDate -> { out.u32(DATE); out.u64(value.nanos) }
            is String -> {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.u32(STRING)
                out.u32(bytes.size + 1)
                out.raw(bytes)
                out.raw(ByteArray(1 + pad(bytes.size + 1)))
            }
            is ByteArray -> {
                out.u32(DATA)
                out.u32(value.size)
                out.raw(value)
                out.raw(ByteArray(pad(value.size)))
            }
            is UUID -> {
                out.u32(UUID_TYPE)
                val b = ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits)
                out.raw(b.array())
            }
            is List<*> -> {
                val content = Writer()
                content.u32(value.size)
                for (item in value) encodeObject(item, content)
                out.u32(ARRAY)
                out.u32(content.size)
                out.raw(content.bytes())
            }
            is Map<*, *> -> {
                val content = Writer()
                content.u32(value.size)
                for ((k, v) in value) {
                    val key = (k as? String ?: throw IllegalArgumentException("XPC keys must be strings")).toByteArray(Charsets.UTF_8)
                    content.raw(key)
                    content.raw(ByteArray(1 + pad(key.size + 1)))
                    encodeObject(v, content)
                }
                out.u32(DICTIONARY)
                out.u32(content.size)
                out.raw(content.bytes())
            }
            else -> throw IllegalArgumentException("cannot encode ${value::class.java.name} as XPC")
        }
    }

    private fun decodeObject(buf: ByteBuffer): Any? {
        return when (val type = buf.int) {
            NULL -> null
            BOOL -> { val b = buf.get(); buf.position(buf.position() + 3); b.toInt() != 0 }
            INT64 -> buf.long
            UINT64 -> XpcUInt64(buf.long)
            DOUBLE -> java.lang.Double.longBitsToDouble(buf.long)
            DATE -> XpcDate(buf.long)
            STRING -> {
                val length = buf.int
                if (length < 1 || length > buf.remaining()) throw IOException("XPC string length $length is out of range")
                val bytes = ByteArray(length)
                buf.get(bytes)
                skip(buf, pad(length))
                String(bytes, 0, length - 1, Charsets.UTF_8)
            }
            DATA -> {
                val length = buf.int
                if (length < 0 || length > buf.remaining()) throw IOException("XPC data length $length is out of range")
                val bytes = ByteArray(length)
                buf.get(bytes)
                skip(buf, pad(length))
                bytes
            }
            UUID_TYPE -> {
                // UUID bytes are in network order regardless of the body's endianness.
                val raw = ByteBuffer.wrap(ByteArray(16).also { buf.get(it) })
                UUID(raw.long, raw.long)
            }
            ARRAY -> {
                buf.int // byte length
                val count = buf.int
                if (count < 0 || count > buf.remaining()) throw IOException("XPC array count $count is out of range")
                List(count) { decodeObject(buf) }
            }
            DICTIONARY -> {
                buf.int // byte length
                val count = buf.int
                if (count < 0 || count > buf.remaining()) throw IOException("XPC dictionary count $count is out of range")
                val map = LinkedHashMap<String, Any?>()
                repeat(count) {
                    val start = buf.position()
                    var end = start
                    while (end < buf.limit() && buf.get(end) != 0.toByte()) end++
                    if (end >= buf.limit()) throw IOException("XPC dictionary key is not terminated")
                    val key = String(ByteArray(end - start).also { buf.get(it) }, Charsets.UTF_8)
                    buf.get() // NUL
                    skip(buf, pad(end - start + 1))
                    map[key] = decodeObject(buf)
                }
                map
            }
            FILE_TRANSFER -> {
                val id = buf.long
                mapOf("FileTransferMessageId" to id, "FileTransferData" to decodeObject(buf))
            }
            else -> throw IOException("unknown XPC type 0x${Integer.toHexString(type)}")
        }
    }

    private fun skip(buf: ByteBuffer, n: Int) {
        buf.position(minOf(buf.limit(), buf.position() + n))
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        val size: Int get() = out.size()
        fun u32(v: Int) { out.write(v); out.write(v ushr 8); out.write(v ushr 16); out.write(v ushr 24) }
        fun u64(v: Long) { for (i in 0 until 8) out.write((v ushr (8 * i)).toInt()) }
        fun raw(b: ByteArray) { out.write(b) }
        fun bytes(): ByteArray = out.toByteArray()
    }
}

/** One RemoteXPC message: the 24-byte wrapper and an optional body. */
class XpcMessage(val flags: Int, val messageId: Long, val body: Any?, val hasBody: Boolean) {
    fun encode(): ByteArray {
        val payload = if (hasBody) XpcCodec.encode(body) else ByteArray(0)
        val out = ByteBuffer.allocate(WRAPPER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(WRAPPER_MAGIC)
        out.putInt(flags)
        out.putLong(payload.size.toLong())
        out.putLong(messageId)
        out.put(payload)
        return out.array()
    }

    companion object {
        const val WRAPPER = 24
        const val WRAPPER_MAGIC = 0x29b00b92
        const val ALWAYS_SET = 0x00000001
        const val DATA = 0x00000100
        const val WANTING_REPLY = 0x00010000
        const val REPLY = 0x00020000
        const val INIT_HANDSHAKE = 0x00400000

        /** Returns the message and how many bytes it used, or null if [buffer] holds less than one. */
        fun decode(buffer: ByteArray, length: Int): Pair<XpcMessage, Int>? {
            if (length < WRAPPER) return null
            val b = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            val magic = b.int
            if (magic != WRAPPER_MAGIC) throw IOException("RemoteXPC frame has the wrong magic 0x${Integer.toHexString(magic)}")
            val flags = b.int
            val bodyLength = b.long
            val id = b.long
            if (bodyLength < 0 || bodyLength > MAX_BODY) throw IOException("RemoteXPC body of $bodyLength bytes is out of range")
            val total = WRAPPER + bodyLength.toInt()
            if (length < total) return null
            val body = if (bodyLength > 0) XpcCodec.decode(buffer.copyOfRange(WRAPPER, total)) else null
            return XpcMessage(flags, id, body, bodyLength > 0) to total
        }

        private const val MAX_BODY = 64L shl 20
    }
}

/**
 * Just enough HTTP/2 to carry RemoteXPC: no HPACK (Apple's peer sends and
 * accepts empty HEADERS), flow control in both directions, SETTINGS and PING
 * acknowledgements. Single-threaded: the caller alternates sends and reads.
 */
class Http2Connection(private val transport: Transport) {
    private var sendWindow = 65_535L
    private val streamSendWindows = HashMap<Int, Long>()
    private var peerInitialWindow = 65_535L
    private val inbound = HashMap<Int, ByteArrayOutputStream>()
    private var buffer = ByteArray(64 * 1024)
    private var filled = 0

    fun start() {
        transport.write(PREFACE)
        writeFrame(TYPE_SETTINGS, 0, 0, settings(SETTINGS_MAX_CONCURRENT_STREAMS to 100, SETTINGS_INITIAL_WINDOW_SIZE to 1_048_576))
        writeFrame(TYPE_WINDOW_UPDATE, 0, 0, u32(983_041))
    }

    fun openStream(stream: Int) {
        inbound.getOrPut(stream) { ByteArrayOutputStream() }
        writeFrame(TYPE_HEADERS, FLAG_END_HEADERS, stream, ByteArray(0))
    }

    fun send(stream: Int, payload: ByteArray, timeoutMs: Int) {
        if (payload.isEmpty()) {
            writeFrame(TYPE_DATA, 0, stream, payload)
            return
        }
        var at = 0
        while (at < payload.size) {
            val n = minOf(MAX_FRAME, payload.size - at).toLong()
            while (sendWindow < n || streamWindow(stream) < n) pump(timeoutMs)
            writeFrame(TYPE_DATA, 0, stream, payload.copyOfRange(at, at + n.toInt()))
            sendWindow -= n
            streamSendWindows[stream] = streamWindow(stream) - n
            at += n.toInt()
        }
    }

    /** Bytes received so far on [stream], consumed by the caller. */
    fun buffered(stream: Int): ByteArrayOutputStream = inbound.getOrPut(stream) { ByteArrayOutputStream() }

    /** Reads and handles one frame. */
    fun pump(timeoutMs: Int) {
        while (filled < 9 || filled < 9 + length(buffer)) {
            if (filled >= 9 && 9 + length(buffer) > buffer.size) buffer = buffer.copyOf(9 + length(buffer))
            val n = transport.read(buffer, filled, buffer.size - filled, timeoutMs)
            if (n < 0) throw IOException("the device closed the RemoteXPC connection")
            filled += n
        }
        val len = length(buffer)
        val type = buffer[3].toInt() and 0xFF
        val flags = buffer[4].toInt() and 0xFF
        val stream = ((buffer[5].toInt() and 0x7F) shl 24) or ((buffer[6].toInt() and 0xFF) shl 16) or
            ((buffer[7].toInt() and 0xFF) shl 8) or (buffer[8].toInt() and 0xFF)
        val payload = buffer.copyOfRange(9, 9 + len)
        System.arraycopy(buffer, 9 + len, buffer, 0, filled - 9 - len)
        filled -= 9 + len
        when (type) {
            TYPE_DATA -> {
                var start = 0
                var end = payload.size
                if (flags and FLAG_PADDED != 0 && payload.isNotEmpty()) {
                    start = 1
                    end -= payload[0].toInt() and 0xFF
                }
                if (end > start) buffered(stream).write(payload, start, end - start)
                if (len > 0) {
                    writeFrame(TYPE_WINDOW_UPDATE, 0, 0, u32(len))
                    writeFrame(TYPE_WINDOW_UPDATE, 0, stream, u32(len))
                }
            }
            TYPE_SETTINGS -> if (flags and FLAG_ACK == 0) {
                var i = 0
                while (i + 6 <= payload.size) {
                    val id = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
                    val value = readU32(payload, i + 2)
                    if (id == SETTINGS_INITIAL_WINDOW_SIZE) {
                        val delta = value - peerInitialWindow
                        peerInitialWindow = value
                        for (key in streamSendWindows.keys.toList()) streamSendWindows[key] = streamSendWindows[key]!! + delta
                    }
                    i += 6
                }
                writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, ByteArray(0))
            }
            TYPE_WINDOW_UPDATE -> if (payload.size >= 4) {
                val inc = readU32(payload, 0) and 0x7FFFFFFF
                if (stream == 0) sendWindow += inc else streamSendWindows[stream] = streamWindow(stream) + inc
            }
            TYPE_PING -> if (flags and FLAG_ACK == 0) writeFrame(TYPE_PING, FLAG_ACK, 0, payload)
            TYPE_GOAWAY -> {
                val code = if (payload.size >= 8) readU32(payload, 4) else -1
                throw IOException("the device ended the RemoteXPC connection (GOAWAY, error $code)")
            }
            TYPE_RST_STREAM -> {
                val code = if (payload.size >= 4) readU32(payload, 0) else -1
                throw IOException("the device reset RemoteXPC stream $stream (error $code)")
            }
            else -> Unit // HEADERS, PRIORITY, CONTINUATION: nothing RemoteXPC needs
        }
    }

    private fun streamWindow(stream: Int): Long = streamSendWindows.getOrPut(stream) { peerInitialWindow }

    private fun length(b: ByteArray) =
        ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)

    private fun writeFrame(type: Int, flags: Int, stream: Int, payload: ByteArray) {
        val frame = ByteArray(9 + payload.size)
        frame[0] = (payload.size ushr 16).toByte()
        frame[1] = (payload.size ushr 8).toByte()
        frame[2] = payload.size.toByte()
        frame[3] = type.toByte()
        frame[4] = flags.toByte()
        frame[5] = ((stream ushr 24) and 0x7F).toByte()
        frame[6] = (stream ushr 16).toByte()
        frame[7] = (stream ushr 8).toByte()
        frame[8] = stream.toByte()
        System.arraycopy(payload, 0, frame, 9, payload.size)
        transport.write(frame)
    }

    companion object {
        val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII)
        const val TYPE_DATA = 0
        const val TYPE_HEADERS = 1
        const val TYPE_RST_STREAM = 3
        const val TYPE_SETTINGS = 4
        const val TYPE_PING = 6
        const val TYPE_GOAWAY = 7
        const val TYPE_WINDOW_UPDATE = 8
        const val FLAG_ACK = 0x1
        const val FLAG_END_HEADERS = 0x4
        const val FLAG_PADDED = 0x8
        const val SETTINGS_MAX_CONCURRENT_STREAMS = 3
        const val SETTINGS_INITIAL_WINDOW_SIZE = 4
        const val MAX_FRAME = 16_384

        private fun settings(vararg pairs: Pair<Int, Int>): ByteArray {
            val out = ByteArray(pairs.size * 6)
            pairs.forEachIndexed { i, (id, v) ->
                out[i * 6] = (id ushr 8).toByte(); out[i * 6 + 1] = id.toByte()
                u32(v).copyInto(out, i * 6 + 2)
            }
            return out
        }

        private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun readU32(b: ByteArray, i: Int): Long =
            ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
    }
}

/**
 * RemoteServiceDiscovery: the first thing to talk to inside the tunnel. One
 * RemoteXPC exchange on the RSD port returns every service the device offers
 * over the tunnel, with the TCP port each one listens on.
 */
class RsdHandshake private constructor(
    val services: Map<String, Service>,
    val properties: Map<String, Any?>,
    val uuid: String
) {
    data class Service(val name: String, val port: Int, val entitlement: String, val usesRemoteXpc: Boolean)

    fun port(name: String): Int? = services[name]?.port

    /** A device property such as ProductVersion, UniqueDeviceID or ProductType. */
    fun property(name: String): String? = properties[name]?.let {
        when (it) {
            is XpcUInt64 -> it.value.toString()
            else -> it.toString()
        }
    }

    companion object {
        private const val ROOT = 1
        private const val REPLY = 3
        private const val MESSAGE_ID = 1L

        fun perform(transport: Transport, timeoutMs: Int = 15_000): RsdHandshake {
            val h2 = Http2Connection(transport)
            h2.start()
            h2.openStream(ROOT)
            // Every message carries the root message id, 1, as idevice sends them.
            h2.send(ROOT, XpcMessage(XpcMessage.ALWAYS_SET, MESSAGE_ID, emptyMap<String, Any?>(), true).encode(), timeoutMs)
            h2.openStream(REPLY)
            h2.send(REPLY, XpcMessage(XpcMessage.INIT_HANDSHAKE or XpcMessage.ALWAYS_SET, MESSAGE_ID, null, false).encode(), timeoutMs)
            h2.send(ROOT, XpcMessage(0x201, MESSAGE_ID, null, false).encode(), timeoutMs)
            val hello = linkedMapOf<String, Any?>(
                "MessageType" to "Handshake",
                "MessagingProtocolVersion" to XpcUInt64(7),
                "UUID" to UUID.randomUUID(),
                "Properties" to linkedMapOf<String, Any?>(
                    "RemoteXPCVersionFlags" to XpcUInt64(0x0100000000000006L),
                    "SensitivePropertiesVisible" to true
                ),
                "Services" to emptyMap<String, Any?>()
            )
            h2.send(ROOT, XpcMessage(XpcMessage.DATA or XpcMessage.ALWAYS_SET, MESSAGE_ID, hello, true).encode(), timeoutMs)
            val reply = receive(h2, ROOT, timeoutMs)
            return parse(reply).also {
                Log.i(LogTag.TUNNEL, "RSD lists ${it.services.size} services (device ${it.property("ProductType") ?: "?"}, iOS ${it.property("OSVersion") ?: it.property("ProductVersion") ?: "?"})")
            }
        }

        /** The next non-empty dictionary on [stream]. */
        private fun receive(h2: Http2Connection, stream: Int, timeoutMs: Int): Map<String, Any?> {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val pending = h2.buffered(stream)
                val bytes = pending.toByteArray()
                val decoded = XpcMessage.decode(bytes, bytes.size)
                if (decoded != null) {
                    val (message, used) = decoded
                    pending.reset()
                    pending.write(bytes, used, bytes.size - used)
                    @Suppress("UNCHECKED_CAST")
                    val body = message.body as? Map<String, Any?>
                    if (body != null && body.isNotEmpty()) return body
                    continue
                }
                val left = (deadline - System.currentTimeMillis()).toInt()
                if (left <= 0) throw SocketTimeoutException("RSD did not answer within $timeoutMs ms")
                h2.pump(left)
            }
        }

        fun parse(reply: Map<String, Any?>): RsdHandshake {
            val raw = reply["Services"] as? Map<*, *> ?: throw IOException("the RSD reply has no Services: ${reply.keys}")
            val services = LinkedHashMap<String, Service>()
            for ((name, value) in raw) {
                val entry = value as? Map<*, *> ?: continue
                val port = (entry["Port"] as? String)?.toIntOrNull() ?: continue
                val props = entry["Properties"] as? Map<*, *>
                services[name as String] = Service(
                    name = name,
                    port = port,
                    entitlement = entry["Entitlement"] as? String ?: "",
                    usesRemoteXpc = props?.get("UsesRemoteXPC") == true
                )
            }
            @Suppress("UNCHECKED_CAST")
            val properties = (reply["Properties"] as? Map<String, Any?>) ?: emptyMap()
            val uuid = reply["UUID"]?.toString() ?: ""
            return RsdHandshake(services, properties, uuid)
        }
    }
}
