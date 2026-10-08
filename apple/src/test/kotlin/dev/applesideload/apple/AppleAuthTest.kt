package dev.applesideload.apple

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The whole sign-in against a fake Grand Slam service: SRP init and
 * complete, the session payload, codes from a trusted device and by text
 * message, and the app token. The fake is written from the protocol
 * (RFC 5054 with Apple's x and PBKDF2 from BouncyCastle), separately from
 * the client; SrpTest pins the client's math to pysrp.
 */
class AppleAuthTest {

    private class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        val plist: Plist get() = PlistReader.parse(body)
        val json: JSONObject get() = JSONObject(String(body))
    }

    private class FakeApple(
        val email: String = "someone@example.com",
        val password: String = "correct horse battery",
        val protocol: String = "s2k"
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Recorded>()
        @Volatile var secondFactor: String? = null
        @Volatile var trusted = false
        val initStatuses = ConcurrentLinkedDeque<Int>()
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iterations = 1000
        val sk = ByteArray(32).also(SecureRandom()::nextBytes)
        val cookie = ByteArray(24).also(SecureRandom()::nextBytes)
        val adsid = "000123-08-0f1e2d3c"
        val idmsToken = "IDMS-TOKEN-xyz"
        private val verifier: BigInteger
        private var b = BigInteger.ONE
        private var serverB = BigInteger.ONE
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base: String

        init {
            val digest = sha256(password.toByteArray())
            val input = if (protocol == "s2k_fo") hex(digest).toByteArray() else digest
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(input, salt, iterations)
            val p = (generator.generateDerivedParameters(256) as KeyParameter).key
            val x = BigInteger(1, sha256(salt + sha256(":".toByteArray() + p)))
            verifier = G.modPow(x, N)
            server.createContext("/") { exchange ->
                try {
                    handle(exchange)
                } catch (error: Throwable) {
                    error.printStackTrace()
                    reply(exchange, 500, "text/plain", "fake failed: $error".toByteArray())
                }
            }
            server.start()
            base = "http://127.0.0.1:${server.address.port}"
        }

        override fun close() = server.stop(0)

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.readBytes()
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            val path = exchange.requestURI.path
            requests += Recorded(exchange.requestMethod, path, headers, body)
            when (path) {
                "/lookup" -> plist(
                    exchange,
                    Plist.dict(
                        "urls" to Plist.dict(
                            "gsService" to Plist.Str("$base/gs"),
                            "trustedDeviceSecondaryAuth" to Plist.Str("$base/tds"),
                            "validateCode" to Plist.Str("$base/validate")
                        )
                    )
                )
                "/gs" -> grandSlam(exchange, PlistReader.parse(body)["Request"]!!)
                "/tds" -> reply(exchange, 200, "text/x-xml-plist", ByteArray(0))
                "/validate" -> plist(
                    exchange,
                    if (headers["security-code"] == "123456") {
                        trusted = true
                        Plist.dict("ec" to Plist.Num(0))
                    } else {
                        Plist.dict("ec" to Plist.Num(-21669), "em" to Plist.Str("Incorrect verification code."))
                    }
                )
                "/auth" -> json(
                    exchange, 200,
                    """{"trustedPhoneNumbers":[{"id":2,"numberWithDialCode":"+1 (•••) •••-••12","obfuscatedNumber":"(•••) •••-••12"}]}"""
                )
                "/auth/verify/phone" -> reply(exchange, 200, "application/json", ByteArray(0))
                "/auth/verify/phone/securitycode" -> {
                    val code = JSONObject(String(body)).getJSONObject("securityCode").getString("code")
                    if (code == "654321") {
                        trusted = true
                        json(exchange, 200, "{}")
                    } else {
                        json(
                            exchange, 400,
                            """{"serviceErrors":[{"code":"-21669","title":"Incorrect verification code","message":"Please try again."}]}"""
                        )
                    }
                }
                else -> reply(exchange, 404, "text/plain", "no such path".toByteArray())
            }
        }

        private fun grandSlam(exchange: HttpExchange, request: Plist) {
            when (request["o"]?.asString) {
                "init" -> {
                    initStatuses.pollFirst()?.let { status ->
                        val page = "<html>\r\n<head><title>$status ${if (status == 503) "Service Temporarily Unavailable" else "Too Many Requests"}</title></head>\r\n" +
                            "<body>\r\n<center><h1>$status</h1></center>\r\n<hr><center>Apple</center>\r\n</body>\r\n</html>\r\n"
                        reply(exchange, status, "text/html", page.toByteArray())
                        return
                    }
                    if (request["u"]?.asString != email) {
                        status(exchange, -20101, "Your account information was entered incorrectly.")
                        return
                    }
                    clientA = BigInteger(1, request["A2k"]!!.asData!!)
                    b = BigInteger(256, SecureRandom())
                    serverB = k.multiply(verifier).add(G.modPow(b, N)).mod(N)
                    plist(
                        exchange,
                        Plist.dict(
                            "Response" to Plist.dict(
                                "Status" to Plist.dict("ec" to Plist.Num(0)),
                                "s" to Plist.Data(salt),
                                "B" to Plist.Data(unsigned(serverB)),
                                "i" to Plist.Num(iterations.toLong()),
                                "c" to Plist.Str("cookie-1"),
                                "sp" to Plist.Str(protocol)
                            )
                        )
                    )
                }
                "complete" -> {
                    check(request["c"]?.asString == "cookie-1") { "the init cookie did not come back" }
                    val u = BigInteger(1, sha256(pad(clientA) + pad(serverB)))
                    val s = clientA.multiply(verifier.modPow(u, N)).modPow(b, N)
                    val key = sha256(unsigned(s))
                    val hn = sha256(unsigned(N))
                    val hg = sha256(pad(G))
                    val xor = ByteArray(32) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
                    val m1 = sha256(xor + sha256(email.toByteArray()) + salt + unsigned(clientA) + unsigned(serverB) + key)
                    if (!m1.contentEquals(request["M1"]?.asData)) {
                        status(exchange, -20101, "Your account information was entered incorrectly.")
                        return
                    }
                    val payload = XmlPlist.write(
                        Plist.dict(
                            "adsid" to Plist.Str(adsid),
                            "GsIdmsToken" to Plist.Str(idmsToken),
                            "sk" to Plist.Data(sk),
                            "c" to Plist.Data(cookie),
                            "fn" to Plist.Str("Alex"),
                            "ln" to Plist.Str("Tester")
                        )
                    )
                    val spd = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                        init(
                            Cipher.ENCRYPT_MODE,
                            SecretKeySpec(hmac(key, "extra data key:"), "AES"),
                            IvParameterSpec(hmac(key, "extra data iv:").copyOf(16))
                        )
                        doFinal(payload)
                    }
                    val statusFields = linkedMapOf<String, Plist>("ec" to Plist.Num(0))
                    val au = secondFactor
                    if (au != null && (au == "repair" || !trusted)) statusFields["au"] = Plist.Str(au)
                    plist(
                        exchange,
                        Plist.dict(
                            "Response" to Plist.dict(
                                "Status" to Plist.Dict(statusFields),
                                "M2" to Plist.Data(sha256(unsigned(clientA) + m1 + key)),
                                "spd" to Plist.Data(spd)
                            )
                        )
                    )
                }
                "apptokens" -> {
                    val app = request["app"]?.asList?.singleOrNull()?.asString
                    val checksum = Mac.getInstance("HmacSHA256").run {
                        init(SecretKeySpec(sk, "HmacSHA256"))
                        doFinal(("apptokens" + adsid + app).toByteArray())
                    }
                    val valid = app == "com.apple.gs.xcode.auth" &&
                        request["u"]?.asString == adsid &&
                        request["t"]?.asString == idmsToken &&
                        cookie.contentEquals(request["c"]?.asData) &&
                        checksum.contentEquals(request["checksum"]?.asData)
                    if (!valid) {
                        status(exchange, -22411, "the app token request did not check out")
                        return
                    }
                    val token = XmlPlist.write(
                        Plist.dict(
                            "status-code" to Plist.Num(200),
                            "t" to Plist.dict(
                                app!! to Plist.dict(
                                    "token" to Plist.Str("DEV-TOKEN"),
                                    "duration" to Plist.Num(3600),
                                    "expiry" to Plist.Num(1_900_000_000_000)
                                )
                            )
                        )
                    )
                    val iv = ByteArray(16).also(SecureRandom()::nextBytes)
                    val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(sk, "AES"), GCMParameterSpec(128, iv))
                        updateAAD("XYZ".toByteArray())
                        doFinal(token)
                    }
                    plist(
                        exchange,
                        Plist.dict(
                            "Response" to Plist.dict(
                                "Status" to Plist.dict("ec" to Plist.Num(0)),
                                "et" to Plist.Data("XYZ".toByteArray() + iv + sealed)
                            )
                        )
                    )
                }
                else -> status(exchange, -1, "unknown operation")
            }
        }

        @Volatile private var clientA = BigInteger.ONE

        private fun status(exchange: HttpExchange, code: Int, message: String) = plist(
            exchange,
            Plist.dict(
                "Response" to Plist.dict(
                    "Status" to Plist.dict("ec" to Plist.Num(code.toLong()), "em" to Plist.Str(message))
                )
            )
        )

        private fun plist(exchange: HttpExchange, value: Plist) =
            reply(exchange, 200, "text/x-xml-plist", XmlPlist.write(value))

        private fun json(exchange: HttpExchange, code: Int, text: String) =
            reply(exchange, code, "application/json", text.toByteArray())

        private fun reply(exchange: HttpExchange, code: Int, type: String, body: ByteArray) {
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) } else exchange.close()
        }
    }

    private object FixedAnisette : AnisetteProvider {
        override val name = "fixed test data"
        override fun isAvailable() = true
        override fun fetch() = AnisetteData(
            oneTimePassword = "OTP-VALUE",
            machineId = "MACHINE-ID",
            routingInfo = "17106176",
            deviceId = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        )
    }

    private fun auth(apple: FakeApple) = AppleAuth(
        FixedAnisette,
        GrandSlam(Http(), bagUrl = "${apple.base}/lookup", retryDelaysMs = listOf(1, 1)),
        authUrl = "${apple.base}/auth"
    )

    private fun gs(apple: FakeApple) = apple.requests.filter { it.path == "/gs" }

    @Test
    fun signsInAndFetchesTheDeveloperToken() = FakeApple().use { apple ->
        val auth = auth(apple)
        val session = (auth.signIn(apple.email, apple.password) as AuthResult.Success).session
        assertEquals(apple.adsid, session.adsid)
        assertArrayEquals(apple.sk, session.sessionKey)
        assertArrayEquals(apple.cookie, session.appTokenCookie)

        val withToken = auth.fetchAppToken(session)
        assertEquals("DEV-TOKEN", withToken.developerToken)
        assertEquals(1_900_000_000_000, withToken.developerTokenExpiry)

        val (init, complete, tokens) = gs(apple)
        // The client Apple sees, on every request.
        for (request in listOf(init, complete, tokens)) {
            assertEquals(GrandSlam.CLIENT_INFO, request.headers["x-mme-client-info"])
            assertEquals(GrandSlam.USER_AGENT, request.headers["user-agent"])
            assertEquals("14.2 (14C18)", request.headers["x-xcode-version"])
            assertEquals("com.apple.gs.xcode.auth", request.headers["x-apple-app-info"])
            assertEquals("text/x-xml-plist", request.headers["content-type"])
            assertEquals("close", request.headers["connection"])
            // As in isideload, the attestation travels inside cpd, not as headers.
            assertEquals("OTP-VALUE", request.plist["Request"]?.get("cpd")?.get("X-Apple-I-MD")?.asString)
            assertEquals("MACHINE-ID", request.plist["Request"]?.get("cpd")?.get("X-Apple-I-MD-M")?.asString)
        }
        val first = init.plist["Request"]!!
        assertEquals("init", first["o"]?.asString)
        assertEquals(listOf("s2k", "s2k_fo"), first["ps"]?.asList?.map { it.asString })
        assertEquals(apple.email, first["u"]?.asString)
        assertEquals("1.0.1", init.plist["Header"]?.get("Version")?.asString)
        val cpd = first["cpd"]!!
        assertEquals("true", cpd["bootstrap"]?.asString)
        assertEquals("true", cpd["icscrec"]?.asString)
        assertEquals("false", cpd["pbe"]?.asString)
        assertEquals("true", cpd["prkgen"]?.asString)
        assertEquals("iCloud", cpd["svct"]?.asString)
        assertEquals("en_US", cpd["loc"]?.asString)
        assertEquals("OTP-VALUE", cpd["X-Apple-I-MD"]?.asString)
        assertEquals("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", cpd["X-Mme-Device-Id"]?.asString)
        assertEquals("complete", complete.plist["Request"]?.get("o")?.asString)
        assertEquals("apptokens", tokens.plist["Request"]?.get("o")?.asString)
    }

    @Test
    fun signsInWithTheHexPasswordProtocol() = FakeApple(protocol = "s2k_fo", password = "pässwörd ünïcode").use { apple ->
        assertTrue(auth(apple).signIn(apple.email, apple.password) is AuthResult.Success)
    }

    @Test
    fun aWrongPasswordIsReportedAsSuch() = FakeApple().use { apple ->
        try {
            auth(apple).signIn(apple.email, apple.password + "x")
            fail("signed in with the wrong password")
        } catch (error: AppleAuthException) {
            assertEquals(-20101, error.code)
            assertTrue(error.message!!, error.message!!.contains("the Apple ID or password is wrong"))
        }
    }

    @Test
    fun aCodeFromATrustedDeviceCanBeRetyped() = FakeApple().use { apple ->
        apple.secondFactor = "trustedDeviceSecondaryAuth"
        val auth = auth(apple)
        val pending = (auth.signIn(apple.email, apple.password) as AuthResult.TrustedDeviceCodeRequired).pending

        val push = apple.requests.single { it.path == "/tds" }
        assertEquals("GET", push.method)
        val identity = java.util.Base64.getEncoder().encodeToString("${apple.adsid}:${apple.idmsToken}".toByteArray())
        assertEquals(identity, push.headers["x-apple-identity-token"])
        assertEquals("17106176", push.headers["x-apple-i-md-rinfo"])
        assertEquals("OTP-VALUE", push.headers["x-apple-i-md"])
        assertEquals(GrandSlam.CLIENT_INFO, push.headers["x-mme-client-info"])

        try {
            auth.submitTrustedDeviceCode(pending, "000000")
            fail("a wrong code was accepted")
        } catch (error: AppleAuthException) {
            assertEquals(-21669, error.code)
        }
        val result = auth.submitTrustedDeviceCode(pending, "123456")
        assertTrue(result is AuthResult.Success)
        assertEquals("123456", apple.requests.last { it.path == "/validate" }.headers["security-code"])
    }

    @Test
    fun aCodeByTextMessage() = FakeApple().use { apple ->
        apple.secondFactor = "secondaryAuth"
        val auth = auth(apple)
        val needed = auth.signIn(apple.email, apple.password) as AuthResult.PhoneCodeRequired
        assertEquals(listOf(TrustedPhoneNumber(2, "+1 (•••) •••-••12")), needed.numbers)
        assertEquals("application/json", apple.requests.single { it.path == "/auth" }.headers["accept"])

        auth.requestPhoneCode(needed.pending, 2)
        val send = apple.requests.single { it.path == "/auth/verify/phone" }
        assertEquals("PUT", send.method)
        assertEquals(2, send.json.getJSONObject("phoneNumber").getInt("id"))
        assertEquals("sms", send.json.getString("mode"))

        try {
            auth.submitPhoneCode(needed.pending, 2, "111111")
            fail("a wrong code was accepted")
        } catch (error: AppleAuthException) {
            assertEquals(-21669, error.code)
        }
        assertTrue(auth.submitPhoneCode(needed.pending, 2, "654321") is AuthResult.Success)
    }

    @Test
    fun repairMeansSignedIn() = FakeApple().use { apple ->
        apple.secondFactor = "repair"
        assertTrue(auth(apple).signIn(apple.email, apple.password) is AuthResult.Success)
    }

    @Test
    fun aBusyServiceIsTriedAgain() = FakeApple().use { apple ->
        apple.initStatuses += listOf(429, 429)
        assertTrue(auth(apple).signIn(apple.email, apple.password) is AuthResult.Success)
        assertEquals(4, gs(apple).size) // three inits, one complete
    }

    @Test
    fun anErrorPageIsExplainedNotParsed() = FakeApple().use { apple ->
        apple.initStatuses += 503
        try {
            auth(apple).signIn(apple.email, apple.password)
            fail("signed in through an error page")
        } catch (error: AppleAuthException) {
            assertEquals(503, error.code)
            assertTrue(error.message!!, error.message!!.contains("HTTP 503 (Service Temporarily Unavailable)"))
        }
        assertEquals(1, gs(apple).size) // 503 is not retried
    }

    @Test
    fun aTamperedTokenIsRefused() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val iv = ByteArray(16).also(SecureRandom()::nextBytes)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD("XYZ".toByteArray())
            doFinal("hello".toByteArray())
        }
        val blob = "XYZ".toByteArray() + iv + sealed
        assertEquals("hello", String(AppleAuth.decryptGcm(blob, key)))
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            AppleAuth.decryptGcm(tampered, key)
            fail("a tampered token decrypted")
        } catch (error: AppleAuthException) {
            assertTrue(error.message!!, error.message!!.contains("did not decrypt"))
        }
    }

    private companion object {
        val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4" +
                "A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60" +
                "95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF" +
                "747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907" +
                "8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861" +
                "60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB" +
                "FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16
        )
        val G: BigInteger = BigInteger.valueOf(2)
        val k = BigInteger(1, sha256(pad(N) + pad(G)))

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        fun hmac(key: ByteArray, label: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(label.toByteArray())
        }

        fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        fun pad(value: BigInteger): ByteArray {
            val bytes = unsigned(value)
            return if (bytes.size >= 256) bytes else ByteArray(256 - bytes.size) + bytes
        }
    }
}
