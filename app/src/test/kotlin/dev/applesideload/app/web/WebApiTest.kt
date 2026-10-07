package dev.applesideload.app.web

import dev.applesideload.app.Action
import dev.applesideload.app.Controls
import dev.applesideload.app.SettingsSnapshot
import dev.applesideload.app.UiState
import dev.applesideload.apple.AppleSession
import dev.applesideload.apple.DeveloperTeam
import dev.applesideload.core.LogLevel
import dev.applesideload.core.LogLine
import dev.applesideload.core.LogTag
import dev.applesideload.device.ConnectionState
import dev.applesideload.device.DiscoveredDevice
import dev.applesideload.sideload.InstallSource
import dev.applesideload.sideload.SideloadStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import kotlin.random.Random

/** Records what the API asked for, so each route can be checked against the call it makes. */
private class RecordingControls : Controls {
    val calls = mutableListOf<String>()
    var refuseWith: String? = null
    val mutableState = MutableStateFlow(UiState())
    val mutableLogs = MutableStateFlow<List<LogLine>>(emptyList())
    var uploaded: File? = null
    var anisette = ""
    var wifi = true

    override val state: StateFlow<UiState> get() = mutableState
    override val logs: StateFlow<List<LogLine>> get() = mutableLogs
    override fun settingsSnapshot() = SettingsSnapshot(anisette, anisette.ifBlank { "https://ani.sidestore.io" }, wifi, "me@example.com", "192.168.1.9")

    private fun record(call: String): Action {
        calls += call
        return refuseWith?.let { Action.Refused(it) } ?: Action.Started
    }

    override fun dismissMessages() { calls += "dismiss" }
    override fun refreshDevices() { calls += "refresh" }
    override fun connectById(id: String) = record("connect:$id")
    override fun connectWireless(address: String) = record("wireless:$address")
    override fun disconnect() { calls += "disconnect" }
    override fun loadApps() = record("apps")
    override fun uninstall(bundleId: String) = record("uninstall:$bundleId")
    override fun signIn(appleId: String, password: String) = record("signin:$appleId:$password")
    override fun submitTwoFactorCode(code: String) = record("code:$code")
    override fun requestPhoneCode(numberId: Int) = record("sms:$numberId")
    override fun selectTeamById(teamId: String) = record("team:$teamId")
    override fun signOut() { calls += "signout" }
    override fun revokeCertificate() = record("revoke")
    override fun selectIpaFile(file: File): Action { uploaded = file; return record("ipa:${file.length()}") }
    override fun installSource(source: InstallSource) = record("source:${source.name}")
    override fun install() = record("install")
    override fun setAnisetteAddress(address: String) { anisette = address; calls += "anisette:$address" }
    override fun setWifiDiscovery(enabled: Boolean) { wifi = enabled; calls += "wifi:$enabled" }
    override fun exportLogs() = "exported log"
}

class WebApiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var controls: RecordingControls
    private lateinit var server: HttpServer
    private var port = 0

    @Before
    fun setUp() {
        controls = RecordingControls()
        val api = WebApi(
            controls = controls,
            asset = { name -> if (name == "index.html") "<html>ok</html>".toByteArray() else null },
            onMain = object : WebApi.MainThread {
                override fun <T> call(block: () -> T): T = block()
            },
            newUploadFile = { File(folder.root, "upload-${System.nanoTime()}.ipa") },
            maxUploadBytes = 4_000_000
        )
        server = HttpServer(0, InetAddress.getLoopbackAddress(), api::handle)
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() = server.close()

    private class Reply(val status: Int, val body: String, val headers: Map<String, List<String>>) {
        val json: JSONObject get() = JSONObject(body)
    }

    private fun call(
        method: String,
        path: String,
        body: ByteArray? = null,
        type: String? = "application/json"
    ): Reply {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            type?.let { connection.setRequestProperty("Content-Type", it) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.use { String(it.readBytes()) } ?: ""
        return Reply(status, text, connection.headerFields.filterKeys { it != null })
    }

    private fun post(path: String, json: String) = call("POST", path, json.toByteArray())

    @Test
    fun servesThePageWithoutLoginAndWithSecurityHeaders() {
        val reply = call("GET", "/")
        assertEquals(200, reply.status)
        assertEquals("<html>ok</html>", reply.body)
        assertTrue(reply.headers["Content-Security-Policy"]!!.first().contains("default-src 'self'"))
        assertEquals("nosniff", reply.headers["X-Content-Type-Options"]!!.first())
        assertEquals(404, call("GET", "/missing.js").status)
        assertEquals(404, call("GET", "/../secret").status.let { if (it == 400) 404 else it })
    }

    @Test
    fun stateCarriesWhatThePageShowsAndNoSecrets() {
        controls.mutableState.value = UiState(
            connection = ConnectionState.READY,
            discovered = listOf(DiscoveredDevice.Wifi("Phone@aa:bb", InetAddress.getByName("192.168.1.20"), 62078)),
            account = AppleSession("me@example.com", "ADSID-SECRET", "IDMS-SECRET", byteArrayOf(1, 2, 3)),
            teams = listOf(DeveloperTeam("TEAM123456", "Me", "Individual", "active")),
            busy = "Installing",
            step = SideloadStep.Uploading(42)
        ).let { it.copy(selectedTeam = it.teams.first()) }
        val reply = call("GET", "/api/state")
        assertEquals(200, reply.status)
        val json = reply.json
        assertEquals("READY", json.getString("connection"))
        assertEquals("Installing", json.getString("busy"))
        assertEquals("me@example.com", json.getJSONObject("account").getString("appleId"))
        assertEquals("TEAM123456", json.getString("selectedTeamId"))
        val device = json.getJSONArray("discovered").getJSONObject(0)
        assertEquals("wifi", device.getString("kind"))
        assertEquals("192.168.1.20", device.getString("address"))
        assertEquals(42, json.getJSONObject("step").getInt("percent"))
        assertEquals("uploading", json.getJSONObject("step").getString("kind"))
        assertEquals("192.168.1.9", json.getJSONObject("settings").getString("lastWirelessAddress"))
        assertFalse(reply.body.contains("SECRET"))
    }

    @Test
    fun everyRouteReachesItsControl() {
        val routes = listOf(
            Triple("/api/devices/refresh", "{}", "refresh"),
            Triple("/api/connect", """{"id":"/dev/bus/usb/001/002"}""", "connect:/dev/bus/usb/001/002"),
            Triple("/api/connect-wireless", """{"address":" 192.168.1.23 "}""", "wireless:192.168.1.23"),
            Triple("/api/disconnect", "{}", "disconnect"),
            Triple("/api/apps/refresh", "{}", "apps"),
            Triple("/api/apps/uninstall", """{"bundleId":"com.example.App.TEAM"}""", "uninstall:com.example.App.TEAM"),
            Triple("/api/signin", """{"appleId":"me@example.com","password":" pa ss "}""", "signin:me@example.com: pa ss "),
            Triple("/api/2fa/code", """{"code":"123 456"}""", "code:123 456"),
            Triple("/api/2fa/sms", """{"numberId":2}""", "sms:2"),
            Triple("/api/team", """{"teamId":"TEAM123456"}""", "team:TEAM123456"),
            Triple("/api/signout", "{}", "signout"),
            Triple("/api/revoke", "{}", "revoke"),
            Triple("/api/install-source", """{"source":"SIDESTORE_LIVECONTAINER"}""", "source:SIDESTORE_LIVECONTAINER"),
            Triple("/api/install-source", """{"source":"SIDESTORE"}""", "source:SIDESTORE"),
            Triple("/api/install", "{}", "install"),
            Triple("/api/dismiss", "{}", "dismiss"),
            Triple("/api/settings", """{"anisetteAddress":"https://ani.example.com","wifiDiscovery":false}""", "anisette:https://ani.example.com")
        )
        for ((path, body, expected) in routes) {
            controls.calls.clear()
            val reply = post(path, body)
            assertTrue("$path answered ${reply.status}: ${reply.body}", reply.status == 200 || reply.status == 202)
            assertTrue(reply.json.getBoolean("ok"))
            assertEquals(path, expected, controls.calls.first())
        }
        assertFalse(controls.wifi)
        assertEquals("exported log", call("GET", "/api/logs/export").body)
    }

    @Test
    fun refusalsAndBadInputAreReportedNotIgnored() {
        controls.refuseWith = "Wait until \"Installing\" finishes."
        val refused = post("/api/install", "{}")
        assertEquals(409, refused.status)
        assertEquals("Wait until \"Installing\" finishes.", refused.json.getString("error"))
        controls.refuseWith = null

        assertEquals(400, post("/api/connect", "{}").status)
        assertEquals(400, post("/api/connect", "not json").status)
        assertEquals(400, post("/api/2fa/sms", """{"numberId":"two"}""").status)
        assertEquals(400, post("/api/install-source", """{"source":"CUSTOM"}""").status)
        assertEquals(400, post("/api/settings", """{"anisetteAddress":"ftp://x"}""").status)
        assertEquals(415, call("POST", "/api/connect", """{"id":"x"}""".toByteArray(), type = "text/plain").status)
        assertEquals(405, call("GET", "/api/connect").status)
        assertEquals(404, call("GET", "/api/nothing").status)
        assertTrue(controls.calls.none { it.startsWith("connect") })
    }

    @Test
    fun uploadsArriveByteForByte() {
        val ipa = Random(7).nextBytes(1_234_567)
        val reply = call("POST", "/api/ipa", ipa, type = "application/octet-stream")
        assertEquals(202, reply.status)
        assertEquals("ipa:${ipa.size}", controls.calls.single())
        assertArrayEquals(ipa, controls.uploaded!!.readBytes())
    }

    @Test
    fun uploadsAreRefusedWhileBusyOrTooLarge() {
        controls.mutableState.value = UiState(busy = "Installing")
        assertEquals(409, call("POST", "/api/ipa", ByteArray(10), type = "application/octet-stream").status)
        controls.mutableState.value = UiState()
        assertEquals(413, call("POST", "/api/ipa", ByteArray(4_000_001), type = "application/octet-stream").status)
        controls.refuseWith = "Wait"
        assertEquals(409, call("POST", "/api/ipa", ByteArray(10), type = "application/octet-stream").status)
        assertTrue("a refused upload is deleted", folder.root.listFiles()!!.isEmpty())
    }

    @Test
    fun aTruncatedUploadIsDiscarded() {
        Socket("127.0.0.1", port).use { socket ->
            val head = "POST /api/ipa HTTP/1.1\r\nHost: x\r\n" +
                "Content-Type: application/octet-stream\r\nContent-Length: 1000\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray() + ByteArray(400))
            socket.shutdownOutput()
            val answer = String(socket.getInputStream().readBytes())
            assertTrue(answer, answer.startsWith("HTTP/1.1 400"))
        }
        assertTrue(controls.calls.isEmpty())
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test
    fun logsAreIncremental() {
        controls.mutableLogs.value = (1L..5L).map { LogLine(1000, LogLevel.INFO, LogTag.APP, "line $it", it) }
        val first = call("GET", "/api/logs?after=0").json
        assertEquals(5, first.getJSONArray("lines").length())
        assertEquals(5, first.getLong("latest"))
        val next = call("GET", "/api/logs?after=3").json
        assertEquals(2, next.getJSONArray("lines").length())
        assertEquals("line 4", next.getJSONArray("lines").getJSONObject(0).getString("message"))
    }

    @Test
    fun malformedRequestsGetAnAnswerNotAHang() {
        fun raw(text: String): String = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(text.toByteArray())
            socket.shutdownOutput()
            String(socket.getInputStream().readBytes())
        }
        assertTrue(raw("NONSENSE\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(raw("POST /api/connect HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n").startsWith("HTTP/1.1 411"))
        assertTrue(raw("GET / HTTP/1.1\r\nX: ${"a".repeat(20_000)}\r\n\r\n").startsWith("HTTP/1.1 431"))
        assertTrue(raw("GET / HTTP/1.1\r\nContent-Length: -4\r\n\r\n").startsWith("HTTP/1.1 400"))
    }
}
