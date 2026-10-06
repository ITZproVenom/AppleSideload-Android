package dev.applesideload.apple

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A signed-in Apple account and the tokens the developer services need. */
data class AppleSession(
    val appleId: String,
    val adsid: String,
    val idmsToken: String,
    val sessionKey: ByteArray,
    val developerToken: String? = null,
    val developerTokenExpiry: Long = 0
) {
    val identityToken: String
        get() = dev.applesideload.core.Base64.encode("$adsid:$idmsToken".toByteArray())

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
    internal val sessionKey: ByteArray,
    internal val password: String
)

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
 * Signing in to an Apple account, the way Xcode does.
 *
 * The exchange is SRP-6a against Apple's Grand Slam service: the password is
 * never sent, Apple proves it knows the verifier, and the shared key then
 * decrypts the session payload. Two factor authentication is a second round
 * with a code the user reads off their own device.
 *
 * Everything here needs attestation headers from an [AnisetteProvider]. If
 * none is available the sign-in is not attempted, because sending made-up
 * headers gets the account flagged rather than signed in.
 */
class AppleAuth(
    private val anisette: AnisetteProvider,
    private val http: Http = Http()
) {

    fun signIn(appleId: String, password: String): AuthResult {
        val srp = SrpClient()
        val first = gsa(
            mapOf(
                "A2k" to Plist.Data(srp.publicABytes),
                "ps" to Plist.Arr(listOf(Plist.Str("s2k"), Plist.Str("s2k_fo"))),
                "u" to Plist.Str(appleId),
                "o" to Plist.Str("init")
            )
        )
        val protocol = first["sp"]?.asString ?: "s2k"
        val salt = first["s"]?.asData
            ?: throw AppleAuthException(0, "Apple did not return an SRP salt")
        val serverB = first["B"]?.asData
            ?: throw AppleAuthException(0, "Apple did not return an SRP challenge")
        val iterations = first["i"]?.asInt
            ?: throw AppleAuthException(0, "Apple did not return an iteration count")
        val cookie = first["c"]?.asString.orEmpty()

        val m1 = srp.process(appleId, password, salt, serverB, iterations, protocol)
        val second = gsa(
            mapOf(
                "c" to Plist.Str(cookie),
                "M1" to Plist.Data(m1),
                "u" to Plist.Str(appleId),
                "o" to Plist.Str("complete")
            )
        )
        second["M2"]?.asData?.let { m2 ->
            if (!srp.verifyServerProof(m2)) {
                throw AppleAuthException(
                    0,
                    "Apple's own proof did not verify",
                    limitation = "the exchange was answered by something that does not know " +
                        "the account verifier"
                )
            }
        }

        val payload = decryptSpd(
            second["spd"]?.asData
                ?: throw AppleAuthException(0, "Apple returned no session payload"),
            srp
        )
        val adsid = payload["adsid"]?.asString
            ?: throw AppleAuthException(0, "the session payload has no account identifier")
        val idmsToken = payload["GsIdmsToken"]?.asString
            ?: throw AppleAuthException(0, "the session payload has no token")

        val status = second["Status"]
        when (status?.get("au")?.asString) {
            "trustedDeviceSecondaryAuth" -> {
                Log.i(LogTag.APPLE, "the account needs a code from a trusted device")
                val pending = PendingAuth(appleId, adsid, idmsToken, srp.key, password)
                requestTrustedDeviceCode(pending)
                return AuthResult.TrustedDeviceCodeRequired(pending)
            }

            "secondaryAuth" -> {
                val pending = PendingAuth(appleId, adsid, idmsToken, srp.key, password)
                return AuthResult.PhoneCodeRequired(pending, trustedPhoneNumbers(pending))
            }
        }

        Log.i(LogTag.APPLE, "signed in as ${dev.applesideload.core.Redaction.apply(appleId)}")
        return AuthResult.Success(
            AppleSession(appleId, adsid, idmsToken, srp.key)
        )
    }

    /**
     * Exchanges the account token for an application token.
     *
     * The developer services do not accept the Grand Slam token directly:
     * each application asks for its own token, proving it may do so with a
     * checksum over the SRP session key. Xcode asks for
     * com.apple.gs.xcode.auth, and so does this.
     */
    fun fetchAppToken(session: AppleSession, app: String = XCODE_APP): AppleSession {
        val checksum = appTokenChecksum(session.sessionKey, session.adsid, app)
        val response = gsa(
            mapOf(
                "u" to Plist.Str(session.adsid),
                "app" to Plist.Arr(listOf(Plist.Str(app))),
                "c" to Plist.Data(checksum),
                "t" to Plist.Str(session.idmsToken),
                "checksum" to Plist.Data(checksum),
                "o" to Plist.Str("apptokens")
            )
        )
        val encrypted = response["et"]?.asData
        val tokens = if (encrypted != null) {
            decryptGcm(encrypted, session.sessionKey)["t"]
        } else {
            response["t"]
        }
        val entry = tokens?.get(app)
            ?: throw AppleAuthException(
                0,
                "Apple did not return a token for $app",
                limitation = "the account may not be enrolled in the developer programme, " +
                    "including the free tier"
            )
        val token = entry["token"]?.asString
            ?: throw AppleAuthException(0, "the token for $app was empty")
        val expiry = entry["expiry"]?.asLong ?: 0
        Log.i(LogTag.APPLE, "received a developer token for $app")
        return session.copy(developerToken = token, developerTokenExpiry = expiry)
    }

    private fun appTokenChecksum(sessionKey: ByteArray, adsid: String, app: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        mac.update("apptokens".toByteArray())
        mac.update(adsid.toByteArray())
        mac.update(app.toByteArray())
        return mac.doFinal()
    }

    /**
     * Apple wraps the token reply in AES-GCM when it sends "et".
     *
     * The key is derived from the session key with the same HMAC scheme as
     * the sign-in payload, and the blob carries a three byte version header
     * and a sixteen byte nonce in front of the ciphertext.
     */
    private fun decryptGcm(blob: ByteArray, sessionKey: ByteArray): Plist {
        if (blob.size < 3 + 16 + 16) {
            throw AppleAuthException(0, "the encrypted token reply was too short to be valid")
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        mac.update("AppleIDClientIdentifier".toByteArray())
        val key = mac.doFinal()
        val iv = blob.copyOfRange(3, 19)
        val body = blob.copyOfRange(19, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        cipher.updateAAD(blob.copyOfRange(0, 3))
        return dev.applesideload.core.PlistReader.parse(cipher.doFinal(body))
    }

    /** Asks Apple to push the code to the account's other devices. */
    fun requestTrustedDeviceCode(pending: PendingAuth) {
        val response = http.request(
            url = "$AUTH/verify/trusteddevice",
            headers = twoFactorHeaders(pending)
        )
        if (response.code !in 200..299) {
            throw AppleAuthException(
                response.code,
                "Apple would not send the verification code",
                alternative = "use a code from a trusted phone number instead"
            )
        }
    }

    fun submitTrustedDeviceCode(pending: PendingAuth, code: String): AuthResult {
        val response = http.request(
            url = "$GSA_BASE/validate",
            headers = twoFactorHeaders(pending) + mapOf("security-code" to code)
        )
        checkTwoFactorResponse(response.code, response.body, code)
        return finishTwoFactor(pending)
    }

    fun trustedPhoneNumbers(pending: PendingAuth): List<TrustedPhoneNumber> {
        val response = http.request(
            url = "$AUTH/auth",
            headers = twoFactorHeaders(pending) + mapOf("Accept" to "application/json")
        )
        if (response.code !in 200..299) return emptyList()
        return runCatching {
            val json = org.json.JSONObject(response.text)
            val numbers = json.getJSONObject("trustedPhoneNumbers")
            val array = numbers.optJSONArray("trustedPhoneNumbers")
                ?: json.optJSONArray("trustedPhoneNumbers")
            buildList {
                for (index in 0 until (array?.length() ?: 0)) {
                    val entry = array!!.getJSONObject(index)
                    add(
                        TrustedPhoneNumber(
                            entry.optInt("id"),
                            entry.optString("numberWithDialCode", entry.optString("obfuscatedNumber"))
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun requestPhoneCode(pending: PendingAuth, numberId: Int) {
        val body = """{"phoneNumber":{"id":$numberId},"mode":"sms"}""".toByteArray()
        val response = http.request(
            url = "$AUTH/verify/phone",
            method = "PUT",
            headers = twoFactorHeaders(pending) + mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = body
        )
        if (response.code !in 200..299) {
            throw AppleAuthException(response.code, "Apple would not send the code by SMS")
        }
    }

    fun submitPhoneCode(pending: PendingAuth, numberId: Int, code: String): AuthResult {
        val body = """{"phoneNumber":{"id":$numberId},"securityCode":{"code":"$code"},"mode":"sms"}"""
        val response = http.request(
            url = "$AUTH/verify/phone/securitycode",
            method = "POST",
            headers = twoFactorHeaders(pending) + mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = body.toByteArray()
        )
        checkTwoFactorResponse(response.code, response.body, code)
        return finishTwoFactor(pending)
    }

    /**
     * After a code is accepted the whole SRP exchange is repeated.
     *
     * Apple does not hand back a session from the verification call itself -
     * the second sign-in simply no longer asks for a code, because the device
     * identifier in the attestation headers is now trusted.
     */
    private fun finishTwoFactor(pending: PendingAuth): AuthResult {
        val result = signIn(pending.appleId, pending.password)
        if (result !is AuthResult.Success) {
            throw AppleAuthException(
                0,
                "Apple asked for another verification code immediately after accepting one",
                limitation = "this happens when the attestation data changes between attempts"
            )
        }
        return result
    }

    private fun checkTwoFactorResponse(code: Int, body: ByteArray, entered: String) {
        if (code in 200..299) return
        val detail = String(body).take(300)
        throw when {
            detail.contains("-21669") || code == 401 -> AppleAuthException(
                code,
                "the ${entered.length} digit code was not accepted",
                alternative = "check the code on the trusted device and try again"
            )

            else -> AppleAuthException(code, "verification failed: $detail")
        }
    }

    // MARK: - Transport

    private fun gsa(request: Map<String, Plist>): Plist {
        val data = anisette.fetch()
        val body = Plist.dict(
            "Header" to Plist.dict("Version" to Plist.Str("1.0.1")),
            "Request" to Plist.Dict(
                LinkedHashMap(request).apply { put("cpd", clientProvidedData(data)) }
            )
        )
        val reply = http.plist(
            url = GSA_BASE,
            request = body,
            headers = mapOf(
                "Content-Type" to "text/x-xml-plist",
                "Accept" to "*/*",
                "User-Agent" to USER_AGENT,
                "X-MMe-Client-Info" to CLIENT_INFO
            )
        )
        val response = reply["Response"] ?: reply
        val status = response["Status"]
        val code = status?.get("ec")?.asInt ?: 0
        if (code != 0) {
            throw AppleAuthException(
                code,
                status?.get("em")?.asString
                    ?: status?.get("ed")?.asString
                    ?: "no further detail was given",
                limitation = when (code) {
                    -20101 -> "the Apple ID or password is wrong"
                    -22406 -> "Apple rejected the attestation headers as invalid"
                    else -> null
                },
                alternative = if (code == -22406) {
                    "check the anisette source in Settings"
                } else {
                    null
                }
            )
        }
        return response
    }

    private fun clientProvidedData(data: AnisetteData): Plist.Dict {
        val fields = linkedMapOf<String, Plist>(
            "bootstrap" to Plist.Bool(true),
            "icscrec" to Plist.Bool(true),
            "pbe" to Plist.Bool(false),
            "prkgen" to Plist.Bool(true),
            "svct" to Plist.Str("iCloud"),
            "loc" to Plist.Str(data.locale),
            "X-MMe-Client-Info" to Plist.Str(CLIENT_INFO)
        )
        data.headers().forEach { (key, value) -> fields[key] = Plist.Str(value) }
        return Plist.Dict(fields)
    }

    private fun twoFactorHeaders(pending: PendingAuth): Map<String, String> {
        val data = anisette.fetch()
        return buildMap {
            put("Accept", "text/x-xml-plist")
            put("Content-Type", "text/x-xml-plist")
            put("User-Agent", USER_AGENT)
            put("X-Apple-Identity-Token", dev.applesideload.core.Base64.encode(
                "${pending.adsid}:${pending.idmsToken}".toByteArray()
            ))
            put("X-Apple-App-Info", "com.apple.gs.xcode.auth")
            put("X-Xcode-Version", XCODE_VERSION)
            put("X-Mme-Client-Info", CLIENT_INFO)
            putAll(data.headers())
        }
    }

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
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = try {
            cipher.doFinal(spd)
        } catch (error: Exception) {
            throw AppleAuthException(
                0,
                "the session payload could not be decrypted, which means the password was " +
                    "not accepted",
                alternative = "check the password and try again"
            )
        }
        return dev.applesideload.core.PlistReader.parse(plain)
    }

    companion object {
        const val XCODE_APP = "com.apple.gs.xcode.auth"

        private const val GSA_BASE = "https://gsa.apple.com/grandslam/GsService2"
        private const val AUTH = "https://gsa.apple.com/auth"
        private const val USER_AGENT = "akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0"
        private const val XCODE_VERSION = "11.2 (11B41)"
        private const val CLIENT_INFO =
            "<MacBookPro13,2> <Mac OS X;10.15.2;19C57> <com.apple.AuthKit/1 (com.apple.dt.Xcode/3594.4.19)>"
    }
}
