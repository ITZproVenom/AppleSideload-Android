package dev.applesideload.apple

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Anisette v3 against a fake source and a fake Apple: the source's
 * provisioning conversation over a real WebSocket, Apple's two provisioning
 * calls with the new identity's headers, the identity kept on disk, the
 * one-minute reuse, a refused identity replaced once, and the v1 fallback.
 * The fakes follow the anisette-v3 protocol (SideStore's server, isideload's
 * remote_v3 client), not this client.
 */
class AnisetteV3Test {

    private class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    /** Apple's half: the URL bag and the two provisioning endpoints. */
    private class FakeAppleProvisioning : Closeable {
        val requests = CopyOnWriteArrayList<Recorded>()

        /** The error code midStartProvisioning answers with; 0 is success. */
        @Volatile var startError = 0

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val prefix = "/apple-" + UUID.randomUUID().toString().take(8)
        val base: String get() = "http://127.0.0.1:${server.address.port}$prefix"

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        override fun close() = server.stop(0)

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.use { it.readBytes() }
            val headers = exchange.requestHeaders.entries.associate { (key, values) -> key.lowercase() to values.joinToString(",") }
            val path = exchange.requestURI.path.removePrefix(prefix)
            requests += Recorded(exchange.requestMethod, path, headers, body)
            when (path) {
                "/lookup" -> plist(
                    exchange,
                    Plist.dict(
                        "urls" to Plist.dict(
                            "midStartProvisioning" to Plist.Str("$base/midStart"),
                            "midFinishProvisioning" to Plist.Str("$base/midFinish")
                        )
                    )
                )
                "/midStart" -> plist(
                    exchange,
                    Plist.dict(
                        "Response" to if (startError != 0) {
                            Plist.dict(
                                "Status" to Plist.dict(
                                    "ec" to Plist.Num(startError.toLong()),
                                    "em" to Plist.Str("This device cannot be provisioned")
                                )
                            )
                        } else {
                            Plist.dict("Status" to Plist.dict("ec" to Plist.Num(0)), "spim" to Plist.Str(SPIM))
                        }
                    )
                )
                "/midFinish" -> {
                    val cpim = PlistReader.parse(body)["Request"]?.get("cpim")?.asString
                    if (cpim != CPIM) {
                        plist(exchange, Plist.dict("Response" to Plist.dict("Status" to Plist.dict("ec" to Plist.Num(-1)))))
                    } else {
                        plist(
                            exchange,
                            Plist.dict(
                                "Response" to Plist.dict(
                                    "Status" to Plist.dict("ec" to Plist.Num(0)),
                                    "ptm" to Plist.Str(PTM),
                                    "tk" to Plist.Str(TK)
                                )
                            )
                        )
                    }
                }
                else -> reply(exchange, 404, "text/plain", "not here".toByteArray())
            }
        }

        private fun plist(exchange: HttpExchange, value: Plist) =
            reply(exchange, 200, "text/x-xml-plist", XmlPlist.write(value))

        private fun reply(exchange: HttpExchange, code: Int, type: String, body: ByteArray) {
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    /**
     * The anisette source: plain HTTP for client_info, get_headers and the
     * v1 request, and a WebSocket for the provisioning session, all on one
     * port as on a real server.
     */
    private class FakeSource : Closeable {
        /** "METHOD /path" of every request, in order. */
        val requests = CopyOnWriteArrayList<String>()
        val sessions = AtomicInteger()
        val identifiers = CopyOnWriteArrayList<String>()
        val spims = CopyOnWriteArrayList<String>()
        val finishes = CopyOnWriteArrayList<JSONObject>()
        val headerRequests = CopyOnWriteArrayList<JSONObject>()
        /** Anything the client did against the protocol. */
        val problems = CopyOnWriteArrayList<String>()

        @Volatile var v3 = true
        @Volatile var clientInfoStatus = 200
        @Volatile var refuseEverything = false

        /** The provisioning data each identifier was given. */
        private val issued = ConcurrentHashMap<String, String>()
        private val otps = AtomicInteger()

        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        // The provider remembers each address for the whole process (what it
        // speaks, the last answer), so every fake gets an address of its own.
        private val prefix = "/source-" + UUID.randomUUID().toString().take(8)
        val base: String get() = "http://127.0.0.1:${server.localPort}$prefix"

        init {
            Thread {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread {
                        socket.use {
                            try {
                                serve(it)
                            } catch (error: Exception) {
                                problems += "the source failed: $error"
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = server.close()

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val head = readHead(input)
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            val method = parts[0]
            if (!parts[1].startsWith(prefix)) problems += "a request outside the source's address: ${parts[1]}"
            val path = parts[1].removePrefix(prefix)
            val headers = lines.drop(1).filter { ':' in it }.associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
            requests += "$method $path"
            if (headers["upgrade"].equals("websocket", ignoreCase = true)) {
                if (path != "/v3/provisioning_session") problems += "a WebSocket was opened at $path"
                provisioningSession(headers, input, output)
                return
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length).also { DataInputStream(input).readFully(it) }
            val (code, text) = route(method, path, String(body, Charsets.UTF_8))
            val bytes = text.toByteArray(Charsets.UTF_8)
            output.write(
                ("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            output.write(bytes)
            output.flush()
        }

        private fun route(method: String, path: String, body: String): Pair<Int, String> = when ("$method $path") {
            "GET /v3/client_info" -> when {
                !v3 -> 404 to "Not Found"
                clientInfoStatus != 200 -> clientInfoStatus to "busy"
                // What public sources publish: Xcode, which the client must not copy.
                else -> 200 to JSONObject()
                    .put("client_info", "<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.dt.Xcode/3594.4.19)>")
                    .put("user_agent", "akd/1.0 CFNetwork/808.1.4")
                    .toString()
            }
            "POST /v3/get_headers" -> {
                val request = JSONObject(body)
                headerRequests += request
                val identifier = request.optString("identifier")
                val adiPb = request.optString("adi_pb")
                if (refuseEverything || issued[identifier] != adiPb) {
                    200 to JSONObject().put("result", "GetHeadersError").put("message", "ADI error -45061").toString()
                } else {
                    200 to JSONObject()
                        .put("result", "Headers")
                        .put("X-Apple-I-MD", "OTP-${otps.incrementAndGet()}")
                        .put("X-Apple-I-MD-M", "MID-$identifier")
                        .put("X-Apple-I-MD-RINFO", "17106176")
                        .toString()
                }
            }
            "GET /" -> 200 to JSONObject()
                .put("X-Apple-I-MD", "V1-OTP")
                .put("X-Apple-I-MD-M", "V1-MID")
                .put("X-Apple-I-MD-LU", "V1-LOCAL-USER")
                .put("X-Apple-I-MD-RINFO", "17106176")
                .put("X-Mme-Device-Id", "V1-DEVICE-ID")
                .put("X-Apple-I-SRL-NO", "0")
                .put("X-Apple-I-Client-Time", "2026-09-01T10:00:00Z")
                .put("X-Apple-I-TimeZone", "UTC")
                .put("X-Apple-Locale", "en_US")
                .put("X-MMe-Client-Info", "<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.dt.Xcode/3594.4.19)>")
                .toString()
            else -> 404 to "Not Found"
        }

        private fun provisioningSession(headers: Map<String, String>, input: InputStream, output: OutputStream) {
            val session = sessions.incrementAndGet()
            val key = headers["sec-websocket-key"] ?: return run { problems += "no Sec-WebSocket-Key" }
            if (headers["sec-websocket-version"] != "13") problems += "WebSocket version ${headers["sec-websocket-version"]}"
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
            )
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            output.flush()

            send(output, JSONObject().put("result", "GiveIdentifier"))
            val identifier = receive(input)?.optString("identifier") ?: return
            identifiers += identifier
            if (Base64.getDecoder().decode(identifier).size != 16) problems += "the identifier is not 16 bytes"

            send(output, JSONObject().put("result", "GiveStartProvisioningData"))
            val spim = receive(input)?.optString("spim") ?: return
            spims += spim

            send(output, JSONObject().put("result", "GiveEndProvisioningData").put("cpim", CPIM))
            val end = receive(input) ?: return
            finishes += end

            val adiPb = "ADI-PB-$session"
            issued[identifier] = adiPb
            send(output, JSONObject().put("result", "ProvisioningSuccess").put("adi_pb", adiPb))
        }

        /** A server frame: final, unmasked text. */
        private fun send(output: OutputStream, message: JSONObject) {
            val payload = message.toString().toByteArray(Charsets.UTF_8)
            val frame = ByteArrayOutputStream()
            frame.write(0x81)
            if (payload.size < 126) {
                frame.write(payload.size)
            } else {
                frame.write(126)
                frame.write(payload.size shr 8)
                frame.write(payload.size and 0xFF)
            }
            frame.write(payload)
            output.write(frame.toByteArray())
            output.flush()
        }

        /** A client frame, which RFC 6455 requires to be masked; null once the client has closed. */
        private fun receive(input: InputStream): JSONObject? {
            val data = DataInputStream(input)
            val first = data.read()
            if (first < 0) return null
            val second = data.readUnsignedByte()
            val opcode = first and 0x0F
            if (second and 0x80 == 0) problems += "a client frame was not masked"
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = data.readUnsignedShort().toLong() else if (length == 127L) length = data.readLong()
            val mask = ByteArray(4).also { data.readFully(it) }
            val payload = ByteArray(length.toInt()).also { data.readFully(it) }
            for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            if (opcode == 0x8) return null
            if (opcode != 0x1 || first and 0x80 == 0) problems += "expected one final text frame, got opcode $opcode"
            return JSONObject(String(payload, Charsets.UTF_8))
        }

        private fun readHead(input: InputStream): String {
            val head = ByteArrayOutputStream()
            while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
                val value = input.read()
                if (value < 0) break
                head.write(value)
            }
            return head.toString("ISO-8859-1").trimEnd()
        }
    }

    private fun provider(source: FakeSource, apple: FakeAppleProvisioning, store: AnisetteStore) = RemoteAnisetteProvider(
        baseUrl = source.base,
        http = Http(timeoutMs = 10_000),
        store = store,
        grandSlam = GrandSlam(Http(timeoutMs = 10_000), bagUrl = "${apple.base}/lookup", retryDelaysMs = listOf(1, 1))
    )

    private fun lowerUuid(identifier: ByteArray): String = ByteBuffer.wrap(identifier).let { UUID(it.long, it.long) }.toString()

    private fun sha256Hex(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun both(block: (FakeSource, FakeAppleProvisioning) -> Unit) =
        FakeSource().use { source -> FakeAppleProvisioning().use { apple -> block(source, apple) } }

    @Test
    fun provisionsThePhonesOwnIdentityThroughTheSource() = both { source, apple ->
        val store = MemoryAnisetteStore()
        val data = provider(source, apple, store).fetch()

        val identity = checkNotNull(store.load()) { "the new identity was not kept" }
        assertEquals("ADI-PB-1", identity.adiPb)
        assertEquals(1, source.sessions.get())
        assertEquals(Base64.getEncoder().encodeToString(identity.identifier), source.identifiers.single())

        // Apple's two calls carry the new identity, in lower case as isideload sends it,
        // and present the client as macOS's akd rather than the Xcode the source describes.
        val deviceId = lowerUuid(identity.identifier)
        assertEquals(deviceId.lowercase(), deviceId)
        for (path in listOf("/midStart", "/midFinish")) {
            val call = apple.requests.single { it.path == path }
            assertEquals("POST", call.method)
            assertEquals(sha256Hex(identity.identifier), call.headers["x-apple-i-md-lu"])
            assertEquals(deviceId, call.headers["x-mme-device-id"])
            assertEquals(GrandSlam.CLIENT_INFO, call.headers["x-mme-client-info"])
            assertEquals(GrandSlam.USER_AGENT, call.headers["user-agent"])
        }
        // Each side got the other's answers unchanged.
        assertEquals(CPIM, PlistReader.parse(apple.requests.single { it.path == "/midFinish" }.body)["Request"]?.get("cpim")?.asString)
        assertEquals(SPIM, source.spims.single())
        assertEquals(PTM, source.finishes.single().optString("ptm"))
        assertEquals(TK, source.finishes.single().optString("tk"))

        assertEquals("OTP-1", data.oneTimePassword)
        assertEquals("MID-${source.identifiers.single()}", data.machineId)
        assertEquals("17106176", data.routingInfo)
        assertEquals(deviceId, data.deviceId)
        assertEquals(
            mapOf("X-Mme-Device-Id" to deviceId, "X-Apple-I-MD" to "OTP-1", "X-Apple-I-MD-M" to data.machineId),
            data.headers()
        )
        assertTrue(source.problems.toString(), source.problems.isEmpty())
    }

    @Test
    fun theKeptIdentityIsUsedAfterARestartAndOneAnswerServesAMinute() = both { source, apple ->
        val file = File(Files.createTempDirectory("anisette").toFile(), "identity.properties")
        val first = provider(source, apple, FileAnisetteStore(file)).fetch()
        // A new provider and a new store over the same file: the app after a restart.
        val again = provider(source, apple, FileAnisetteStore(file)).fetch()

        assertEquals("no second provisioning", 1, source.sessions.get())
        assertEquals("the answer is reused within a minute", 1, source.headerRequests.size)
        assertEquals(first, again)
        assertEquals("ADI-PB-1", FileAnisetteStore(file).load()?.adiPb)

        // Another identity never gets this one's answer.
        val other = provider(source, apple, MemoryAnisetteStore()).fetch()
        assertEquals(2, source.sessions.get())
        assertEquals(2, source.headerRequests.size)
        assertNotEquals(first.deviceId, other.deviceId)
        assertEquals("OTP-2", other.oneTimePassword)
        assertTrue(source.problems.toString(), source.problems.isEmpty())
    }

    @Test
    fun anIdentityTheSourceRefusesIsReplacedOnce() = both { source, apple ->
        val stale = AnisetteIdentity(ByteArray(16) { it.toByte() }, "STALE-ADI-PB")
        val store = MemoryAnisetteStore().apply { save(stale) }
        val data = provider(source, apple, store).fetch()

        assertEquals(1, source.sessions.get())
        assertEquals(listOf("STALE-ADI-PB", "ADI-PB-1"), source.headerRequests.map { it.optString("adi_pb") })
        assertEquals("ADI-PB-1", store.load()?.adiPb)
        assertNotEquals(stale.deviceId, data.deviceId)
        assertEquals("OTP-1", data.oneTimePassword)
    }

    @Test
    fun aSourceThatKeepsRefusingIsReportedInsteadOfRetriedForever() = both { source, apple ->
        source.refuseEverything = true
        val error = runCatching { provider(source, apple, MemoryAnisetteStore()).fetch() }.exceptionOrNull()
        assertTrue("$error", error is AnisetteUnavailable)
        assertTrue(error!!.message!!, "ADI error -45061" in error.message!!)
        // The first identity, then exactly one replacement.
        assertEquals(2, source.sessions.get())
        assertEquals(2, source.headerRequests.size)
    }

    @Test
    fun appleRefusingToProvisionIsReported() = both { source, apple ->
        apple.startError = -45054
        val store = MemoryAnisetteStore()
        val error = runCatching { provider(source, apple, store).fetch() }.exceptionOrNull()
        assertTrue("$error", error is AnisetteUnavailable)
        assertTrue(error!!.message!!, "Apple refused" in error.message!! && "-45054" in error.message!!)
        assertNull("nothing half-made is kept", store.load())
        assertTrue("no headers are asked for without an identity", source.headerRequests.isEmpty())
    }

    @Test
    fun aSourceWithoutV3GetsTheV1Request() = both { source, apple ->
        source.v3 = false
        val data = provider(source, apple, MemoryAnisetteStore()).fetch()

        assertEquals(listOf("GET /v3/client_info", "GET /"), source.requests.toList())
        assertEquals(0, source.sessions.get())
        assertTrue("Apple is not involved", apple.requests.isEmpty())
        assertEquals("V1-OTP", data.oneTimePassword)
        assertEquals("V1-MID", data.machineId)
        assertEquals("V1-DEVICE-ID", data.deviceId)
        val headers = data.headers()
        assertEquals("V1-LOCAL-USER", headers["X-Apple-I-MD-LU"])
        assertEquals("0", headers["X-Apple-I-SRL-NO"])
        assertEquals("2026-09-01T10:00:00Z", headers["X-Apple-I-Client-Time"])
        assertEquals("UTC", headers["X-Apple-I-TimeZone"])
        assertEquals("en_US", headers["X-Apple-Locale"])
        // The source's own client description is never passed on.
        assertTrue(headers.keys.none { it.equals("X-MMe-Client-Info", ignoreCase = true) })
    }

    @Test
    fun aFailingV3SourceIsNotMistakenForAV1One() = both { source, apple ->
        source.clientInfoStatus = 503
        val error = runCatching { provider(source, apple, MemoryAnisetteStore()).fetch() }.exceptionOrNull()
        assertTrue("$error", error is AnisetteUnavailable)
        assertTrue(error!!.message!!, "HTTP 503" in error.message!!)
        assertEquals(listOf("GET /v3/client_info"), source.requests.toList())
    }

    @Test
    fun anAddressThatIsNotHttpIsExplained() {
        val error = runCatching {
            RemoteAnisetteProvider(baseUrl = "ftp://127.0.0.1:1", store = MemoryAnisetteStore()).fetch()
        }.exceptionOrNull()
        assertTrue("$error", error is AnisetteUnavailable)
        assertTrue(error!!.message!!, "not an http or https address" in error.message!!)
    }

    private companion object {
        const val SPIM = "c3BpbS1mcm9tLWFwcGxl"
        const val CPIM = "Y3BpbS1mcm9tLXRoZS1zb3VyY2U="
        const val PTM = "cHRtLWZyb20tYXBwbGU="
        const val TK = "dGstZnJvbS1hcHBsZQ=="
    }
}
