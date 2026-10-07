package dev.applesideload.device.remote

import dev.applesideload.core.Base64
import java.security.MessageDigest

/**
 * The authTag a remote-pairing Bonjour advertisement carries.
 *
 * It is SipHash-2-4 of the advertised identifier, keyed with the advertiser's
 * 16-byte altIRK, and six bytes of the 64-bit result are sent: the low six,
 * most significant first. Whoever holds the altIRK (it is exchanged in pair-setup) can tell the
 * advertisement apart from every other one on the network.
 */
object AuthTag {

    fun compute(altIrk: ByteArray, identifier: String): ByteArray {
        require(altIrk.size == 16) { "an altIRK is 16 bytes, not ${altIrk.size}" }
        val k0 = littleEndian(altIrk, 0)
        val k1 = littleEndian(altIrk, 8)
        val hash = sipHash24(k0, k1, identifier.toByteArray(Charsets.UTF_8))
        // Bytes 5, 4, ... 0 of the little-endian result, in that order.
        return ByteArray(6) { i -> ((hash ushr (8 * (5 - i))) and 0xFF).toByte() }
    }

    fun matches(altIrk: ByteArray, identifier: String, advertised: String): Boolean {
        val decoded = runCatching { Base64.decode(advertised.trim()) }.getOrNull()
            ?: return false
        if (decoded.size != 6 || altIrk.size != 16) return false
        return MessageDigest.isEqual(decoded, compute(altIrk, identifier))
    }

    private fun littleEndian(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    /** SipHash-2-4, as in the reference paper. */
    fun sipHash24(k0: Long, k1: Long, message: ByteArray): Long {
        var v0 = k0 xor 0x736f6d6570736575L
        var v1 = k1 xor 0x646f72616e646f6dL
        var v2 = k0 xor 0x6c7967656e657261L
        var v3 = k1 xor 0x7465646279746573L

        fun round() {
            v0 += v1; v1 = java.lang.Long.rotateLeft(v1, 13); v1 = v1 xor v0; v0 = java.lang.Long.rotateLeft(v0, 32)
            v2 += v3; v3 = java.lang.Long.rotateLeft(v3, 16); v3 = v3 xor v2
            v0 += v3; v3 = java.lang.Long.rotateLeft(v3, 21); v3 = v3 xor v0
            v2 += v1; v1 = java.lang.Long.rotateLeft(v1, 17); v1 = v1 xor v2; v2 = java.lang.Long.rotateLeft(v2, 32)
        }

        val whole = message.size / 8 * 8
        var i = 0
        while (i < whole) {
            val m = littleEndian(message, i)
            v3 = v3 xor m
            round(); round()
            v0 = v0 xor m
            i += 8
        }
        var last = (message.size.toLong() and 0xFF) shl 56
        for (j in 0 until message.size - whole) {
            last = last or ((message[whole + j].toLong() and 0xFF) shl (8 * j))
        }
        v3 = v3 xor last
        round(); round()
        v0 = v0 xor last
        v2 = v2 xor 0xFF
        round(); round(); round(); round()
        return v0 xor v1 xor v2 xor v3
    }
}
