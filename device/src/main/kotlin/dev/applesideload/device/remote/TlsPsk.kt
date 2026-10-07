package dev.applesideload.device.remote

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.Transport
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock

/**
 * TLS 1.2 with a pre-shared key, which is how the Remote Pairing tunnel is
 * protected once pair-verify has produced a shared secret.
 *
 * The device's tunnel listener (the port createListener returns) speaks
 * TLS-PSK with an empty identity and the X25519 pair-verify secret as the key;
 * it prefers PSK-AES256-CBC-SHA384 and also accepts PSK-AES128-CBC-SHA. Java's
 * own TLS stack has no PSK suites at all, so this drives BouncyCastle's TLS
 * protocol engine in non-blocking mode over our [Transport].
 *
 * Reads and writes come from different threads (the tunnel has a reader and
 * a writer thread). The protocol object is guarded by [protocolLock], which
 * is never held while blocking on the inner transport. Every TLS record the
 * protocol produces is queued under that lock, so the queue holds records in
 * the order their sequence numbers were assigned, and is written out in that
 * order by whichever thread holds [outputLock]. The reader thread never
 * waits for that lock: if a writer holds it, the writer drains what the
 * reader queued. That way a write that blocks on a full socket cannot stop
 * the reader, which would otherwise risk both ends waiting on each other.
 */
class TlsPskTransport private constructor(
    private val inner: Transport,
    private val protocol: TlsClientProtocol
) : Transport {

    private val protocolLock = Any()
    private val outputLock = ReentrantLock()
    private val queued = ArrayDeque<ByteArray>() // guarded by protocolLock
    private val scratch = ByteArray(32 * 1024)
    @Volatile private var closed = false

    override val description: String get() = "TLS-PSK over ${inner.description}"

    override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (length == 0) return 0
        while (true) {
            synchronized(protocolLock) {
                val available = protocol.applicationDataAvailable()
                if (available > 0) {
                    return protocol.readInput(into, offset, minOf(available, length))
                }
                if (protocol.isClosed) return -1
            }
            if (closed) return -1
            val count = inner.read(scratch, 0, scratch.size, timeoutMs)
            if (count < 0) return -1
            if (count == 0) continue
            val hasOutput = synchronized(protocolLock) {
                protocol.offerInput(scratch, 0, count)
                queuePendingOutput()
            }
            if (hasOutput) flush(OUTPUT_TIMEOUT_MS, wait = false)
        }
    }

    override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        if (closed) throw IOException("the TLS-PSK tunnel is closed")
        synchronized(protocolLock) {
            protocol.writeApplicationData(from, offset, length)
            queuePendingOutput()
        }
        flush(timeoutMs, wait = true)
    }

    /** Moves what the protocol has produced onto [queued]; [protocolLock] must be held. */
    private fun queuePendingOutput(): Boolean {
        val size = protocol.availableOutputBytes
        if (size > 0) {
            val out = ByteArray(size)
            protocol.readOutput(out, 0, size)
            queued.addLast(out)
        }
        return queued.isNotEmpty()
    }

    /**
     * Writes queued records in order. With [wait] false it returns at once
     * when another thread is already writing; that thread picks up whatever
     * was queued before it lets go of the lock.
     */
    private fun flush(timeoutMs: Int, wait: Boolean) {
        while (true) {
            if (wait) outputLock.lock() else if (!outputLock.tryLock()) return
            try {
                while (true) {
                    val next = synchronized(protocolLock) { queued.removeFirstOrNull() } ?: break
                    inner.write(next, timeoutMs)
                }
            } finally {
                outputLock.unlock()
            }
            // Something queued between the last check and the unlock, by a
            // thread that found the lock taken, is written here.
            if (synchronized(protocolLock) { queued.isEmpty() }) return
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            synchronized(protocolLock) {
                protocol.close()
                queuePendingOutput()
            }
            flush(2_000, wait = true)
        }
        inner.close()
    }

    companion object {
        private const val OUTPUT_TIMEOUT_MS = 15_000

        /** The suites the device's tunnel listener accepts, its preferred one first. */
        val CIPHER_SUITES = intArrayOf(
            CipherSuite.TLS_PSK_WITH_AES_256_CBC_SHA384,
            CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA
        )

        /**
         * Runs the handshake over [inner] with [psk] as the key and an empty
         * identity, returning a transport that carries application data.
         */
        fun connect(inner: Transport, psk: ByteArray, timeoutMs: Int = 15_000): TlsPskTransport {
            var handshakeDone = false
            var alert: String? = null
            val client = object : PSKTlsClient(
                BcTlsCrypto(SecureRandom()),
                BasicTlsPSKIdentity(ByteArray(0), psk.copyOf())
            ) {
                override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()
                override fun getSupportedCipherSuites(): IntArray = CIPHER_SUITES.copyOf()
                // The ClientHello that is known to work against iOS 27's tunnel
                // listener (idevice's native TLS-PSK) carries no extensions at
                // all, so offer none: no encrypt-then-MAC, no extended master
                // secret, nothing the device's TLS stack could choke on.
                override fun getClientExtensions(): java.util.Hashtable<*, *> = java.util.Hashtable<Int, ByteArray>()
                override fun shouldUseExtendedMasterSecret(): Boolean = false
                override fun notifyHandshakeComplete() {
                    super.notifyHandshakeComplete()
                    handshakeDone = true
                }
                override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
                    alert = AlertDescription.getText(alertDescription)
                }
            }
            val protocol = TlsClientProtocol()
            val buffer = ByteArray(16 * 1024)
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                protocol.connect(client)
                while (!handshakeDone) {
                    val size = protocol.availableOutputBytes
                    if (size > 0) {
                        val out = ByteArray(size)
                        protocol.readOutput(out, 0, size)
                        inner.write(out, timeoutMs)
                    }
                    if (handshakeDone) break
                    val left = (deadline - System.currentTimeMillis()).toInt()
                    if (left <= 0) throw SocketTimeoutException("the TLS-PSK handshake took longer than $timeoutMs ms")
                    val count = inner.read(buffer, 0, buffer.size, left)
                    if (count < 0) {
                        throw IOException(
                            "the device closed the tunnel during the TLS-PSK handshake" +
                                (alert?.let { " (alert: $it)" } ?: "")
                        )
                    }
                    if (count > 0) protocol.offerInput(buffer, 0, count)
                }
                // Anything still queued after the final flight (none for a TLS 1.2 client).
                val size = protocol.availableOutputBytes
                if (size > 0) {
                    val out = ByteArray(size)
                    protocol.readOutput(out, 0, size)
                    inner.write(out, timeoutMs)
                }
            } catch (error: TlsFatalAlert) {
                runCatching { inner.close() }
                throw RemotePairingException(
                    "TLS-PSK handshake failed: ${AlertDescription.getText(error.alertDescription)}" +
                        " - the tunnel key from pair-verify was not accepted", error
                )
            } catch (error: IOException) {
                runCatching { inner.close() }
                throw RemotePairingException("TLS-PSK handshake failed: ${Log.describe(error)}", error)
            }
            Log.i(LogTag.TUNNEL, "TLS-PSK tunnel established over ${inner.description}")
            return TlsPskTransport(inner, protocol)
        }
    }
}
