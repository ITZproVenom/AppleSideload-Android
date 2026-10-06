package dev.applesideload.apple

import android.content.Context
import android.content.pm.PackageManager
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.File
import java.util.UUID

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
    val locale: String
) {
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
 * An anisette source the user points at themselves.
 *
 * This is off unless an address is entered in Settings. It is the only
 * working path on a device without the native bridge, and it is the user's
 * choice rather than a default, because it means the attestation step
 * happens somewhere other than this phone.
 */
class RemoteAnisetteProvider(
    private val baseUrl: String = AnisetteServers.default.address,
    private val deviceId: String = UUID.randomUUID().toString().uppercase(),
    private val http: Http = Http()
) : AnisetteProvider {

    override val name: String = "anisette source at $baseUrl"

    override fun isAvailable(): Boolean = baseUrl.isNotBlank()

    override fun fetch(): AnisetteData {
        if (baseUrl.isBlank()) {
            throw AnisetteUnavailable(
                detail = "no anisette address is configured",
                limitation = "sign-in cannot be attempted without attestation data",
                alternative = "enter an address in Settings"
            )
        }
        val response = try {
            http.getJson(baseUrl.trimEnd('/') + "/")
        } catch (error: Exception) {
            throw AnisetteUnavailable(
                detail = "the configured source could not be reached: ${error.message}",
                limitation = "the address must answer with the usual anisette JSON object",
                alternative = "check the address in Settings"
            )
        }
        fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { response[it] }
        val otp = field("X-Apple-I-MD", "otp")
        val machine = field("X-Apple-I-MD-M", "machineID", "machine_id")
        if (otp == null || machine == null) {
            throw AnisetteUnavailable(
                detail = "the source answered without the required fields",
                limitation = "X-Apple-I-MD and X-Apple-I-MD-M are both needed",
                alternative = null
            )
        }
        Log.i(LogTag.APPLE, "attestation data came from the configured source")
        return AnisetteData(
            oneTimePassword = otp,
            machineId = machine,
            routingInfo = field("X-Apple-I-MD-RINFO", "routing_info") ?: "17106176",
            deviceId = field("X-Mme-Device-Id", "device_id") ?: deviceId,
            localUserId = field("X-Apple-I-MD-LU", "local_user_id")
                ?: deviceId.uppercase().take(64),
            deviceSerial = field("X-Apple-I-SRL-NO", "device_serial") ?: "0",
            clientTime = field("X-Apple-I-Client-Time", "client_time")
                ?: dev.applesideload.core.IsoDate.now(),
            timeZone = field("X-Apple-I-TimeZone", "time_zone") ?: "UTC",
            locale = field("X-Apple-Locale", "locale") ?: "en_US"
        )
    }
}
