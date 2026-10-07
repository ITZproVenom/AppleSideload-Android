package dev.applesideload.device.remote

import dev.applesideload.device.Transport
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The iPhone's end of a CoreDevice tunnel, for tests: a plain TCP over IPv6
 * responder written from RFC 9293, separately from [TunnelStack].
 *
 * It answers SYNs on the ports in [services] (running each service on its own
 * thread with the connection as a [Transport]) and resets anything else. It
 * checks every packet the host sends - IP version, addresses, TCP checksum,
 * that data stays inside the advertised window - and records what is wrong in
 * [problems] instead of throwing on its reader thread. Data is accepted only in
 * order; anything else is dropped and answered with a duplicate ACK, which a
 * real stack may also do. On request it drops the host's first data segment,
 * swaps two of its own outgoing segments, or holds its window shut.
 */
internal class FakeTunnelPeer(
    private val link: Transport,
    private val deviceAddress: ByteArray,
    private val hostAddress: ByteArray,
    private val services: Map<Int, (Transport) -> Unit>,
    private val mss: Int = 1000,
    private val windowScale: Int = 2
) : AutoCloseable {
    /** Ports whose connections lose the first data segment the host sends. */
    val dropFirstData: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Ports whose connections send the first two segments of a write the wrong way round. */
    val reorder: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Ports whose connections advertise a zero window until [resume]. */
    val paused: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    val problems = CopyOnWriteArrayList<String>()

    /** The MSS and window scale (-1 when absent) of every SYN the host sent. */
    val synOptions = CopyOnWriteArrayList<Pair<Int, Int>>()

    @Volatile var droppedSegments = 0
        private set
    @Volatile var reorderedWrites = 0
        private set
    @Volatile var windowProbes = 0
        private set
    @Volatile var resetsSent = 0
        private set
    @Volatile var largestSegmentFromHost = 0
        private set
    @Volatile var bytesFromHost = 0L
        private set

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val connections = HashMap<Int, Conn>() // by the host's port; guarded by lock
    private val random = SecureRandom()
    @Volatile private var closed = false
    @Volatile private var linkDown = false // the host's side of the link ended
    private val reader = Thread(::readLoop, "fake-iphone-tcp").apply { isDaemon = true }

    fun start(): FakeTunnelPeer {
        reader.start()
        return this
    }

    val openConnections: Int get() = lock.withLock { connections.size }

    /** Opens the window of every connection on [port] and tells the host. */
    fun resume(port: Int) {
        lock.withLock {
            paused.remove(port)
            connections.values.filter { it.port == port }.forEach { it.sendAck() }
        }
    }

    override fun close() {
        // This is the tunnel being lost, so no service may get a goodbye (FIN)
        // out on it. Closing the link alone is not enough: its two directions
        // close one after the other, and the reader winding down in between
        // would let a service's FIN through. Every send holds the lock and
        // checks this flag first.
        lock.withLock { closed = true }
        runCatching { link.close() }
        lock.withLock {
            connections.values.forEach { it.inbound.close() }
            changed.signalAll()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(256 * 1024)
        var filled = 0
        try {
            while (!closed) {
                val count = try {
                    link.read(buffer, filled, buffer.size - filled, 1_000)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (count < 0) break
                filled += count
                var start = 0
                while (filled - start >= IPV6_HEADER) {
                    val total = IPV6_HEADER + u16(buffer, start + 4)
                    if (filled - start < total) break
                    handle(buffer.copyOfRange(start, start + total))
                    start += total
                }
                System.arraycopy(buffer, start, buffer, 0, filled - start)
                filled -= start
            }
        } catch (_: IOException) {
            // The link went away; the tests look at what the host saw.
        } catch (error: Throwable) {
            problems += "the fake iPhone's reader failed: $error"
        } finally {
            linkDown = true
            lock.withLock {
                connections.values.forEach { it.inbound.close() }
                changed.signalAll()
            }
        }
    }

    private fun handle(p: ByteArray) {
        val version = (p[0].toInt() and 0xF0) ushr 4
        if (version != 6) { problems += "IP version $version"; return }
        if (p[6].toInt() != 6) { problems += "next header ${p[6]} is not TCP"; return }
        if (p[7].toInt() and 0xFF == 0) problems += "hop limit 0"
        if (!p.copyOfRange(8, 24).contentEquals(hostAddress)) problems += "source address ${p.copyOfRange(8, 24).toHex()}"
        if (!p.copyOfRange(24, 40).contentEquals(deviceAddress)) problems += "destination address ${p.copyOfRange(24, 40).toHex()}"
        if (sum(p) != 0xFFFF) problems += "bad TCP checksum"
        val t = IPV6_HEADER
        val srcPort = u16(p, t)
        val dstPort = u16(p, t + 2)
        val seq = u32(p, t + 4)
        val ack = u32(p, t + 8)
        val dataOffset = ((p[t + 12].toInt() and 0xF0) ushr 4) * 4
        val flags = p[t + 13].toInt() and 0x3F
        val window = u16(p, t + 14)
        if (dataOffset < 20 || t + dataOffset > p.size) { problems += "data offset $dataOffset"; return }
        val data = p.copyOfRange(t + dataOffset, p.size)
        lock.withLock {
            val conn = connections[srcPort]
            if (flags and RST != 0) {
                if (conn != null) {
                    conn.reset = true
                    conn.inbound.close()
                    connections.remove(srcPort)
                    changed.signalAll()
                }
                return
            }
            if (flags and SYN != 0) {
                if (flags and ACK != 0) { problems += "the host sent a SYN-ACK"; return }
                val (peerMss, peerScale) = options(p, t + 20, t + dataOffset)
                synOptions += peerMss to peerScale
                if (conn != null) {
                    if (conn.irs == seq && !conn.established) conn.sendSynAck() // our SYN-ACK was slow
                    else problems += "a second SYN on host port $srcPort"
                    return
                }
                val service = services[dstPort]
                if (service == null) {
                    resetsSent++
                    send(build(dstPort, srcPort, 0, seq + 1, RST or ACK, 0, null, 0, 0, null))
                    return
                }
                val created = Conn(srcPort, dstPort, seq, peerMss, peerScale, window, service)
                connections[srcPort] = created
                created.sendSynAck()
                return
            }
            if (conn == null) {
                if (flags and ACK != 0) send(build(dstPort, srcPort, ack, 0, RST, 0, null, 0, 0, null))
                return
            }
            conn.segment(seq, ack, flags, window, data)
        }
    }

    /** Writes one packet to the host. Lock held, so packets never interleave. */
    private fun send(packet: ByteArray) {
        if (closed) throw IOException("the link to the host is gone")
        link.write(packet, 15_000)
    }

    private inner class Conn(
        val hostPort: Int,
        val port: Int,
        val irs: Int,
        peerMss: Int,
        private val hostScale: Int,
        synWindow: Int,
        private val service: (Transport) -> Unit
    ) : Transport {
        val inbound = Pipe()
        private val iss = random.nextInt()
        private var sndUna = iss
        private var sndNxt = iss + 1
        private var rcvNxt = irs + 1
        private var hostWindow = synWindow.toLong() // a SYN's window is never scaled
        private val sendMss = if (peerMss > 0) minOf(mss, peerMss) else 536
        private val ourScale = if (hostScale >= 0) windowScale else 0
        private val theirScale = if (hostScale >= 0) hostScale else 0
        var established = false
        var reset = false
        private var finSent = false
        private var finAcked = false
        private var hostFin = false
        private var dropPending = port in dropFirstData
        private var reorderPending = port in reorder

        override val description: String get() = "fake iPhone port $port <- host port $hostPort"

        private fun windowBytes(): Long = if (port in paused) 0L else minOf(WINDOW ushr ourScale, 0xFFFF).toLong() shl ourScale

        private fun advertised(): Int = (windowBytes() ushr ourScale).toInt()

        fun sendSynAck() {
            val options = if (hostScale >= 0) {
                byteArrayOf(2, 4, (mss ushr 8).toByte(), mss.toByte(), 1, 3, 3, windowScale.toByte())
            } else {
                byteArrayOf(2, 4, (mss ushr 8).toByte(), mss.toByte())
            }
            val window = if (port in paused) 0 else 0xFFFF
            send(build(port, hostPort, iss, rcvNxt, SYN or ACK, window, null, 0, 0, options))
        }

        fun sendAck() {
            send(build(port, hostPort, sndNxt, rcvNxt, ACK, advertised(), null, 0, 0, null))
        }

        /** One segment from the host on an existing connection. Lock held. */
        fun segment(seq: Int, ack: Int, flags: Int, window: Int, data: ByteArray) {
            if (flags and ACK == 0) { problems += "a segment without ACK on port $port"; return }
            if (!established) {
                if (ack != iss + 1) { problems += "the handshake ACK acknowledged ${ack - iss}, not our SYN"; return }
                established = true
                sndUna = ack
                hostWindow = window.toLong() shl theirScale
                Thread(::runService, "fake-iphone-service-$port").apply { isDaemon = true; start() }
            } else {
                if (ack - sndNxt > 0) problems += "the host acknowledged ${ack - sndNxt} bytes never sent on port $port"
                if (ack - sndUna > 0 && ack - sndNxt <= 0) {
                    sndUna = ack
                    if (finSent && ack == sndNxt) finAcked = true
                }
                hostWindow = window.toLong() shl theirScale
                changed.signalAll()
            }
            if (data.isNotEmpty()) {
                largestSegmentFromHost = maxOf(largestSegmentFromHost, data.size)
                if (seq - rcvNxt + data.size > windowBytes()) {
                    problems += "the host sent past the window on port $port (window ${windowBytes()})"
                }
                if (dropPending) {
                    // Lost on the way: no ACK, the host has to find out and send it again.
                    dropPending = false
                    droppedSegments++
                    return
                }
                val skip = rcvNxt - seq
                if (skip >= 0 && skip < data.size && !hostFin && windowBytes() > 0) {
                    inbound.write(data, skip, data.size - skip)
                    rcvNxt += data.size - skip
                    bytesFromHost += data.size - skip
                }
            } else if (flags and FIN == 0 && seq - rcvNxt < 0) {
                // No data and a sequence number already received: a zero-window
                // probe, which RFC 9293 answers with an ACK carrying the window.
                windowProbes++
                sendAck()
                return
            }
            if (flags and FIN != 0 && seq + data.size == rcvNxt && !hostFin) {
                hostFin = true
                rcvNxt += 1
                inbound.close()
            }
            if (data.isNotEmpty() || flags and FIN != 0) sendAck()
            if (hostFin && finAcked) connections.remove(hostPort)
        }

        private fun runService() {
            try {
                service(this)
            } catch (error: IOException) {
                if (!expectedFailure()) problems += "service on port $port failed: $error"
            } catch (error: Throwable) {
                problems += "service on port $port failed: $error"
            } finally {
                // Send the FIN; after the link is gone there is nobody to send it to.
                try {
                    close()
                } catch (error: IOException) {
                    if (!expectedFailure()) problems += "the FIN on port $port could not be sent: $error"
                }
            }
        }

        /**
         * Whether an I/O failure is just the tunnel going away. A link that
         * fails under a writer is noticed by the reader a moment later, so
         * give it that moment before calling the failure a problem.
         */
        private fun expectedFailure(): Boolean {
            if (!reset && !closed && !linkDown) reader.join(2_000)
            return reset || closed || linkDown
        }

        override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
            inbound.read(into, offset, length, timeoutMs)

        override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
            lock.withLock {
                var at = offset
                val end = offset + length
                var held: ByteArray? = null
                while (at < end) {
                    if (reset || closed) throw IOException("the host reset the connection on port $port")
                    if (finSent) throw IOException("write after close on port $port")
                    val room = hostWindow - (sndNxt - sndUna)
                    if (room <= 0) {
                        held?.let { send(it); held = null }
                        if (!changed.await(20, TimeUnit.SECONDS)) {
                            throw SocketTimeoutException("the host's window stayed shut on port $port")
                        }
                        continue
                    }
                    val n = minOf((end - at).toLong(), sendMss.toLong(), room).toInt()
                    val packet = build(port, hostPort, sndNxt, rcvNxt, ACK or PSH, advertised(), from, at, n, null)
                    sndNxt += n
                    at += n
                    if (reorderPending && held == null && at < end) {
                        held = packet
                        continue
                    }
                    send(packet)
                    held?.let {
                        send(it)
                        held = null
                        reorderPending = false
                        reorderedWrites++
                    }
                }
                held?.let { send(it) }
            }
        }

        override fun close() {
            lock.withLock {
                if (finSent || reset) return
                finSent = true
                send(build(port, hostPort, sndNxt, rcvNxt, FIN or ACK, advertised(), null, 0, 0, null))
                sndNxt += 1
            }
        }
    }

    private fun build(
        srcPort: Int, dstPort: Int, seq: Int, ack: Int, flags: Int, window: Int,
        data: ByteArray?, offset: Int, length: Int, options: ByteArray?
    ): ByteArray {
        val optionBytes = options?.size ?: 0
        val tcpLength = 20 + optionBytes + length
        val p = ByteArray(IPV6_HEADER + tcpLength)
        p[0] = 0x60
        put16(p, 4, tcpLength)
        p[6] = 6
        p[7] = 64
        deviceAddress.copyInto(p, 8)
        hostAddress.copyInto(p, 24)
        val t = IPV6_HEADER
        put16(p, t, srcPort)
        put16(p, t + 2, dstPort)
        put32(p, t + 4, seq)
        put32(p, t + 8, ack)
        p[t + 12] = (((20 + optionBytes) / 4) shl 4).toByte()
        p[t + 13] = flags.toByte()
        put16(p, t + 14, window)
        options?.copyInto(p, t + 20)
        if (data != null) System.arraycopy(data, offset, p, t + 20 + optionBytes, length)
        put16(p, t + 16, sum(p).inv() and 0xFFFF)
        return p
    }

    companion object {
        const val IPV6_HEADER = 40
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        const val WINDOW = 256 * 1024

        /** The ones' complement sum over the IPv6 pseudo-header and the TCP segment, folded. */
        fun sum(p: ByteArray): Int {
            var total = 0L
            for (i in 8 until 40 step 2) total += u16(p, i)
            val tcpLength = p.size - IPV6_HEADER
            total += tcpLength.toLong() + 6
            var i = IPV6_HEADER
            while (i + 1 < p.size) { total += u16(p, i); i += 2 }
            if (i < p.size) total += (p[i].toInt() and 0xFF) shl 8
            while (total ushr 16 != 0L) total = (total and 0xFFFF) + (total ushr 16)
            return total.toInt()
        }

        /** MSS and window scale (-1 if absent) from a SYN's options. */
        fun options(p: ByteArray, from: Int, to: Int): Pair<Int, Int> {
            var peerMss = 0
            var scale = -1
            var i = from
            while (i < to) {
                val kind = p[i].toInt() and 0xFF
                if (kind == 0) break
                if (kind == 1) { i++; continue }
                if (i + 1 >= to) break
                val len = p[i + 1].toInt() and 0xFF
                if (kind == 2 && len == 4) peerMss = u16(p, i + 2)
                if (kind == 3 && len == 3) scale = p[i + 2].toInt() and 0xFF
                i += maxOf(len, 2)
            }
            return peerMss to scale
        }

        fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)
        fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
        fun put32(b: ByteArray, i: Int, v: Int) { put16(b, i, v ushr 16); put16(b, i + 2, v) }
    }
}
