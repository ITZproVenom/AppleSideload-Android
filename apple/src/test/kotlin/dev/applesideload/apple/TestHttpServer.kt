package dev.applesideload.apple

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

/**
 * Just enough HTTP/1.1 for the fake services in these tests.
 *
 * Android unit tests compile against android.jar, which has no
 * com.sun.net.httpserver, so this stands in for it on plain sockets: one
 * request per connection (the client under test sends Connection: close),
 * a body by Content-Length, and every answer with a length and a standard
 * reason phrase, which the client's error messages quote.
 */
internal class TestHttpServer(private val handler: (TestExchange) -> Unit) : Closeable {

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ serve(socket) }, "test-http").apply { isDaemon = true }.start()
            }
        }, "test-http-accept").apply { isDaemon = true }.start()
    }

    override fun close() = server.close()

    private fun serve(socket: Socket) = socket.use {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val head = readHead(input) ?: return@use
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 3) return@use
        val headers = LinkedHashMap<String, MutableList<String>>()
        lines.drop(1).filter { ':' in it }.forEach { line ->
            headers.getOrPut(line.substringBefore(':').trim()) { mutableListOf() } += line.substringAfter(':').trim()
        }
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        check(header("Transfer-Encoding") == null) { "chunked request bodies are not expected from this client" }
        val body = ByteArray(header("Content-Length")?.toInt() ?: 0).also { DataInputStream(input).readFully(it) }
        val exchange = TestExchange(parts[0], URI(parts[1]), headers, ByteArrayInputStream(body), socket.getOutputStream())
        try {
            handler(exchange)
        } catch (error: Throwable) {
            error.printStackTrace()
            if (!exchange.responded) exchange.respond(500, "text/plain", "the fake failed: $error".toByteArray())
        }
        if (!exchange.responded) exchange.respond(500, "text/plain", "the fake did not answer".toByteArray())
    }

    /** The request line and headers, up to the blank line; null if the client went away first. */
    private fun readHead(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            if (value < 0) return null
            head.write(value)
            matched = when {
                value == END[matched].code -> matched + 1
                value == END[0].code -> 1
                else -> 0
            }
        }
        return head.toString("ISO-8859-1").trimEnd()
    }

    private companion object {
        const val END = "\r\n\r\n"
    }
}

/** One request and its answer, with the names com.sun.net.httpserver uses. */
internal class TestExchange(
    val requestMethod: String,
    val requestURI: URI,
    val requestHeaders: Map<String, List<String>>,
    val requestBody: InputStream,
    private val output: OutputStream
) {
    @Volatile
    var responded = false
        private set

    fun respond(code: Int, type: String, body: ByteArray) {
        check(!responded) { "the request was answered twice" }
        responded = true
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        412 -> "Precondition Failed"
        423 -> "Locked"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status $code"
    }
}
