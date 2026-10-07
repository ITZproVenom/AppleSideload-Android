package dev.applesideload.device

import dev.applesideload.core.Bytes
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The phone's side of usbmux, written from what usbmuxd's device.c sends and
 * expects, so [MuxDevice] can be exercised without a cable.
 *
 * It is strict where the real phone is believed to be strict: the version
 * packet must use the short header, SETUP must reset the counters, the mux
 * counters must run in order, data must carry ACK alone, sequence numbers
 * must line up, and the host must never send past the window offered to it.
 * Every violation is recorded in [violations] rather than thrown, so a test
 * can assert there were none after the fact.
 */
internal class FakeMuxPhone(
    private val replyMajor: Long = 2,
    private val stalePackets: Int = 0,
    /** Receive buffer per connection on the phone; the window offered is what is free of it. */
    private val receiveBuffer: Int = 131_072
) : AutoCloseable {

    private val toHost = ChunkQueue { "phone to host" }
    private val toPhone = ChunkQueue { "host to phone" }

    val violations = CopyOnWriteArrayList<String>()
    val hostFlags = CopyOnWriteArrayList<Int>()
    private val listeners = ConcurrentHashMap<Int, (PhoneSocket) -> Unit>()
    private val sockets = ConcurrentHashMap<Int, PhoneSocket>()

    @Volatile var versionRequest: ByteArray? = null
    @Volatile var setupPacket: ByteArray? = null
    @Volatile var hostClosedTransport = false

    /** The rx counter this phone puts in its headers; the host must send it back. */
    @Volatile var phoneRxField = 0x0102

    private var v2 = false
    private val writeLock = Any()
    private var phoneTx = 0
    private var expectedHostTx = -1

    /** Every rx counter this phone has sent; the host may echo any it has seen, or 0xFFFF before any. */
    private val rxFieldsSent = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    /** The rx counter in the host's latest packet. */
    @Volatile var lastHostRx = -1
    @Volatile private var running = true

    /** What [MuxDevice] is given as its transport. */
    val host: Transport = object : Transport {
        override val description = "fake USB"
        override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
            toHost.read(into, offset, length, timeoutMs)

        override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
            if (!running) throw IOException("the fake phone was unplugged")
            toPhone.put(from.copyOfRange(offset, offset + length))
        }

        override fun close() {
            hostClosedTransport = true
            toHost.finish()
            toPhone.finish()
        }
    }

    private val thread = Thread({ loop() }, "fake-phone").apply { isDaemon = true; start() }

    fun listen(port: Int, service: (PhoneSocket) -> Unit) {
        listeners[port] = service
    }

    /** Makes the USB link die the way a pulled cable does. */
    fun unplug() {
        running = false
        toHost.fail(IOException("the fake phone was unplugged"))
        toPhone.finish()
    }

    override fun close() {
        running = false
        toHost.finish()
        toPhone.finish()
        sockets.values.forEach { it.closeLocally() }
    }

    // MARK: - Host to phone

    private fun readExactly(length: Int): ByteArray? {
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = try {
                toPhone.read(out, filled, length - filled, 200)
            } catch (idle: SocketTimeoutException) {
                if (!running) return null
                continue
            }
            if (read < 0) return null
            filled += read
        }
        return out
    }

    private fun loop() {
        try {
            // The version request, which must use the 8 byte header.
            val head = readExactly(8) ?: return
            val protocol = Bytes.u32be(head, 0)
            val length = Bytes.u32be(head, 4).toInt()
            if (protocol != 0L) violations += "first packet had protocol $protocol, not version"
            if (length != 20) violations += "version request was $length bytes, not 20"
            val body = readExactly(length - 8) ?: return
            versionRequest = head + body
            if (Bytes.u32be(body, 0) != 2L) violations += "version request asked for ${Bytes.u32be(body, 0)}"
            repeat(stalePackets) { index ->
                // What an earlier session could leave in the pipe: v2 TCP packets.
                val tcp = ByteArray(20)
                Bytes.putU16be(tcp, 0, 62078)
                Bytes.putU16be(tcp, 2, 4000 + index)
                tcp[12] = 0x50
                tcp[13] = FLAG_ACK.toByte()
                writeRaw(6, tcp, forceV2 = true)
            }
            val reply = ByteArray(12)
            Bytes.putU32be(reply, 0, replyMajor)
            writeRaw(0, reply, forceV2 = false)
            v2 = replyMajor >= 2
            if (v2) {
                val setup = readPacket() ?: return
                setupPacket = setup.raw
                if (setup.protocol != 2L) violations += "expected SETUP after the version reply, got ${setup.protocol}"
                if (setup.raw.size != 17) violations += "SETUP was ${setup.raw.size} bytes, not 17"
                if (setup.body.size != 1 || setup.body[0] != 0x07.toByte()) violations += "SETUP payload was not 0x07"
            }
            while (running) {
                val packet = readPacket() ?: return
                when (packet.protocol) {
                    6L -> tcp(packet.body)
                    else -> violations += "host sent protocol ${packet.protocol}"
                }
            }
        } catch (error: Throwable) {
            if (running) violations += "fake phone crashed: $error"
        }
    }

    private class Packet(val protocol: Long, val body: ByteArray, val raw: ByteArray)

    private fun readPacket(): Packet? {
        val head = readExactly(8) ?: return null
        val protocol = Bytes.u32be(head, 0)
        val length = Bytes.u32be(head, 4).toInt()
        val headerSize = if (v2) 16 else 8
        if (length < headerSize) {
            violations += "host packet of $length bytes is shorter than its header"
            return null
        }
        val rest = readExactly(length - 8) ?: return null
        if (v2) {
            if (Bytes.u32be(rest, 0) != 0xfeedfaceL) violations += "host packet without feedface magic"
            val tx = Bytes.u16be(rest, 4)
            val rx = Bytes.u16be(rest, 6)
            if (protocol == 2L) {
                if (tx != 0) violations += "SETUP carried tx $tx, not 0"
                if (rx != 0xFFFF) violations += "SETUP carried rx $rx, not 0xFFFF"
            } else {
                if (tx != expectedHostTx) violations += "host tx counter was $tx, expected $expectedHostTx"
                if (rx != 0xFFFF && rx !in rxFieldsSent) violations += "host rx counter $rx was never sent"
                lastHostRx = rx
            }
            expectedHostTx = (tx + 1) and 0xFFFF
        }
        val body = rest.copyOfRange(headerSize - 8, rest.size)
        return Packet(protocol, body, head + rest)
    }

    // MARK: - Phone to host

    private fun writeRaw(protocol: Long, body: ByteArray, forceV2: Boolean? = null) {
        synchronized(writeLock) {
            val sequenced = forceV2 ?: v2
            val headerSize = if (sequenced) 16 else 8
            val out = ByteArray(headerSize + body.size)
            Bytes.putU32be(out, 0, protocol)
            Bytes.putU32be(out, 4, out.size.toLong())
            if (sequenced) {
                Bytes.putU32be(out, 8, 0xfeedfaceL)
                Bytes.putU16be(out, 12, phoneTx)
                Bytes.putU16be(out, 14, phoneRxField)
                phoneTx = (phoneTx + 1) and 0xFFFF
                if (forceV2 == null) rxFieldsSent += phoneRxField
            }
            System.arraycopy(body, 0, out, headerSize, body.size)
            toHost.put(out)
        }
    }

    fun sendControl(type: Int, text: String) {
        writeRaw(1, byteArrayOf(type.toByte()) + text.toByteArray())
    }

    private fun segment(
        localPort: Int,
        hostPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        window: Int,
        data: ByteArray = ByteArray(0)
    ) {
        val tcp = ByteArray(20 + data.size)
        Bytes.putU16be(tcp, 0, localPort)
        Bytes.putU16be(tcp, 2, hostPort)
        Bytes.putU32be(tcp, 4, seq.toLong() and 0xFFFFFFFFL)
        Bytes.putU32be(tcp, 8, ack.toLong() and 0xFFFFFFFFL)
        tcp[12] = 0x50
        tcp[13] = flags.toByte()
        Bytes.putU16be(tcp, 14, (window shr 8).coerceIn(0, 0xFFFF))
        System.arraycopy(data, 0, tcp, 20, data.size)
        writeRaw(6, tcp)
    }

    private fun tcp(body: ByteArray) {
        val hostPort = Bytes.u16be(body, 0)
        val port = Bytes.u16be(body, 2)
        val seq = Bytes.u32be(body, 4).toInt()
        val ack = Bytes.u32be(body, 8).toInt()
        val flags = body[13].toInt() and 0xFF
        val window = Bytes.u16be(body, 14) shl 8
        val data = body.copyOfRange(20, body.size)
        hostFlags += flags
        if ((body[12].toInt() and 0xF0) shr 4 != 5) violations += "host TCP header length was not 20"
        if (flags and FLAG_PSH != 0) violations += "host set PSH"
        if (flags and FLAG_FIN != 0) violations += "host sent FIN; usbmuxd ends connections with RST"

        if (flags == FLAG_SYN) {
            if (seq != 0) violations += "SYN carried seq $seq, not 0"
            val service = listeners[port]
            if (service == null) {
                segment(port, hostPort, 0, seq + 1, FLAG_RST or FLAG_ACK, 0)
                return
            }
            val socket = PhoneSocket(port, hostPort, hostWindow = window)
            sockets[hostPort] = socket
            segment(port, hostPort, 0, seq + 1, FLAG_SYN or FLAG_ACK, receiveBuffer)
            Thread({ runCatching { service(socket) } }, "fake-service-$port").apply {
                isDaemon = true
                start()
            }
            return
        }
        val socket = sockets[hostPort]
        if (socket == null) {
            if (flags and FLAG_RST == 0) violations += "packet for unknown host port $hostPort"
            return
        }
        socket.fromHost(seq, ack, flags, window, data)
    }

    /** One connection as the phone's service sees it. */
    inner class PhoneSocket(val port: Int, val hostPort: Int, hostWindow: Int) {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val received = ChunkQueue { "phone socket $port" }
        private var unread = 0L
        private var sendNext = 1          // the phone's ISN is 0; the SYN took it
        private var hostAcked = 1
        private var hostWindow = hostWindow
        private var receiveNext = 1       // the host's SYN took 0
        private var offered = receiveBuffer
        @Volatile var resetByHost = false
        var minimumHostWindow = Int.MAX_VALUE
        var established = false

        // Every segment is built and queued under [lock], so the window and
        // acknowledgement in it are never older than one sent before it.
        private fun send(seq: Int, flags: Int, window: Int, data: ByteArray = ByteArray(0)) =
            segment(port, hostPort, seq, receiveNext, flags, window, data)

        fun fromHost(seq: Int, ack: Int, flags: Int, window: Int, data: ByteArray) {
            lock.withLock {
                if (flags and FLAG_RST != 0) {
                    resetByHost = true
                    received.finish()
                    changed.signalAll()
                    return
                }
                if (flags != FLAG_ACK) violations += "host data/ack segment had flags 0x${Integer.toHexString(flags)}"
                hostAcked = ack
                hostWindow = window
                minimumHostWindow = minOf(minimumHostWindow, window)
                changed.signalAll()
                established = true
                if (data.isEmpty()) return
                if (seq != receiveNext) violations += "host data seq $seq, expected $receiveNext"
                val limit = receiveNext + offered
                if (seq + data.size - limit > 0) {
                    violations += "host sent ${seq + data.size - limit} bytes past the window offered"
                }
                receiveNext = seq + data.size
                unread += data.size
                received.put(data)
                offered = (receiveBuffer - unread).toInt().coerceAtLeast(0)
                send(sendNext, FLAG_ACK, offered)
            }
        }

        /** Reads what the host sent, opening the window as a real stack would. */
        fun read(length: Int, timeoutMs: Int = 10_000): ByteArray? {
            val out = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val read = received.read(out, filled, length - filled, timeoutMs)
                if (read < 0) return if (filled == 0) null else out.copyOf(filled)
                filled += read
                lock.withLock {
                    unread -= read
                    val before = offered
                    offered = (receiveBuffer - unread).toInt()
                    if (offered - before >= 1024 || unread == 0L) send(sendNext, FLAG_ACK, offered)
                }
            }
            return out
        }

        /** Sends to the host, never past the window the host offered. */
        fun send(data: ByteArray, chunk: Int = 16 * 1024) {
            var at = 0
            while (at < data.size) {
                lock.withLock {
                    while (true) {
                        if (resetByHost) throw IOException("reset by host")
                        if (hostAcked + hostWindow - sendNext > 0) break
                        if (!changed.await(10, TimeUnit.SECONDS)) {
                            throw IOException("the host kept its window closed")
                        }
                    }
                    val count = minOf(chunk, data.size - at, hostAcked + hostWindow - sendNext)
                    send(sendNext, FLAG_ACK, offered, data.copyOfRange(at, at + count))
                    sendNext += count
                    at += count
                }
            }
        }

        /** Sends a segment again exactly as it went the first time. */
        fun repeatLast(data: ByteArray) = lock.withLock {
            send(sendNext - data.size, FLAG_ACK, offered, data)
        }

        fun reset() = lock.withLock { send(sendNext, FLAG_RST or FLAG_ACK, 0) }

        fun finish() = lock.withLock { send(sendNext, FLAG_FIN or FLAG_ACK, offered) }

        fun awaitHostReset(timeoutMs: Long = 5_000): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (!resetByHost && System.currentTimeMillis() < end) Thread.sleep(10)
            return resetByHost
        }

        fun closeLocally() = received.finish()
    }

    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
    }
}
