package dev.applesideload.apple

import dev.applesideload.core.Base64
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.Redaction
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A signed-in Apple account and the tokens the developer services need. */
data class AppleSession(
    val appleId: String,
    val adsid: String,
    val idmsToken: String,
    /**
     * The session payload's "sk": the key Apple issued with this sign-in.
     * App-token requests are signed with it and their replies are
     * encrypted with it.
     */
    val sessionKey: ByteArray,
    /** The session payload's "c", which app-token requests send back. */
    val appTokenCookie: ByteArray = ByteArray(0),
    val developerToken: String? = null,
    val developerTokenExpiry: Long = 0
) {
    val identityToken: String
        get() = Base64.encode("$adsid:$idmsToken".toByteArray())

    override fun equals(other: Any?): Boolean =
        other is AppleSession && other.adsid == adsid && other.idmsToken == idmsToken

    override fun hashCode(): Int = adsid.hashCode() * 31 + idmsToken.hashCode()

    override fun toString(): String = "AppleSession(appleId=$appleId, adsid=$adsid)"
}

/** What the sign-in needs next. */
sealed class AuthResult {
    data class Success(val session: AppleSession) : AuthResult()

    /** Apple sent a six digit code to the account's trusted devices. */
    data class TrustedDeviceCodeRequired(val pending: PendingAuth) : AuthResult()

    /** Apple wants a code sent by SMS to one of these numbers. */
    data class PhoneCodeRequired(
        val pending: PendingAuth,
        val numbers: List<TrustedPhoneNumber>
    ) : AuthResult()
}

data class TrustedPhoneNumber(val id: Int, val maskedNumber: String)

/** The half-finished sign-in that a two factor code completes. */
class PendingAuth internal constructor(
    internal val appleId: String,
    internal val adsid: String,
    internal val idmsToken: String,
    internal val password: String
) {
    override fun toString(): String = "PendingAuth(appleId=${Redaction.apply(appleId)})"
}

/** Raised when Apple refuses, carrying Apple's own reason. */
class AppleAuthException(
    val code: Int,
    val detail: String,
    val limitation: String? = null,
    val alternative: String? = null
) : Exception(
    buildString {
        append("Apple refused the sign-in: ")
        append(detail)
        if (code != 0) append(" (error $code)")
        if (limitation != null) append(". Limitation: $limitation")
        if (alternative != null) append(". Alternative: $alternative")
    }
)

/**
 * Signing in to an Apple account, step for step as SideInstaller's
 * isideload does it (auth/apple_account.rs).
 *
 * The exchange is SRP-6a against Apple's Grand Slam service: the password is
 * never sent, Apple proves it knows the verifier, and the shared key then
 * decrypts the session payload. Two factor authentication is a second round
 * with a code the user reads off their own device, after which the sign-in
 * is simply repeated.
 *
 * Everything here needs attestation headers from an [AnisetteProvider]. If
 * none is available the sign-in is not attempted, because sending made-up
 * headers gets the account flagged rather than signed in.
 */
class AppleAuth(
    private val anisette: AnisetteProvider,
    private val grandSlam: GrandSlam = GrandSlam(),
    /** Where the text-message code endpoints live; tests point it elsewhere. */
    private val authUrl: String = AUTH
) {

    fun signIn(appleId: String, password: String): AuthResult {
        Log.i(LogTag.APPLE, "signing in to Apple as ${Redaction.apply(appleId)}")
        val data = anisette.fetch()
        val service = gsUrl("gsService")
        val cpd = clientProvidedData(data)

        val srp = SrpClient()
        val first = grandSlamRequest(
            service,
            request(
                "A2k" to Plist.Data(srp.publicABytes),
                "cpd" to cpd,
                "o" to Plist.Str("init"),
                "ps" to Plist.Arr(listOf(Plist.Str("s2k"), Plist.Str("s2k_fo"))),
                "u" to Plist.Str(appleId)
            ),
            step = "the first sign-in step"
        )
        val salt = first["s"]?.asData ?: throw incomplete("an SRP salt")
        val serverB = first["B"]?.asData ?: throw incomplete("an SRP challenge")
        val iterations = first["i"]?.asInt ?: throw incomplete("an iteration count")
        val cookie = first["c"]?.asString ?: throw incomplete("a session cookie")
        val protocol = first["sp"]?.asString ?: throw incomplete("the password protocol")
        if (protocol != "s2k" && protocol != "s2k_fo") {
            throw AppleAuthException(0, "Apple asked for the unsupported password protocol \"$protocol\"")
        }
        Log.i(LogTag.APPLE, "sign-in step 1 done (protocol $protocol, $iterations iterations)")

        val m1 = srp.process(appleId, password, salt, serverB, iterations, protocol)
        val second = grandSlamRequest(
            service,
            request(
                "M1" to Plist.Data(m1),
                "c" to Plist.Str(cookie),
                "cpd" to cpd,
                "o" to Plist.Str("complete"),
                "u" to Plist.Str(appleId)
            ),
            step = "the second sign-in step",
            headers = mapOf("Connection" to "close")
        )
        val m2 = second["M2"]?.asData ?: throw incomplete("its own proof (M2)")
        if (!srp.verifyServerProof(m2)) {
            throw AppleAuthException(
                0,
                "Apple's own proof did not verify",
                limitation = "the exchange was answered by something that does not know the account verifier"
            )
        }
        Log.i(LogTag.APPLE, "sign-in step 2 done; Apple's proof verified")

        val spd = decryptSpd(second["spd"]?.asData ?: throw incomplete("the session payload"), srp)
        val adsid = spd["adsid"]?.asString ?: throw incomplete("the account identifier")
        val idmsToken = spd["GsIdmsToken"]?.asString ?: throw incomplete("the account token")
        val pending = PendingAuth(appleId, adsid, idmsToken, password)

        when (val au = second["Status"]?.get("au")?.asString) {
            null, "repair" -> Unit // "repair" only means the account has no two factor set up.
            "trustedDeviceSecondaryAuth" -> {
                Log.i(LogTag.APPLE, "Apple wants a code from one of the account's trusted devices")
                requestTrustedDeviceCode(pending)
                return AuthResult.TrustedDeviceCodeRequired(pending)
            }
            "secondaryAuth" -> {
                Log.i(LogTag.APPLE, "Apple wants a code sent by text message")
                return AuthResult.PhoneCodeRequired(pending, trustedPhoneNumbers(pending))
            }
            else -> {
                // As isideload: an extra step is not needed when Apple
                // already issued the password-equivalent token.
                if (spd["t"]?.get("com.apple.gs.idms.pet")?.get("token")?.asString == null) {
                    throw AppleAuthException(
                        0,
                        "Apple asks for an extra step (\"$au\") that this app cannot complete",
                        alternative = "sign in once at appleid.apple.com to clear it, then try again"
                    )
                }
                Log.i(LogTag.APPLE, "Apple named an extra step (\"$au\"), but the sign-in is complete")
            }
        }

        Log.i(LogTag.APPLE, "signed in as ${Redaction.apply(appleId)}")
        return AuthResult.Success(
            AppleSession(
                appleId = appleId,
                adsid = adsid,
                idmsToken = idmsToken,
                sessionKey = spd["sk"]?.asData ?: ByteArray(0),
                appTokenCookie = spd["c"]?.asData ?: ByteArray(0)
            )
        )
    }

    /**
     * Exchanges the account token for an application token.
     *
     * The developer services do not accept the Grand Slam token directly:
     * each application asks for its own token, signing the request with the
     * session payload's "sk" and sending back its "c". Xcode asks for
     * com.apple.gs.xcode.auth, and so does this.
     */
    fun fetchAppToken(session: AppleSession, app: String = XCODE_APP): AppleSession {
        if (session.sessionKey.size != 32 || session.appTokenCookie.isEmpty()) {
            throw AppleAuthException(
                0,
                "this sign-in did not include the keys needed for a developer token",
                alternative = "sign out and sign in again"
            )
        }
        val data = anisette.fetch()
        val checksum = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(session.sessionKey, "HmacSHA256"))
            update("apptokens".toByteArray())
            update(session.adsid.toByteArray())
            update(app.toByteArray())
            doFinal()
        }
        val response = grandSlamRequest(
            gsUrl("gsService"),
            request(
                "app" to Plist.Arr(listOf(Plist.Str(app))),
                "c" to Plist.Data(session.appTokenCookie),
                "checksum" to Plist.Data(checksum),
                "cpd" to clientProvidedData(data),
                "o" to Plist.Str("apptokens"),
                "u" to Plist.Str(session.adsid),
                "t" to Plist.Str(session.idmsToken)
            ),
            step = "the developer token request"
        )
        val encrypted = response["et"]?.asData ?: throw incomplete("the encrypted developer token")
        val reply = try {
            PlistReader.parse(decryptGcm(encrypted, session.sessionKey))
        } catch (error: AppleAuthException) {
            throw error
        } catch (error: Exception) {
            throw AppleAuthException(0, "the developer token Apple sent could not be read: ${error.message}")
        }
        val status = reply["status-code"]?.asInt
        if (status != 200) {
            throw AppleAuthException(status ?: 0, "Apple did not issue a token for $app (status $status)")
        }
        val entry = reply["t"]?.get(app)
            ?: throw AppleAuthException(
                0,
                "Apple did not return a token for $app",
                limitation = "the account may not be enrolled in the developer programme, including the free tier"
            )
        val token = entry["token"]?.asString?.takeIf { it.isNotEmpty() }
            ?: throw AppleAuthException(0, "the token for $app was empty")
        Log.i(LogTag.APPLE, "received a developer token for $app")
        return session.copy(developerToken = token, developerTokenExpiry = entry["expiry"]?.asLong ?: 0)
    }

    // MARK: - Two factor authentication

    /** Asks Apple to push a code to the account's trusted devices. */
    fun requestTrustedDeviceCode(pending: PendingAuth) {
        val data = anisette.fetch()
        val response = grandSlam.send(gsUrl("trustedDeviceSecondaryAuth"), headers = twoFactorHeaders(pending, data))
        if (response.code !in 200..299) {
            throw AppleAuthException(
                response.code,
                "Apple would not send the verification code (HTTP ${response.code}, ${response.reason})",
                alternative = "try again in a minute"
            )
        }
        Log.i(LogTag.APPLE, "Apple sent a code to the account's trusted devices")
    }

    /**
     * Checks a code from a trusted device, then signs in again.
     *
     * A wrong code (-21669) throws and leaves the sign-in waiting, so the
     * code can simply be entered again.
     */
    fun submitTrustedDeviceCode(pending: PendingAuth, code: String): AuthResult {
        val data = anisette.fetch()
        val url = gsUrl("validateCode")
        val reply = try {
            Http.parsePlist(url, grandSlam.send(url, headers = twoFactorHeaders(pending, data) + ("security-code" to code)))
        } catch (error: IOException) {
            throw AppleAuthException(0, "the code could not be checked: ${error.message}")
        }
        val status = reply["Status"] ?: reply
        when (val ec = status["ec"]?.asInt ?: 0) {
            0 -> Unit
            WRONG_CODE -> throw wrongCode(status["em"]?.asString)
            else -> throw AppleAuthException(ec, status["em"]?.asString ?: "the code was not accepted")
        }
        Log.i(LogTag.APPLE, "Apple accepted the code; signing in again")
        return signIn(pending.appleId, pending.password)
    }

    fun trustedPhoneNumbers(pending: PendingAuth): List<TrustedPhoneNumber> {
        val data = anisette.fetch()
        val response = try {
            grandSlam.send(authUrl, headers = twoFactorHeaders(pending, data), json = true)
        } catch (error: IOException) {
            Log.w(LogTag.APPLE, "the trusted phone numbers could not be loaded: ${error.message}")
            return emptyList()
        }
        val array = runCatching { JSONObject(response.text).optJSONArray("trustedPhoneNumbers") }.getOrNull()
        if (array == null) {
            Log.w(LogTag.APPLE, "Apple listed no trusted phone numbers (HTTP ${response.code})")
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                add(
                    TrustedPhoneNumber(
                        entry.optInt("id"),
                        entry.optString("numberWithDialCode").ifBlank { entry.optString("obfuscatedNumber") }
                    )
                )
            }
        }
    }

    fun requestPhoneCode(pending: PendingAuth, numberId: Int) {
        val data = anisette.fetch()
        val body = JSONObject()
            .put("phoneNumber", JSONObject().put("id", numberId))
            .put("mode", "sms")
            .toString()
        val response = grandSlam.send(
            "$authUrl/verify/phone",
            method = "PUT",
            headers = twoFactorHeaders(pending, data),
            body = body.toByteArray(),
            json = true
        )
        if (response.code in 200..299) {
            Log.i(LogTag.APPLE, "Apple sent a code by text message")
            return
        }
        serviceError(response.text)?.let { error ->
            if (error.code == "-22979" || error.code == "-22981") {
                // Apple will not send another yet, but the last code is still good.
                Log.i(LogTag.APPLE, "Apple did not send a new text (${error.title}); the last code still works")
                return
            }
            throw AppleAuthException(error.code.toIntOrNull() ?: response.code, "${error.title}: ${error.message}")
        }
        if (response.code == 412 && isActiveChallenge(response.text, numberId)) {
            Log.i(LogTag.APPLE, "a text with a code is already on its way to that number")
            return
        }
        throw AppleAuthException(
            response.code,
            "Apple would not send the code by text message (HTTP ${response.code}, ${response.reason})"
        )
    }

    fun submitPhoneCode(pending: PendingAuth, numberId: Int, code: String): AuthResult {
        val data = anisette.fetch()
        val body = JSONObject()
            .put("securityCode", JSONObject().put("code", code))
            .put("phoneNumber", JSONObject().put("id", numberId))
            .put("mode", "sms")
            .toString()
        val response = grandSlam.send(
            "$authUrl/verify/phone/securitycode",
            method = "POST",
            headers = twoFactorHeaders(pending, data),
            body = body.toByteArray(),
            json = true
        )
        if (response.code !in 200..299) {
            val error = serviceError(response.text)
                ?: throw AppleAuthException(
                    response.code,
                    "the code could not be checked (HTTP ${response.code}, ${response.reason})"
                )
            if (error.code == WRONG_CODE.toString()) throw wrongCode("${error.title}: ${error.message}")
            throw AppleAuthException(error.code.toIntOrNull() ?: response.code, "${error.title}: ${error.message}")
        }
        Log.i(LogTag.APPLE, "Apple accepted the code; signing in again")
        return signIn(pending.appleId, pending.password)
    }

    private class ServiceError(val code: String, val title: String, val message: String)

    private fun serviceError(text: String): ServiceError? = runCatching {
        val first = JSONObject(text).optJSONArray("serviceErrors")?.optJSONObject(0) ?: return null
        ServiceError(
            first.optString("code").ifBlank { "unknown" },
            first.optString("title").ifBlank { "No title provided" },
            first.optString("message").ifBlank { "No message provided" }
        )
    }.getOrNull()

    /** A 412 that only means the requested text is already on its way (isideload's check). */
    private fun isActiveChallenge(text: String, numberId: Int): Boolean = runCatching {
        val json = JSONObject(text)
        val code = json.getJSONObject("securityCode")
        val numbers = json.optJSONArray("trustedPhoneNumbers")
        json.optString("mode") == "sms" &&
            json.optString("type") == "verification" &&
            json.optString("authenticationType") == "hsa2" &&
            json.getJSONObject("trustedPhoneNumber").optInt("id", -1) == numberId &&
            numbers != null && (0 until numbers.length()).any { numbers.optJSONObject(it)?.optInt("id", -1) == numberId } &&
            code.optInt("length") == 6 &&
            !code.optBoolean("tooManyCodesSent") &&
            !code.optBoolean("tooManyCodesValidated") &&
            !code.optBoolean("securityCodeLocked") &&
            !code.optBoolean("securityCodeCooldown")
    }.getOrDefault(false)

    private fun wrongCode(detail: String?) = AppleAuthException(
        WRONG_CODE,
        "the verification code was not accepted" + if (detail.isNullOrBlank()) "" else " ($detail)",
        alternative = "check the code and enter it again"
    )

    /** isideload's build_2fa_headers: the attestation headers, the account's identity token and routing info. */
    private fun twoFactorHeaders(pending: PendingAuth, data: AnisetteData): Map<String, String> =
        data.headers() + mapOf(
            "X-Apple-Identity-Token" to Base64.encode("${pending.adsid}:${pending.idmsToken}".toByteArray()),
            "X-Apple-I-MD-RINFO" to data.routingInfo
        )

    // MARK: - Transport

    private fun request(vararg fields: Pair<String, Plist>): Plist = Plist.dict(
        "Header" to Plist.dict("Version" to Plist.Str("1.0.1")),
        "Request" to Plist.dict(*fields)
    )

    private fun gsUrl(key: String): String = try {
        grandSlam.url(key)
    } catch (error: IOException) {
        throw AppleAuthException(0, "Apple's sign-in service could not be reached: ${error.message}")
    }

    /** One Grand Slam request, retried on 429, with Apple's error turned into an [AppleAuthException]. */
    private fun grandSlamRequest(
        url: String,
        body: Plist,
        step: String,
        headers: Map<String, String> = emptyMap()
    ): Plist {
        val response = try {
            grandSlam.plistRequest(url, body, headers, retry429 = true)
        } catch (error: HttpStatusException) {
            throw AppleAuthException(
                error.code,
                "Apple's sign-in service answered $step with HTTP ${error.code} (${error.reason})",
                limitation = when {
                    error.code == 429 -> "Apple limits how often an account may sign in"
                    error.code >= 500 -> "Apple's service is refusing or failing this request"
                    else -> null
                },
                alternative = if (error.code == 429 || error.code >= 500) "wait a few minutes and try again" else null
            )
        } catch (error: IOException) {
            throw AppleAuthException(0, "$step failed: ${error.message}")
        }
        val status = response["Status"] ?: response
        val code = status["ec"]?.asInt ?: 0
        if (code != 0) {
            throw AppleAuthException(
                code,
                status["em"]?.asString ?: status["ed"]?.asString ?: "no further detail was given",
                limitation = when (code) {
                    -20101 -> "the Apple ID or password is wrong"
                    -22406 -> "Apple rejected the attestation headers as invalid"
                    else -> null
                },
                alternative = if (code == -22406) "pick another anisette source in Settings" else null
            )
        }
        return response
    }

    /** isideload's client-provided data: fixed fields plus the attestation headers. */
    private fun clientProvidedData(data: AnisetteData): Plist.Dict {
        val fields = linkedMapOf<String, Plist>(
            "bootstrap" to Plist.Str("true"),
            "icscrec" to Plist.Str("true"),
            "loc" to Plist.Str("en_US"),
            "pbe" to Plist.Str("false"),
            "prkgen" to Plist.Str("true"),
            "svct" to Plist.Str("iCloud")
        )
        data.headers().forEach { (key, value) -> fields[key] = Plist.Str(value) }
        return Plist.Dict(fields)
    }

    private fun incomplete(what: String) = AppleAuthException(0, "Apple's reply did not include $what")

    /**
     * Unwraps the session payload.
     *
     * The payload is AES-256-CBC under keys derived from the SRP session key
     * with fixed labels, which is what keeps the account tokens off the wire
     * in the clear.
     */
    private fun decryptSpd(spd: ByteArray, srp: SrpClient): Plist {
        val key = srp.hmac("extra data key:")
        val iv = srp.hmac("extra data iv:").copyOfRange(0, 16)
        val plain = try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(spd)
            }
        } catch (error: Exception) {
            throw AppleAuthException(0, "the session payload could not be decrypted")
        }
        return try {
            PlistReader.parse(plain)
        } catch (error: Exception) {
            throw AppleAuthException(0, "the session payload could not be read: ${error.message}")
        }
    }

    companion object {
        const val XCODE_APP = "com.apple.gs.xcode.auth"

        private const val AUTH = "https://gsa.apple.com/auth"
        private const val WRONG_CODE = -21669

        /**
         * The token reply: "XYZ", a sixteen byte nonce, then AES-256-GCM
         * ciphertext and tag under the session payload's "sk", with the
         * three header bytes as associated data (isideload's decrypt_gcm).
         * BouncyCastle does the GCM, as not every Android version's
         * platform cipher takes a sixteen byte nonce.
         */
        internal fun decryptGcm(blob: ByteArray, key: ByteArray): ByteArray {
            if (blob.size < 3 + 16 + 16) {
                throw AppleAuthException(0, "the encrypted token is too short to be valid (${blob.size} bytes)")
            }
            val header = blob.copyOfRange(0, 3)
            if (!header.contentEquals("XYZ".toByteArray())) {
                throw AppleAuthException(0, "the encrypted token is in an unknown format")
            }
            if (key.size != 32) throw AppleAuthException(0, "the session key is ${key.size} bytes, not 32")
            val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
            cipher.init(false, AEADParameters(KeyParameter(key), 128, blob.copyOfRange(3, 19), header))
            val body = blob.copyOfRange(19, blob.size)
            val out = ByteArray(cipher.getOutputSize(body.size))
            return try {
                var length = cipher.processBytes(body, 0, body.size, out, 0)
                length += cipher.doFinal(out, length)
                out.copyOf(length)
            } catch (error: InvalidCipherTextException) {
                throw AppleAuthException(0, "the encrypted token did not decrypt with this session's key")
            }
        }
    }
}
