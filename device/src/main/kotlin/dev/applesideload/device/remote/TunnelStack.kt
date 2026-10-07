package dev.applesideload.device.remote

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.Transport
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A small userspace TCP over IPv6, running on the raw packet stream of a
 * CoreDevice tunnel.
 *
 * Once CDTunnel is up the device expects IPv6 packets from our tunnel address
 * to its own, and every service (RemoteServiceDiscovery, the lockdown shims,
 * AFC) is an ordinary TCP port at the device's tunnel address. Android gives
 * an app no way to put such packets on a real interface without a VPN, so the
 * TCP state machine lives here instead.
 *
 * The packet stream underneath is itself reliable and ordered (it is TLS over
 * TCP), so loss only happens if the device drops a packet under pressure; the
 * stack still retransmits from the oldest unacknowledged byte with a doubling
 * timeout, fast-retransmits on three duplicate ACKs and probes a zero window,
 * so it never stalls on that.
 *
 * Threads: one reads packets, one writes them from a queue (so the reader
 * never blocks on output), and a ticker drives retransmission timers. Each
 * connection is a [Transport]: reads block until data, end of stream (-1) or
 * the timeout ([SocketTimeoutException]); writes block while the send buffer
 * is full.
 */
class TunnelStack(
    private val link: Transport,
    private val local: ByteArray,
    private val remote: ByteArray,
    mtu: Int
) : AutoCloseable {

    init {
        require(local.size == 16 && remote.size == 16) { "tunnel addresses must be IPv6" }
    }

    /** Largest TCP payload per packet: MTU minus the IPv6 and TCP headers. */
    val mss: Int = (mtu - IPV6_HEADER - TCP_HEADER).coerceIn(536, 65_000)

    private val connections = ConcurrentHashMap<Int, Connection>()
    private val outgoing = LinkedBlockingQueue<ByteArray>()
    private val random = SecureRandom()
    @Volatile private var failure: IOException? = null
    @Volatile private var closed = false
    private val threads = mutableListOf<Thread>()

    fun start(): TunnelStack {
        threads += Thread(::readLoop, "tunnel-reader").apply { isDaemon = true; start() }
        threads += Thread(::writeLoop, "tunnel-writer").apply { isDaemon = true; start() }
        threads += Thread(::tickLoop, "tunnel-timer").apply { isDaemon = true; start() }
        return this
    }

    val isAlive: Boolean get() = !closed && failure == null

    /** Opens a TCP connection to [port] at the device's tunnel address. */
    fun connect(port: Int, timeoutMs: Int = 10_000): Transport {
        failure?.let { throw IOException("the tunnel is down: ${it.message}", it) }
        if (closed) throw IOException("the tunnel is closed")
        var localPort: Int
        do {
            localPort = 49152 + random.nextInt(16383)
        } while (connections.containsKey(localPort))
        val conn = Connection(localPort, port)
        connections[localPort] = conn
        try {
            conn.open(timeoutMs)
        } catch (error: IOException) {
            connections.remove(localPort)
            throw error
        }
        return conn
    }

    override fun close() {
        if (closed) return
        closed = true
        connections.values.forEach { it.fail(IOException("the tunnel was closed")) }
        connections.clear()
        outgoing.offer(POISON)
        link.close()
        threads.forEach { it.interrupt() }
    }

    private fun die(error: IOException) {
        if (failure != null || closed) return
        failure = error
        Log.w(LogTag.TUNNEL, "tunnel stopped: ${Log.describe(error)}")
        connections.values.forEach { it.fail(IOException("the tunnel went down: ${error.message}", error)) }
        close()
    }

    // ---- packet I/O --------------------------------------------------------

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
                if (count < 0) throw IOException("the device closed the tunnel")
                filled += count
                var start = 0
                while (filled - start >= IPV6_HEADER) {
                    val version = (buffer[start].toInt() and 0xF0) ushr 4
                    if (version != 6) {
                        throw IOException("the tunnel carried a non-IPv6 packet (version $version)")
                    }
                    val payload = u16(buffer, start + 4)
                    val total = IPV6_HEADER + payload
                    if (total > buffer.size) throw IOException("a tunnel packet of $total bytes is larger than any MTU")
                    if (filled - start < total) break
                    handlePacket(buffer, start, total)
                    start += total
                }
                if (start > 0) {
                    System.arraycopy(buffer, start, buffer, 0, filled - start)
                    filled -= start
                }
            }
        } catch (error: IOException) {
            die(error)
        } catch (error: RuntimeException) {
            die(IOException("tunnel reader failed: ${Log.describe(error)}", error))
        }
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val first = outgoing.poll(1, TimeUnit.SECONDS) ?: continue
                if (first === POISON) return
                // Coalesce whatever is queued into one write: the link is a stream.
                var batch = first
                var next = outgoing.peek()
                if (next != null && next !== POISON) {
                    val parts = ArrayList<ByteArray>()
                    parts += first
                    var size = first.size
                    while (size < 256 * 1024) {
                        next = outgoing.peek() ?: break
                        if (next === POISON) break
                        parts += outgoing.poll()!!
                        size += next.size
                    }
                    batch = ByteArray(size)
                    var at = 0
                    for (p in parts) { System.arraycopy(p, 0, batch, at, p.size); at += p.size }
                }
                link.write(batch, 30_000)
            }
        } catch (_: InterruptedException) {
            // closing
        } catch (error: IOException) {
            die(error)
        } catch (error: RuntimeException) {
            die(IOException("tunnel writer failed: ${Log.describe(error)}", error))
        }
    }

    private fun tickLoop() {
        try {
            while (!closed) {
                Thread.sleep(TICK_MS)
                val now = System.currentTimeMillis()
                for (conn in connections.values) conn.tick(now)
            }
        } catch (_: InterruptedException) {
            // closing
        }
    }

    private fun handlePacket(buf: ByteArray, start: Int, total: Int) {
        val nextHeader = buf[start + 6].toInt() and 0xFF
        if (nextHeader != PROTO_TCP) return // ICMPv6 and anything else: nothing to do
        val tcp = start + IPV6_HEADER
        val tcpLength = total - IPV6_HEADER
        if (tcpLength < TCP_HEADER) return
        val srcPort = u16(buf, tcp)
        val dstPort = u16(buf, tcp + 2)
        val seq = u32(buf, tcp + 4)
        val ack = u32(buf, tcp + 8)
        val dataOffset = ((buf[tcp + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < TCP_HEADER || dataOffset > tcpLength) return
        val flags = buf[tcp + 13].toInt() and 0x3F
        val window = u16(buf, tcp + 14)
        val conn = connections[dstPort]
        if (conn == null || conn.remotePort != srcPort) {
            if (flags and RST == 0) sendResetFor(srcPort, dstPort, seq, ack, flags, tcpLength - dataOffset)
            return
        }
        var peerMss = 0
        var peerScale = -1
        if (flags and SYN != 0) {
            var i = tcp + TCP_HEADER
            val end = tcp + dataOffset
            while (i < end) {
                val kind = buf[i].toInt() and 0xFF
                if (kind == 0) break
                if (kind == 1) { i++; continue }
                if (i + 1 >= end) break
                val len = buf[i + 1].toInt() and 0xFF
                if (len < 2 || i + len > end) break
                when (kind) {
                    2 -> if (len == 4) peerMss = u16(buf, i + 2)
                    3 -> if (len == 3) peerScale = minOf(buf[i + 2].toInt() and 0xFF, 14)
                }
                i += len
            }
        }
        conn.receive(seq, ack, flags, window, buf, tcp + dataOffset, tcpLength - dataOffset, peerMss, peerScale)
    }

    /** Answers a segment for a connection we do not have, like a kernel would. */
    private fun sendResetFor(srcPort: Int, dstPort: Int, seq: Int, ack: Int, flags: Int, length: Int) {
        if (flags and ACK != 0) {
            outgoing.offer(buildSegment(dstPort, srcPort, ack, 0, RST, 0, null, 0, 0, null))
        } else {
            val segLen = length + (if (flags and SYN != 0) 1 else 0) + (if (flags and FIN != 0) 1 else 0)
            outgoing.offer(buildSegment(dstPort, srcPort, 0, seq + segLen, RST or ACK, 0, null, 0, 0, null))
        }
    }

    private fun buildSegment(
        srcPort: Int, dstPort: Int, seq: Int, ack: Int, flags: Int, window: Int,
        data: ByteArray?, offset: Int, length: Int, options: ByteArray?
    ): ByteArray {
        val optLen = options?.size ?: 0
        val tcpLen = TCP_HEADER + optLen + length
        val packet = ByteArray(IPV6_HEADER + tcpLen)
        packet[0] = 0x60
        put16(packet, 4, tcpLen)
        packet[6] = PROTO_TCP.toByte()
        packet[7] = 64
        System.arraycopy(local, 0, packet, 8, 16)
        System.arraycopy(remote, 0, packet, 24, 16)
        val t = IPV6_HEADER
        put16(packet, t, srcPort)
        put16(packet, t + 2, dstPort)
        put32(packet, t + 4, seq)
        put32(packet, t + 8, ack)
        packet[t + 12] = (((TCP_HEADER + optLen) / 4) shl 4).toByte()
        packet[t + 13] = flags.toByte()
        put16(packet, t + 14, window)
        if (options != null) System.arraycopy(options, 0, packet, t + TCP_HEADER, optLen)
        if (data != null && length > 0) System.arraycopy(data, offset, packet, t + TCP_HEADER + optLen, length)
        put16(packet, t + 16, checksum(packet, t, tcpLen))
        return packet
    }

    private fun checksum(packet: ByteArray, tcpStart: Int, tcpLen: Int): Int {
        var sum = 0L
        for (i in 8 until 40 step 2) sum += u16(packet, i)
        sum += tcpLen.toLong() + PROTO_TCP
        var i = tcpStart
        val end = tcpStart + tcpLen
        while (i + 1 < end) { sum += u16(packet, i); i += 2 }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val result = sum.inv().toInt() and 0xFFFF
        return if (result == 0) 0xFFFF else result
    }

    // ---- one connection ----------------------------------------------------

    private inner class Connection(val localPort: Int, val remotePort: Int) : Transport {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()

        private var state = State.SYN_SENT
        private val iss = random.nextInt()
        private var sndUna = iss
        private var sndNxt = iss
        private var sndWnd = 0L
        private var peerScale = 0
        private var ourScale = 0
        private var sendMss = mss
        private var rcvNxt = 0

        private val sendBuf = ByteRing(SEND_BUFFER)      // bytes from sndUna onward
        private val recvBuf = ByteRing(RECEIVE_BUFFER)
        private var finQueued = false                  // close() called, FIN to be sent after data
        private var finSent = false
        private var finSeq = 0
        private var finAcked = false
        private var peerFin = false
        private var error: IOException? = null

        private var rto = INITIAL_RTO_MS
        private var retries = 0
        private var timerAt = 0L                       // 0 = no retransmit timer running
        private var dupAcks = 0
        private var recovering = false
        private var recoverPoint = 0                   // sndNxt when loss was detected
        private val outOfOrder = java.util.TreeMap<Long, ByteArray>() // keyed by offset from rcvNxt base
        private var outOfOrderBytes = 0
        private var rcvOffset = 0L                     // bytes received in order so far
        private var lastAdvertised = 0
        private var closedAt = 0L

        override val description: String get() = "tunnel TCP [${remoteText()}]:$remotePort"

        fun open(timeoutMs: Int) {
            lock.withLock {
                sndNxt = iss + 1
                sendSyn()
                timerAt = System.currentTimeMillis() + rto
                val deadline = System.currentTimeMillis() + timeoutMs
                while (state == State.SYN_SENT && error == null) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) {
                        state = State.CLOSED
                        throw SocketTimeoutException("no answer from port $remotePort on the device within $timeoutMs ms")
                    }
                    changed.await(left, TimeUnit.MILLISECONDS)
                }
                error?.let { throw it }
            }
        }

        private fun sendSyn() {
            // MSS, NOP, window scale 8, padded to a 4-byte multiple.
            val opts = byteArrayOf(
                2, 4, (mss ushr 8).toByte(), mss.toByte(),
                1, 3, 3, WINDOW_SCALE.toByte()
            )
            outgoing.offer(buildSegment(localPort, remotePort, iss, 0, SYN, 65535, null, 0, 0, opts))
        }

        override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            if (length == 0) return 0
            lock.withLock {
                val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
                while (recvBuf.size == 0) {
                    error?.let { throw it }
                    if (peerFin || finQueued || state == State.CLOSED) return -1
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw SocketTimeoutException("read timed out after $timeoutMs ms ($description; ${stateText()})")
                    changed.await(minOf(left, 60_000L), TimeUnit.MILLISECONDS)
                }
                val count = recvBuf.take(into, offset, length)
                // Reopen the window if it had become small since the last advertisement.
                val window = receiveWindow()
                if (lastAdvertised < 2 * mss && window - lastAdvertised >= 2 * mss && !peerFin) sendAck()
                return count
            }
        }

        override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
            var at = offset
            val end = offset + length
            lock.withLock {
                val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
                while (at < end) {
                    error?.let { throw it }
                    if (finQueued || state != State.ESTABLISHED) throw IOException("the connection is closed ($description)")
                    val space = sendBuf.space
                    if (space == 0) {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) throw SocketTimeoutException("write timed out after $timeoutMs ms ($description; ${stateText()})")
                        changed.await(minOf(left, 60_000L), TimeUnit.MILLISECONDS)
                        continue
                    }
                    val n = minOf(space, end - at)
                    sendBuf.put(from, at, n)
                    at += n
                    pump()
                }
            }
        }

        override fun close() {
            lock.withLock {
                if (finQueued || state == State.CLOSED) return
                finQueued = true
                closedAt = System.currentTimeMillis()
                if (error != null || state != State.ESTABLISHED) {
                    state = State.CLOSED
                    connections.remove(localPort, this)
                } else {
                    pump()
                }
                changed.signalAll()
            }
        }

        fun fail(cause: IOException) {
            lock.withLock {
                if (error == null) error = cause
                state = State.CLOSED
                changed.signalAll()
            }
        }

        /** The connection's counters, for timeouts that end up in a bug report. Lock held. */
        private fun stateText(): String =
            "state $state, unacked ${sndNxt - sndUna}, buffered ${sendBuf.size}, peer window $sndWnd, " +
                "received ${recvBuf.size}, reordered $outOfOrderBytes, retries $retries, " +
                "timer ${if (timerAt == 0L) "off" else "${timerAt - System.currentTimeMillis()} ms"}, recovering $recovering"

        private fun receiveWindow(): Int = recvBuf.space

        private fun advertisedWindow(): Int {
            val window = receiveWindow()
            lastAdvertised = window
            return minOf(window ushr ourScale, 0xFFFF)
        }

        private fun sendAck() {
            outgoing.offer(buildSegment(localPort, remotePort, sndNxt, rcvNxt, ACK, advertisedWindow(), null, 0, 0, null))
        }

        /** Sends whatever the peer's window allows, then the FIN once all data is out. Lock held. */
        private fun pump() {
            if (state != State.ESTABLISHED) return
            while (true) {
                val inFlight = (sndNxt - sndUna).toLong()
                val unsent = sendBuf.size - inFlight.toInt()
                if (unsent <= 0) break
                val allowed = sndWnd - inFlight
                if (allowed <= 0) break
                val n = minOf(unsent.toLong(), allowed, sendMss.toLong()).toInt()
                sendData(sndNxt, inFlight.toInt(), n)
                sndNxt += n
                if (timerAt == 0L) timerAt = System.currentTimeMillis() + rto
            }
            if (finQueued && !finSent && (sndNxt - sndUna) == sendBuf.size) {
                finSeq = sndNxt
                outgoing.offer(buildSegment(localPort, remotePort, sndNxt, rcvNxt, FIN or ACK, advertisedWindow(), null, 0, 0, null))
                sndNxt += 1
                finSent = true
                if (timerAt == 0L) timerAt = System.currentTimeMillis() + rto
            }
            // Zero window with data waiting: arm the timer so tick() probes it.
            if (sndWnd == 0L && sendBuf.size > 0 && sndNxt == sndUna && timerAt == 0L) {
                timerAt = System.currentTimeMillis() + rto
            }
        }

        private fun sendData(seq: Int, bufferOffset: Int, n: Int) {
            val chunk = ByteArray(n)
            sendBuf.peek(bufferOffset, chunk, 0, n)
            val flags = ACK or PSH
            outgoing.offer(buildSegment(localPort, remotePort, seq, rcvNxt, flags, advertisedWindow(), chunk, 0, n, null))
        }

        fun tick(now: Long) {
            lock.withLock {
                if (state == State.CLOSED) return
                if (finQueued && now - closedAt > LINGER_MS) {
                    // The peer never finished the close; drop it quietly.
                    state = State.CLOSED
                    connections.remove(localPort, this)
                    return
                }
                if (timerAt == 0L || now < timerAt) return
                retries++
                if (retries > MAX_RETRIES) {
                    error = IOException("the device stopped acknowledging data on port $remotePort")
                    state = State.CLOSED
                    connections.remove(localPort, this)
                    changed.signalAll()
                    return
                }
                rto = minOf(rto * 2, MAX_RTO_MS)
                timerAt = now + rto
                if (state == State.ESTABLISHED && sndNxt != sndUna) {
                    recovering = true
                    recoverPoint = sndNxt
                }
                when (state) {
                    State.SYN_SENT -> sendSyn()
                    State.ESTABLISHED -> retransmitHead(probe = true)
                    State.CLOSED -> Unit
                }
            }
        }

        /** Resends the oldest unacknowledged segment (or a 1-byte window probe). Lock held. */
        private fun retransmitHead(probe: Boolean) {
            val inFlight = sndNxt - sndUna
            val dataInFlight = minOf(inFlight, sendBuf.size)
            when {
                dataInFlight > 0 -> sendData(sndUna, 0, minOf(dataInFlight, sendMss))
                finSent && !finAcked -> outgoing.offer(
                    buildSegment(localPort, remotePort, finSeq, rcvNxt, FIN or ACK, advertisedWindow(), null, 0, 0, null)
                )
                probe && sendBuf.size > 0 && sndWnd == 0L -> {
                    // Zero-window probe, the way Linux does it: an ACK carrying an
                    // already-acknowledged sequence number, which the peer must
                    // answer with its current window. Sending a byte past the
                    // window instead would put sndNxt beyond what the peer
                    // accepts, and it would then discard our ACKs as out of window.
                    outgoing.offer(
                        buildSegment(localPort, remotePort, sndUna - 1, rcvNxt, ACK, advertisedWindow(), null, 0, 0, null)
                    )
                }
                else -> timerAt = 0L
            }
        }

        fun receive(
            seq: Int, ack: Int, flags: Int, window: Int,
            data: ByteArray, offset: Int, length: Int, peerMss: Int, scale: Int
        ) {
            lock.withLock {
                if (state == State.CLOSED) return
                if (flags and RST != 0) {
                    error = if (state == State.SYN_SENT) {
                        ConnectException("the device refused port $remotePort")
                    } else {
                        IOException("the device reset the connection ($description)")
                    }
                    state = State.CLOSED
                    connections.remove(localPort, this)
                    changed.signalAll()
                    return
                }
                if (state == State.SYN_SENT) {
                    if (flags and SYN == 0 || flags and ACK == 0 || ack != iss + 1) return
                    rcvNxt = seq + 1
                    sndUna = ack
                    if (scale >= 0) { peerScale = scale; ourScale = WINDOW_SCALE } else { peerScale = 0; ourScale = 0 }
                    if (peerMss > 0) sendMss = minOf(mss, peerMss)
                    sndWnd = window.toLong() // the window in a SYN is never scaled
                    state = State.ESTABLISHED
                    timerAt = 0L
                    retries = 0
                    rto = INITIAL_RTO_MS
                    sendAck()
                    changed.signalAll()
                    return
                }
                if (flags and SYN != 0) {
                    // A retransmitted SYN-ACK: our ACK was lost.
                    sendAck()
                    return
                }
                if (flags and ACK != 0) handleAck(ack, window, length == 0 && flags and FIN == 0)

                var advanced = false
                if (length > 0 || flags and FIN != 0) {
                    advanced = acceptSegment(seq, flags and FIN != 0, data, offset, length)
                    // In order or not, acknowledge: duplicates tell the peer where we are.
                    sendAck()
                }
                if (advanced) changed.signalAll()
                if (peerFin && finQueued && finAcked) {
                    state = State.CLOSED
                    connections.remove(localPort, this)
                }
            }
        }

        /**
         * Takes a segment's payload into the receive buffer, trimming what was
         * already received and keeping segments that arrive ahead of a gap so
         * the peer only has to resend the gap. Returns whether rcvNxt moved.
         * Lock held.
         */
        private fun acceptSegment(seq: Int, fin: Boolean, data: ByteArray, offset: Int, length: Int): Boolean {
            if (peerFin) return false
            var start = seq - rcvNxt                    // where this segment begins relative to rcvNxt
            var off = offset
            var len = length
            if (start < 0) {
                if (start + len < 0 || (start + len == 0 && !fin)) return false // entirely old
                off -= start
                len += start
                start = 0
            }
            val window = recvBuf.space
            if (start > 0) {
                // Ahead of a gap: keep it if it fits in the window and the reorder budget.
                if (len > 0 && start + len <= window && outOfOrderBytes + len <= MAX_OUT_OF_ORDER) {
                    val key = rcvOffset + start
                    if (!outOfOrder.containsKey(key)) {
                        outOfOrder[key] = data.copyOfRange(off, off + len)
                        outOfOrderBytes += len
                    }
                }
                return false
            }
            val take = minOf(len, window)
            var moved = false
            if (take > 0) {
                recvBuf.put(data, off, take)
                rcvNxt += take
                rcvOffset += take
                moved = true
            }
            if (fin && take == len) {
                rcvNxt += 1
                peerFin = true
                outOfOrder.clear()
                outOfOrderBytes = 0
                return true
            }
            // Pull in anything queued that is now contiguous.
            while (outOfOrder.isNotEmpty()) {
                val first = outOfOrder.firstEntry()
                val begin = first.key
                val bytes = first.value
                if (begin > rcvOffset) break
                outOfOrder.pollFirstEntry()
                outOfOrderBytes -= bytes.size
                val skip = (rcvOffset - begin).toInt()
                if (skip >= bytes.size) continue
                val n = minOf(bytes.size - skip, recvBuf.space)
                if (n <= 0) break
                recvBuf.put(bytes, skip, n)
                rcvNxt += n
                rcvOffset += n
                moved = true
            }
            return moved
        }

        private fun handleAck(ack: Int, rawWindow: Int, pure: Boolean) {
            val newWindow = rawWindow.toLong() shl peerScale
            val acked = ack - sndUna
            if (acked > 0 && ack - sndNxt <= 0) {
                var dataAcked = acked
                if (finSent && ack - finSeq > 0) {
                    finAcked = true
                    dataAcked -= 1
                }
                sendBuf.drop(minOf(dataAcked, sendBuf.size))
                sndUna = ack
                sndWnd = newWindow
                retries = 0
                rto = INITIAL_RTO_MS
                dupAcks = 0
                if (finQueued) closedAt = System.currentTimeMillis()
                timerAt = if (sndNxt != sndUna) System.currentTimeMillis() + rto else 0L
                if (recovering) {
                    if (ack - recoverPoint >= 0) {
                        recovering = false
                    } else {
                        // A partial ACK: the next hole starts at the new sndUna, resend it now.
                        retransmitHead(probe = false)
                    }
                }
                changed.signalAll()
                pump()
                if (finAcked && peerFin) {
                    state = State.CLOSED
                    connections.remove(localPort, this)
                }
            } else if (acked == 0) {
                val opened = newWindow > sndWnd
                sndWnd = newWindow
                // A peer answering probes of a zero window is alive, however long it stays shut.
                if (newWindow == 0L) retries = 0
                if (sndNxt != sndUna && !opened && pure) {
                    dupAcks++
                    if (dupAcks == 3) {
                        recovering = true
                        recoverPoint = sndNxt
                        retransmitHead(probe = false)
                    }
                } else if (opened) {
                    pump()
                }
            }
        }
    }

    private enum class State { SYN_SENT, ESTABLISHED, CLOSED }

    private fun remoteText(): String =
        java.net.InetAddress.getByAddress(remote).hostAddress ?: "device"

    /** A bounded FIFO of bytes. */
    internal class ByteRing(private val capacity: Int) {
        private var data = ByteArray(minOf(capacity, 64 * 1024))
        private var head = 0
        var size = 0
            private set
        val space: Int get() = capacity - size

        fun put(src: ByteArray, offset: Int, length: Int) {
            require(length <= space)
            ensure(size + length)
            var written = 0
            while (written < length) {
                val tail = (head + size) % data.size
                val n = minOf(length - written, data.size - tail)
                System.arraycopy(src, offset + written, data, tail, n)
                written += n
                size += n
            }
        }

        fun peek(from: Int, dst: ByteArray, offset: Int, length: Int) {
            require(from + length <= size)
            var copied = 0
            while (copied < length) {
                val pos = (head + from + copied) % data.size
                val n = minOf(length - copied, data.size - pos)
                System.arraycopy(data, pos, dst, offset + copied, n)
                copied += n
            }
        }

        fun take(dst: ByteArray, offset: Int, length: Int): Int {
            val n = minOf(length, size)
            peek(0, dst, offset, n)
            drop(n)
            return n
        }

        fun drop(n: Int) {
            require(n <= size)
            head = (head + n) % data.size
            size -= n
            if (size == 0) head = 0
        }

        private fun ensure(needed: Int) {
            if (needed <= data.size) return
            var cap = data.size
            while (cap < needed) cap *= 2
            val bigger = ByteArray(minOf(cap, capacity).coerceAtLeast(needed))
            peek(0, bigger, 0, size)
            data = bigger
            head = 0
        }
    }

    companion object {
        const val IPV6_HEADER = 40
        const val TCP_HEADER = 20
        const val PROTO_TCP = 6
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10

        const val WINDOW_SCALE = 8
        const val SEND_BUFFER = 1 shl 20
        const val RECEIVE_BUFFER = 4 shl 20
        const val INITIAL_RTO_MS = 400L
        const val MAX_RTO_MS = 8_000L
        const val MAX_RETRIES = 8
        const val TICK_MS = 50L
        const val LINGER_MS = 10_000L
        const val MAX_OUT_OF_ORDER = 1 shl 20

        private val POISON = ByteArray(0)

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        private fun u32(b: ByteArray, i: Int) =
            ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
                ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
        private fun put32(b: ByteArray, i: Int, v: Int) {
            b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
        }
    }
}
