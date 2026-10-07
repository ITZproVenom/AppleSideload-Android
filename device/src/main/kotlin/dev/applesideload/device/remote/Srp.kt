package dev.applesideload.device.remote

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a over the RFC 5054 3072-bit group with SHA-512, as Apple's pair-setup
 * uses it: the username is "Pair-Setup" and the password is the six-digit PIN.
 *
 * The arithmetic and the order of the hashed values follow the srp crate the
 * reference implementation (idevice) pairs with: u = H(A | B) and the server
 * proof H(A | M1 | K) use the values as minimal big-endian bytes, while the
 * client proof pads A and B to the length of N.
 */
object Srp {
    val N: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
            "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
            "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
            "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
        16
    )
    val G: BigInteger = BigInteger.valueOf(5)
    const val USERNAME = "Pair-Setup"

    /** Length of N in bytes: 384. */
    val N_LENGTH: Int = bytes(N).size

    private val random = SecureRandom()

    fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    /** Minimal unsigned big-endian bytes, as BigUint::to_bytes_be gives them. */
    fun bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    fun pad(value: ByteArray, length: Int = N_LENGTH): ByteArray {
        require(value.size <= length) { "an SRP value of ${value.size} bytes does not fit in $length" }
        return ByteArray(length - value.size) + value
    }

    fun number(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    /** k = H(N | pad(g)). */
    val k: BigInteger by lazy { number(sha512(bytes(N), pad(bytes(G)))) }

    /** x = H(salt | H(username ":" password)). */
    fun x(salt: ByteArray, password: String, username: String = USERNAME): BigInteger =
        number(sha512(salt, sha512("$username:$password".toByteArray(Charsets.UTF_8))))

    fun verifier(salt: ByteArray, password: String): BigInteger = G.modPow(x(salt, password), N)

    private fun u(a: BigInteger, b: BigInteger): BigInteger = number(sha512(bytes(a), bytes(b)))

    /** M1 = H(H(N) xor H(g) | H(username) | salt | pad(A) | pad(B) | K). */
    fun clientProof(a: BigInteger, b: BigInteger, key: ByteArray, salt: ByteArray, username: String = USERNAME): ByteArray {
        val hn = sha512(bytes(N))
        val hg = sha512(bytes(G))
        val xor = ByteArray(hn.size) { i -> (hn[i].toInt() xor hg[i].toInt()).toByte() }
        return sha512(
            xor,
            sha512(username.toByteArray(Charsets.UTF_8)),
            salt,
            pad(bytes(a)),
            pad(bytes(b)),
            key
        )
    }

    /** M2 = H(A | M1 | K). */
    fun serverProof(a: BigInteger, m1: ByteArray, key: ByteArray): ByteArray = sha512(bytes(a), m1, key)

    fun randomSecret(): BigInteger {
        val secret = ByteArray(32)
        random.nextBytes(secret)
        return number(secret)
    }

    /** What both sides end with: the session key K and both proofs. */
    class Result(val key: ByteArray, val clientProof: ByteArray, val serverProof: ByteArray) {
        fun checkServer(proof: ByteArray): Boolean = MessageDigest.isEqual(proof, serverProof)
        fun checkClient(proof: ByteArray): Boolean = MessageDigest.isEqual(proof, clientProof)
    }

    /** The side that knows the PIN because it was told it: the iPhone, or us over USB. */
    class Client(private val secret: BigInteger = randomSecret()) {
        val publicKey: BigInteger = G.modPow(secret, N)

        fun process(salt: ByteArray, serverPublic: ByteArray, password: String): Result {
            val b = number(serverPublic)
            if (b.mod(N).signum() == 0) throw RemotePairingException("the device sent an invalid SRP public key")
            val u = u(publicKey, b)
            val x = x(salt, password)
            val base = b.add(N).subtract(k.multiply(G.modPow(x, N)).mod(N)).mod(N)
            val s = base.modPow(u.multiply(x).add(secret), N)
            val key = sha512(bytes(s))
            val m1 = clientProof(publicKey, b, key, salt)
            return Result(key, m1, serverProof(publicKey, m1, key))
        }
    }

    /** The side that chose the PIN and shows it: us, when the iPhone pairs to this phone. */
    class Server(val salt: ByteArray, password: String) {
        private val v: BigInteger = verifier(salt, password)
        private val secret: BigInteger
        val publicKey: BigInteger

        init {
            // The reference keeps B a full 384 bytes; a shorter one is a 1 in
            // 256 event and some peers mishandle it, so draw again instead.
            var b: BigInteger
            var pub: BigInteger
            do {
                b = randomSecret()
                pub = k.multiply(v).mod(N).add(G.modPow(b, N)).mod(N)
            } while (bytes(pub).size != N_LENGTH)
            secret = b
            publicKey = pub
        }

        fun process(clientPublic: ByteArray): Result {
            val a = number(clientPublic)
            if (a.mod(N).signum() == 0) throw RemotePairingException("the device sent an invalid SRP public key")
            val u = u(a, publicKey)
            val s = a.multiply(v.modPow(u, N)).mod(N).modPow(secret, N)
            val key = sha512(bytes(s))
            val m1 = clientProof(a, publicKey, key, salt)
            return Result(key, m1, serverProof(a, m1, key))
        }
    }
}
