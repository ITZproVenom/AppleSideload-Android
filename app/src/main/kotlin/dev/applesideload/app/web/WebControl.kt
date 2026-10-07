package dev.applesideload.app.web

import android.os.Looper
import dev.applesideload.app.SideloadApplication
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/** One way to reach the phone, e.g. its Wi-Fi address or its USB tethering address. */
data class LanAddress(val label: String, val address: String, val url: String)

data class WebStatus(
    val running: Boolean = false,
    val port: Int = WebControl.DEFAULT_PORT,
    val addresses: List<LanAddress> = emptyList(),
    val error: String? = null
)

/**
 * Owns the LAN web controller: the server and where it can be reached.
 *
 * The server listens on every interface, so the page opens over Wi-Fi, over
 * the phone's hotspot and over USB or Ethernet tethering alike. It is only
 * ever started by the user, from Settings, and runs inside
 * [WebControlService] so Android keeps it alive in the background.
 */
class WebControl(private val app: SideloadApplication) {

    private val _status = MutableStateFlow(WebStatus(port = app.settings.webPort))
    val status: StateFlow<WebStatus> = _status.asStateFlow()

    private var server: HttpServer? = null

    private val mainThread = object : WebApi.MainThread {
        override fun <T> call(block: () -> T): T =
            if (Looper.myLooper() == Looper.getMainLooper()) block() else runBlocking(Dispatchers.Main) { block() }
    }

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        val port = app.settings.webPort
        val api = WebApi(
            controls = app.controller,
            asset = { name -> runCatching { app.assets.open("web/$name").use { it.readBytes() } }.getOrNull() },
            onMain = mainThread,
            newUploadFile = { app.controller.newImportFile() }
        )
        val created = HttpServer(port, null, api::handle)
        return try {
            created.start()
            server = created
            val addresses = lanAddresses(port)
            _status.update { it.copy(running = true, port = port, addresses = addresses, error = null) }
            Log.i(LogTag.APP, "web controller listening on port $port" +
                if (addresses.isEmpty()) " (no network is connected yet)" else ": " + addresses.joinToString { it.url })
            true
        } catch (error: IOException) {
            created.close()
            val reason = "The web controller could not listen on port $port: ${error.message}. " +
                "Another app may be using it; pick another port in Settings."
            Log.e(LogTag.APP, reason)
            _status.update { it.copy(running = false, error = reason) }
            false
        }
    }

    @Synchronized
    fun stop() {
        server?.close()
        if (server != null) Log.i(LogTag.APP, "web controller stopped")
        server = null
        _status.update { it.copy(running = false, addresses = emptyList()) }
    }

    /** Re-reads the phone's addresses; returns true when they changed. */
    fun refreshAddresses(): Boolean {
        if (server == null) return false
        val fresh = lanAddresses(_status.value.port)
        if (fresh == _status.value.addresses) return false
        _status.update { it.copy(addresses = fresh) }
        return true
    }

    /** Takes effect the next time the controller starts. */
    fun setPort(port: Int) {
        require(port in 1024..65535)
        app.settings.webPort = port
        _status.update { if (it.running) it else it.copy(port = port) }
    }

    fun reportError(reason: String) {
        Log.e(LogTag.APP, reason)
        _status.update { it.copy(running = false, error = reason) }
    }

    fun clearError() = _status.update { it.copy(error = null) }

    companion object {
        const val DEFAULT_PORT = 8686

        fun lanAddresses(port: Int): List<LanAddress> {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return emptyList()
            return interfaces
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { network ->
                    network.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                        .map { address ->
                            val host = address.hostAddress ?: return@map null
                            LanAddress(label(network.name), host, "http://$host:$port")
                        }
                        .filterNotNull()
                }
                .distinctBy { it.address }
                .sortedBy { order(it.label) }
        }

        /** Android's interface names say which link an address is on. */
        fun label(name: String): String = when {
            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> "USB tethering (wired)"
            name.startsWith("eth") -> "Ethernet (wired)"
            name.startsWith("ap") || name.startsWith("swlan") || name == "wlan1" || name.startsWith("softap") -> "Hotspot"
            name.startsWith("wlan") -> "Wi-Fi"
            name.startsWith("bt-pan") -> "Bluetooth tethering"
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-rmnet") -> "Mobile data (not reachable from the LAN)"
            name.startsWith("tun") || name.startsWith("ppp") -> "VPN"
            else -> name
        }

        private fun order(label: String): Int = when {
            label == "Wi-Fi" -> 0
            label == "Hotspot" -> 1
            label.contains("wired") -> 2
            label.startsWith("Mobile") -> 9
            else -> 5
        }
    }
}
