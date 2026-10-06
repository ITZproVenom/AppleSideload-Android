package dev.applesideload.signing

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The private key and certificate a signature is made with.
 *
 * The key is generated on the phone and never leaves it. Apple only ever
 * sees the certificate signing request, which is the public half, and
 * returns a certificate; that is exactly how Xcode does it on a Mac.
 */
class SigningIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate
) {
    /** The ten character team identifier Apple puts in the certificate's OU. */
    val teamId: String
        get() = Regex("OU=([A-Z0-9]{10})").find(certificate.subjectX500Principal.name)
            ?.groupValues?.get(1).orEmpty()

    val commonName: String
        get() = Regex("CN=([^,]+)").find(certificate.subjectX500Principal.name)
            ?.groupValues?.get(1).orEmpty()

    val expiresAt: Long get() = certificate.notAfter.time

    companion object {
        /**
         * Makes a key pair and the request Apple signs.
         *
         * Returns the private key and the PEM request; the private key has
         * to be kept, because a certificate is useless without the key it
         * was issued for.
         */
        fun createCertificateRequest(commonName: String = "AppleSideload"): Pair<KeyPair, String> {
            val keyPair = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
            val builder = JcaPKCS10CertificationRequestBuilder(
                X500Name("CN=$commonName"),
                keyPair.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val csr = builder.build(signer)
            val pem = buildString {
                append("-----BEGIN CERTIFICATE REQUEST-----\n")
                java.util.Base64.getEncoder().encodeToString(csr.encoded)
                    .chunked(64).forEach { append(it).append('\n') }
                append("-----END CERTIFICATE REQUEST-----\n")
            }
            Log.i(LogTag.SIGN, "generated a signing key and certificate request on this device")
            return keyPair to pem
        }

        /** Rebuilds an identity from a stored key and the certificate Apple returned. */
        fun from(privateKeyDer: ByteArray, certificateDer: ByteArray): SigningIdentity {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
            return SigningIdentity(key, certificate)
        }
    }
}
