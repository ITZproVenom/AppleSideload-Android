package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS over a transport that is not a socket.
 *
 * Lockdown answers plainly until StartSession, and from then on everything is
 * inside TLS with the host certificate from the pairing record presented as
 * the client identity. There is no socket to hand to SSLSocketFactory here -
 * the bytes are going over USB bulk endpoints - so the handshake is driven
 * through an SSLEngine by hand.
 *
 * The device presents the certificate this host issued to it during pairing.
 * It is checked against the copy in the pairing record rather than against a
 * public trust store, which is the only meaningful check available: both
 * certificates were minted by this app.
 */
class TlsTransport private constructor(
    private val inner: Transport,
    private val engine: SSLEngine
) : Transport {

    override val description: String get() = "TLS over ${inner.description}"

    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize + 1024)
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize + 1024)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize + 1024)

    init {
        netIn.limit(0)
        appIn.limit(0)
    }

    override fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        while (!appIn.hasRemaining()) {
            if (!fill(timeoutMs)) return -1
        }
        val count = minOf(appIn.remaining(), length)
        appIn.get(into, offset, count)
        return count
    }

    override fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        val source = ByteBuffer.wrap(from, offset, length)
        while (source.hasRemaining()) {
            netOut.clear()
            val result = engine.wrap(source, netOut)
            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    netOut.flip()
                    val chunk = ByteArray(netOut.remaining())
                    netOut.get(chunk)
                    inner.write(chunk, timeoutMs)
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                }
                else -> throw DeviceException(
                    operation = "TLS write",
                    reason = "the engine reported ${result.status}"
                )
            }
        }
    }

    override fun close() {
        runCatching { engine.closeOutbound() }
        inner.close()
    }

    /** Pulls one record from the transport and decrypts whatever it holds. */
    private fun fill(timeoutMs: Int): Boolean {
        while (true) {
            if (netIn.hasRemaining()) {
                appIn.clear()
                val result = engine.unwrap(netIn, appIn)
                when (result.status) {
                    SSLEngineResult.Status.OK -> {
                        appIn.flip()
                        compact()
                        if (appIn.hasRemaining()) return true
                        if (!netIn.hasRemaining()) continue
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        appIn.limit(0)
                        if (!readMore(timeoutMs)) return false
                    }
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                        appIn.limit(0)
                    }
                    SSLEngineResult.Status.CLOSED -> return false
                    else -> return false
                }
            } else if (!readMore(timeoutMs)) {
                return false
            }
        }
    }

    private fun compact() {
        if (netIn.hasRemaining()) {
            val rest = ByteArray(netIn.remaining())
            netIn.get(rest)
            netIn = ByteBuffer.wrap(rest)
        } else {
            netIn.limit(0)
        }
    }

    private fun readMore(timeoutMs: Int): Boolean {
        val buffer = ByteArray(8192)
        val read = inner.read(buffer, 0, buffer.size, timeoutMs)
        if (read <= 0) return false
        val existing = ByteArray(netIn.remaining())
        netIn.get(existing)
        val combined = ByteArray(existing.size + read)
        System.arraycopy(existing, 0, combined, 0, existing.size)
        System.arraycopy(buffer, 0, combined, existing.size, read)
        netIn = ByteBuffer.wrap(combined)
        return true
    }

    companion object {
        /**
         * Performs the handshake and returns the encrypted transport.
         *
         * iOS accepts TLS 1.0 through 1.2 here depending on version, and the
         * platform picks the highest both sides allow. An older device that
         * only offers TLS 1.0 is a real case, so the protocol list is not
         * narrowed beyond what the platform provides.
         */
        fun handshake(inner: Transport, record: PairingRecord): TlsTransport {
            val identity = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(
                    "host",
                    record.hostPrivateKey(),
                    PASSWORD,
                    arrayOf(record.hostCertificate(), record.rootCertificate())
                )
            }
            val keyManagers = KeyManagerFactory.getInstance("PKIX").run {
                init(identity, PASSWORD)
                keyManagers
            }
            val expected = record.deviceCertificate()
            val trust = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    throw UnsupportedOperationException("this side is never a server")

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val presented = chain.firstOrNull()
                        ?: throw DeviceException(
                            operation = "TLS handshake",
                            reason = "the device presented no certificate"
                        )
                    if (!presented.encoded.contentEquals(expected.encoded)) {
                        throw DeviceException(
                            operation = "TLS handshake",
                            reason = "the device presented a certificate this pairing record " +
                                "does not know",
                            limitation = "the pairing record belongs to a different device, or " +
                                "the device has been re-paired with another host",
                            alternative = "pair this device again"
                        )
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            val context = SSLContext.getInstance("TLS")
            context.init(keyManagers, trust, null)
            val engine = context.createSSLEngine().apply {
                useClientMode = true
            }
            val tls = TlsTransport(inner, engine)
            tls.drive()
            Log.i(
                LogTag.LOCKDOWN,
                "session is encrypted with ${engine.session.protocol} " +
                    engine.session.cipherSuite
            )
            return tls
        }

        private val PASSWORD = CharArray(0)
    }

    /** Runs the handshake to completion. */
    private fun drive() {
        engine.beginHandshake()
        var status = engine.handshakeStatus
        while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
            status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (status) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(ByteBuffer.allocate(0), netOut)
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        val chunk = ByteArray(netOut.remaining())
                        netOut.get(chunk)
                        inner.write(chunk)
                    }
                    status = result.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!netIn.hasRemaining() && !readMore(20_000)) {
                        throw DeviceException(
                            operation = "TLS handshake",
                            reason = "the device stopped responding during the handshake"
                        )
                    }
                    appIn.clear()
                    val result = engine.unwrap(netIn, appIn)
                    appIn.flip()
                    when (result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                            if (!readMore(20_000)) {
                                throw DeviceException(
                                    operation = "TLS handshake",
                                    reason = "the device closed the connection mid handshake"
                                )
                            }
                        SSLEngineResult.Status.BUFFER_OVERFLOW ->
                            appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                        SSLEngineResult.Status.CLOSED -> throw DeviceException(
                            operation = "TLS handshake",
                            reason = "the device rejected the handshake and closed the session"
                        )
                        else -> Unit
                    }
                    compact()
                    status = engine.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) {
                        val task = engine.delegatedTask ?: break
                        task.run()
                    }
                    status = engine.handshakeStatus
                }
                else -> status = engine.handshakeStatus
            }
        }
        appIn.limit(0)
    }
}
