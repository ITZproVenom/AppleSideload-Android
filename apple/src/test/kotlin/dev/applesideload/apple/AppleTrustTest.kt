package dev.applesideload.apple

import org.junit.Test
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleTrustTest {

    /** gsa.apple.com and its issuer as the server sent them in October 2026. */
    private val gsaChain: List<X509Certificate> by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/gsa.apple.com-chain.pem"))
        stream.use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            .map { it as X509Certificate }
    }

    /** Inside the captured leaf's validity, so the test does not expire with it. */
    private val whenCaptured = SimpleDateFormat("yyyy-MM-dd").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse("2026-07-01")!!

    private fun validate(chain: List<X509Certificate>, anchors: List<X509Certificate>) {
        val path = CertificateFactory.getInstance("X.509").generateCertPath(chain)
        val parameters = PKIXParameters(anchors.map { TrustAnchor(it, null) }.toSet()).apply {
            isRevocationEnabled = false
            date = whenCaptured
        }
        CertPathValidator.getInstance("PKIX").validate(path, parameters)
    }

    @Test
    fun theEmbeddedRootsAreApplesPublishedOnes() {
        val roots = AppleTrust.roots
        assertEquals(
            listOf("Apple Root CA", "Apple Root CA - G2", "Apple Root CA - G3"),
            roots.map { root ->
                root.subjectX500Principal.name.split(",").first { it.startsWith("CN=") }.removePrefix("CN=")
            }
        )
        assertEquals(
            listOf(
                "B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24",
                "C2:B9:B0:42:DD:57:83:0E:7D:11:7D:AC:55:AC:8A:E1:94:07:D3:8E:41:D8:8F:32:15:BC:3A:89:04:44:A0:50",
                "63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79"
            ),
            roots.map { AppleTrust.fingerprint(it.encoded) }
        )
        roots.forEach { root ->
            assertEquals(root.subjectX500Principal, root.issuerX500Principal)
            root.verify(root.publicKey)
        }
    }

    @Test
    fun theSignInServersChainEndsAtTheAppleRootCa() {
        assertEquals(2, gsaChain.size)
        assertTrue("gsa.apple.com" in gsaChain[0].subjectX500Principal.name)
        validate(gsaChain, AppleTrust.roots)
    }

    @Test
    fun withoutTheAppleRootCaTheSignInServerIsNotTrusted() {
        // What Android did: the newer roots alone do not anchor this chain.
        assertFailsWith<CertPathValidatorException> {
            validate(gsaChain, AppleTrust.roots.drop(1))
        }
    }

    @Test
    fun onlyApplesHostsUseTheEmbeddedRoots() {
        listOf("gsa.apple.com", "GSA.Apple.COM", "apple.com", "developerservices2.apple.com.").forEach {
            assertTrue(AppleTrust.appliesTo(it), it)
        }
        listOf(null, "", "evilapple.com", "apple.com.example.net", "ani.sidestore.io", "apple.co").forEach {
            assertFalse(AppleTrust.appliesTo(it), it.toString())
        }
    }

    private class Fixed(private val accept: Boolean, private val name: String) : X509TrustManager {
        var calls = 0
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            calls += 1
            if (!accept) throw CertificateException("$name rejects it")
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun engine(host: String) = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        .createSSLEngine(host, 443)

    @Test
    fun theSystemStoreIsAskedFirstAndTheFallbackOnlyWhenItRejects() {
        val chain = gsaChain.toTypedArray()
        val system = Fixed(accept = true, name = "system")
        val fallback = Fixed(accept = true, name = "apple")
        FallbackTrustManager(system, fallback, AppleTrust::appliesTo)
            .checkServerTrusted(chain, "ECDHE_RSA", engine("gsa.apple.com"))
        assertEquals(1, system.calls)
        assertEquals(0, fallback.calls)

        val rejecting = Fixed(accept = false, name = "system")
        FallbackTrustManager(rejecting, fallback, AppleTrust::appliesTo)
            .checkServerTrusted(chain, "ECDHE_RSA", engine("gsa.apple.com"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun otherHostsNeverReachTheFallback() {
        val fallback = Fixed(accept = true, name = "apple")
        val trust = FallbackTrustManager(Fixed(accept = false, name = "system"), fallback, AppleTrust::appliesTo)
        val error = assertFailsWith<CertificateException> {
            trust.checkServerTrusted(gsaChain.toTypedArray(), "ECDHE_RSA", engine("ani.sidestore.io"))
        }
        assertEquals("system rejects it", error.message)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun whenBothRejectTheErrorCarriesBothReasons() {
        val trust = FallbackTrustManager(
            Fixed(accept = false, name = "system"),
            Fixed(accept = false, name = "apple"),
            AppleTrust::appliesTo
        )
        val error = assertFailsWith<CertificateException> {
            trust.checkServerTrusted(gsaChain.toTypedArray(), "ECDHE_RSA", engine("gsa.apple.com"))
        }
        val message = error.message.orEmpty()
        assertTrue("system rejects it" in message && "apple rejects it" in message, message)
        assertEquals(1, error.suppressed.size)
    }

    @Test
    fun clientCertificatesAreNeverAccepted() {
        val trust = FallbackTrustManager(Fixed(true, "system"), Fixed(true, "apple"), AppleTrust::appliesTo)
        assertFailsWith<CertificateException> { trust.checkClientTrusted(gsaChain.toTypedArray(), "RSA") }
    }

    @Test
    fun theRealTrustManagerAndSocketFactoryBuild() {
        // Loads the JVM's own store as the "system" side; on a phone it is Android's.
        val issuers = AppleTrust.trustManager.acceptedIssuers.map { it.subjectX500Principal }
        AppleTrust.roots.forEach { assertTrue(it.subjectX500Principal in issuers) }
        assertTrue(AppleTrust.socketFactory.supportedCipherSuites.isNotEmpty())
    }
}
