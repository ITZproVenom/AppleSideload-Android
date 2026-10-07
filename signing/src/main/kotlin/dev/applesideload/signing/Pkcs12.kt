package dev.applesideload.signing

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.crypto.engines.DESedeEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.bc.BcPKCS12MacCalculatorBuilder
import org.bouncycastle.pkcs.bc.BcPKCS12PBEOutputEncryptorBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.asn1.DERBMPString

/**
 * The signing identity as a PKCS#12 file.
 *
 * SideStore and AltStore sign apps on the iPhone with the same certificate
 * that installed them, which they read from ALTCertificate.p12 in their own
 * bundle, protected with the certificate's machine id - the same hand-off
 * AltServer and isideload make. Both the key and the certificate are
 * encrypted with SHA-1/3DES and the MAC is SHA-1, because that is what
 * SecPKCS12Import reliably accepts on every iOS version.
 */
object Pkcs12 {

    fun export(identity: SigningIdentity, password: String): ByteArray {
        val chars = password.toCharArray()
        val extensions = JcaX509ExtensionUtils()
        val keyId: SubjectKeyIdentifier = extensions.createSubjectKeyIdentifier(identity.certificate.publicKey)
        val friendlyName = DERBMPString(identity.commonName)

        val certBag = JcaPKCS12SafeBagBuilder(identity.certificate).apply {
            addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_friendlyName, friendlyName)
            addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_localKeyId, keyId)
        }.build()

        val encryptor = BcPKCS12PBEOutputEncryptorBuilder(
            PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC,
            CBCBlockCipher.newInstance(DESedeEngine())
        ).build(chars)

        val keyBag = JcaPKCS12SafeBagBuilder(identity.privateKey, encryptor).apply {
            addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_friendlyName, friendlyName)
            addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_localKeyId, keyId)
        }.build()

        val certEncryptor = BcPKCS12PBEOutputEncryptorBuilder(
            PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC,
            CBCBlockCipher.newInstance(DESedeEngine())
        ).build(chars)

        val pfx = PKCS12PfxPduBuilder().apply {
            addEncryptedData(certEncryptor, certBag)
            addData(keyBag)
        }.build(BcPKCS12MacCalculatorBuilder(), chars)
        return pfx.getEncoded("DER")
    }
}
