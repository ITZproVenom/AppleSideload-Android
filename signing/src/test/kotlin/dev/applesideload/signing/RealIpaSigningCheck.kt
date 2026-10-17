package dev.applesideload.signing

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Date

/**
 * Signs a real IPA with the app's own signer so an independent tool (rcodesign)
 * can check the result in CI. It does nothing unless SAMPLE_IPA names a file,
 * so ordinary test runs skip it.
 */
class RealIpaSigningCheck {

    @Test
    fun signsARealIpaForExternalVerification() {
        val ipa = System.getenv("SAMPLE_IPA")?.let(::File)?.takeIf { it.isFile } ?: return
        val out = File(System.getenv("SIGNED_OUT") ?: "build/real-ipa")
        out.deleteRecursively()
        out.mkdirs()

        Security.addProvider(BouncyCastleProvider())
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val name = X500Name("CN=Apple Development: Test (ABCDE12345), OU=ABCDE12345, O=Test, C=US")
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(42), Date(), Date(System.currentTimeMillis() + 86_400_000),
                name, keys.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        )
        val identity = SigningIdentity(keys.private, certificate)
        val profile = ProvisioningProfile(PROFILE.toByteArray())

        val tree = File(out, "tree")
        ipa.inputStream().use { IpaPackage.extract(it, tree) }
        val app = File(tree, "Payload").listFiles()!!.single { it.name.endsWith(".app") }
        BundleSigner(identity, profile).sign(app)

        File(out, "identity.p12").writeBytes(Pkcs12.export(identity, "pw"))
        IpaPackage.pack(tree, File(out, "signed.ipa"))
    }

    private companion object {
        const val PROFILE = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>UUID</key><string>11111111-2222-3333-4444-555555555555</string>
<key>Name</key><string>Test</string>
<key>TeamIdentifier</key><array><string>ABCDE12345</string></array>
<key>Entitlements</key><dict>
<key>application-identifier</key><string>ABCDE12345.com.test.sidestore</string>
<key>com.apple.developer.team-identifier</key><string>ABCDE12345</string>
<key>get-task-allow</key><true/>
<key>keychain-access-groups</key><array><string>ABCDE12345.*</string></array>
</dict></dict></plist>"""
    }
}
