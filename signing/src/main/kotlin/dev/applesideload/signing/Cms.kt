package dev.applesideload.signing

import dev.applesideload.core.Plist
import dev.applesideload.core.XmlPlist
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * The CMS signature that sits in the signature slot.
 *
 * It signs the code directory and nothing else. Apple adds two attributes of
 * its own carrying the hashes of every code directory in the signature, one
 * as a property list and one in DER, and the device checks them, so both are
 * produced here.
 */
object Cms {

    private val CD_HASHES_PLIST = ASN1ObjectIdentifier("1.2.840.113635.100.9.1")
    private val CD_HASHES_DER = ASN1ObjectIdentifier("1.2.840.113635.100.9.2")

    /**
     * [primary] is the code directory the signature covers; [all] is every
     * code directory in the signature, in slot order, which is what the
     * hash attributes list.
     */
    fun sign(
        primary: ByteArray,
        all: List<ByteArray>,
        identity: SigningIdentity,
        extraCertificates: List<X509Certificate> = emptyList()
    ): ByteArray {
        val generator = CMSSignedDataGenerator()
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(identity.privateKey)
        val attributes = AttributeTable(
            ASN1EncodableVector().apply {
                add(Attribute(CD_HASHES_PLIST, DERSet(DEROctetString(cdHashesPlist(all)))))
                add(Attribute(CD_HASHES_DER, DERSet(cdHashesDer(all))))
            }
        )
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .setSignedAttributeGenerator(
                    DefaultSignedAttributeTableGenerator(attributes)
                )
                .build(signer, identity.certificate)
        )
        generator.addCertificates(
            JcaCertStore(listOf(identity.certificate) + extraCertificates)
        )
        // Detached: the code directory is already in the superblob, so the
        // signature carries only the proof.
        return generator.generate(CMSProcessableByteArray(primary), false).encoded
    }

    /** The plist attribute: truncated SHA-1 style hashes of each directory. */
    private fun cdHashesPlist(all: List<ByteArray>): ByteArray {
        val hashes = all.map { directory ->
            Plist.Data(MessageDigest.getInstance("SHA-256").digest(directory).copyOfRange(0, 20))
        }
        return XmlPlist.write(Plist.dict("cdhashes" to Plist.Arr(hashes)))
    }

    /** The DER attribute: the full hashes with their algorithm identifiers. */
    private fun cdHashesDer(all: List<ByteArray>): DERSequence {
        val entries = all.map { directory ->
            DERSequence(
                arrayOf(
                    ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"),
                    DEROctetString(MessageDigest.getInstance("SHA-256").digest(directory))
                )
            )
        }
        return DERSequence(entries.toTypedArray())
    }
}
