package dev.applesideload.app.web

import dev.applesideload.app.Action
import dev.applesideload.app.Controls
import dev.applesideload.app.SettingsSnapshot
import dev.applesideload.app.UiState
import dev.applesideload.core.LogLine
import dev.applesideload.sideload.InstallSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The page that ships in the APK and the server that answers it must agree. */
class PageContractTest {
    private val assets = File("src/main/assets/web")

    private val api = WebApi(
        controls = object : Controls {
            override val state: StateFlow<UiState> = MutableStateFlow(UiState())
            override val logs: StateFlow<List<LogLine>> = MutableStateFlow(emptyList())
            override fun settingsSnapshot() = SettingsSnapshot("", "https://ani.sidestore.io", true, "", "")
            override fun dismissMessages() = Unit
            override fun refreshDevices() = Unit
            override fun connectById(id: String) = Action.Started
            override fun connectWireless(address: String) = Action.Started
            override fun disconnect() = Unit
            override fun loadApps() = Action.Started
            override fun uninstall(bundleId: String) = Action.Started
            override fun signIn(appleId: String, password: String) = Action.Started
            override fun submitTwoFactorCode(code: String) = Action.Started
            override fun requestPhoneCode(numberId: Int) = Action.Started
            override fun selectTeamById(teamId: String) = Action.Started
            override fun signOut() = Unit
            override fun revokeCertificate() = Action.Started
            override fun selectIpaFile(file: File) = Action.Started
            override fun installSource(source: InstallSource) = Action.Started
            override fun install() = Action.Started
            override fun setAnisetteAddress(address: String) = Unit
            override fun setWifiDiscovery(enabled: Boolean) = Unit
            override fun exportLogs() = ""
        },
        asset = { name -> File(assets, name).takeIf { it.isFile }?.readBytes() },
        onMain = object : WebApi.MainThread {
            override fun <T> call(block: () -> T): T = block()
        },
        newUploadFile = { File.createTempFile("upload", ".ipa").apply { deleteOnExit() } }
    )

    private fun get(path: String) = api.handle(HttpRequest("GET", path, emptyMap(), emptyMap(), 0, ByteArray(0).inputStream(), null))

    @Test
    fun everyFileThePageLoadsIsServedWithItsType() {
        val html = String(get("/").body)
        val referenced = Regex("""(?:src|href)="([a-z0-9_-]+\.(?:js|css|svg))"""").findAll(html).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("app.js", "app.css", "icon.svg"), referenced)
        for (name in referenced) {
            val reply = get("/$name")
            assertEquals(name, 200, reply.status)
            val expected = mapOf("js" to "text/javascript", "css" to "text/css", "svg" to "image/svg+xml")[name.substringAfterLast('.')]!!
            assertTrue(reply.contentType, reply.contentType.startsWith(expected))
        }
    }

    @Test
    fun everyApiThePageCallsExists() {
        val script = File(assets, "app.js").readText()
        val paths = Regex(""""(/api/[a-z0-9/-]+)""").findAll(script).map { it.groupValues[1].substringBefore('?') }.toSet()
        assertTrue("the page calls the API", paths.size >= 15)
        for (path in paths) {
            val method = if (path in setOf("/api/state", "/api/logs", "/api/logs/export")) "GET" else "POST"
            val body = "{}".toByteArray()
            val reply = api.handle(
                HttpRequest(method, path, emptyMap(), mapOf("content-type" to "application/json"), body.size.toLong(), body.inputStream(), null)
            )
            assertNotEquals("$path is not a route", 404, reply.status)
            assertNotEquals("$path does not take $method", 405, reply.status)
        }
    }
}
