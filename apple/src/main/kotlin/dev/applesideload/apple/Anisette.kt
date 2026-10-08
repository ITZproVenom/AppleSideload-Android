package dev.applesideload.apple

import android.content.Context
import android.content.pm.PackageManager
import dev.applesideload.core.IsoDate
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The attestation headers Apple's identity service requires.
 *
 * Apple will not accept a sign-in that does not carry these. They are
 * produced by Apple's own ADI code - the same code that ships inside iTunes,
 * inside macOS, and inside the Apple Music app for Android - from a
 * provisioning session tied to a device identifier. There is no published
 * algorithm and no way to compute them from first principles.
 */
data class AnisetteData(
    /** X-Apple-I-MD: the one-time token for this request. */
    val oneTimePassword: String,
    /** X-Apple-I-MD-M: the machine token from provisioning. */
    val machineId: String,
    /** X-Apple-I-MD-RINFO: routing info, 17106176 for a provisioned client. */
    val routingInfo: String,
    /** X-Mme-Device-Id: the device identifier the session was provisioned for. */
    val deviceId: String,
    /** X-Apple-I-MD-LU: the local user hash. */
    val localUserId: String,
    val deviceSerial: String = "0",
    /**
     * The clock the one-time password was generated against.
     *
     * Apple checks this against the token, so the source's own time has to
     * be used rather than the phone's. A few seconds of drift is the usual
     * cause of a sign-in that fails with no useful error.
     */
    val clientTime: String,
    val timeZone: String,
    val locale: String,
    /**
     * X-MMe-Client-Info: the Apple client the data is presented as.
     *
     * It has to be one Apple still accepts: since autumn 2026 Grand Slam
     * answers a sign-in that claims to be Xcode 11 with HTTP 503. Sources
     * publish the one their data belongs to, and that is what is sent.
     */
    val clientInfo: String = DEFAULT_CLIENT_INFO,
    /** The User-Agent that goes with [clientInfo]. */
    val userAgent: String = DEFAULT_USER_AGENT
) {
    companion object {
        /** What anisette v3 sources publish today (GET /v3/client_info). */
        const val DEFAULT_CLIENT_INFO =
            "<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"
        const val DEFAULT_USER_AGENT = "akd/1.0 CFNetwork/808.1.4"
    }

    fun headers(): Map<String, String> = mapOf(
        "X-Apple-I-MD" to oneTimePassword,
        "X-Apple-I-MD-M" to machineId,
        "X-Apple-I-MD-RINFO" to routingInfo,
        "X-Apple-I-MD-LU" to localUserId,
        "X-Mme-Device-Id" to deviceId,
        "X-Apple-I-SRL-NO" to deviceSerial,
        "X-Apple-I-Client-Time" to clientTime,
        "X-Apple-I-TimeZone" to timeZone,
        "X-Apple-Locale" to locale
    )
}

/**
 * The community anisette sources, the same list SideStore and SideInstaller
 * read from.
 *
 * These exist because the attestation step needs Apple's closed ADI code,
 * which cannot run on an Android phone without a native bridge. A source
 * answers with the headers for one request and never sees the Apple ID or
 * the password: the sign-in itself still happens between this app and Apple.
 */
object AnisetteServers {
    const val LIST_URL = "https://servers.sidestore.io/servers.json"

    data class Server(val name: String, val address: String)

    /** Used on first launch and whenever the live list cannot be fetched. */
    val bundled: List<Server> = listOf(
        Server("SideStore", "https://ani.sidestore.io"),
        Server("SideStore (.app)", "https://ani.sidestore.app"),
        Server("SideStore (.zip)", "https://ani.sidestore.zip"),
        Server("SideStore (.xyz)", "https://ani.846969.xyz"),
        Server("nythepegasus", "https://ani.npeg.us"),
        Server("WE. Studio", "https://anisette.wedotstud.io"),
        Server("SteX", "https://ani.xu30.top"),
        Server("iDH Server", "https://ani.idevicehacked.com"),
        Server("neoarz", "https://ani.neoarz.com"),
        Server("crystall1nedev", "https://anisette.crystall1ne.dev")
    )

    val default: Server get() = bundled.first()

    /** Fetches the published list, falling back to [bundled] on any failure. */
    fun fetchList(http: Http = Http()): List<Server> = runCatching {
        val response = http.request(LIST_URL, headers = mapOf("User-Agent" to "AppleSideload"))
        if (response.code !in 200..299) return@runCatching bundled
        val array = org.json.JSONObject(response.text).getJSONArray("servers")
        buildList {
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                val address = entry.optString("address")
                if (address.isNotBlank()) {
                    add(Server(entry.optString("name", address), address.trimEnd('/')))
                }
            }
        }.ifEmpty { bundled }
    }.getOrDefault(bundled)
}

/** Where anisette data comes from. */
interface AnisetteProvider {
    val name: String

    /** True when this source can actually produce data right now. */
    fun isAvailable(): Boolean

    /** Throws [AnisetteUnavailable] rather than returning anything invented. */
    fun fetch(): AnisetteData
}

/**
 * Raised when attestation data cannot be produced.
 *
 * This is deliberately not swallowed anywhere: signing in with made-up
 * headers fails at Apple with an opaque error, so the failure is reported
 * here where the reason is still known.
 */
class AnisetteUnavailable(
    val detail: String,
    val limitation: String,
    val alternative: String?
) : Exception(
    buildString {
        append("Apple attestation data is unavailable: ")
        append(detail)
        append(". Limitation: ")
        append(limitation)
        if (alternative != null) {
            append(". Alternative: ")
            append(alternative)
        }
    }
)

/**
 * The on-device source: Apple's ADI libraries from the Apple Music app.
 *
 * Apple Music for Android ships libCoreADI.so and libstoreservicescore.so,
 * which are the real ADI implementation compiled for Android. Using them
 * means calling ADILoadLibraryWithPath, ADISetProvisioningPath,
 * ADIProvisioningStart, ADIProvisioningEnd and ADIGenerateOTP through JNI,
 * which needs a small native bridge compiled with the NDK.
 *
 * This build does not contain that bridge, so this provider reports what is
 * missing instead of pretending. It still checks for the libraries, because
 * whether they are present is the first thing a diagnostic report needs.
 */
class AdiAnisetteProvider(private val context: Context) : AnisetteProvider {

    override val name: String = "Apple ADI libraries on this device"

    /** True when the Apple Music app is installed and could supply the libraries. */
    fun hasAppleMusic(): Boolean = try {
        context.packageManager.getPackageInfo(APPLE_MUSIC, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Libraries already extracted into this app's own storage, if any. */
    fun extractedLibraries(): List<String> =
        File(context.filesDir, "adi").listFiles()?.map { it.name }.orEmpty()

    override fun isAvailable(): Boolean = false

    override fun fetch(): AnisetteData = throw AnisetteUnavailable(
        detail = "this build has no native ADI bridge" +
            if (hasAppleMusic()) ", although Apple Music is installed and ships the libraries" else "",
        limitation = "the attestation headers Apple requires are produced by Apple's closed " +
            "ADI code. Calling it needs a JNI bridge to libCoreADI.so compiled with the " +
            "Android NDK, which is not part of this build, and the algorithm cannot be " +
            "reimplemented from the outside",
        alternative = "set an anisette source in Settings that you control and trust"
    )

    private companion object {
        const val APPLE_MUSIC = "com.apple.android.music"
    }
}

/**
 * This phone's own attestation identity with an anisette v3 source.
 *
 * [identifier] is sixteen random bytes made here; [adiPb] is the
 * provisioning state Apple's ADI code produced for it, which the source
 * needs back with every request. Both stay on the phone between launches,
 * so Apple keeps seeing one machine: after one two factor code it trusts
 * that machine and stops asking.
 */
class AnisetteIdentity(val identifier: ByteArray, val adiPb: String) {
    init {
        require(identifier.size == 16) { "an identifier is 16 bytes" }
        require(adiPb.isNotBlank()) { "the provisioning data is empty" }
    }

    /** X-Mme-Device-Id, derived from the identifier so a new identity is a new device. */
    val deviceId: String
        get() {
            val buffer = ByteBuffer.wrap(identifier)
            return UUID(buffer.long, buffer.long).toString().uppercase()
        }

    /** X-Apple-I-MD-LU: SHA-256 of the identifier, as other v3 clients send it. */
    val localUserId: String
        get() = MessageDigest.getInstance("SHA-256").digest(identifier)
            .joinToString("") { "%02X".format(it) }

    val identifierBase64: String get() = java.util.Base64.getEncoder().encodeToString(identifier)
}

/** Where [AnisetteIdentity] is kept. */
interface AnisetteStore {
    fun load(): AnisetteIdentity?
    fun save(identity: AnisetteIdentity)
    fun clear()
}

/** Kept only as long as the process: for tests, and as the default. */
class MemoryAnisetteStore : AnisetteStore {
    @Volatile private var identity: AnisetteIdentity? = null
    override fun load(): AnisetteIdentity? = identity
    override fun save(identity: AnisetteIdentity) { this.identity = identity }
    override fun clear() { identity = null }
}

/** One small file in the app's private storage. */
class FileAnisetteStore(private val file: File) : AnisetteStore {

    @Synchronized
    override fun load(): AnisetteIdentity? = runCatching {
        if (!file.isFile) return null
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        AnisetteIdentity(
            identifier = java.util.Base64.getDecoder().decode(properties.getProperty(KEY_IDENTIFIER).orEmpty()),
            adiPb = properties.getProperty(KEY_ADI_PB).orEmpty()
        )
    }.getOrNull()

    @Synchronized
    override fun save(identity: AnisetteIdentity) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(KEY_IDENTIFIER, identity.identifierBase64)
            setProperty(KEY_ADI_PB, identity.adiPb)
        }
        // Written aside and renamed, so a crash never leaves half a file.
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { properties.store(it, "anisette v3 identity") }
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw IOException("could not save the attestation identity")
        }
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private companion object {
        const val KEY_IDENTIFIER = "identifier"
        const val KEY_ADI_PB = "adi_pb"
    }
}

/**
 * An anisette source at an address from Settings.
 *
 * A v3 source (every SideStore server is one) is used the way SideStore uses
 * it: this phone makes its own identity once, Apple provisions it through the
 * source, and from then on the source only computes the one-time password
 * for that identity. A source without /v3 gets the old v1 request, whose
 * data belongs to the source's shared identities; that still signs in, but
 * Apple may ask for a code every time, because it sees a different machine.
 *
 * The source never sees the Apple ID or the password: the sign-in itself
 * still happens between this app and Apple.
 */
class RemoteAnisetteProvider(
    private val baseUrl: String = AnisetteServers.default.address,
    private val deviceId: String = UUID.randomUUID().toString().uppercase(),
    private val http: Http = Http(),
    private val store: AnisetteStore = MemoryAnisetteStore(),
    private val lookupUrl: String = APPLE_LOOKUP
) : AnisetteProvider {

    override val name: String = "anisette source at $baseUrl"

    override fun isAvailable(): Boolean = baseUrl.isNotBlank()

    private val base: String get() = baseUrl.trim().trimEnd('/')

    override fun fetch(): AnisetteData {
        if (baseUrl.isBlank()) {
            throw AnisetteUnavailable(
                detail = "no anisette address is configured",
                limitation = "sign-in cannot be attempted without attestation data",
                alternative = "enter an address in Settings"
            )
        }
        val client = clientInfo()
        return if (client != null) fetchV3(client) else fetchV1()
    }

    private class ClientInfo(val clientInfo: String, val userAgent: String)

    /** The source's v3 client description, or null when it only speaks v1. */
    private fun clientInfo(): ClientInfo? {
        KNOWN[base]?.let { return it as? ClientInfo }
        val response = try {
            http.request("$base/v3/client_info", headers = mapOf("Accept" to "application/json"))
        } catch (error: IOException) {
            throw unreachable(error)
        }
        if (response.code >= 500) {
            // A v3 source that is failing right now, not one without v3.
            throw AnisetteUnavailable(
                detail = "the source answered HTTP ${response.code}",
                limitation = "the source is having trouble of its own",
                alternative = "try again in a while, or pick another anisette source in Settings"
            )
        }
        val json = if (response.code in 200..299) runCatching { JSONObject(response.text) }.getOrNull() else null
        val description = json?.optString("client_info").orEmpty()
        if (description.isBlank()) {
            Log.i(LogTag.APPLE, "the anisette source has no v3 interface, so its shared v1 data is used")
            KNOWN[base] = V1_ONLY
            return null
        }
        val info = ClientInfo(
            clientInfo = description,
            userAgent = json?.optString("user_agent").orEmpty().ifBlank { AnisetteData.DEFAULT_USER_AGENT }
        )
        KNOWN[base] = info
        return info
    }

    private fun fetchV3(client: ClientInfo): AnisetteData = synchronized(LOCK) {
        var identity = store.load() ?: provision(client)
        var reply = headersFor(identity)
        if (reply is HeadersReply.Refused) {
            // Usually provisioning data the source can no longer use; a new
            // identity fixes that, at the price of one more two factor code.
            Log.w(LogTag.APPLE, "the anisette source refused this phone's identity (${reply.message}); making a new one")
            store.clear()
            identity = provision(client)
            reply = headersFor(identity)
        }
        when (reply) {
            is HeadersReply.Refused -> throw AnisetteUnavailable(
                detail = "the source could not produce attestation data: ${reply.message}",
                limitation = "the source computes the one-time password with Apple's own code; it reported a failure",
                alternative = "try again, or pick another anisette source in Settings"
            )
            is HeadersReply.Headers -> {
                Log.i(LogTag.APPLE, "attestation data came from the configured source (v3, this phone's own identity)")
                AnisetteData(
                    oneTimePassword = reply.oneTimePassword,
                    machineId = reply.machineId,
                    routingInfo = reply.routingInfo,
                    deviceId = identity.deviceId,
                    localUserId = identity.localUserId,
                    deviceSerial = "0",
                    clientTime = IsoDate.now(),
                    timeZone = "UTC",
                    locale = "en_US",
                    clientInfo = client.clientInfo,
                    userAgent = client.userAgent
                )
            }
        }
    }

    private sealed class HeadersReply {
        class Headers(val oneTimePassword: String, val machineId: String, val routingInfo: String) : HeadersReply()
        class Refused(val message: String) : HeadersReply()
    }

    private fun headersFor(identity: AnisetteIdentity): HeadersReply {
        val body = JSONObject()
            .put("identifier", identity.identifierBase64)
            .put("adi_pb", identity.adiPb)
            .toString()
        val response = try {
            http.request(
                url = "$base/v3/get_headers",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                body = body.toByteArray(Charsets.UTF_8)
            )
        } catch (error: IOException) {
            throw unreachable(error)
        }
        val json = runCatching { JSONObject(response.text) }.getOrNull() ?: throw AnisetteUnavailable(
            detail = "the source answered HTTP ${response.code} without the expected JSON",
            limitation = "a v3 source answers /v3/get_headers with a JSON object",
            alternative = "check the address in Settings"
        )
        return when (val result = json.optString("result")) {
            "Headers" -> {
                val otp = json.optString("X-Apple-I-MD")
                val machine = json.optString("X-Apple-I-MD-M")
                if (otp.isBlank() || machine.isBlank()) {
                    HeadersReply.Refused("the answer had no X-Apple-I-MD or X-Apple-I-MD-M")
                } else {
                    HeadersReply.Headers(otp, machine, json.optString("X-Apple-I-MD-RINFO").ifBlank { "17106176" })
                }
            }
            "GetHeadersError" -> HeadersReply.Refused(json.optString("message").ifBlank { "no reason given" })
            else -> HeadersReply.Refused("an unexpected answer \"$result\"")
        }
    }

    /**
     * Makes a new identity: Apple's provisioning, relayed through the source.
     *
     * The source runs Apple's ADI code and says what it needs; this side
     * talks to Apple's two provisioning endpoints itself and passes the
     * answers back. Nothing about the Apple account is involved.
     */
    private fun provision(client: ClientInfo): AnisetteIdentity {
        Log.i(LogTag.APPLE, "setting up this phone's own attestation identity with Apple, through $base")
        val identifier = ByteArray(16).also(SecureRandom()::nextBytes)
        val draft = DraftIdentity(identifier)
        val headers = mapOf(
            "User-Agent" to client.userAgent,
            "X-Mme-Client-Info" to client.clientInfo,
            "Accept" to "*/*",
            "X-Mme-Device-Id" to draft.deviceId,
            "X-Apple-I-MD-LU" to draft.localUserId,
            "X-Apple-I-SRL-NO" to "0",
            "X-Apple-I-Client-Time" to IsoDate.now(),
            "X-Apple-I-TimeZone" to "UTC",
            "X-Apple-Locale" to "en_US"
        )
        val (startUrl, finishUrl) = provisioningUrls(headers)
        val socketUrl = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring(8)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring(7)
            else -> throw AnisetteUnavailable(
                detail = "the address $base is not an http or https address",
                limitation = "a v3 source is reached over HTTP(S) and a WebSocket",
                alternative = "check the address in Settings"
            )
        } + "/v3/provisioning_session"

        val socket = try {
            WebSocketClient.connect(socketUrl)
        } catch (error: IOException) {
            throw AnisetteUnavailable(
                detail = "the source's provisioning session could not be opened: ${error.message}",
                limitation = "a new identity is made through the source's /v3/provisioning_session WebSocket",
                alternative = "try again, or pick another anisette source in Settings"
            )
        }
        socket.use {
            try {
                return converse(socket, identifier, draft, headers, startUrl, finishUrl)
            } catch (error: IOException) {
                throw provisioningFailed("the connection failed: ${error.message}")
            }
        }
    }

    /** The source leads; each of its requests is answered until it reports success. */
    private fun converse(
        socket: WebSocketClient,
        identifier: ByteArray,
        draft: DraftIdentity,
        headers: Map<String, String>,
        startUrl: String,
        finishUrl: String
    ): AnisetteIdentity {
        while (true) {
            val text = socket.receiveText() ?: throw provisioningFailed("the source ended the session early")
            val message = runCatching { JSONObject(text) }.getOrNull()
                ?: throw provisioningFailed("the source sent something that is not JSON")
            when (val result = message.optString("result")) {
                "GiveIdentifier" ->
                    socket.sendText(JSONObject().put("identifier", draft.identifierBase64).toString())

                "GiveStartProvisioningData" -> {
                    val response = appleProvisioning(startUrl, emptyMap(), headers)
                    socket.sendText(JSONObject().put("spim", base64Field(response, "spim")).toString())
                }

                "GiveEndProvisioningData" -> {
                    val cpim = message.optString("cpim").ifBlank {
                        throw provisioningFailed("the source asked to finish without the data for it")
                    }
                    val response = appleProvisioning(finishUrl, mapOf("cpim" to Plist.Str(cpim)), headers)
                    socket.sendText(
                        JSONObject()
                            .put("ptm", base64Field(response, "ptm"))
                            .put("tk", base64Field(response, "tk"))
                            .toString()
                    )
                }

                "ProvisioningSuccess" -> {
                    val adiPb = message.optString("adi_pb").ifBlank {
                        throw provisioningFailed("the source reported success without the provisioning data")
                    }
                    val identity = AnisetteIdentity(identifier, adiPb)
                    store.save(identity)
                    Log.i(LogTag.APPLE, "this phone's attestation identity is set up")
                    return identity
                }

                else -> {
                    val reason = message.optString("message")
                    throw provisioningFailed(
                        "the source stopped with \"$result\"" + if (reason.isNotBlank()) ": $reason" else ""
                    )
                }
            }
        }
    }

    /** The identity being made, before the source confirms it. */
    private class DraftIdentity(identifier: ByteArray) {
        private val shape = AnisetteIdentity(identifier, "pending")
        val deviceId = shape.deviceId
        val localUserId = shape.localUserId
        val identifierBase64 = shape.identifierBase64
    }

    private fun provisioningUrls(headers: Map<String, String>): Pair<String, String> {
        val urls = runCatching {
            val response = http.request(lookupUrl, headers = headers)
            if (response.code !in 200..299) null else dev.applesideload.core.PlistReader.parse(response.body)["urls"]
        }.getOrNull()
        return (urls?.get("midStartProvisioning")?.asString ?: APPLE_START) to
            (urls?.get("midFinishProvisioning")?.asString ?: APPLE_FINISH)
    }

    private fun appleProvisioning(url: String, request: Map<String, Plist>, headers: Map<String, String>): Plist {
        val reply = try {
            http.plist(
                url = url,
                request = Plist.dict("Header" to Plist.Dict(emptyMap()), "Request" to Plist.Dict(request)),
                headers = headers
            )
        } catch (error: IOException) {
            throw provisioningFailed("Apple's provisioning service: ${error.message}")
        }
        val response = reply["Response"] ?: reply
        val status = response["Status"]
        val code = status?.get("ec")?.asInt ?: 0
        if (code != 0) {
            throw provisioningFailed(
                "Apple refused: ${status?.get("em")?.asString ?: "no further detail"} (error $code)"
            )
        }
        return response
    }

    private fun base64Field(response: Plist, key: String): String {
        val value = response[key]
        return value?.asString
            ?: value?.asData?.let { java.util.Base64.getEncoder().encodeToString(it) }
            ?: throw provisioningFailed("Apple's answer had no $key")
    }

    private fun provisioningFailed(detail: String) = AnisetteUnavailable(
        detail = "setting up this phone's attestation identity failed: $detail",
        limitation = "Apple has to provision an identity once, through the anisette source, before a sign-in",
        alternative = "try again, or pick another anisette source in Settings"
    )

    private fun unreachable(error: Exception) = AnisetteUnavailable(
        detail = "the configured source could not be reached: ${error.message}",
        limitation = "the address must answer with the usual anisette JSON",
        alternative = "check the address in Settings"
    )

    /** The old request: one GET answered with headers from the source's own identities. */
    private fun fetchV1(): AnisetteData {
        val response = try {
            http.getJson("$base/")
        } catch (error: Exception) {
            throw unreachable(error)
        }
        fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { response[it]?.takeIf(String::isNotBlank) }
        val otp = field("X-Apple-I-MD", "otp")
        val machine = field("X-Apple-I-MD-M", "machineID", "machine_id")
        if (otp == null || machine == null) {
            throw AnisetteUnavailable(
                detail = "the source answered without the required fields",
                limitation = "X-Apple-I-MD and X-Apple-I-MD-M are both needed",
                alternative = null
            )
        }
        Log.i(LogTag.APPLE, "attestation data came from the configured source (v1, the source's shared identity)")
        return AnisetteData(
            oneTimePassword = otp,
            machineId = machine,
            routingInfo = field("X-Apple-I-MD-RINFO", "routing_info") ?: "17106176",
            deviceId = field("X-Mme-Device-Id", "device_id") ?: deviceId,
            localUserId = field("X-Apple-I-MD-LU", "local_user_id")
                ?: deviceId.uppercase().take(64),
            deviceSerial = field("X-Apple-I-SRL-NO", "device_serial") ?: "0",
            clientTime = field("X-Apple-I-Client-Time", "client_time") ?: IsoDate.now(),
            timeZone = field("X-Apple-I-TimeZone", "time_zone") ?: "UTC",
            locale = field("X-Apple-Locale", "locale") ?: "en_US",
            clientInfo = field("X-MMe-Client-Info", "X-Mme-Client-Info", "client_info")
                ?: AnisetteData.DEFAULT_CLIENT_INFO
        )
    }

    companion object {
        const val APPLE_LOOKUP = "https://gsa.apple.com/grandslam/GsService2/lookup"
        private const val APPLE_START = "https://gsa.apple.com/grandslam/MidService/startMachineProvisioning"
        private const val APPLE_FINISH = "https://gsa.apple.com/grandslam/MidService/finishMachineProvisioning"

        /** One identity is made at a time, however many sign-in steps ask at once. */
        private val LOCK = Any()

        /** What each source address turned out to speak, for this process. */
        private val KNOWN = ConcurrentHashMap<String, Any>()
        private val V1_ONLY = Any()
    }
}
