package dev.applesideload.apple

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The little of RFC 6455 an anisette v3 source needs: one text conversation.
 *
 * Provisioning a v3 identity is a short exchange of JSON messages over a
 * WebSocket. Pulling in a networking library for that is not worth it, so
 * this is a plain client: TLS with the platform's trust store and a checked
 * host name, masked frames out, fragmented and control frames in.
 */
internal class WebSocketClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream
) : Closeable {

    private val random = SecureRandom()
    @Volatile private var closed = false

    /** Sends one text message. */
    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /** The next whole text (or binary) message, or null once the server closes. */
    fun receiveText(): String? {
        val message = ByteArrayOutputStream()
        var inMessage = false
        while (true) {
            val frame = readFrame()
            when (frame.opcode) {
                OP_TEXT, OP_BINARY -> {
                    if (inMessage) throw IOException("a new message began before the last one ended")
                    message.write(frame.payload)
                    if (frame.fin) return message.toString("UTF-8")
                    inMessage = true
                }
                OP_CONTINUATION -> {
                    if (!inMessage) throw IOException("a continuation frame came with no message to continue")
                    message.write(frame.payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("the message is too large")
                    if (frame.fin) return message.toString("UTF-8")
                }
                OP_CLOSE -> {
                    // Echo the status back, as the protocol asks, then stop.
                    runCatching { sendFrame(OP_CLOSE, frame.payload.copyOfRange(0, minOf(2, frame.payload.size))) }
                    closed = true
                    return null
                }
                OP_PING -> sendFrame(OP_PONG, frame.payload)
                OP_PONG -> Unit
                else -> throw IOException("the server sent an unknown frame type ${frame.opcode}")
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            // 1000, a normal closure.
            runCatching { sendFrame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
        }
        runCatching { socket.close() }
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(): Frame {
        val first = readByte()
        val second = readByte()
        val length = when (val short = second and 0x7F) {
            126 -> (readByte() shl 8) or readByte()
            127 -> {
                var value = 0L
                repeat(8) { value = (value shl 8) or readByte().toLong() }
                if (value < 0 || value > MAX_MESSAGE) throw IOException("the frame is too large")
                value.toInt()
            }
            else -> short
        }
        if (length > MAX_MESSAGE) throw IOException("the frame is too large")
        val mask = if (second and 0x80 != 0) readFully(4) else null
        val payload = readFully(length)
        if (mask != null) for (index in payload.indices) {
            payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        return Frame(first and 0x80 != 0, first and 0x0F, payload)
    }

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(0x80 or opcode)
        // Every frame a client sends is masked (RFC 6455 section 5.3).
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                frame.write(0x80 or 126)
                frame.write(payload.size shr 8)
                frame.write(payload.size and 0xFF)
            }
            else -> {
                frame.write(0x80 or 127)
                val size = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) frame.write(((size shr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        frame.write(mask)
        frame.write(ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() })
        output.write(frame.toByteArray())
        output.flush()
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException("the server ended the connection")
        return value
    }

    private fun readFully(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw EOFException("the server ended the connection after $offset of $count bytes")
            offset += read
        }
        return buffer
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_MESSAGE = 4 * 1024 * 1024
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Opens [url] (ws:// or wss://) and completes the opening handshake. */
        fun connect(url: String, timeoutMs: Int = 30_000, userAgent: String = "AppleSideload"): WebSocketClient {
            val uri = URI(url)
            val secure = when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> false
                else -> throw IOException("not a WebSocket address: $url")
            }
            val host = uri.host ?: throw IOException("no host in $url")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: "")

            val plain = Socket()
            val socket: Socket = try {
                plain.connect(InetSocketAddress(host, port), timeoutMs)
                plain.soTimeout = timeoutMs
                if (secure) secureSocket(plain, host, port) else plain
            } catch (error: Exception) {
                runCatching { plain.close() }
                throw if (error is IOException) error else IOException(error.message, error)
            }
            try {
                val input = socket.getInputStream().buffered()
                val output = socket.getOutputStream()
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
                val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: $userAgent\r\n\r\n"
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()

                val head = readHead(input)
                val lines = head.split("\r\n")
                val status = lines.first()
                if (status.split(' ').getOrNull(1) != "101") {
                    throw IOException("the server did not open a WebSocket: $status")
                }
                val headers = lines.drop(1).mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }.toMap()
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
                )
                if (headers["sec-websocket-accept"] != expected) {
                    throw IOException("the server's WebSocket handshake did not match the request")
                }
                return WebSocketClient(socket, input, output)
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw if (error is IOException) error else IOException(error.message, error)
            }
        }

        private fun secureSocket(plain: Socket, host: String, port: Int): SSLSocket {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = factory.createSocket(plain, host, port, true) as SSLSocket
            socket.soTimeout = plain.soTimeout
            val parameters = socket.sslParameters
            if (!HostCheck.isIpLiteral(host)) parameters.serverNames = listOf(SNIHostName(host))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = parameters
            socket.startHandshake()
            // Checked here as well, so it holds wherever the platform's own
            // endpoint check is not applied to a bare socket.
            val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw IOException("$host sent no certificate")
            if (!HostCheck.matches(host, leaf)) {
                throw IOException("the certificate $host sent is not for $host")
            }
            return socket
        }

        /** The response line and headers, up to the blank line. */
        private fun readHead(input: InputStream): String {
            val head = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < 4) {
                val value = input.read()
                if (value < 0) throw EOFException("the server ended the connection during the handshake")
                head.write(value)
                matched = if (value.toByte() == end[matched]) matched + 1 else if (value.toByte() == end[0]) 1 else 0
                if (head.size() > 16_384) throw IOException("the server's handshake response is too long")
            }
            return head.toString("ISO-8859-1").trimEnd()
        }
    }
}

/** Host name checks for a certificate, as HTTPS defines them (RFC 6125). */
internal object HostCheck {

    fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    fun matches(host: String, certificate: X509Certificate): Boolean {
        val names = runCatching { certificate.subjectAlternativeNames }.getOrNull().orEmpty()
        val wanted = host.trimEnd('.').lowercase()
        return if (isIpLiteral(wanted)) {
            names.any { it.size >= 2 && it[0] == 7 && (it[1] as? String)?.lowercase() == wanted }
        } else {
            names.any { it.size >= 2 && it[0] == 2 && (it[1] as? String)?.let { name -> dnsMatches(wanted, name) } == true }
        }
    }

    /** One left-most "*" label at most, which never matches across dots or a bare suffix. */
    fun dnsMatches(host: String, pattern: String): Boolean {
        val name = pattern.trimEnd('.').lowercase()
        if (!name.startsWith("*.")) return host == name
        val suffix = name.substring(1)
        if (suffix.count { it == '.' } < 2) return false
        return host.endsWith(suffix) && host.length > suffix.length && host.indexOf('.') == host.length - suffix.length
    }
}
