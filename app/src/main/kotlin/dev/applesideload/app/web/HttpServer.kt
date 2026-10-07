package dev.applesideload.app.web

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One parsed HTTP request. The body is read by the handler, at most [contentLength] bytes. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names are lower case. */
    val headers: Map<String, String>,
    val contentLength: Long,
    val body: InputStream,
    val remote: InetAddress?
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

    /** Reads the whole body as UTF-8, refusing anything larger than [limit] bytes. */
    fun bodyText(limit: Int = 64 * 1024): String {
        if (contentLength > limit) throw HttpError(413, "The request body is larger than $limit bytes.")
        val bytes = body.readNBytesCompat(contentLength.toInt())
        if (bytes.size.toLong() != contentLength) throw HttpError(400, "The request body ended early.")
        return String(bytes, Charsets.UTF_8)
    }
}

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8", headers: Map<String, String> = emptyMap()) =
            HttpResponse(status, type, text.toByteArray(Charsets.UTF_8), headers)
    }
}

/** Thrown by a handler to answer with [status] and a plain message. */
class HttpError(val status: Int, override val message: String) : Exception(message)

/**
 * A small HTTP/1.1 server for the LAN controller.
 *
 * It does exactly what the controller needs and nothing more: one request per
 * connection (Connection: close), bodies sized by Content-Length only, bounded
 * header size and a bounded number of worker threads, so a misbehaving client
 * on the network cannot exhaust the phone.
 */
class HttpServer(
    private val port: Int,
    private val bindAddress: InetAddress? = null,
    private val handler: (HttpRequest) -> HttpResponse
) : Closeable {

    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers: ExecutorService = ThreadPoolExecutor(
        1, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue()
    ) { runnable -> Thread(runnable, "web-worker").apply { isDaemon = true } }

    /** The port actually bound, which differs from [port] only when [port] is 0. */
    val boundPort: Int get() = socket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(bindAddress, port), 64)
        } catch (error: IOException) {
            server.close()
            throw error
        }
        socket = server
        acceptThread = Thread({ acceptLoop(server) }, "web-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                continue
            }
            try {
                workers.execute { serve(client) }
            } catch (_: RejectedExecutionException) {
                // Every worker is busy: answer at once instead of queueing without bound.
                runCatching {
                    client.soTimeout = 2_000
                    write(client, HttpResponse.text(503, "The phone is busy with other requests. Try again."))
                }
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use {
            var input: InputStream? = null
            try {
                client.soTimeout = READ_TIMEOUT_MS
                client.tcpNoDelay = true
                val buffered = client.getInputStream().buffered(16 * 1024)
                input = buffered
                val request = parse(buffered, client.inetAddress) ?: return
                val response = try {
                    handler(request)
                } catch (error: HttpError) {
                    HttpResponse.text(error.status, error.message)
                } catch (error: Exception) {
                    HttpResponse.text(500, "The phone failed to handle the request: ${error.message ?: error::class.java.simpleName}")
                }
                write(client, response)
            } catch (error: HttpError) {
                runCatching { write(client, HttpResponse.text(error.status, error.message)) }
            } catch (_: IOException) {
                // The client went away; nothing to answer.
            }
            input?.let { finish(client, it) }
        }
    }

    /**
     * Closes gracefully: a socket closed with unread input sends a reset,
     * and a reset can make the client drop the answer it was just sent -
     * for example a "too large" reply to an upload still in flight.
     */
    private fun finish(client: Socket, input: InputStream) {
        runCatching {
            client.shutdownOutput()
            client.soTimeout = 1_000
            val buffer = ByteArray(8 * 1024)
            var drained = 0
            while (drained < DRAIN_LIMIT) {
                val read = input.read(buffer)
                if (read < 0) break
                drained += read
            }
        }
    }

    /** Returns null when the client closed the connection without sending anything. */
    private fun parse(input: InputStream, remote: InetAddress?): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "Malformed request line.")
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        if (!target.startsWith("/")) throw HttpError(400, "Only origin-form request targets are accepted.")

        val headers = LinkedHashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: throw HttpError(400, "The headers ended early.")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES || headers.size >= MAX_HEADERS) {
                throw HttpError(431, "The request headers are too large.")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "Malformed header line.")
            headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) {
            throw HttpError(411, "Send the body with a Content-Length; chunked uploads are not supported.")
        }
        val length = headers["content-length"]?.let {
            it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: throw HttpError(400, "Invalid Content-Length.")
        } ?: 0L

        val rawPath = target.substringBefore('?')
        val path = decode(rawPath)
        if (path.contains("..")) throw HttpError(400, "Invalid path.")
        val query = if (target.contains('?')) parseQuery(target.substringAfter('?')) else emptyMap()
        return HttpRequest(method, path, query, headers, length, LimitedInputStream(input, length), remote)
    }

    private fun write(client: Socket, response: HttpResponse) {
        val out = BufferedOutputStream(client.getOutputStream(), 16 * 1024)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        head.append("Content-Type: ").append(response.contentType).append("\r\n")
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        head.append("X-Frame-Options: DENY\r\n")
        head.append("Referrer-Policy: no-referrer\r\n")
        head.append("Content-Security-Policy: default-src 'self'; img-src 'self' data:; frame-ancestors 'none'\r\n")
        response.headers.forEach { (name, value) ->
            require(!name.contains('\n') && !value.contains('\n') && !name.contains('\r') && !value.contains('\r'))
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        workers.shutdownNow()
    }

    companion object {
        const val MAX_WORKERS = 12
        const val MAX_HEADERS = 64
        const val MAX_HEADER_BYTES = 16 * 1024
        const val READ_TIMEOUT_MS = 30_000
        const val DRAIN_LIMIT = 1024 * 1024

        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream(128)
            while (true) {
                val byte = input.read()
                if (byte == -1) {
                    return if (buffer.size() == 0) null else throw HttpError(400, "The request ended in the middle of a line.")
                }
                if (byte == '\n'.code) break
                if (byte != '\r'.code) buffer.write(byte)
                if (buffer.size() > MAX_HEADER_BYTES) throw HttpError(431, "A request line is too long.")
            }
            return String(buffer.toByteArray(), Charsets.ISO_8859_1)
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            throw HttpError(400, "Invalid percent-encoding.")
        }

        fun parseQuery(query: String): Map<String, String> = query.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val name = pair.substringBefore('=')
                val value = if (pair.contains('=')) pair.substringAfter('=') else ""
                try {
                    URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
                } catch (_: IllegalArgumentException) {
                    throw HttpError(400, "Invalid percent-encoding.")
                }
            }

        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            507 -> "Insufficient Storage"
            else -> "Status"
        }
    }
}

/** Stops at the declared body length, so a handler can never read into the next request. */
private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val count = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
    override fun close() = Unit
}

/** InputStream.readNBytes(int) is API 33+; this works on every supported Android version. */
internal fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val out = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(out, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return if (filled == count) out else out.copyOf(filled)
}
