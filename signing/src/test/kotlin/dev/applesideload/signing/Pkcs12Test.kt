package dev.applesideload.signing

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.util.Date

class Pkcs12Test {

    @Test
    fun exportOpensWithTheMachineIdAsPassword() {
        Security.addProvider(BouncyCastleProvider())
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val name = X500Name("CN=AppleSideload, OU=ABCDE12345")
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(42), Date(), Date(System.currentTimeMillis() + 86_400_000),
                name, keys.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        )
        val identity = SigningIdentity(keys.private, certificate)
        val p12 = Pkcs12.export(identity, "MACHINE-ID")

        val store = KeyStore.getInstance("PKCS12", "BC")
        store.load(p12.inputStream(), "MACHINE-ID".toCharArray())
        val alias = store.aliases().toList().single()
        assertNotNull(store.getKey(alias, "MACHINE-ID".toCharArray()))
        assertEquals(certificate, store.getCertificate(alias))
    }
}
