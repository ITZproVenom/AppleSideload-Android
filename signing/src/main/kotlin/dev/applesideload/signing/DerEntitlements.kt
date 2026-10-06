package dev.applesideload.signing

import dev.applesideload.core.Plist
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.BERTags
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.DERUTF8String

/**
 * Entitlements in DER, which iOS 15 and later require.
 *
 * The same entitlements already go in as XML; from iOS 15 the kernel also
 * reads this encoding and refuses a signature whose two copies disagree, so
 * it is generated from the same property list rather than written twice.
 *
 * The shape is Apple's: an application-tagged sequence holding a version and
 * a set of key/value pairs, with dictionaries nesting as further sets.
 */
object DerEntitlements {

    fun encode(entitlements: Plist): ByteArray {
        val body = DERSequence(
            arrayOf(
                ASN1Integer(1),
                encodeDictionary(entitlements)
            )
        )
        return DERTaggedObject(true, BERTags.APPLICATION, 16, body).encoded
    }

    private fun encodeDictionary(value: Plist): ASN1Encodable {
        val entries = (value.asDict ?: emptyMap()).entries
            .sortedBy { it.key }
            .map { (key, item) ->
                DERSequence(arrayOf(DERUTF8String(key), encodeValue(item)))
            }
        return DERSet(entries.toTypedArray())
    }

    private fun encodeValue(value: Plist): ASN1Encodable = when (value) {
        is Plist.Bool -> ASN1Boolean.getInstance(value.value)
        is Plist.Num -> ASN1Integer(value.value)
        is Plist.Real -> ASN1Integer(value.value.toLong())
        is Plist.Str -> DERUTF8String(value.value)
        is Plist.Arr -> DERSequence(value.value.map { encodeValue(it) }.toTypedArray())
        is Plist.Dict -> encodeDictionary(value)
        is Plist.Data -> org.bouncycastle.asn1.DEROctetString(value.value)
        is Plist.Stamp -> DERUTF8String(value.epochSeconds.toString())
    }
}
