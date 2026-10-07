package dev.applesideload.apple

import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trust for Apple's own servers.
 *
 * Apple ID sign-in happens on gsa.apple.com, whose certificate comes from
 * Apple's private "Apple Server Authentication CA" under "Apple Root CA".
 * That root is not in Android's certificate store, so Android refused every
 * sign-in with "Unacceptable certificate: CN=Apple Root CA". The server does
 * send the root along, but a root is only trusted if the device already holds
 * it, and Android also turns down the SHA-1 signature that this 2006 root
 * signs itself with whenever it arrives from a server instead of the store.
 *
 * Apple publishes its roots at apple.com/certificateauthority. The three that
 * matter are embedded here, each checked against Apple's published SHA-256
 * fingerprint before it is used, and they become extra trust anchors for
 * apple.com hosts only. Android's own store is still asked first, so a host
 * it already trusts is verified exactly as before, and nothing else is
 * relaxed: the chain must still verify, be in date, and name the host.
 */
object AppleTrust {

    /** Whether [host] is one of Apple's, the only hosts these roots are used for. */
    fun appliesTo(host: String?): Boolean {
        val name = host?.lowercase()?.trimEnd('.') ?: return false
        return name == "apple.com" || name.endsWith(".apple.com")
    }

    /** Apple's roots, each verified against its published fingerprint. */
    val roots: List<X509Certificate> by lazy {
        val factory = CertificateFactory.getInstance("X.509")
        ROOTS.map { root ->
            val der = Base64.getDecoder().decode(root.base64)
            val actual = fingerprint(der)
            check(actual == root.sha256) {
                "the embedded ${root.name} certificate has fingerprint $actual, " +
                    "not Apple's published ${root.sha256}"
            }
            factory.generateCertificate(der.inputStream()) as X509Certificate
        }
    }

    /**
     * Android's store first, then Apple's roots for Apple's hosts.
     *
     * Built lazily: the system store is only loaded the first time an Apple
     * host is contacted.
     */
    val trustManager: X509ExtendedTrustManager by lazy {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            roots.forEachIndexed { index, root -> setCertificateEntry("apple-root-$index", root) }
        }
        FallbackTrustManager(
            system = platformTrustManager(null),
            fallback = platformTrustManager(store),
            fallbackApplies = ::appliesTo
        )
    }

    /** TLS sockets that verify with [trustManager]. */
    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    internal fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    private fun platformTrustManager(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("the platform offered no X.509 trust manager")
    }

    private class Root(val name: String, val sha256: String, val base64: String)

    private val ROOTS = listOf(
        // 2006, RSA 2048, valid until 2035. Issues the Apple Server Authentication CA behind gsa.apple.com.
        Root(
            name = "Apple Root CA",
            sha256 = "B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24",
            base64 =
                "MIIEuzCCA6OgAwIBAgIBAjANBgkqhkiG9w0BAQUFADBiMQswCQYDVQQGEwJVUzETMBEGA1UEChMK" +
                "QXBwbGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkxFjAUBgNV" +
                "BAMTDUFwcGxlIFJvb3QgQ0EwHhcNMDYwNDI1MjE0MDM2WhcNMzUwMjA5MjE0MDM2WjBiMQswCQYD" +
                "VQQGEwJVUzETMBEGA1UEChMKQXBwbGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlv" +
                "biBBdXRob3JpdHkxFjAUBgNVBAMTDUFwcGxlIFJvb3QgQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IB" +
                "DwAwggEKAoIBAQDkkakJH5HbHkdQ6wXtXnmELes2oldMVeyLGYne+Uts9QerIjAC6Bg++FAJ039B" +
                "qJj50cpmnCRrEdCju+QbKsMflZ56DKRHi1vUFjczy8QPTc4UadHJGXL1XQ7Vf1+b8iUDulWPTV0N" +
                "8WQ1IxVLFVkds5T39pyez1C6wVhQZ48ItCD3y6wsIG9wtj8BMIy3Q88PnT3zK0koGsj+zrW5Dtle" +
                "HNbLPbU6rfQPDgCSC7EhFi501TwN22IWq6NxkkdTVcGvL0Gz+PvjcM3mo0xFfh9Ma1CWQYnEdGIL" +
                "EINBhzOKgbEwWOxaBDKMaLOPHd5lc/9nXmW8Sdh2nzMUZaF3lMktAgMBAAGjggF6MIIBdjAOBgNV" +
                "HQ8BAf8EBAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUK9BpR5R2Cf70a40uQKb3R01/" +
                "CF4wHwYDVR0jBBgwFoAUK9BpR5R2Cf70a40uQKb3R01/CF4wggERBgNVHSAEggEIMIIBBDCCAQAG" +
                "CSqGSIb3Y2QFATCB8jAqBggrBgEFBQcCARYeaHR0cHM6Ly93d3cuYXBwbGUuY29tL2FwcGxlY2Ev" +
                "MIHDBggrBgEFBQcCAjCBthqBs1JlbGlhbmNlIG9uIHRoaXMgY2VydGlmaWNhdGUgYnkgYW55IHBh" +
                "cnR5IGFzc3VtZXMgYWNjZXB0YW5jZSBvZiB0aGUgdGhlbiBhcHBsaWNhYmxlIHN0YW5kYXJkIHRl" +
                "cm1zIGFuZCBjb25kaXRpb25zIG9mIHVzZSwgY2VydGlmaWNhdGUgcG9saWN5IGFuZCBjZXJ0aWZp" +
                "Y2F0aW9uIHByYWN0aWNlIHN0YXRlbWVudHMuMA0GCSqGSIb3DQEBBQUAA4IBAQBcNplMLXi37Yyb" +
                "3PN3m/J20ncwT8EfhYOFG5k9RzfyqZtAjizUsZAS2L70c5vu0mQPy3lPNNiiPvl4/2vIB+x9OYOL" +
                "UyDTOMSxv5pPCmv/K/xZpwUJfBdAVhEedNO3iyM7R6PVbyTi69G3cN8PReEnyvFteO3ntRcXqNx+" +
                "IjXKJdXZD9Zr1KIkIxH3oayPc4FgxhtbCS+SsvhESPBgOJ4V9T0mZyCKM2r3DYLP3uujL/lTaltk" +
                "wGMzd/c6ByxW69oPIQ7aunMZT7XZNn/Bh1XZp5m5MkL72NVxnn6hUrcbvZNCJBIqxw8dtk2cXmPI" +
                "S4AXUKqK1drk/NAJBzewdXUh"
        ),
        // 2014, RSA 4096, valid until 2039.
        Root(
            name = "Apple Root CA - G2",
            sha256 = "C2:B9:B0:42:DD:57:83:0E:7D:11:7D:AC:55:AC:8A:E1:94:07:D3:8E:41:D8:8F:32:15:BC:3A:89:04:44:A0:50",
            base64 =
                "MIIFkjCCA3qgAwIBAgIIAeDltYNno+AwDQYJKoZIhvcNAQEMBQAwZzEbMBkGA1UEAwwSQXBwbGUg" +
                "Um9vdCBDQSAtIEcyMSYwJAYDVQQLDB1BcHBsZSBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0eTETMBEG" +
                "A1UECgwKQXBwbGUgSW5jLjELMAkGA1UEBhMCVVMwHhcNMTQwNDMwMTgxMDA5WhcNMzkwNDMwMTgx" +
                "MDA5WjBnMRswGQYDVQQDDBJBcHBsZSBSb290IENBIC0gRzIxJjAkBgNVBAsMHUFwcGxlIENlcnRp" +
                "ZmljYXRpb24gQXV0aG9yaXR5MRMwEQYDVQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzCCAiIw" +
                "DQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANgREkhI2imKScUcx+xuM23+TfvgHN6sXuI2pyT5" +
                "f1BrTM65MFQn5bPW7SXmMLYFN14UIhHF6Kob0vuy0gmVOKTvKkmMXT5xZgM4+xb1hYjkWpIMBDLy" +
                "yED7Ul+f9sDx47pFoFDVEovy3d6RhiPw9bZyLgHaC/YuOQhfGaFjQQscp5TBhsRTL3b2CtcM0YM/" +
                "GlMZ81fVJ3/8E7j4ko380yhDPLVoACVdJ2LT3VXdRCCQgzWTxb+4Gftr49wIQuavbfqeQMpOhYV4" +
                "SbHXw8EwOTKrfl+q04tvny0aIWhwZ7Oj8ZhBbZF8+NfbqOdfIRqMM78xdLe40fTgIvS/cjTf94FN" +
                "cX1RoeKz8NMoFnNvzcytN31O661A4T+B/fc9Cj6i8b0xlilZ3MIZgIxbdMYs0xBTJh0UT8TUgWY8" +
                "h2czJxQI6bR3hDRSj4n4aJgXv8O7qhOTH11UL6jHfPsNFL4VPSQ08prcdUFmIrQB1guvkJ4M6mL4" +
                "m1k8COKWNORj3rw31OsMiANDC1CvoDTdUE0V+1ok2Az6DGOeHwOx4e7hqkP0ZmUoNwIx7wHHHtHM" +
                "n23KVDpA287PT0aLSmWaasZobNfMmRtHsHLDd4/E92GcdB/O/WuhwpyUgquUoue9G7q5cDmVF8Up" +
                "8zlYNPXEpMZ7YLlmQ1A/bmH8DvmGqmAMQ0uVAgMBAAGjQjBAMB0GA1UdDgQWBBTEmRNsGAPCe8Cj" +
                "oA1/coB6HHcmjTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjANBgkqhkiG9w0BAQwF" +
                "AAOCAgEAUabz4vS4PZO/Lc4Pu1vhVRROTtHlznldgX/+tvCHM/jvlOV+3Gp5pxy+8JS3ptEwnMgN" +
                "CnWefZKVfhidfsJxaXwU6s+DDuQUQp50DhDNqxq6EWGBeNjxtUVAeKuowM77fWM3aPbn+6/Gw0vs" +
                "HzYmE1SGlHKy6gLti23kDKaQwFd1z4xCfVzmMX3zybKSaUYOiPjjLUKyOKimGY3xn83uamW8GrAl" +
                "vacp/fQ+onVJv57byfenHmOZ4VxG/5IFjPoeIPmGlFYl5bRXOJ3riGQUIUkhOb9iZqmxospvPyFg" +
                "xYnURTbImHy99v6ZSYA7LNKmp4gDBDEZt7Y6YUX6yfIjyGNzv1aJMbDZfGKnexWoiIqrOEDCzBL/" +
                "FePwN983csvMmOa/orz6JopxVtfnJBtIRD6e/J/JzBrsQzwBvDR4yGn1xuZW7AYJNpDrFEobXsmI" +
                "I9oDMJELuDY++ee1KG++P+w8j2Ud5cAeh6Squpj9kuNsJnfdBrRkBof0Tta6SqoWqPQFZ2aWuuJV" +
                "ecMsXUmPgEkrihLHdoBR37q9ZV0+N0djMenl9MU/S60EinpxLK8JQzcPqOMyT/RFtm2XNuyE9QoB" +
                "6he7hY1Ck3DDUOUUi78/w0EP3SIEIwiKum1xRKtzCTrJ+VKACd+66eYWyi4uTLLT3OUEVLLUNIAy" +
                "tbwPF+E="
        ),
        // 2014, ECDSA P-384, valid until 2039.
        Root(
            name = "Apple Root CA - G3",
            sha256 = "63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79",
            base64 =
                "MIICQzCCAcmgAwIBAgIILcX8iNLFS5UwCgYIKoZIzj0EAwMwZzEbMBkGA1UEAwwSQXBwbGUgUm9v" +
                "dCBDQSAtIEczMSYwJAYDVQQLDB1BcHBsZSBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0eTETMBEGA1UE" +
                "CgwKQXBwbGUgSW5jLjELMAkGA1UEBhMCVVMwHhcNMTQwNDMwMTgxOTA2WhcNMzkwNDMwMTgxOTA2" +
                "WjBnMRswGQYDVQQDDBJBcHBsZSBSb290IENBIC0gRzMxJjAkBgNVBAsMHUFwcGxlIENlcnRpZmlj" +
                "YXRpb24gQXV0aG9yaXR5MRMwEQYDVQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzB2MBAGByqG" +
                "SM49AgEGBSuBBAAiA2IABJjpLz1AcqTtkyJygRMc3RCV8cWjTnHcFBbZDuWmBSp3ZHtfTjjTuxxE" +
                "tX/1H7YyYl3J6YRbTzBPEVoA/VhYDKX1DyxNB0cTddqXl5dvMVztK517IDvYuVTZXpmkOlEKMaNC" +
                "MEAwHQYDVR0OBBYEFLuw3qFYM4iapIqZ3r6966/ayySrMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0P" +
                "AQH/BAQDAgEGMAoGCCqGSM49BAMDA2gAMGUCMQCD6cHEFl4aXTQY2e3v9GwOAEZLuN+yRhHFD/3m" +
                "eoyhpmvOwgPUnPWTxnS4at+qIxUCMG1mihDK1A3UT82NQz60imOlM27jbdoXt2QfyFMm+YhidDkL" +
                "F1vLUagM6BgD56KyKA=="
        )
    )
}

/**
 * Verifies a server with [system] and, only when that fails and the server is
 * one [fallbackApplies] to, with [fallback].
 *
 * Both are complete platform trust managers, so each runs every check it
 * normally would; this only decides which anchors may end the chain. It never
 * accepts a client certificate, since the app is only ever the client here.
 */
internal class FallbackTrustManager(
    private val system: X509TrustManager,
    private val fallback: X509TrustManager,
    private val fallbackApplies: (String?) -> Boolean
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        verify(host = null, hostKnown = false, { system.checkServerTrusted(chain, authType) }) {
            fallback.checkServerTrusted(chain, authType)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val host = (socket as? SSLSocket)?.handshakeSession?.peerHost
        verify(host, host != null, { system.check(chain, authType, socket) }) {
            fallback.check(chain, authType, socket)
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        val host = engine?.handshakeSession?.peerHost ?: engine?.peerHost
        verify(host, host != null, { system.check(chain, authType, engine) }) {
            fallback.check(chain, authType, engine)
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = refuseClient()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        refuseClient()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        refuseClient()

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + fallback.acceptedIssuers

    /**
     * Runs [first]; if it rejects the chain, runs [second] unless the host is
     * known not to be one the fallback is for. When both reject, the error
     * says so and carries both reasons.
     */
    private inline fun verify(host: String?, hostKnown: Boolean, first: () -> Unit, second: () -> Unit) {
        try {
            first()
        } catch (systemError: CertificateException) {
            if (hostKnown && !fallbackApplies(host)) throw systemError
            try {
                second()
            } catch (fallbackError: CertificateException) {
                throw CertificateException(
                    "the server certificate is trusted neither by Android " +
                        "(${systemError.message}) nor by Apple's published roots " +
                        "(${fallbackError.message})",
                    systemError
                ).apply { addSuppressed(fallbackError) }
            }
        }
    }

    private fun refuseClient(): Nothing =
        throw CertificateException("this app does not accept client certificates")

    private fun X509TrustManager.check(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        if (this is X509ExtendedTrustManager) checkServerTrusted(chain, authType, socket)
        else checkServerTrusted(chain, authType)
    }

    private fun X509TrustManager.check(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        if (this is X509ExtendedTrustManager) checkServerTrusted(chain, authType, engine)
        else checkServerTrusted(chain, authType)
    }
}
