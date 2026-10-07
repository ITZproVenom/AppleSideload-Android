package dev.applesideload.device.remote

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** The primitives pair-setup, pair-verify and the control channel are built from. */
object RpCrypto {
    val random = SecureRandom()

    /** HKDF-SHA512. A null salt is HashLen zero bytes, as RFC 5869 says. */
    fun hkdf(ikm: ByteArray, salt: String?, info: String, length: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(ikm, salt?.toByteArray(Charsets.US_ASCII), info.toByteArray(Charsets.US_ASCII)))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }

    /** Four zero bytes and the eight-character message label, e.g. "PS-Msg05". */
    fun labelNonce(label: String): ByteArray {
        val text = label.toByteArray(Charsets.US_ASCII)
        require(text.size == 8) { "a pairing nonce label is eight characters" }
        return ByteArray(4) + text
    }

    /** The eight-byte little-endian counter, then four zero bytes. */
    fun counterNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[i] = ((counter ushr (8 * i)) and 0xFF).toByte()
        return nonce
    }

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, ByteArray(0)))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val written = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, written)
        return out
    }

    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, ByteArray(0)))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        try {
            val written = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val total = written + cipher.doFinal(out, written)
            return out.copyOf(total)
        } catch (error: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw RemotePairingException("a message did not decrypt: its authentication tag is wrong", error)
        } catch (error: org.bouncycastle.crypto.DataLengthException) {
            throw RemotePairingException("a message is too short to decrypt", error)
        }
    }

    fun ed25519Generate(): ByteArray {
        val key = Ed25519PrivateKeyParameters(random)
        return key.encoded
    }

    fun ed25519Public(privateKey: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    class X25519KeyPair {
        private val key = X25519PrivateKeyParameters(random)
        val publicKey: ByteArray = key.generatePublicKey().encoded

        fun agree(peerPublic: ByteArray): ByteArray {
            if (peerPublic.size != 32) {
                throw RemotePairingException("the device's X25519 key is ${peerPublic.size} bytes, not 32")
            }
            val agreement = X25519Agreement()
            agreement.init(key)
            val secret = ByteArray(agreement.agreementSize)
            try {
                agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), secret, 0)
            } catch (error: IllegalStateException) {
                throw RemotePairingException("the device's X25519 key is degenerate", error)
            }
            return secret
        }
    }
}
