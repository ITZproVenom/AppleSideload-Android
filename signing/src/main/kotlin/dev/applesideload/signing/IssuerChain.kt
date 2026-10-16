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
    ): X509Certificate? {
        val address = issuerUrl(certificate) ?: return null
        for (candidate in candidateUrls(address)) {
            val bytes = runCatching { download(candidate) }.getOrNull() ?: continue
            val issuer = runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            }.getOrNull() ?: continue
            // It must be the certificate that actually signed this one.
            val signedThis = runCatching { certificate.verify(issuer.publicKey) }.isSuccess
            if (signedThis) return issuer
        }
        return null
    }

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
