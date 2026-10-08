package dev.applesideload.app.web

import dev.applesideload.app.Action
import dev.applesideload.app.Controls
import dev.applesideload.sideload.InstallSource
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress

/**
 * The web controller's routes: the page itself and a JSON API covering
 * everything the app's screens can do.
 *
 * The controller is meant for the user's own local network and has no
 * login: whoever on that network can open the page can use it, which is
 * why [refusal] turns away everyone else. Actions answer at once - 202 when the operation was started, 409
 * with the reason when it was refused, for example because another
 * operation is still running - and the page follows progress through
 * /api/state, exactly as the phone's screens follow the same state.
 */
class WebApi(
    private val controls: Controls,
    /** Reads a bundled file of the page, or null when there is none. */
    private val asset: (String) -> ByteArray?,
    /** Runs a call on the app's main thread and returns its result. */
    private val onMain: MainThread,
    /** A fresh file to receive an uploaded IPA. */
    private val newUploadFile: () -> File,
    private val maxUploadBytes: Long = MAX_UPLOAD_BYTES,
    /** Whether a client (first) that reached this phone at its address (second) is on the phone's network. */
    private val isLocal: (InetAddress, InetAddress?) -> Boolean = LocalNetwork::accepts
) {

    interface MainThread {
        fun <T> call(block: () -> T): T
    }

    fun handle(request: HttpRequest): HttpResponse {
        refusal(request)?.let { return it }
        if (!request.path.startsWith("/api/")) return page(request)
        return when (request.method to request.path) {
            "GET" to "/api/state" -> state()
            "GET" to "/api/logs" -> logs(request)
            "GET" to "/api/logs/export" -> HttpResponse.text(
                200,
                onMain.call { controls.exportLogs() },
                headers = mapOf("Content-Disposition" to "attachment; filename=\"AppleSideload-log.txt\"")
            )
            "POST" to "/api/dismiss" -> done { controls.dismissMessages() }
            "POST" to "/api/devices/refresh" -> done { controls.refreshDevices() }
            "POST" to "/api/connect" -> {
                val value = body(request).string("id")
                action { controls.connectById(value) }
            }
            "POST" to "/api/connect-wireless" -> {
                val value = body(request).string("address")
                action { controls.connectWireless(value) }
            }
            "POST" to "/api/disconnect" -> done { controls.disconnect() }
            "POST" to "/api/remote/start" -> action { controls.startRemotePairing() }
            "POST" to "/api/remote/stop" -> done { controls.stopRemotePairing() }
            "POST" to "/api/remote/connect" -> {
                val value = body(request).string("udid")
                action { controls.connectRemote(value) }
            }
            "POST" to "/api/remote/forget" -> {
                val value = body(request).string("udid")
                action { controls.forgetRemote(value) }
            }
            "POST" to "/api/apps/refresh" -> action { controls.loadApps() }
            "POST" to "/api/apps/uninstall" -> {
                val value = body(request).string("bundleId")
                action { controls.uninstall(value) }
            }
            "POST" to "/api/signin" -> {
                val fields = body(request)
                action { controls.signIn(fields.string("appleId"), fields.string("password", trim = false)) }
            }
            "POST" to "/api/2fa/code" -> {
                val value = body(request).string("code")
                action { controls.submitTwoFactorCode(value) }
            }
            "POST" to "/api/2fa/sms" -> {
                val value = body(request).int("numberId")
                action { controls.requestPhoneCode(value) }
            }
            "POST" to "/api/team" -> {
                val value = body(request).string("teamId")
                action { controls.selectTeamById(value) }
            }
            "POST" to "/api/signout" -> done { controls.signOut() }
            "POST" to "/api/revoke" -> action { controls.revokeCertificate() }
            "POST" to "/api/install-source" -> {
                val name = body(request).string("source")
                val source = InstallSource.entries.firstOrNull { it.name == name && it != InstallSource.CUSTOM }
                    ?: throw HttpError(400, "Unknown source \"$name\".")
                action { controls.installSource(source) }
            }
            "POST" to "/api/install" -> action { controls.install() }
            "POST" to "/api/ipa" -> upload(request)
            "POST" to "/api/settings" -> settings(request)
            else -> if (KNOWN.contains(request.path)) {
                json(405, JSONObject().put("ok", false).put("error", "${request.method} is not allowed on ${request.path}."))
            } else {
                json(404, JSONObject().put("ok", false).put("error", "No such API: ${request.path}"))
            }
        }
    }

    /**
     * Who the controller will not answer, since it has no login.
     *
     * - Devices outside this phone's network, and anything over mobile data.
     * - Requests addressed to a name rather than to the phone's address (an
     *   IP or localhost). This stops DNS rebinding, where a website points
     *   its own name at this phone to read the controller through the
     *   browser of someone on the network.
     * - Requests that change something and say they come from another
     *   website (the Origin header). Without this, any page open in a
     *   browser on the network could quietly send commands here.
     */
    private fun refusal(request: HttpRequest): HttpResponse? {
        val remote = request.remote
        if (remote == null || !isLocal(remote, request.local)) {
            return HttpResponse.text(403, "Only devices on the same local network as the phone can use the web controller.")
        }
        val host = request.header("host")?.trim().orEmpty()
        if (!isDirectHost(host)) {
            return HttpResponse.text(403, "Open the web controller at the address the app shows in Settings, such as http://192.168.1.20:8686.")
        }
        if (request.method != "GET" && request.method != "HEAD") {
            val origin = request.header("origin")?.trim()
            if (origin != null && !origin.equals("http://$host", ignoreCase = true)) {
                return json(403, JSONObject().put("ok", false).put("error", "Requests from other websites are refused."))
            }
        }
        return null
    }

    // MARK: - Routes

    private fun page(request: HttpRequest): HttpResponse {
        if (request.method != "GET" && request.method != "HEAD") {
            return HttpResponse.text(405, "Only GET is allowed here.")
        }
        val name = when (request.path) {
            "/", "/index.html" -> "index.html"
            else -> request.path.removePrefix("/")
        }
        if (!name.matches(Regex("[A-Za-z0-9_-]+\\.(html|js|css|svg)"))) return HttpResponse.text(404, "Not found.")
        val bytes = asset(name) ?: return HttpResponse.text(404, "Not found.")
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            else -> "image/svg+xml"
        }
        return HttpResponse(200, type, if (request.method == "HEAD") ByteArray(0) else bytes)
    }

    private fun state(): HttpResponse {
        val body = onMain.call { StateJson.encode(controls.state.value, controls.settingsSnapshot()) }
        return json(200, body)
    }

    private fun logs(request: HttpRequest): HttpResponse {
        val after = request.query["after"]?.toLongOrNull() ?: 0L
        return json(200, StateJson.logs(controls.logs.value, after))
    }

    private fun upload(request: HttpRequest): HttpResponse {
        val length = request.contentLength
        if (length <= 0) throw HttpError(411, "Send the IPA as the request body with a Content-Length.")
        if (length > maxUploadBytes) throw HttpError(413, "The IPA is larger than ${maxUploadBytes / 1_000_000_000} GB.")
        controls.state.value.busy?.let { busy ->
            return refused("Wait until \"$busy\" finishes before uploading.")
        }
        val target = newUploadFile()
        val free = (target.parentFile ?: target.absoluteFile.parentFile)?.usableSpace ?: Long.MAX_VALUE
        if (free < length + UPLOAD_HEADROOM_BYTES) {
            throw HttpError(507, "The phone has ${free / 1_000_000} MB free; the IPA needs ${length / 1_000_000} MB plus some room to sign it.")
        }
        var written = 0L
        try {
            target.outputStream().buffered(256 * 1024).use { out ->
                val buffer = ByteArray(256 * 1024)
                while (written < length) {
                    val read = request.body.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                }
            }
        } catch (error: IOException) {
            target.delete()
            throw HttpError(400, "The upload broke off after ${written / 1_000_000} MB: ${error.message}")
        }
        if (written != length) {
            target.delete()
            throw HttpError(400, "The upload ended after $written of $length bytes.")
        }
        val outcome = onMain.call { controls.selectIpaFile(target) }
        if (outcome is Action.Refused) target.delete()
        return actionResponse(outcome)
    }

    private fun settings(request: HttpRequest): HttpResponse {
        val fields = body(request)
        if (fields.has("anisetteAddress")) {
            val address = fields.string("anisetteAddress")
            if (address.isNotEmpty() && !Regex("^https?://[^\\s/]+(:[0-9]+)?(/\\S*)?$").matches(address)) {
                throw HttpError(400, "The attestation address must start with https:// or http://.")
            }
            onMain.call { controls.setAnisetteAddress(address) }
        }
        if (fields.has("wifiDiscovery")) {
            val enabled = try {
                fields.getBoolean("wifiDiscovery")
            } catch (_: JSONException) {
                throw HttpError(400, "wifiDiscovery must be true or false.")
            }
            onMain.call { controls.setWifiDiscovery(enabled) }
        }
        if (fields.has("shareLogs")) {
            val enabled = try {
                fields.getBoolean("shareLogs")
            } catch (_: JSONException) {
                throw HttpError(400, "shareLogs must be true or false.")
            }
            onMain.call { controls.setShareLogs(enabled) }
        }
        return ok()
    }

    // MARK: - Helpers

    private fun action(block: () -> Action): HttpResponse = actionResponse(onMain.call(block))

    private fun actionResponse(outcome: Action): HttpResponse = when (outcome) {
        Action.Started -> json(202, JSONObject().put("ok", true))
        is Action.Refused -> refused(outcome.reason)
    }

    private fun refused(reason: String) = json(409, JSONObject().put("ok", false).put("error", reason))

    private fun done(block: () -> Unit): HttpResponse {
        onMain.call(block)
        return ok()
    }

    private fun ok() = json(200, JSONObject().put("ok", true))

    private fun json(status: Int, body: JSONObject) =
        HttpResponse.text(status, body.toString(), "application/json; charset=utf-8")

    private fun body(request: HttpRequest): JSONObject {
        val type = request.header("content-type") ?: ""
        if (!type.startsWith("application/json")) {
            throw HttpError(415, "Send JSON with Content-Type: application/json.")
        }
        val text = request.bodyText()
        return try {
            JSONObject(text.ifBlank { "{}" })
        } catch (_: JSONException) {
            throw HttpError(400, "The request body is not valid JSON.")
        }
    }

    private fun JSONObject.string(name: String, trim: Boolean = true): String {
        if (!has(name) || isNull(name)) throw HttpError(400, "\"$name\" is missing.")
        val value = opt(name) as? String ?: throw HttpError(400, "\"$name\" must be a string.")
        return if (trim) value.trim() else value
    }

    private fun JSONObject.int(name: String): Int {
        if (!has(name)) throw HttpError(400, "\"$name\" is missing.")
        return (opt(name) as? Number)?.toInt() ?: throw HttpError(400, "\"$name\" must be a number.")
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 8L * 1_000_000_000
        const val UPLOAD_HEADROOM_BYTES = 64L * 1_000_000

        private val KNOWN = setOf(
            "/api/state", "/api/logs", "/api/logs/export", "/api/dismiss",
            "/api/devices/refresh", "/api/connect", "/api/connect-wireless", "/api/disconnect",
            "/api/remote/start", "/api/remote/stop", "/api/remote/connect", "/api/remote/forget",
            "/api/apps/refresh", "/api/apps/uninstall", "/api/signin", "/api/2fa/code", "/api/2fa/sms",
            "/api/team", "/api/signout", "/api/revoke", "/api/install-source", "/api/install",
            "/api/ipa", "/api/settings"
        )

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val PORT = Regex("""\d{1,5}""")

        /** A Host header naming this phone by address: an IPv4 or [IPv6] literal, or localhost, with an optional port. */
        fun isDirectHost(host: String): Boolean {
            val name: String
            val port: String?
            if (host.startsWith("[")) {
                val end = host.indexOf(']')
                if (end < 0) return false
                name = host.substring(1, end)
                val after = host.substring(end + 1)
                port = when {
                    after.isEmpty() -> null
                    after.startsWith(":") -> after.substring(1)
                    else -> return false
                }
                if (!name.contains(':')) return false
            } else {
                name = host.substringBefore(':')
                port = if (host.contains(':')) host.substringAfter(':') else null
                if (name.lowercase() != "localhost" && !IPV4.matches(name)) return false
            }
            return port == null || PORT.matches(port)
        }
    }
}
