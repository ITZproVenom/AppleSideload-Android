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
    /** X-Apple-I-MD-RINFO: routing info, sent with two factor requests. */
    val routingInfo: String,
    /** X-Mme-Device-Id: the device identifier the data belongs to. */
    val deviceId: String,
    /**
     * Headers a v1 source adds to its shared data (local user, clock,
     * locale). A v3 identity needs none of them, as in SideInstaller.
     */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Optional client identity profile matched to these anisette tokens. */
    val clientInfo: String? = null,
    val userAgent: String? = null
) {
    /** The attestation headers for a Grand Slam or developer request (isideload's get_headers). */
    fun headers(): Map<String, String> = linkedMapOf(
        "X-Mme-Device-Id" to deviceId,
        "X-Apple-I-MD" to oneTimePassword,
        "X-Apple-I-MD-M" to machineId
    ).apply { putAll(extraHeaders) }

    /** Match Apple client identification to the Android anisette tokens. */
    fun clientHeaders(): Map<String, String> = buildMap {
        clientInfo?.let { put("X-Mme-Client-Info", it) }
        userAgent?.let { put("User-Agent", it) }
    }

    /** The tokens stay out of logs. */
    override fun toString(): String = "AnisetteData(deviceId=$deviceId)"
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

    enum class Library { ANDROID, WINDOWS, MAC }
    enum class ClientKind { WINDOWS, MAC }

    private val windowsAdiHosts = setOf("ani.jkcoxson.com", "win.ani.sidestore.io", "ani2.stikstore.app")
    private val macAdiHosts = setOf("mac-ani.jkcoxson.com", "new.ani.sidestore.io")
    private val stockClientPrefixes = listOf(
        "<MacBookPro13,2> <macOS;13.1;22C65>",
        "<MacBookPro15,1> <Mac OS X;10.15.2;19C57>"
    )

    fun libraryOf(address: String, servers: List<Server> = bundled): Library {
        val key = normalize(address)
        val host = runCatching { java.net.URI(key).host?.lowercase() }.getOrNull()
        if (host in macAdiHosts) return Library.MAC
        if (host in windowsAdiHosts) return Library.WINDOWS
        val displayName = servers.firstOrNull { normalize(it.address) == key }?.name.orEmpty().lowercase()
        return when {
            "mac adi" in displayName -> Library.MAC
            "windows" in displayName -> Library.WINDOWS
            else -> Library.ANDROID
        }
    }

    fun libraryOf(address: String, advertisedClientInfo: String?, servers: List<Server> = bundled): Library {
        val known = libraryOf(address, servers)
        if (known != Library.ANDROID) return known
        val info = advertisedClientInfo.orEmpty()
        return when {
            "<Windows;" in info -> Library.WINDOWS
            info.contains("<MacBookPro13,3>") || info.contains("<Mac OS X;10.12.6;") -> Library.MAC
            else -> Library.ANDROID
        }
    }

    fun clientKind(address: String, servers: List<Server> = bundled): ClientKind =
        if (libraryOf(address, servers) == Library.MAC) ClientKind.MAC else ClientKind.WINDOWS

    fun candidates(pick: String, servers: List<Server>): List<String> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<String>()
        fun add(address: String) {
            val trimmed = address.trim().trimEnd('/')
            if (trimmed.isNotBlank() && seen.add(normalize(trimmed))) out += trimmed
        }
        val all = (listOf(Server("selected", pick)) + servers).distinctBy { normalize(it.address) }
        val pickLibrary = libraryOf(pick, all)
        add(pick)
        if (pickLibrary != Library.ANDROID) all.filter { libraryOf(it.address, all) == pickLibrary }.forEach { add(it.address) }
        all.filter { libraryOf(it.address, all) == Library.WINDOWS }.forEach { add(it.address) }
        all.filter { libraryOf(it.address, all) != Library.MAC }.forEach { add(it.address) }
        all.forEach { add(it.address) }
        return out
    }

    private fun normalize(address: String): String = address.trim().trimEnd('/').lowercase()

    val bundled: List<Server> = listOf(
        Server("jkcoxson (Windows ADI)", "https://ani.jkcoxson.com"),
        Server("SideStore (Windows ADI)", "https://win.ani.sidestore.io"),
        Server("StikStore (Windows ADI)", "https://ani2.stikstore.app"),
        Server("SideStore", "https://ani.sidestore.io"),
        Server("SideStore (.app)", "https://ani.sidestore.app"),
        Server("SideStore (.zip)", "https://ani.sidestore.zip"),
        Server("SideStore (.xyz)", "https://ani.846969.xyz"),
        Server("SideStore (New)", "https://new.ani.sidestore.io"),
        Server("jkcoxson (Mac ADI)", "https://mac-ani.jkcoxson.com"),
        Server("nythepegasus", "https://ani.npeg.us"),
        Server("WE. Studio", "https://anisette.wedotstud.io"),
        Server("SteX", "https://ani.xu30.top"),
        Server("iDH Server", "https://ani.idevicehacked.com"),
        Server("neoarz", "https://ani.neoarz.com"),
        Server("crystall1nedev", "https://anisette.crystall1ne.dev")
    )

    val default: Server get() = bundled.first()
    val legacyDefault: Server get() = bundled.first { it.address == "https://ani.sidestore.io" }

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
        }.ifEmpty { bundled }.let { live ->
            (live + bundled.filter { libraryOf(it.address) != Library.ANDROID }).distinctBy { normalize(it.address) }
        }
    }.getOrDefault(bundled)
}

/** Where anisette data comes from. */
interface AnisetteProvider {
    val name: String
    val serverAddress: String get() = ""

    /** True when this source can actually produce data right now. */
    fun isAvailable(): Boolean

    /** Throws [AnisetteUnavailable] rather than returning anything invented. */
    fun fetch(): AnisetteData

    /** Applies Apple's hsc 434 resync payload to this identity when supported. */
    fun resync(sim: String): AnisetteData = throw AnisetteUnavailable(
        detail = "the selected anisette source does not support identity resync",
        limitation = "Apple requested a v3 anisette resync that this provider cannot perform",
        alternative = "choose a supported anisette v3 server"
    )
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

    /**
     * X-Mme-Device-Id: the identifier as a UUID, in lower case exactly as
     * isideload writes it (Uuid::from_bytes(identifier).to_string()).
     */
    val deviceId: String
        get() {
            val buffer = ByteBuffer.wrap(identifier)
            return UUID(buffer.long, buffer.long).toString()
        }

    /** X-Apple-I-MD-LU: SHA-256 of the identifier in lower-case hex, as isideload sends it. */
    val localUserId: String
        get() = MessageDigest.getInstance("SHA-256").digest(identifier)
            .joinToString("") { "%02x".format(it) }

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
 * With a v3 source, the app provisions and persists its identity through that
 * server. By default Apple sees the Windows/iTunes profile used by SideInstaller
 * 1.5.2; the optional legacy Android profile is for older servers only. Apple's
 * 2FA endpoints continue to use the separate Mac profile.
 */
class RemoteAnisetteProvider(
    private val baseUrl: String = AnisetteServers.default.address,
    private val deviceId: String = UUID.randomUUID().toString().uppercase(),
    private val http: Http = Http(),
    private val store: AnisetteStore = MemoryAnisetteStore(),
    private val grandSlam: GrandSlam = GrandSlam(http),
    /** Use SideInstaller's Android client identity with v3 anisette tokens. */
    private val useAndroidClientInfo: Boolean = false,
    private val servers: List<AnisetteServers.Server> = AnisetteServers.bundled,
    private val storeForLibrary: ((AnisetteServers.Library) -> AnisetteStore)? = null
) : AnisetteProvider {

    override val name: String = "anisette source at $baseUrl"
    override val serverAddress: String get() = base

    override fun isAvailable(): Boolean = baseUrl.isNotBlank()

    private val base: String get() = baseUrl.trim().trimEnd('/')

    private val activeStore: AnisetteStore
        get() {
            val factory = storeForLibrary ?: return store
            val library = AnisetteServers.libraryOf(base, sourceInfo?.clientInfo, servers)
            return factory(library)
        }

    override fun fetch(): AnisetteData {
        if (baseUrl.isBlank()) {
            throw AnisetteUnavailable(
                detail = "no anisette address is configured",
                limitation = "sign-in cannot be attempted without attestation data",
                alternative = "enter an address in Settings"
            )
        }
        val info = sourceInfo
        if (useAndroidClientInfo && info == null) {
            throw AnisetteUnavailable(
                detail = "the selected source does not expose an anisette v3 interface",
                limitation = "the legacy Android client profile requires v3 anisette data",
                alternative = "choose an anisette v3 server or turn off the legacy profile"
            )
        }
        return if (info != null) fetchV3() else fetchV1()
    }

    private data class SourceInfo(val clientInfo: String, val userAgent: String)

    private val sourceInfo: SourceInfo? by lazy {
        val response = try {
            http.request("$base/v3/client_info", headers = mapOf("Accept" to "application/json"))
        } catch (error: IOException) {
            throw unreachable(error)
        }
        if (response.code >= 500) {
            throw AnisetteUnavailable(
                detail = "the source answered HTTP ${response.code} (${response.reason})",
                limitation = "the source is having trouble of its own",
                alternative = "try again in a while, or pick another anisette source in Settings"
            )
        }
        val json = if (response.code in 200..299) runCatching { JSONObject(response.text) }.getOrNull() else null
        // Public v3 servers often advertise Xcode/macOS even though these
        // headers are generated with Android ADI; that metadata is not used.
        val clientInfo = json?.optString("client_info").orEmpty()
        if (clientInfo.isBlank()) {
            Log.i(LogTag.APPLE, "the anisette source has no v3 interface, so its shared v1 data is used")
            null
        } else SourceInfo(clientInfo, json?.optString("user_agent").orEmpty())
    }

    private data class ClientProfile(val info: String, val userAgent: String)

    private fun selectedClientProfile(): ClientProfile {
        if (useAndroidClientInfo) return ClientProfile(ANDROID_CLIENT_INFO, ANDROID_USER_AGENT)
        val advertised = sourceInfo
        return when (AnisetteServers.libraryOf(base, advertised?.clientInfo, servers)) {
            AnisetteServers.Library.MAC -> ClientProfile(
                advertised?.clientInfo?.takeIf { it.isNotBlank() } ?: MAC_SERVER_CLIENT_INFO,
                advertised?.userAgent?.takeIf { it.isNotBlank() } ?: WINDOWS_USER_AGENT
            )
            AnisetteServers.Library.WINDOWS -> ClientProfile(
                advertised?.clientInfo?.takeIf { it.contains("<Windows;", ignoreCase = true) } ?: WINDOWS_CLIENT_INFO,
                advertised?.userAgent?.takeIf { it.isNotBlank() } ?: WINDOWS_USER_AGENT
            )
            AnisetteServers.Library.ANDROID -> ClientProfile(WINDOWS_CLIENT_INFO, WINDOWS_USER_AGENT)
        }
    }

    private fun selectedClientHeaders(): Map<String, String> = selectedClientProfile().let {
        mapOf("X-Mme-Client-Info" to it.info, "User-Agent" to it.userAgent)
    }

    override fun resync(sim: String): AnisetteData = synchronized(LOCK) {
        if (sourceInfo == null) {
            throw AnisetteUnavailable(
                detail = "the selected source has no v3 anisette resync interface",
                limitation = "Apple's Windows ADI resync requires a v3 source",
                alternative = "choose an anisette v3 server"
            )
        }
        val identity = activeStore.load() ?: throw AnisetteUnavailable(
            detail = "there is no provisioned identity to resync",
            limitation = "Apple's resync payload applies to an existing anisette v3 identity",
            alternative = "retry sign-in to provision an identity"
        )
        val data = headersFor(identity, sim)
        when (data) {
            is HeadersReply.Refused -> throw AnisetteUnavailable(
                detail = "the source refused Apple's identity resync (${data.message})",
                limitation = "the anisette server could not apply Apple's resync payload",
                alternative = "try another v3 anisette server"
            )
            is HeadersReply.Headers -> anisetteData(identity, data).also {
                CACHE["$base|$useAndroidClientInfo"] = Cached(it, System.nanoTime())
                Log.i(LogTag.APPLE, "the anisette source applied Apple's identity resync")
            }
        }
    }

    private fun fetchV3(): AnisetteData = synchronized(LOCK) {
        val clientHeaders = selectedClientHeaders()
        val identityStore = activeStore
        var identity = identityStore.load() ?: provision(clientHeaders)
        val cacheKey = "$base|$useAndroidClientInfo"
        CACHE[cacheKey]?.let { cached ->
            if (cached.data.deviceId == identity.deviceId && System.nanoTime() - cached.madeAt < CACHE_NANOS) {
                return cached.data
            }
        }
        var reply = headersFor(identity)
        if (reply is HeadersReply.Refused) {
            // Usually provisioning data the source can no longer use; a new
            // identity fixes that, at the price of one more two factor code.
            Log.w(LogTag.APPLE, "the anisette source refused this phone's identity (${reply.message}); making a new one")
            identityStore.clear()
            identity = provision(clientHeaders)
            reply = headersFor(identity)
        }
        when (reply) {
            is HeadersReply.Refused -> throw AnisetteUnavailable(
                detail = "the source could not produce attestation data: ${reply.message}",
                limitation = "the source computes the one-time password with Apple's own code; it reported a failure",
                alternative = "try again, or pick another anisette source in Settings"
            )
            is HeadersReply.Headers -> {
                Log.i(LogTag.APPLE, if (useAndroidClientInfo) "attestation data came from the configured source (v3; legacy Android client profile)" else "attestation data came from the configured source (v3; Windows client profile)")
                anisetteData(identity, reply).also { CACHE[cacheKey] = Cached(it, System.nanoTime()) }
            }
        }
    }

    private fun anisetteData(identity: AnisetteIdentity, reply: HeadersReply.Headers) = selectedClientProfile().let { profile ->
        AnisetteData(
            oneTimePassword = reply.oneTimePassword,
            machineId = reply.machineId,
            routingInfo = reply.routingInfo,
            deviceId = identity.deviceId,
            clientInfo = profile.info,
            userAgent = profile.userAgent
        )
    }

    private sealed class HeadersReply {
        class Headers(val oneTimePassword: String, val machineId: String, val routingInfo: String) : HeadersReply()
        class Refused(val message: String) : HeadersReply()
    }

    private fun headersFor(identity: AnisetteIdentity, sim: String? = null): HeadersReply {
        val body = JSONObject()
            .put("identifier", identity.identifierBase64)
            .put("adi_pb", identity.adiPb)
        if (sim != null) {
            body.put("sim", sim)
                .put("device_id", identity.deviceId)
                .put("md_lu", identity.localUserId)
        }
        val response = try {
            http.request(
                url = "$base/v3/get_headers",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                body = body.toString().toByteArray(Charsets.UTF_8)
            )
        } catch (error: IOException) {
            throw unreachable(error)
        }
        val json = runCatching { JSONObject(response.text) }.getOrNull() ?: throw AnisetteUnavailable(
            detail = "the source answered HTTP ${response.code} (${response.reason}) without the expected JSON",
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
     * talks to Apple's two provisioning endpoints itself (addresses from the
     * URL bag, Grand Slam's headers plus the new identity's two) and passes
     * the answers back, as isideload's remote_v3 does. Nothing about the
     * Apple account is involved.
     */
    private fun provision(clientHeaders: Map<String, String>): AnisetteIdentity {
        Log.i(LogTag.APPLE, "setting up this phone's own attestation identity with Apple, through $base")
        val identifier = ByteArray(16).also(SecureRandom()::nextBytes)
        val draft = DraftIdentity(identifier)
        val headers = clientHeaders + mapOf(
            "X-Apple-I-MD-LU" to draft.localUserId,
            "X-Mme-Device-Id" to draft.deviceId
        )
        val startUrl = appleUrl("midStartProvisioning")
        val finishUrl = appleUrl("midFinishProvisioning")
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
                    activeStore.save(identity)
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

    private fun appleUrl(key: String): String = try {
        grandSlam.url(key)
    } catch (error: IOException) {
        throw provisioningFailed("Apple's URL bag could not be read: ${error.message}")
    }

    private fun appleProvisioning(url: String, request: Map<String, Plist>, headers: Map<String, String>): Plist {
        val response = try {
            grandSlam.plistRequest(
                url = url,
                body = Plist.dict("Header" to Plist.Dict(emptyMap()), "Request" to Plist.Dict(request)),
                headers = headers,
                retry429 = false
            )
        } catch (error: IOException) {
            throw provisioningFailed("Apple's provisioning service: ${error.message}")
        }
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
        val extras = linkedMapOf<String, String>()
        field("X-Apple-I-MD-LU", "local_user_id")?.let { extras["X-Apple-I-MD-LU"] = it }
        extras["X-Apple-I-SRL-NO"] = field("X-Apple-I-SRL-NO", "device_serial") ?: "0"
        extras["X-Apple-I-Client-Time"] = field("X-Apple-I-Client-Time", "client_time") ?: IsoDate.now()
        extras["X-Apple-I-TimeZone"] = field("X-Apple-I-TimeZone", "time_zone") ?: "UTC"
        extras["X-Apple-Locale"] = field("X-Apple-Locale", "locale") ?: "en_US"
        return AnisetteData(
            oneTimePassword = otp,
            machineId = machine,
            routingInfo = field("X-Apple-I-MD-RINFO", "routing_info") ?: "17106176",
            deviceId = field("X-Mme-Device-Id", "device_id") ?: deviceId,
            extraHeaders = extras,
            clientInfo = selectedClientProfile().info,
            userAgent = selectedClientProfile().userAgent
        )
    }

    private class Cached(val data: AnisetteData, val madeAt: Long)

    companion object {
        /** One identity is made at a time, however many sign-in steps ask at once. */
        private val LOCK = Any()

        /** SideInstaller v1.5.2: Windows/iTunes works with current Android and Windows ADI servers. */
        const val WINDOWS_CLIENT_INFO = "<PC> <Windows;6.2(0,0);9200> <com.apple.AuthKit/1 (com.apple.iTunes/12.12.4.1)>"
        const val WINDOWS_USER_AGENT = "akd/1.0 CFNetwork/808.1.4"
        const val MAC_SERVER_CLIENT_INFO = "<MacBookPro13,3> <Mac OS X;10.12.6;16G29>"

        /** Earlier Android profile retained only as a troubleshooting fallback. */
        const val ANDROID_CLIENT_INFO = "<Pixel 7> <Android;14;UQ1A.240205.002> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"
        const val ANDROID_USER_AGENT = "akd/1.0 CFNetwork/808.1.4"

        /** The last v3 answer per source and profile mode, reused for up to a minute. */
        private val CACHE = ConcurrentHashMap<String, Cached>()
        private const val CACHE_NANOS = 60_000_000_000L
    }
}
