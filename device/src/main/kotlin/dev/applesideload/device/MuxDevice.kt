package dev.applesideload.device

import dev.applesideload.core.Bytes
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * The host side of usbmux, implemented here rather than borrowed.
 *
 * On a desktop, usbmuxd owns the USB interface and every other program talks
 * to it over a socket. There is no usbmuxd on Android: this app is the host,
 * so it has to speak the protocol the phone actually speaks on the wire.
 *
 * That protocol is a small mux header followed by a complete TCP header, and
 * the phone really does run a TCP stack behind it. Opening a service means a
 * three way handshake to a port on the device, sequence numbers in both
 * directions, acknowledgements, and windows: the phone stops sending when it
 * believes this side has no room, and data sent beyond the window it offered
 * is not read.
 *
 * The details follow usbmuxd's device.c, the only widely used host
 * implementation:
 *
 *  * The version packet is exchanged with the short 8 byte header (protocol
 *    and length). Only after the phone answers with version 2 does every
 *    packet carry the 16 byte header with the 0xfeedface magic and the two
 *    sequence counters, starting with a SETUP packet that resets them.
 *  * Data goes out with the ACK flag alone, never PSH, and a connection is
 *    ended with RST, which is what the phone expects from a host.
 *  * The window is sent shifted right by 8 bits, without a scale option.
 */
class MuxDevice(private val transport: Transport) : AutoCloseable {

    private val running = AtomicBoolean(true)
    private val connections = ConcurrentHashMap<Int, MuxConnection>()

    /**
     * Local ports start somewhere random so a session opened right after
     * another one on the same cable never reuses a port the phone may still
     * remember from the last.
     */
    private val portLock = Any()
    private var nextPort = Random.nextInt(FIRST_PORT, LAST_PORT + 1)

    /** 0 until the phone has answered the version packet. */
    @Volatile
    private var version = 0

    private val writeLock = Any()

    /** The mux level counters of version 2. [txSeq] is guarded by [writeLock]. */
    private var txSeq = 0

    @Volatile
    private var rxSeq = 0xFFFF

    @Volatile
    private var failure: Throwable? = null

    private val reader = Thread({ readLoop() }, "mux-reader").apply { isDaemon = true }

    /**
     * Agrees a protocol version with the phone and starts reading.
     *
     * The reply decides the header format from then on, so nothing else may
     * be sent or read until it has arrived.
     */
    fun start() {
        val hello = ByteArray(VERSION_BODY)
        Bytes.putU32be(hello, 0, 2)   // major
        Bytes.putU32be(hello, 4, 0)   // minor
        Bytes.putU32be(hello, 8, 0)   // padding
        sendPacket(PROTOCOL_VERSION, hello)

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(VERSION_TIMEOUT_MS)
        var skipped = 0
        val reply: Packet
        while (true) {
            val packet = try {
                readPacket(deadline)
            } catch (timeout: SocketTimeoutException) {
                throw DeviceException(
                    operation = "usbmux version exchange",
                    reason = "the iPhone did not answer the version packet within " +
                        "${VERSION_TIMEOUT_MS / 1000} seconds",
                    alternative = "unlock the iPhone, unplug it, plug it back in and connect again",
                    cause = timeout
                )
            }
            if (packet.protocol == PROTOCOL_VERSION) {
                reply = packet
                break
            }
            // Packets left over from a session that ended without the cable
            // being unplugged. The length field sits in the same place in
            // either header format, so skipping them stays in step.
            skipped += 1
            if (skipped > MAX_STALE_PACKETS) {
                throw DeviceException(
                    operation = "usbmux version exchange",
                    reason = "the iPhone sent $skipped other packets and no version reply",
                    alternative = "unplug the iPhone, plug it back in and connect again"
                )
            }
        }
        if (skipped > 0) {
            Log.i(LogTag.USBMUX, "skipped $skipped packets left over from an earlier session")
        }
        if (reply.payload.size < VERSION_BODY) {
            throw DeviceException(
                operation = "usbmux version exchange",
                reason = "the version reply carried ${reply.payload.size} bytes instead of $VERSION_BODY"
            )
        }
        val major = Bytes.u32be(reply.payload, 0)
        val minor = Bytes.u32be(reply.payload, 4)
        when (major) {
            2L -> {
                version = 2
                sendPacket(PROTOCOL_SETUP, byteArrayOf(SETUP_PAYLOAD))
            }

            1L -> version = 1
            else -> throw DeviceException(
                operation = "usbmux version exchange",
                reason = "the iPhone speaks mux version $major.$minor, which this app does not know"
            )
        }
        Log.i(LogTag.USBMUX, "the iPhone speaks mux version $major.$minor")
        reader.start()
    }

    /** Opens a TCP connection to a port on the phone. */
    fun connect(port: Int): Transport {
        val operation = "usbmux connect to port $port"
        failure?.let { earlier ->
            throw DeviceException(
                operation = operation,
                reason = "the USB link to the iPhone failed earlier: ${Log.describe(earlier)}",
                alternative = "unplug the iPhone, plug it back in and connect again"
            )
        }
        if (!running.get()) {
            throw DeviceException(operation = operation, reason = "the USB connection was closed")
        }
        val connection = register(port)
        Log.d(LogTag.USBMUX, "connecting ${connection.description}")
        try {
            sendSegment(connection, FLAG_SYN)
        } catch (error: IOException) {
            connections.remove(connection.localPort, connection)
            throw DeviceException(
                operation = operation,
                reason = "the connection request could not be sent: ${Log.describe(error)}",
                cause = error
            )
        }
        when (connection.awaitHandshake(CONNECT_TIMEOUT_MS)) {
            Handshake.CONNECTED -> return connection
            Handshake.REFUSED -> {
                connections.remove(connection.localPort, connection)
                throw DeviceException(
                    operation = operation,
                    reason = "the iPhone refused the connection",
                    limitation = "nothing is listening on that port on the iPhone"
                )
            }

            Handshake.FAILED -> {
                connections.remove(connection.localPort, connection)
                throw DeviceException(operation = operation, reason = connection.reason)
            }

            Handshake.PENDING -> {
                connection.abandon()
                throw DeviceException(
                    operation = operation,
                    reason = failure?.let { "the USB link failed: ${Log.describe(it)}" }
                        ?: "the iPhone did not answer the connection request within " +
                        "${CONNECT_TIMEOUT_MS / 1000} seconds"
                )
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        val reason = "the USB connection to the iPhone was closed"
        connections.values.forEach { it.fail(reason) }
        connections.clear()
        runCatching { transport.close() }
    }

    private fun register(devicePort: Int): MuxConnection {
        synchronized(portLock) {
            repeat(LAST_PORT - FIRST_PORT + 1) {
                val local = nextPort
                nextPort = if (nextPort >= LAST_PORT) FIRST_PORT else nextPort + 1
                if (!connections.containsKey(local)) {
                    val connection = MuxConnection(local, devicePort)
                    connections[local] = connection
                    return connection
                }
            }
        }
        throw DeviceException(
            operation = "usbmux connect to port $devicePort",
            reason = "every local port is already in use"
        )
    }

    /** Marks the link broken: every connection fails and no new one starts. */
    private fun fail(error: Throwable) {
        if (failure == null) failure = error
        val reason = "the USB link to the iPhone failed: ${Log.describe(error)}"
        connections.values.forEach { it.fail(reason) }
    }

    // MARK: - Reading

    private class Packet(val protocol: Long, val payload: ByteArray)

    private fun readLoop() {
        try {
            while (running.get()) {
                val packet = readPacket(deadline = null)
                when (packet.protocol) {
                    PROTOCOL_TCP -> handleTcp(packet.payload)
                    PROTOCOL_CONTROL -> handleControl(packet.payload)
                    else -> Log.d(LogTag.USBMUX, "ignoring a mux packet of protocol ${packet.protocol}")
                }
            }
        } catch (error: Throwable) {
            if (running.get()) {
                Log.w(LogTag.USBMUX, "the USB link to the iPhone stopped: ${Log.describe(error)}")
                fail(error)
            }
        }
    }

    /**
     * Reads one mux packet.
     *
     * With a [deadline] (System.nanoTime) this gives up when it passes; without
     * one it waits for as long as the device stays open, since an idle phone
     * sends nothing and that is not an error.
     */
    private fun readPacket(deadline: Long?): Packet {
        val base = readExactly(BASE_HEADER, deadline)
        val protocol = Bytes.u32be(base, 0)
        val length = Bytes.u32be(base, 4)
        val headerSize = if (version >= 2) HEADER_V2 else BASE_HEADER
        if (length < headerSize || length > MAX_PACKET) {
            throw DeviceException(
                operation = "usbmux read",
                reason = "a packet declared $length bytes with a $headerSize byte header, " +
                    "so the stream is out of step"
            )
        }
        if (version >= 2) {
            val rest = readExactly(HEADER_V2 - BASE_HEADER, deadline)
            val magic = Bytes.u32be(rest, 0)
            if (magic != MAGIC) {
                throw DeviceException(
                    operation = "usbmux read",
                    reason = "a packet carried magic ${java.lang.Long.toHexString(magic)} " +
                        "instead of feedface, so the stream is out of step"
                )
            }
            // usbmuxd keeps the phone's rx counter and sends it straight back.
            rxSeq = Bytes.u16be(rest, 6)
        }
        val bodyLength = (length - headerSize).toInt()
        val body = if (bodyLength > 0) readExactly(bodyLength, deadline) else ByteArray(0)
        return Packet(protocol, body)
    }

    private fun readExactly(length: Int, deadline: Long?): ByteArray {
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            if (!running.get()) throw IOException("the USB connection was closed")
            val wait = if (deadline == null) {
                POLL_MS
            } else {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) {
                    throw SocketTimeoutException("the iPhone sent nothing in time over USB")
                }
                minOf(left, POLL_MS.toLong()).toInt()
            }
            val read = try {
                transport.read(out, filled, length - filled, wait)
            } catch (idle: SocketTimeoutException) {
                continue
            }
            if (read < 0) throw IOException("the USB stream from the iPhone ended")
            filled += read
        }
        return out
    }

    private fun handleControl(payload: ByteArray) {
        if (payload.isEmpty()) {
            Log.d(LogTag.USBMUX, "the iPhone sent an empty control packet")
            return
        }
        val text = String(payload, 1, payload.size - 1, Charsets.UTF_8).trimEnd('\u0000', '\n')
        when (payload[0].toInt() and 0xFF) {
            CONTROL_ERROR -> Log.w(LogTag.USBMUX, "the iPhone reported a mux error: $text")
            CONTROL_WARNING -> Log.w(LogTag.USBMUX, "the iPhone reported a mux warning: $text")
            CONTROL_INFO -> Log.i(LogTag.USBMUX, "the iPhone says: $text")
            else -> Log.d(LogTag.USBMUX, "control packet ${payload[0].toInt() and 0xFF}: $text")
        }
    }

    private fun handleTcp(packet: ByteArray) {
        if (packet.size < TCP_HEADER) {
            Log.w(LogTag.USBMUX, "a TCP packet was shorter than its header")
            return
        }
        val sourcePort = Bytes.u16be(packet, 0)
        val destinationPort = Bytes.u16be(packet, 2)
        val sequence = Bytes.u32be(packet, 4).toInt()
        val acknowledgement = Bytes.u32be(packet, 8).toInt()
        val dataOffset = ((packet[12].toInt() and 0xF0) shr 4) * 4
        val flags = packet[13].toInt() and 0xFF
        val window = Bytes.u16be(packet, 14) shl WINDOW_SHIFT
        val payloadStart = if (dataOffset in TCP_HEADER..packet.size) dataOffset else TCP_HEADER

        val connection = connections[destinationPort]
        if (connection == null || connection.devicePort != sourcePort) {
            // usbmuxd answers a packet for a connection it does not know with
            // a reset, so the phone lets go of its side too.
            if (flags and FLAG_RST == 0) {
                Log.d(LogTag.USBMUX, "resetting a packet for unknown local port $destinationPort")
                sendAnonymousReset(destinationPort, sourcePort, sequence)
            }
            return
        }
        connection.receive(
            flags, sequence, acknowledgement, window,
            packet, payloadStart, packet.size - payloadStart
        )
    }

    // MARK: - Writing

    /** Sends a segment with no data: SYN, a bare ACK, or RST. */
    private fun sendSegment(connection: MuxConnection, flags: Int) {
        synchronized(writeLock) {
            val body = ByteArray(TCP_HEADER)
            connection.fillHeader(body, flags, 0)
            sendPacketLocked(PROTOCOL_TCP, body)
        }
    }

    /**
     * Sends as much of the data as the phone's window allows, up to [max].
     *
     * Returns the count sent, which is 0 when the window has closed since the
     * caller looked, or -1 if the connection is no longer open.
     */
    private fun sendData(connection: MuxConnection, data: ByteArray, offset: Int, max: Int): Int {
        synchronized(writeLock) {
            val count = connection.sendable(max)
            if (count <= 0) return count
            val body = ByteArray(TCP_HEADER + count)
            System.arraycopy(data, offset, body, TCP_HEADER, count)
            connection.fillHeader(body, FLAG_ACK, count)
            sendPacketLocked(PROTOCOL_TCP, body)
            return count
        }
    }

    private fun sendAnonymousReset(localPort: Int, devicePort: Int, sequence: Int) {
        val body = ByteArray(TCP_HEADER)
        Bytes.putU16be(body, 0, localPort)
        Bytes.putU16be(body, 2, devicePort)
        Bytes.putU32be(body, 8, (sequence + 1).toLong() and MASK32)
        body[12] = ((TCP_HEADER / 4) shl 4).toByte()
        body[13] = FLAG_RST.toByte()
        sendPacket(PROTOCOL_TCP, body)
    }

    private fun sendPacket(protocol: Long, body: ByteArray) {
        synchronized(writeLock) { sendPacketLocked(protocol, body) }
    }

    private fun sendPacketLocked(protocol: Long, body: ByteArray) {
        val sequenced = version >= 2
        val headerSize = if (sequenced) HEADER_V2 else BASE_HEADER
        val out = ByteArray(headerSize + body.size)
        Bytes.putU32be(out, 0, protocol)
        Bytes.putU32be(out, 4, out.size.toLong())
        if (sequenced) {
            Bytes.putU32be(out, 8, MAGIC)
            if (protocol == PROTOCOL_SETUP) {
                txSeq = 0
                rxSeq = 0xFFFF
            }
            Bytes.putU16be(out, 12, txSeq)
            Bytes.putU16be(out, 14, rxSeq)
            txSeq = (txSeq + 1) and 0xFFFF
        }
        System.arraycopy(body, 0, out, headerSize, body.size)
        try {
            transport.write(out, 0, out.size, WRITE_TIMEOUT_MS)
        } catch (error: IOException) {
            // A write that failed part way leaves the phone in the middle of a
            // packet, so nothing sent after it would be read correctly.
            fail(error)
            throw error
        }
    }

    // MARK: - Connections

    private enum class State { CONNECTING, ESTABLISHED, CLOSED }

    private enum class Handshake { PENDING, CONNECTED, REFUSED, FAILED }

    /** One TCP connection over the mux, presented as an ordinary byte stream. */
    private inner class MuxConnection(
        val localPort: Int,
        val devicePort: Int
    ) : Transport {

        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val writer = ReentrantLock()
        private val inbound = ChunkQueue { description }

        private var state = State.CONNECTING
        private var handshake = Handshake.PENDING
        private var closeReason: String? = null
        private var warnedAboutGap = false

        /** Sending: the next sequence number, the oldest unacknowledged, and the phone's window. */
        private var sendNext = 0
        private var sendUnacknowledged = 0
        private var sendWindow = 0

        /** Receiving: the next sequence number expected, and the window last offered. */
        private var receiveNext = 0
        private var offeredWindow = 0

        override val description: String
            get() = "mux $localPort to device port $devicePort"

        val reason: String
            get() = lock.withLock { closeReason ?: "$description is closed" }

        // Called by the reader thread for every segment on this connection.
        fun receive(
            flags: Int,
            sequence: Int,
            acknowledgement: Int,
            window: Int,
            packet: ByteArray,
            start: Int,
            length: Int
        ) {
            var completeHandshake = false
            var acknowledge = false
            var reset = false
            var finished = false
            var endOfStream = false
            lock.withLock {
                when (state) {
                    State.CONNECTING -> {
                        if ((flags and HANDSHAKE_FLAGS) == (FLAG_SYN or FLAG_ACK)) {
                            receiveNext = sequence + 1
                            sendUnacknowledged = acknowledgement
                            sendWindow = window
                            completeHandshake = true
                        } else {
                            val refused = flags and FLAG_RST != 0
                            state = State.CLOSED
                            handshake = if (refused) Handshake.REFUSED else Handshake.FAILED
                            closeReason = if (refused) {
                                "the iPhone refused the connection to port $devicePort"
                            } else {
                                "the iPhone answered the connection to port $devicePort with " +
                                    "flags 0x${Integer.toHexString(flags)} instead of SYN and ACK"
                            }
                            // usbmuxd resets anything but a refusal, which already ended it.
                            reset = !refused
                            finished = true
                            changed.signalAll()
                        }
                    }

                    State.ESTABLISHED -> {
                        sendUnacknowledged = acknowledgement
                        sendWindow = window
                        changed.signalAll()
                        when {
                            flags and FLAG_RST != 0 -> {
                                state = State.CLOSED
                                closeReason = "the iPhone reset the connection on port $devicePort"
                                finished = true
                            }

                            flags and FLAG_SYN != 0 -> {
                                if (sequence + 1 == receiveNext) {
                                    // The handshake answer again; it already counted.
                                    acknowledge = true
                                } else {
                                    state = State.CLOSED
                                    closeReason = "the iPhone restarted the connection on port " +
                                        "$devicePort part way through"
                                    reset = true
                                    finished = true
                                }
                            }

                            else -> {
                                if (length > 0) {
                                    accept(sequence, packet, start, length)
                                    acknowledge = true
                                }
                                if (flags and FLAG_FIN != 0) {
                                    // usbmuxd ends the connection outright when the
                                    // phone closes it; what already arrived stays readable.
                                    state = State.CLOSED
                                    closeReason = "the iPhone closed the connection on port $devicePort"
                                    acknowledge = false
                                    reset = true
                                    finished = true
                                    endOfStream = true
                                }
                            }
                        }
                    }

                    State.CLOSED -> Unit
                }
            }
            if (completeHandshake) {
                // Sent before anyone is told the connection is up, so it reaches
                // the phone ahead of any data.
                sendSegment(this, FLAG_ACK)
                lock.withLock {
                    if (state == State.CONNECTING) {
                        state = State.ESTABLISHED
                        handshake = Handshake.CONNECTED
                    }
                    changed.signalAll()
                }
                return
            }
            if (acknowledge) sendSegment(this, FLAG_ACK)
            if (finished) {
                if (endOfStream) inbound.finish() else inbound.fail(IOException(reason))
                connections.remove(localPort, this)
            }
            if (reset) sendSegment(this, FLAG_RST)
        }

        /** Queues the new part of a data segment. Called with [lock] held. */
        private fun accept(sequence: Int, packet: ByteArray, start: Int, length: Int) {
            // How much of this segment arrived before: positive when the phone
            // repeated data, negative when something was skipped. The
            // subtraction wraps exactly as TCP sequence numbers do.
            val already = receiveNext - sequence
            when {
                already == 0 -> inbound.put(packet.copyOfRange(start, start + length))
                already in 1 until length -> {
                    Log.d(LogTag.USBMUX, "$description: dropping $already repeated bytes")
                    inbound.put(packet.copyOfRange(start + already, start + length))
                }

                already >= length -> {
                    Log.d(LogTag.USBMUX, "$description: dropping a repeated segment")
                    return
                }

                else -> {
                    if (!warnedAboutGap) {
                        warnedAboutGap = true
                        Log.w(
                            LogTag.USBMUX,
                            "$description: the iPhone skipped ${-already} bytes of the stream"
                        )
                    }
                    inbound.put(packet.copyOfRange(start, start + length))
                }
            }
            receiveNext = sequence + length
        }

        /** Writes the TCP header for a segment carrying [length] bytes. Called with the write lock held. */
        fun fillHeader(body: ByteArray, flags: Int, length: Int) {
            lock.withLock {
                Bytes.putU16be(body, 0, localPort)
                Bytes.putU16be(body, 2, devicePort)
                Bytes.putU32be(body, 4, sendNext.toLong() and MASK32)
                Bytes.putU32be(body, 8, receiveNext.toLong() and MASK32)
                body[12] = ((TCP_HEADER / 4) shl 4).toByte()
                body[13] = flags.toByte()
                val units = windowUnits()
                Bytes.putU16be(body, 14, units)
                offeredWindow = units shl WINDOW_SHIFT
                // The checksum stays zero, as usbmuxd leaves it: this is not
                // real IP and the phone does not check it.
                sendNext += length
                if (flags and FLAG_SYN != 0) sendNext += 1
            }
        }

        /** The room left in the receive buffer, in the shifted units the header carries. */
        private fun windowUnits(): Int {
            val free = (RECEIVE_WINDOW - inbound.queuedBytes).coerceIn(0L, RECEIVE_WINDOW.toLong())
            // Rounded up, so acknowledging data never pulls the window's right
            // edge back by a fraction of a unit.
            val units = (free + (1L shl WINDOW_SHIFT) - 1) shr WINDOW_SHIFT
            return units.toInt().coerceAtMost(0xFFFF)
        }

        /** How much may be sent now, up to [max], or -1 if the connection is not open. */
        fun sendable(max: Int): Int = lock.withLock {
            if (state != State.ESTABLISHED) -1 else minOf(max, sendableLocked())
        }

        private fun sendableLocked(): Int {
            val inFlight = (sendNext - sendUnacknowledged).coerceAtLeast(0)
            return (sendWindow - inFlight).coerceAtLeast(0)
        }

        fun awaitHandshake(timeoutMs: Long): Handshake = lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (handshake == Handshake.PENDING && state == State.CONNECTING && remaining > 0L) {
                remaining = changed.awaitNanos(remaining)
            }
            if (handshake == Handshake.PENDING && state == State.CLOSED) Handshake.FAILED else handshake
        }

        /** Gives up on a connection the phone never answered. */
        fun abandon() {
            lock.withLock {
                state = State.CLOSED
                closeReason = "the iPhone never answered the connection to port $devicePort"
                changed.signalAll()
            }
            inbound.fail(IOException(reason))
            connections.remove(localPort, this)
            // Should the answer still come, the phone is told to forget it.
            if (running.get() && failure == null) runCatching { sendSegment(this, FLAG_RST) }
        }

        /** The link under every connection broke. */
        fun fail(why: String) {
            lock.withLock {
                if (state != State.CLOSED) {
                    state = State.CLOSED
                    closeReason = why
                    if (handshake == Handshake.PENDING) handshake = Handshake.FAILED
                }
                changed.signalAll()
            }
            inbound.fail(IOException(why))
        }

        override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            val count = inbound.read(into, offset, length, timeoutMs)
            if (count > 0 && windowUpdateDue()) {
                // The phone stops when the window it was offered runs out, and
                // only hears that room opened up if this side says so.
                runCatching { sendSegment(this, FLAG_ACK) }
            }
            return count
        }

        private fun windowUpdateDue(): Boolean = lock.withLock {
            state == State.ESTABLISHED &&
                (windowUnits() shl WINDOW_SHIFT) - offeredWindow >= WINDOW_UPDATE_STEP
        }

        override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
            writer.withLock {
                var sent = 0
                while (sent < length) {
                    awaitSendable(timeoutMs)
                    val count = sendData(this, from, offset + sent, minOf(length - sent, MAX_PAYLOAD))
                    if (count < 0) throw IOException(reason)
                    sent += count
                }
            }
        }

        private fun awaitSendable(timeoutMs: Int) {
            lock.withLock {
                var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                while (true) {
                    if (state != State.ESTABLISHED) {
                        throw IOException(closeReason ?: "$description is closed")
                    }
                    if (sendableLocked() > 0) return
                    if (timeoutMs <= 0) {
                        changed.await()
                    } else {
                        if (remaining <= 0L) {
                            throw SocketTimeoutException(
                                "the iPhone accepted no more data for ${timeoutMs}ms on $description"
                            )
                        }
                        remaining = changed.awaitNanos(remaining)
                    }
                }
            }
        }

        override fun close() {
            val wasOpen = lock.withLock {
                val open = state == State.ESTABLISHED
                if (state != State.CLOSED) {
                    state = State.CLOSED
                    closeReason = "$description was closed"
                }
                changed.signalAll()
                open
            }
            inbound.finish()
            connections.remove(localPort, this)
            if (wasOpen && running.get() && failure == null) {
                // usbmuxd ends every connection this way.
                runCatching { sendSegment(this, FLAG_RST) }
            }
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = 0L
        const val PROTOCOL_CONTROL = 1L
        const val PROTOCOL_SETUP = 2L
        const val PROTOCOL_TCP = 6L
        const val MAGIC = 0xfeedfaceL
        const val MASK32 = 0xFFFFFFFFL

        /** Protocol and length: the whole header before version 2. */
        const val BASE_HEADER = 8

        /** Protocol, length, magic, and the two 16 bit counters. */
        const val HEADER_V2 = 16
        const val VERSION_BODY = 12
        const val SETUP_PAYLOAD: Byte = 0x07
        const val TCP_HEADER = 20

        /** usbmuxd drops anything over 64 KiB; far past that, the stream is out of step. */
        const val MAX_PACKET = 1L shl 20
        const val MAX_STALE_PACKETS = 256

        const val CONTROL_ERROR = 3
        const val CONTROL_WARNING = 5
        const val CONTROL_INFO = 7

        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_ACK = 0x10
        const val HANDSHAKE_FLAGS = FLAG_SYN or FLAG_ACK or FLAG_RST or FLAG_FIN

        const val FIRST_PORT = 1
        const val LAST_PORT = 0xFFFF

        /**
         * The window offered to the phone, and the shift it is sent with.
         *
         * usbmuxd offers 128 KiB and scales by eight bits in the header
         * rather than negotiating a scale option.
         */
        const val WINDOW_SHIFT = 8
        const val RECEIVE_WINDOW = 131_072
        const val WINDOW_UPDATE_STEP = 32 * 1024

        /** usbmuxd sends up to 48 KiB a segment; this stays well inside that. */
        const val MAX_PAYLOAD = 32 * 1024

        const val VERSION_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val WRITE_TIMEOUT_MS = 30_000
        const val POLL_MS = 1_000
    }
}
