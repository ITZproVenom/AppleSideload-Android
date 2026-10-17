package dev.applesideload.signing

import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The certificate that issued a development certificate.
 *
 * iOS checks the chain from the signature up to Apple's root, and the
 * intermediate (Apple Worldwide Developer Relations) is not something it
 * fetches while installing, so it travels inside the signature, as AltSign and
 * zsign embed it. The certificate names its issuer's address in its own
 * Authority Information Access extension, so that is where it is read from.
 */
object IssuerChain {

    // Apple's published WWDR G3 intermediate and Apple Root CA. The issuer
    // AIA fetch is attempted first; these pinned public certificates let
    // signing continue when the device can't reach Apple's certificate host.
    private val bundledWwdrG3 = decodeCertificate(
        "MIIEUTCCAzmgAwIBAgIQfK9pCiW3Of57m0R6wXjF7jANBgkqhkiG9w0BAQsFADBiMQswCQYDVQQGEwJVUzETMBEGA1UEChMKQXBwbGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkxFjAUBgNVBAMTDUFwcGxlIFJvb3QgQ0EwHhcNMjAwMjE5MTgxMzQ3WhcNMzAwMjIwMDAwMDAwWjB1MUQwQgYDVQQDDDtBcHBsZSBXb3JsZHdpZGUgRGV2ZWxvcGVyIFJlbGF0aW9ucyBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0eTELMAkGA1UECwwCRzMxEzARBgNVBAoMCkFwcGxlIEluYy4xCzAJBgNVBAYTAlVTMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2PWJ/KhZC4fHTJEuLVaQ03gdpDDppUjvC0O/LYT7JF1FG+XrWTYSXFRknmxiLbTGl8rMPPbWBpH85QKmHGq0edVny6zpPwcR4YS8Rx1mjjmi6LRJ7TrS4RBgeo6TjMrA2gzAg9Dj+ZHWp4zIwXPirkbRYp2SqJBgN31ols2N4Pyb+ni743uvLRfdW/6AWSN1F7gSwe0b5TTO/iK1nkmw5VW/j4SiPKi6xYaVFuQAyZ8D0MyzOhZ71gVcnetHrg21LYwOaU1A0EtMOwSejSGxrC5DVDDOwYqGlJhL32oNP/77HK6XF8J4CjDgXx9UO0m3JQAaN4LSVpelUkl8YDib7wIDAQABo4HvMIHsMBIGA1UdEwEB/wQIMAYBAf8CAQAwHwYDVR0jBBgwFoAUK9BpR5R2Cf70a40uQKb3R01/CF4wRAYIKwYBBQUHAQEEODA2MDQGCCsGAQUFBzABhihodHRwOi8vb2NzcC5hcHBsZS5jb20vb2NzcDAzLWFwcGxlcm9vdGNhMC4GA1UdHwQnMCUwI6AhoB+GHWh0dHA6Ly9jcmwuYXBwbGUuY29tL3Jvb3QuY3JsMB0GA1UdDgQWBBQJ/sAVkPmvZAqSErkmKGMMl+ynsjAOBgNVHQ8BAf8EBAMCAQYwEAYKKoZIhvdjZAYCAQQCBQAwDQYJKoZIhvcNAQELBQADggEBAK1lE+j24IF3RAJHQr5fpTkg6mKp/cWQyXMT1Z6b0KoPjY3L7QHPbChAW8dVJEH4/M/BtSPp3Ozxb8qAHXfCxGFJJWevD8o5Ja3T43rMMygNDi6hV0Bz+uZcrgZRKe3jhQxPYdwyFot30ETKXXIDMUacrptAGvr04NM++i+MZp+XxFRZ79JI9AeZSWBZGcfdlNHAwWx/eCHvDOs7bJmCS1JgOLU5gm3sUjFTvg+RTElJdI+mUcuER04ddSduvfnSXPN/wmwLCTbiZOTCNwMUGdXqapSqqdv+9poIZ4vvK7iqF0mDr8/LvOnP6pVxsLRFoszlh6oKw0E6eVzaUDSdlTs="
    )
    private val bundledRoot = decodeCertificate(
        "MIIEuzCCA6OgAwIBAgIBAjANBgkqhkiG9w0BAQUFADBiMQswCQYDVQQGEwJVUzETMBEGA1UEChMKQXBwbGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkxFjAUBgNVBAMTDUFwcGxlIFJvb3QgQ0EwHhcNMDYwNDI1MjE0MDM2WhcNMzUwMjA5MjE0MDM2WjBiMQswCQYDVQQGEwJVUzETMBEGA1UEChMKQXBwbGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkxFjAUBgNVBAMTDUFwcGxlIFJvb3QgQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDkkakJH5HbHkdQ6wXtXnmELes2oldMVeyLGYne+Uts9QerIjAC6Bg++FAJ039BqJj50cpmnCRrEdCju+QbKsMflZ56DKRHi1vUFjczy8QPTc4UadHJGXL1XQ7Vf1+b8iUDulWPTV0N8WQ1IxVLFVkds5T39pyez1C6wVhQZ48ItCD3y6wsIG9wtj8BMIy3Q88PnT3zK0koGsj+zrW5DtleHNbLPbU6rfQPDgCSC7EhFi501TwN22IWq6NxkkdTVcGvL0Gz+PvjcM3mo0xFfh9Ma1CWQYnEdGILEINBhzOKgbEwWOxaBDKMaLOPHd5lc/9nXmW8Sdh2nzMUZaF3lMktAgMBAAGjggF6MIIBdjAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUK9BpR5R2Cf70a40uQKb3R01/CF4wHwYDVR0jBBgwFoAUK9BpR5R2Cf70a40uQKb3R01/CF4wggERBgNVHSAEggEIMIIBBDCCAQAGCSqGSIb3Y2QFATCB8jAqBggrBgEFBQcCARYeaHR0cHM6Ly93d3cuYXBwbGUuY29tL2FwcGxlY2EvMIHDBggrBgEFBQcCAjCBthqBs1JlbGlhbmNlIG9uIHRoaXMgY2VydGlmaWNhdGUgYnkgYW55IHBhcnR5IGFzc3VtZXMgYWNjZXB0YW5jZSBvZiB0aGUgdGhlbiBhcHBsaWNhYmxlIHN0YW5kYXJkIHRlcm1zIGFuZCBjb25kaXRpb25zIG9mIHVzZSwgY2VydGlmaWNhdGUgcG9saWN5IGFuZCBjZXJ0aWZpY2F0aW9uIHByYWN0aWNlIHN0YXRlbWVudHMuMA0GCSqGSIb3DQEBBQUAA4IBAQBcNplMLXi37Yyb3PN3m/J20ncwT8EfhYOFG5k9RzfyqZtAjizUsZAS2L70c5vu0mQPy3lPNNiiPvl4/2vIB+x9OYOLUyDTOMSxv5pPCmv/K/xZpwUJfBdAVhEedNO3iyM7R6PVbyTi69G3cN8PReEnyvFteO3ntRcXqNx+IjXKJdXZD9Zr1KIkIxH3oayPc4FgxhtbCS+SsvhESPBgOJ4V9T0mZyCKM2r3DYLP3uujL/lTaltkwGMzd/c6ByxW69oPIQ7aunMZT7XZNn/Bh1XZp5m5MkL72NVxnn6hUrcbvZNCJBIqxw8dtk2cXmPIS4AXUKqK1drk/NAJBzewdXUh"
    )

    internal fun bundledFallbackChain(): List<X509Certificate> = listOf(bundledWwdrG3, bundledRoot)

    private fun decodeCertificate(encoded: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(encoded))) as X509Certificate

    /** The issuer's address from the certificate, or null if it names none. */
    fun issuerUrl(certificate: X509Certificate): String? {
        val extension = certificate.getExtensionValue(Extension.authorityInfoAccess.id) ?: return null
        val access = AuthorityInformationAccess.getInstance(ASN1OctetString.getInstance(extension).octets)
        return access.accessDescriptions
            .firstOrNull {
                it.accessMethod == AccessDescription.id_ad_caIssuers &&
                    it.accessLocation.tagNo == GeneralName.uniformResourceIdentifier
            }
            ?.let { (it.accessLocation.name as ASN1String).string }
    }

    /** Only Apple's own certificate host is contacted, and over HTTPS first. */
    fun candidateUrls(url: String): List<String> {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return emptyList()
        val host = parsed.host.lowercase()
        if (host != "apple.com" && !host.endsWith(".apple.com")) return emptyList()
        if (parsed.protocol != "http" && parsed.protocol != "https") return emptyList()
        val secure = "https://" + host + parsed.file
        return listOf(secure, "http://" + host + parsed.file).distinct()
    }

    /**
     * Downloads the issuer. [download] returns the certificate bytes for an
     * address, or null; it is a parameter so the lookup can be tested.
     */
    fun fetch(
        certificate: X509Certificate,
        download: (String) -> ByteArray? = ::httpGet
    ): List<X509Certificate> {
        val address = issuerUrl(certificate)
        val issuer = address?.let { candidateAddress ->
            candidateUrls(candidateAddress).firstNotNullOfOrNull { candidate ->
                val bytes = runCatching { download(candidate) }.getOrNull() ?: return@firstNotNullOfOrNull null
                val candidateCert = runCatching {
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
                }.getOrNull() ?: return@firstNotNullOfOrNull null
                candidateCert.takeIf { verifies(certificate, it) }
            }
        } ?: bundledWwdrG3.takeIf { verifies(certificate, it) }
        if (issuer == null) return emptyList()

        val chain = mutableListOf(issuer)
        // This root is included in Apple's system trust store and, like
        // SideInstaller, travels in the CMS for offline certificate-chain validation.
        if (issuer.issuerX500Principal == bundledRoot.subjectX500Principal && verifies(issuer, bundledRoot)) {
            chain += bundledRoot
        }
        return chain
    }

    private fun verifies(certificate: X509Certificate, issuer: X509Certificate): Boolean =
        runCatching { certificate.verify(issuer.publicKey) }.isSuccess

    private fun httpGet(address: String): ByteArray? {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        return try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { it.readNBytesCapped(64 * 1024) }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCapped(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw java.io.IOException("the certificate is too large")
        }
        return out.toByteArray()
    }
}
