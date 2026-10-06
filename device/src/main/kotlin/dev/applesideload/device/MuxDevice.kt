package dev.applesideload.device

import dev.applesideload.core.Bytes
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device side of usbmux, implemented here rather than borrowed.
 *
 * On a desktop, usbmuxd owns the USB interface and every other program talks
 * to it over a socket. There is no usbmuxd on Android: this app is the host,
 * so it has to speak the protocol the phone actually speaks on the wire.
 *
 * That protocol is a small mux header followed by a complete TCP header, and
 * the phone really does run a TCP stack behind it. Opening a service means
 * performing a three way handshake to a port on the device, tracking sequence
 * numbers in both directions, and acknowledging what arrives. None of that can
 * be skipped: the device drops a packet whose sequence or window it does not
 * believe, and the symptom is a service that connects and then says nothing.
 *
 * Reference: libusbmuxd and usbmuxd's device.c, go-ios, and pymobiledevice3.
 * The field layout below matches those implementations.
 */
class MuxDevice(private val transport: Transport) : AutoCloseable {

    private val running = AtomicBoolean(true)
    private val nextPort = AtomicInteger(0xF000)
    private val connections = ConcurrentHashMap<Int, MuxConnection>()
    private var version = 1
    private var txSeq = 0
    private var rxSeq = 0
    private val writeLock = Any()

    @Volatile
    private var readerError: Throwable? = null

    private val reader = Thread({ readLoop() }, "mux-reader").apply { isDaemon = true }

    /**
     * Agrees a protocol version with the device.
     *
     * Version 2 adds the two sequence counters in the mux header itself, which
     * every current device uses. A device answering with version 1 is still
     * usable, so the reply decides rather than an assumption.
     */
    fun start() {
        val payload = ByteArray(12)
        Bytes.putU32be(payload, 0, 2)   // major
        Bytes.putU32be(payload, 4, 0)   // minor
        Bytes.putU32be(payload, 8, 0)   // padding
        sendPacket(PROTOCOL_VERSION, payload, includeSequence = false)

        val reply = readPacket() ?: throw DeviceException(
            operation = "usbmux version exchange",
            reason = "the device did not answer the version packet"
        )
        if (reply.protocol != PROTOCOL_VERSION) {
            throw DeviceException(
                operation = "usbmux version exchange",
                reason = "the device answered with protocol ${reply.protocol} " +
                    "instead of a version packet"
            )
        }
        val major = Bytes.u32be(reply.payload, 0).toInt()
        version = if (major >= 2) 2 else 1
        Log.i(LogTag.USBMUX, "device speaks mux version $major; using version $version")
        reader.start()
    }

    /** Opens a TCP connection to a port on the device. */
    fun connect(port: Int): Transport {
        check(running.get()) { "the mux device is closed" }
        val localPort = nextPort.getAndIncrement() and 0xFFFF
        val connection = MuxConnection(localPort, port)
        connections[localPort] = connection

        Log.d(LogTag.USBMUX, "connecting local $localPort to device port $port")
        sendTcp(connection, FLAG_SYN, ByteArray(0))

        val handshake = connection.handshake.poll(10, TimeUnit.SECONDS)
            ?: run {
                connections.remove(localPort)
                throw DeviceException(
                    operation = "usbmux connect to port $port",
                    reason = readerError?.let { "the mux stream failed: ${Log.describe(it)}" }
                        ?: "the device never answered the TCP handshake",
                    limitation = "a port with no service listening is refused rather than " +
                        "left open"
                )
            }
        if (!handshake) {
            connections.remove(localPort)
            throw DeviceException(
                operation = "usbmux connect to port $port",
                reason = "the device refused the connection",
                limitation = "the service behind that port is not running on this device"
            )
        }
        sendTcp(connection, FLAG_ACK, ByteArray(0))
        connection.established = true
        return connection
    }

    override fun close() {
        running.set(false)
        connections.values.forEach { runCatching { it.markClosed() } }
        connections.clear()
        runCatching { transport.close() }
    }

    // MARK: - Reader

    private fun readLoop() {
        try {
            while (running.get()) {
                val packet = readPacket() ?: continue
                if (packet.protocol != PROTOCOL_TCP) {
                    Log.d(LogTag.USBMUX, "ignoring mux protocol ${packet.protocol}")
                    continue
                }
                handleTcp(packet.payload)
            }
        } catch (error: Throwable) {
            if (running.get()) {
                readerError = error
                Log.e(LogTag.USBMUX, "the mux reader stopped", error)
                connections.values.forEach { it.markClosed() }
            }
        }
    }

    private fun handleTcp(packet: ByteArray) {
        if (packet.size < TCP_HEADER) {
            Log.w(LogTag.USBMUX, "a TCP packet was shorter than its header")
            return
        }
        val sourcePort = Bytes.u16be(packet, 0)
        val destinationPort = Bytes.u16be(packet, 2)
        val sequence = Bytes.u32be(packet, 4)
        val flags = packet[13].toInt() and 0xFF
        val window = Bytes.u16be(packet, 14)
        val headerWords = (packet[12].toInt() and 0xF0) shr 4
        val headerLength = headerWords * 4
        val connection = connections[destinationPort]
        if (connection == null) {
            Log.d(LogTag.USBMUX, "a packet arrived for unknown local port $destinationPort")
            return
        }
        if (connection.devicePort != sourcePort) {
            Log.d(LogTag.USBMUX, "a packet claimed the wrong device port")
            return
        }

        if (flags and FLAG_RST != 0) {
            connection.handshake.offer(false)
            connection.markClosed()
            return
        }

        if (flags and FLAG_SYN != 0 && flags and FLAG_ACK != 0) {
            connection.remoteSeq = (sequence + 1).toInt()
            connection.localSeq += 1
            connection.window = window shl WINDOW_SHIFT
            connection.handshake.offer(true)
            return
        }

        val payloadStart = if (headerLength in TCP_HEADER..packet.size) headerLength else TCP_HEADER
        val payload = packet.copyOfRange(payloadStart, packet.size)
        if (payload.isNotEmpty()) {
            connection.remoteSeq = (sequence + payload.size).toInt()
            connection.deliver(payload)
            // Acknowledged immediately. The device stops sending once its view
            // of our window closes, and a late acknowledgement reads exactly
            // like a hung service.
            runCatching { sendTcp(connection, FLAG_ACK, ByteArray(0)) }
        }
        if (flags and FLAG_FIN != 0) {
            connection.markClosed()
        }
    }

    // MARK: - Writing

    private fun sendTcp(connection: MuxConnection, flags: Int, payload: ByteArray) {
        val packet = ByteArray(TCP_HEADER + payload.size)
        Bytes.putU16be(packet, 0, connection.localPort)
        Bytes.putU16be(packet, 2, connection.devicePort)
        Bytes.putU32be(packet, 4, connection.localSeq.toLong())
        Bytes.putU32be(packet, 8, connection.remoteSeq.toLong())
        packet[12] = ((TCP_HEADER / 4) shl 4).toByte()
        packet[13] = flags.toByte()
        Bytes.putU16be(packet, 14, RECEIVE_WINDOW shr WINDOW_SHIFT)
        // The checksum is left at zero, as usbmuxd does: this is not real IP
        // and the device does not verify it.
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, TCP_HEADER, payload.size)
            connection.localSeq += payload.size
        }
        sendPacket(PROTOCOL_TCP, packet, includeSequence = version >= 2)
    }

    private fun sendPacket(protocol: Int, payload: ByteArray, includeSequence: Boolean) {
        synchronized(writeLock) {
            val headerSize = if (includeSequence) HEADER_V2 else HEADER_V1
            val out = ByteArray(headerSize + payload.size)
            Bytes.putU32be(out, 0, protocol.toLong())
            Bytes.putU32be(out, 4, out.size.toLong())
            Bytes.putU32be(out, 8, MAGIC)
            if (includeSequence) {
                Bytes.putU16be(out, 12, txSeq)
                Bytes.putU16be(out, 14, rxSeq)
                txSeq += 1
            }
            System.arraycopy(payload, 0, out, headerSize, payload.size)
            transport.write(out)
        }
    }

    private class Packet(val protocol: Int, val payload: ByteArray)

    private fun readPacket(): Packet? {
        val head = transport.readFully(12, READ_TIMEOUT)
        val protocol = Bytes.u32be(head, 0).toInt()
        val length = Bytes.u32be(head, 4).toInt()
        val magic = Bytes.u32be(head, 8)
        if (magic != MAGIC) {
            throw DeviceException(
                operation = "usbmux read",
                reason = "a packet carried magic ${java.lang.Long.toHexString(magic)} " +
                    "instead of feedface, so the stream is out of step"
            )
        }
        val sequenced = version >= 2 || protocol == PROTOCOL_TCP
        val headerSize = if (sequenced && protocol != PROTOCOL_VERSION) HEADER_V2 else HEADER_V1
        if (length < headerSize) {
            throw DeviceException(
                operation = "usbmux read",
                reason = "a packet declared $length bytes, less than its own header"
            )
        }
        if (headerSize == HEADER_V2) {
            val sequence = transport.readFully(4, READ_TIMEOUT)
            rxSeq = Bytes.u16be(sequence, 0)
        }
        val body = if (length > headerSize) {
            transport.readFully(length - headerSize, READ_TIMEOUT)
        } else {
            ByteArray(0)
        }
        return Packet(protocol, body)
    }

    /** One TCP connection over the mux, presented as an ordinary byte stream. */
    private inner class MuxConnection(
        val localPort: Int,
        val devicePort: Int
    ) : Transport {
        var localSeq: Int = 0
        var remoteSeq: Int = 0
        var window: Int = RECEIVE_WINDOW
        var established = false
        val handshake = ArrayBlockingQueue<Boolean>(1)

        private val incoming = ArrayBlockingQueue<ByteArray>(256)
        private var pending: ByteArray? = null
        private var pendingAt = 0
        private val closed = AtomicBoolean(false)

        override val description: String
            get() = "mux $localPort to device port $devicePort"

        fun deliver(payload: ByteArray) {
            if (!incoming.offer(payload)) {
                Log.w(LogTag.USBMUX, "the receive queue for $description is full")
            }
        }

        fun markClosed() {
            if (closed.compareAndSet(false, true)) {
                incoming.offer(ByteArray(0))
            }
        }

        override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            var current = pending
            if (current == null || pendingAt >= current.size) {
                if (closed.get() && incoming.isEmpty()) return -1
                current = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    ?: throw IOException("the device sent nothing within ${timeoutMs}ms on $description")
                if (current.isEmpty()) return -1
                pending = current
                pendingAt = 0
            }
            val count = minOf(current.size - pendingAt, length)
            System.arraycopy(current, pendingAt, into, offset, count)
            pendingAt += count
            return count
        }

        override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
            if (closed.get()) throw IOException("$description is closed")
            var sent = 0
            while (sent < length) {
                val chunk = minOf(length - sent, MAX_PAYLOAD)
                val slice = from.copyOfRange(offset + sent, offset + sent + chunk)
                sendTcp(this, FLAG_ACK or FLAG_PSH, slice)
                sent += chunk
            }
        }

        override fun close() {
            if (!closed.get() && established) {
                runCatching { sendTcp(this, FLAG_FIN or FLAG_ACK, ByteArray(0)) }
            }
            markClosed()
            connections.remove(localPort)
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_TCP = 6
        const val MAGIC = 0xfeedfaceL
        const val HEADER_V1 = 12
        const val HEADER_V2 = 16
        const val TCP_HEADER = 20
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10

        /**
         * The window advertised to the device, and the shift it is sent with.
         *
         * usbmuxd scales the window by eight bits in the header rather than
         * negotiating a scale option, and the device expects exactly that.
         */
        const val WINDOW_SHIFT = 8
        const val RECEIVE_WINDOW = 131_072
        const val MAX_PAYLOAD = 16 * 1024
        const val READ_TIMEOUT = 30_000
    }
}
