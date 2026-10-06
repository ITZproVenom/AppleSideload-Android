package dev.applesideload.signing

import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import org.bouncycastle.cms.CMSSignedData
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * An embedded.mobileprovision file.
 *
 * The device checks three things against this: that the signature on the app
 * was made by a certificate the profile lists, that the device's UDID is in
 * the profile, and that the entitlements the app claims are a subset of the
 * ones the profile allows. The file itself is a property list inside a CMS
 * envelope signed by Apple.
 */
class ProvisioningProfile(val raw: ByteArray) {

    val plist: Plist = parse(raw)

    val uuid: String = plist["UUID"]?.asString.orEmpty()
    val name: String = plist["Name"]?.asString.orEmpty()
    val teamIdentifier: String =
        plist["TeamIdentifier"]?.asList?.firstOrNull()?.asString
            ?: plist["Entitlements"]?.get("com.apple.developer.team-identifier")?.asString
            ?: ""
    val applicationIdentifier: String =
        plist["Entitlements"]?.get("application-identifier")?.asString.orEmpty()

    /** The identifier without the team prefix, which is the bundle id. */
    val bundleIdentifier: String = applicationIdentifier.substringAfter('.', "")

    val expirationDate: Date? = (plist["ExpirationDate"] as? Plist.Stamp)?.date

    val provisionedDevices: List<String> =
        plist["ProvisionedDevices"]?.asList?.mapNotNull { it.asString }.orEmpty()

    val entitlements: Plist = plist["Entitlements"] ?: Plist.dict()

    /** The certificates this profile will accept a signature from. */
    val certificates: List<X509Certificate> =
        plist["DeveloperCertificates"]?.asList?.mapNotNull { entry ->
            entry.asData?.let { der ->
                runCatching {
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                }.getOrNull()
            }
        }.orEmpty()

    val isExpired: Boolean get() = expirationDate?.before(Date()) == true

    /** Days left, which matters because a free profile only lasts seven. */
    val daysRemaining: Int
        get() = expirationDate?.let {
            ((it.time - System.currentTimeMillis()) / 86_400_000L).toInt()
        } ?: 0

    fun covers(udid: String): Boolean =
        provisionedDevices.any { it.equals(udid, ignoreCase = true) }

    fun matches(certificate: X509Certificate): Boolean =
        certificates.any { it.serialNumber == certificate.serialNumber }

    fun entitlementsXml(): ByteArray = XmlPlist.write(entitlements)

    companion object {
        /**
         * Unwraps the CMS envelope.
         *
         * Apple signs the profile, and that signature is not checked here:
         * the device checks it, and a profile that fails there is rejected
         * at install time with a clear error.
         */
        fun parse(raw: ByteArray): Plist {
            val content = runCatching {
                (CMSSignedData(raw).signedContent?.content as? ByteArray)
            }.getOrNull()
            if (content != null) return PlistReader.parse(content)

            // Some tools hand over the bare plist, so that is accepted too.
            return runCatching { PlistReader.parse(raw) }.getOrElse {
                throw SigningException(
                    operation = "reading the provisioning profile",
                    reason = "the file is neither a signed profile nor a property list"
                )
            }
        }
    }
}

/** Every failure in the signing engine, with enough detail to act on. */
class SigningException(
    val operation: String,
    val reason: String,
    val limitation: String? = null,
    val alternative: String? = null,
    cause: Throwable? = null
) : Exception(
    buildString {
        append(operation)
        append(" failed: ")
        append(reason)
        if (limitation != null) append(". Limitation: $limitation")
        if (alternative != null) append(". Alternative: $alternative")
    },
    cause
)
