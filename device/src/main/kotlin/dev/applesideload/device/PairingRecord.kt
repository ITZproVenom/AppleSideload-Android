package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.UUID

/**
 * The credentials a host needs to talk to a paired iPhone.
 *
 * Pairing is not a handshake that can be repeated on demand: the device
 * stores the host certificate the first time the user taps Trust, and every
 * later session proves possession of the matching private key. Losing this
 * record means pairing again with the device unlocked, so it is written to
 * disk encrypted and never regenerated silently.
 *
 * The field names are Apple's, because the same dictionary is sent back to
 * lockdown verbatim during ValidatePair and is the format libimobiledevice,
 * go-ios and pymobiledevice3 all read. Keeping them identical means a record
 * made here is not a private dialect.
 */
data class PairingRecord(
    val deviceCertificatePem: ByteArray,
    val hostCertificatePem: ByteArray,
    val rootCertificatePem: ByteArray,
    val hostPrivateKeyPem: ByteArray,
    val rootPrivateKeyPem: ByteArray,
    val hostId: String,
    val systemBuid: String,
    val udid: String,
    val escrowBag: ByteArray? = null,
    val wifiMacAddress: String? = null
) {

    fun deviceCertificate(): X509Certificate = readCertificate(deviceCertificatePem, "device")

    fun hostCertificate(): X509Certificate = readCertificate(hostCertificatePem, "host")

    fun rootCertificate(): X509Certificate = readCertificate(rootCertificatePem, "root")

    fun hostPrivateKey(): PrivateKey = readPrivateKey(hostPrivateKeyPem, "host")

    fun rootPrivateKey(): PrivateKey = readPrivateKey(rootPrivateKeyPem, "root")

    /**
     * The dictionary lockdown expects.
     *
     * [withPrivateKeys] is false for Pair and ValidatePair - the device has no
     * business seeing host private keys, and libimobiledevice does not send
     * them either - and true only when the record is being stored.
     */
    fun toPlist(withPrivateKeys: Boolean): Plist.Dict {
        val fields = linkedMapOf<String, Plist>(
            "DeviceCertificate" to Plist.Data(deviceCertificatePem),
            "HostCertificate" to Plist.Data(hostCertificatePem),
            "RootCertificate" to Plist.Data(rootCertificatePem),
            "HostID" to Plist.Str(hostId),
            "SystemBUID" to Plist.Str(systemBuid)
        )
        if (withPrivateKeys) {
            fields["HostPrivateKey"] = Plist.Data(hostPrivateKeyPem)
            fields["RootPrivateKey"] = Plist.Data(rootPrivateKeyPem)
            fields["UDID"] = Plist.Str(udid)
        }
        escrowBag?.let { fields["EscrowBag"] = Plist.Data(it) }
        wifiMacAddress?.let { fields["WiFiMACAddress"] = Plist.Str(it) }
        return Plist.Dict(fields)
    }

    override fun equals(other: Any?): Boolean =
        other is PairingRecord && other.udid == udid && other.hostId == hostId &&
            other.hostCertificatePem.contentEquals(hostCertificatePem)

    override fun hashCode(): Int = udid.hashCode() * 31 + hostId.hashCode()

    override fun toString(): String =
        "PairingRecord(udid=$udid, hostId=$hostId, escrowBag=${escrowBag != null})"

    companion object {
        /** Apple's own validity window for these certificates: ten years. */
        private const val VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000

        /**
         * Mints the three certificates pairing needs.
         *
         * [devicePublicKeyPem] is the PEM RSA public key lockdown returns for
         * the DevicePublicKey value. The host generates its own root, signs a
         * host certificate with it, and then signs the device's public key
         * with the same root - that last certificate is what the device keeps
         * and presents during the TLS handshake of every later session.
         *
         * This mirrors libimobiledevice's pair_record_generate_keys exactly,
         * including the CA extension on the root and the key usage bits,
         * because lockdown rejects pairing requests whose certificates do not
         * look like this.
         */
        fun generate(
            devicePublicKeyPem: ByteArray,
            udid: String,
            systemBuid: String
        ): PairingRecord {
            val random = SecureRandom()
            val rootKey = rsaKeyPair(random)
            val hostKey = rsaKeyPair(random)
            val devicePublicKey = Pem.readPublicKey(devicePublicKeyPem)

            val notBefore = Date(System.currentTimeMillis() - 60_000)
            val notAfter = Date(System.currentTimeMillis() + VALIDITY_MS)

            val rootName = X500Name("CN=Root Certification Authority")
            val rootCert = JcaX509v3CertificateBuilder(
                rootName,
                BigInteger.ONE,
                notBefore,
                notAfter,
                rootName,
                rootKey.public
            ).apply {
                addExtension(Extension.basicConstraints, true, BasicConstraints(0))
                addExtension(
                    Extension.subjectKeyIdentifier,
                    false,
                    JcaX509ExtensionUtils().createSubjectKeyIdentifier(rootKey.public)
                )
            }.signedBy(rootKey.private)

            val hostCert = JcaX509v3CertificateBuilder(
                rootName,
                BigInteger.TWO,
                notBefore,
                notAfter,
                X500Name("CN=Host Certificate"),
                hostKey.public
            ).apply {
                addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                addExtension(
                    Extension.keyUsage,
                    true,
                    KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
                )
                addExtension(
                    Extension.subjectKeyIdentifier,
                    false,
                    JcaX509ExtensionUtils().createSubjectKeyIdentifier(hostKey.public)
                )
            }.signedBy(rootKey.private)

            val deviceCert = X509v3CertificateBuilder(
                rootName,
                BigInteger.valueOf(3),
                notBefore,
                notAfter,
                X500Name("CN=Device Certificate"),
                SubjectPublicKeyInfo.getInstance(devicePublicKey.encoded)
            ).apply {
                addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                addExtension(
                    Extension.keyUsage,
                    true,
                    KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
                )
                addExtension(
                    Extension.subjectKeyIdentifier,
                    false,
                    JcaX509ExtensionUtils().createSubjectKeyIdentifier(devicePublicKey)
                )
            }.signedBy(rootKey.private)

            Log.i(LogTag.PAIR, "generated a pairing identity for $udid")
            return PairingRecord(
                deviceCertificatePem = Pem.write("CERTIFICATE", deviceCert.encoded),
                hostCertificatePem = Pem.write("CERTIFICATE", hostCert.encoded),
                rootCertificatePem = Pem.write("CERTIFICATE", rootCert.encoded),
                hostPrivateKeyPem = Pem.write("PRIVATE KEY", hostKey.private.encoded),
                rootPrivateKeyPem = Pem.write("PRIVATE KEY", rootKey.private.encoded),
                hostId = UUID.randomUUID().toString().uppercase(),
                systemBuid = systemBuid,
                udid = udid
            )
        }

        fun fromPlist(plist: Plist): PairingRecord {
            fun data(key: String): ByteArray = plist[key]?.asData
                ?: throw DeviceException(
                    operation = "reading a pairing record",
                    reason = "the stored record has no $key"
                )
            return PairingRecord(
                deviceCertificatePem = data("DeviceCertificate"),
                hostCertificatePem = data("HostCertificate"),
                rootCertificatePem = data("RootCertificate"),
                hostPrivateKeyPem = data("HostPrivateKey"),
                rootPrivateKeyPem = data("RootPrivateKey"),
                hostId = plist["HostID"]?.asString
                    ?: throw DeviceException(
                        operation = "reading a pairing record",
                        reason = "the stored record has no HostID"
                    ),
                systemBuid = plist["SystemBUID"]?.asString
                    ?: throw DeviceException(
                        operation = "reading a pairing record",
                        reason = "the stored record has no SystemBUID"
                    ),
                udid = plist["UDID"]?.asString.orEmpty(),
                escrowBag = plist["EscrowBag"]?.asData,
                wifiMacAddress = plist["WiFiMACAddress"]?.asString
            )
        }

        private fun rsaKeyPair(random: SecureRandom): KeyPair =
            KeyPairGenerator.getInstance("RSA").run {
                initialize(2048, random)
                generateKeyPair()
            }

        private fun X509v3CertificateBuilder.signedBy(key: PrivateKey): X509Certificate {
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(key)
            return JcaX509CertificateConverter().getCertificate(build(signer))
        }

        private fun readCertificate(pem: ByteArray, which: String): X509Certificate = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
        } catch (error: Exception) {
            throw DeviceException(
                operation = "reading the $which certificate",
                reason = "the stored certificate is not valid X.509 PEM: ${error.message}",
                alternative = "pair this device again",
                cause = error
            )
        }

        private fun readPrivateKey(pem: ByteArray, which: String): PrivateKey = try {
            val der = Pem.readBody(pem)
            java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (error: Exception) {
            throw DeviceException(
                operation = "reading the $which private key",
                reason = "the stored key is not a PKCS#8 RSA key: ${error.message}",
                alternative = "pair this device again",
                cause = error
            )
        }
    }
}

/**
 * PEM in and out.
 *
 * Lockdown hands over keys and certificates as PEM text inside plist data, so
 * this has to exist regardless of what the platform offers.
 */
object Pem {
    fun write(label: String, der: ByteArray): ByteArray {
        val body = java.util.Base64.getEncoder().encodeToString(der)
        return buildString {
            append("-----BEGIN ").append(label).append("-----\n")
            body.chunked(64).forEach { append(it).append('\n') }
            append("-----END ").append(label).append("-----\n")
        }.toByteArray()
    }

    /** The DER inside a PEM block, whatever the label is. */
    fun readBody(pem: ByteArray): ByteArray {
        val text = String(pem)
        val body = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")
            .replace(" ", "")
        return java.util.Base64.getDecoder().decode(body)
    }

    /**
     * Reads the device public key.
     *
     * Lockdown returns either an RSA public key block (PKCS#1, the usual case)
     * or a SubjectPublicKeyInfo block, and the two are not interchangeable, so
     * both are handled instead of assuming.
     */
    fun readPublicKey(pem: ByteArray): PublicKey {
        val der = readBody(pem)
        val label = String(pem).lineSequence().firstOrNull { it.startsWith("-----BEGIN") }.orEmpty()
        val spki = if (label.contains("RSA PUBLIC KEY")) {
            val rsa = org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(der)
            SubjectPublicKeyInfo(
                org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                    org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.rsaEncryption,
                    org.bouncycastle.asn1.DERNull.INSTANCE
                ),
                rsa
            ).encoded
        } else {
            der
        }
        return try {
            java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(spki))
        } catch (error: Exception) {
            throw DeviceException(
                operation = "reading the device public key",
                reason = "lockdown returned a key this app cannot parse: ${error.message}",
                cause = error
            )
        }
    }
}
