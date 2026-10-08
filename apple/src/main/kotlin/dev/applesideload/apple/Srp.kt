package dev.applesideload.apple

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SRP-6a as Apple's identity service uses it.
 *
 * The password never leaves the device: the client proves knowledge of it
 * through the SRP exchange, which is why this has to be implemented properly
 * rather than posting credentials somewhere. Apple uses the 2048 bit group
 * from RFC 5054 with SHA-256, and derives the password verifier input with
 * PBKDF2 over the SHA-256 of the password ("s2k"), or over its hex encoding
 * ("s2k_fo").
 */
class SrpClient internal constructor(private val a: BigInteger) {

    constructor(random: SecureRandom = SecureRandom()) : this(privateValue(random))

    /** A = g^a mod N, the public value sent with the first request. */
    val publicA: BigInteger = G.modPow(a, N)

    val publicABytes: ByteArray get() = unsigned(publicA)

    private var sessionKey: ByteArray? = null

    /** The shared key, available once [process] has run. */
    val key: ByteArray get() = sessionKey ?: error("the SRP exchange has not completed")

    /**
     * Completes the exchange and returns M1, the client's proof.
     *
     * [protocol] is "s2k" or "s2k_fo" exactly as Apple reports it; the two
     * differ only in what is fed to PBKDF2, and guessing wrong produces an
     * authentication failure that looks like a wrong password.
     */
    fun process(
        username: String,
        password: String,
        salt: ByteArray,
        serverB: ByteArray,
        iterations: Int,
        protocol: String
    ): ByteArray {
        val b = BigInteger(1, serverB)
        require(b.mod(N) != BigInteger.ZERO) { "the server sent an invalid B" }

        // x = H(s | H(":" | p)): Apple leaves the account name out of x (so an
        // Apple ID can change its address without a new verifier) but keeps
        // the separator, exactly as corecrypto does with noUsernameInX.
        val password = derivePassword(password, salt, iterations, protocol)
        val x = BigInteger(1, hash(salt + hash(":".toByteArray() + password)))
        val k = BigInteger(1, hash(pad(N) + pad(G)))
        val u = BigInteger(1, hash(pad(publicA) + pad(b)))
        require(u != BigInteger.ZERO) { "the server sent a B that makes the exchange unsafe" }

        val base = b.subtract(k.multiply(G.modPow(x, N)).mod(N)).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        val sharedKey = hash(unsigned(s))
        sessionKey = sharedKey

        // M1 = H(H(N) xor H(PAD(g)) | H(I) | s | A | B | K), as RFC 5054
        // clients (pysrp in RFC 5054 mode, corecrypto) compute it.
        val hn = hash(unsigned(N))
        val hg = hash(pad(G))
        val hxor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val m1 = hash(
            hxor + hash(username.toByteArray()) + salt + unsigned(publicA) + unsigned(b) + sharedKey
        )
        expectedM2 = hash(unsigned(publicA) + m1 + sharedKey)
        return m1
    }

    private var expectedM2: ByteArray? = null

    /** Checks the server's own proof, which is what makes this mutual. */
    fun verifyServerProof(m2: ByteArray): Boolean =
        expectedM2?.let { MessageDigest.isEqual(it, m2) } ?: false

    /** HMAC-SHA256 over the session key, used to derive the negotiation keys. */
    fun hmac(label: String, extra: ByteArray = ByteArray(0)): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray())
        if (extra.isNotEmpty()) mac.update(extra)
        return mac.doFinal()
    }

    private fun derivePassword(
        password: String,
        salt: ByteArray,
        iterations: Int,
        protocol: String
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        val input = when (protocol) {
            "s2k" -> digest
            "s2k_fo" -> hex(digest).toByteArray()
            else -> throw IllegalArgumentException(
                "Apple asked for the unknown SRP protocol \"$protocol\""
            )
        }
        // PBKDF2 by hand: the platform's PBKDF2WithHmacSHA256 takes a char[]
        // and UTF-8 encodes it, which mangles the raw digest bytes "s2k" uses.
        return pbkdf2(input, salt, iterations)
    }

    private fun pbkdf2(secret: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0) { "Apple sent an iteration count of $iterations" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        // One block is enough: the derived key is exactly one SHA-256 output.
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var block = mac.doFinal()
        val result = block.copyOf()
        repeat(iterations - 1) {
            block = mac.doFinal(block)
            for (index in result.indices) {
                result[index] = (result[index].toInt() xor block[index].toInt()).toByte()
            }
        }
        return result
    }

    private fun hash(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hex(data: ByteArray): String =
        data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun pad(value: BigInteger): ByteArray {
        val bytes = unsigned(value)
        val width = (N.bitLength() + 7) / 8
        if (bytes.size >= width) return bytes
        return ByteArray(width - bytes.size) + bytes
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    companion object {
        /** The 2048 bit group from RFC 5054, which is what Apple negotiates. */
        private val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4" +
                "A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60" +
                "95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF" +
                "747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907" +
                "8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861" +
                "60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB" +
                "FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16
        )
        private val G = BigInteger.valueOf(2)
        private val WIDTH = (N.bitLength() + 7) / 8

        /**
         * A random private value whose public value fills all 256 bytes.
         *
         * SRP implementations disagree on whether A is padded inside the
         * hashes; with no leading zero byte every reading gives the same
         * bytes, so a rare sign-in failure cannot come from that.
         */
        private fun privateValue(random: SecureRandom): BigInteger {
            while (true) {
                val candidate = BigInteger(256, random)
                if (candidate.signum() == 0) continue
                val bytes = G.modPow(candidate, N).toByteArray()
                val width = if (bytes[0] == 0.toByte()) bytes.size - 1 else bytes.size
                if (width == WIDTH) return candidate
            }
        }
    }
}
