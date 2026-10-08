package dev.applesideload.apple

import java.math.BigInteger
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known answers from pysrp in RFC 5054 mode with no username in x, set up
 * the way pypush signs in to Apple (and with pypush's password derivation).
 * pysrp's own server accepted each M1 and its client accepted each M2, so
 * these pin the exact math Apple's GrandSlam service expects.
 */
class SrpTest {

    private class Vector(
        val username: String,
        val password: String,
        val protocol: String,
        val iterations: Int,
        val salt: String,
        val a: String,
        val publicA: String,
        val serverB: String,
        val m1: String,
        val m2: String,
        val key: String
    )

    private val vectors = listOf(
        Vector(
            username = "someone@example.com",
            password = "correct horse battery staple",
            protocol = "s2k",
            iterations = 20248,
            salt = "7e1b3a5c9d2f4e6081a3c5e7f9b2d4f6",
            a = "1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f" +
                "1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f" +
                "1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f" +
                "1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f" +
                "1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f" +
                "1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f",
            publicA = "0a48a52e423a01d04d2e186534c817fc85f5a4152315b538abfb3a5abe1949475667e18958c2de5d94de116e1ab1caa1" +
                "72fa91d1e1e348a0bbb5101a61e58d462f9d99aeafa584e98e0ea840d4f127c5b8e9d6c2de6f5de952da385e77659926" +
                "981ec149de5c122984a9071c8bde77fbe10f6785c3e61ce981f7d4e126ac9efdd9e6dad200ae6af58aae86494c779946" +
                "e4a6e52ccaf96633ad318b93a64aa389a5d74422c1e9c5955274b1f287ac69c0daf639ee3a4bbef9c33f25432f6e9686" +
                "1fa5fc6ac3beed51a633db95c14bff81975199f044db54688065d8178aa7434c780d28d08b023906666c8f8455e1bf55" +
                "3a86d7777e3c897b91655c97508551f0",
            serverB = "05f46296d8121bdb98214dc8ce4fa323477c835b0584d4b79ca0564a49a278b3711c8ed0fefb975d7d87a133ff5c764e" +
                "ae184d3e4d44b6c30453e28491df2a87ee0741bdabb80bb1eb6a18c7bfe271bc846c910426ddccc1e0399d6cb5abc260" +
                "7deb69a067dd44f4f09353465e478134fd7e5dd295580e30518d3e0aa98df070e491d8e631843139c7fd6993fb7089e8" +
                "fb9c4d0ee33073693345866dc526802bdc81e035b7fa69f95114fd1da65196681d619dc61d543d8832ac227e1ad09f8e" +
                "64c42dfaa8fe987c21b5effdfd463b0cc95c726759a4b42d9484cd4936c50f7f642655e3721659c1eb3587d769046d55" +
                "9fdfe3f248f3df2d6ef2e2d563a6583e",
            m1 = "4cc95c7f7d3a20e3c82052cedaeee37c1655dba95a12aa332d0378112c893571",
            m2 = "b817af67b6133f27da1fde7a2a4334c76b09108fed73632ebb7fcbddc36c4704",
            key = "6ee2f08f41c288a65825732153fc87f277f414991161b3bb7add736ac156e7f8"
        ),
        Vector(
            username = "Test.User@icloud.com",
            password = "pässwörd-ünïcode",
            protocol = "s2k_fo",
            iterations = 1000,
            salt = "00a1b2c3d4e5f60718293a4b5c6d7e8f",
            a = "a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5" +
                "a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5" +
                "a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5" +
                "a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5" +
                "a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5" +
                "a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5",
            publicA = "83e2d1cea217808ee4e423f647882dc1bf8f7e93d41de49f73ecffb8a2b8cf4b2f14063e9992ebf3c8b482e84d500004" +
                "33b0b40da977c0cd69551d3b45593e314b8f116122e72d169b23932426f128c6483daac59405140ccf9622ae725c5bce" +
                "ede31a2e55490c886fba968008066d81fec94dd9e200727aca8e9b46db65d42f9a4d7bc8d6540ce3c390b8cf2f866798" +
                "26d5576c777e798df5006cc1896166b7c9fb20293003ae57f35fa19127231233cdb70bb5e8d323a6d1880fb780f48f31" +
                "69d9af6c9ffebf9f6360d34f63592257a6f88cff1cafd115c861a361d2000eccca01d129eb26402036fbd834c74dd218" +
                "5ec116e481a0d15add731e618b56f576",
            serverB = "a9dea6f8f970c6c1a7df19a40a31e368b03ea3cc6287004ea9e1ec2d75aea6beca9ef811465c19a1910dfc170c0e6ff8" +
                "57d39c10ce6b366447bb6b06af5cc9af9f6dde58b1243a79fbc9bed24283a8e1ae7bd0880ed1b0fb4e8e57010c6e8e5d" +
                "e83fb76b7862e49702e7683455ec18ca9fbd5bb86844af7fb2a8b9aaed44fd09613827b02ebe75517d609affad26a1e0" +
                "13ce3ab091290c5992d613161825dc42163ea1dca24478eefae2068d7291320429591e4d345a83b77c3643da21c4eda4" +
                "49a15ed969cfdad660f17d51a71af93346c5a6c599a259cd5e9e23b209e21345ae2888e03b63e4a609a2ce36ceb72def" +
                "d660d346d06c8258f781384abab7f00f",
            m1 = "b436b365c4c44bd768db7d7ac3c2e553e04a0c57655e5bb5d9a9cc1c8be38154",
            m2 = "79e646d88a89a3c71f0fe117363a0815e2d231c6d8152f65dae55c8ef87ff632",
            key = "a67e0799819b9df51ae2bf000565bea525853d9c5579d98aa2454f527ae06f82"
        )
    )

    @Test
    fun matchesTheReferenceImplementation() {
        for (vector in vectors) {
            val client = SrpClient(BigInteger(vector.a, 16))
            assertEquals(vector.protocol, vector.publicA, hex(client.publicABytes))
            val m1 = client.process(
                vector.username,
                vector.password,
                unhex(vector.salt),
                unhex(vector.serverB),
                vector.iterations,
                vector.protocol
            )
            assertEquals(vector.protocol, vector.m1, hex(m1))
            assertEquals(vector.protocol, vector.key, hex(client.key))
            assertTrue(vector.protocol, client.verifyServerProof(unhex(vector.m2)))
        }
    }

    @Test
    fun rejectsAWrongServerProof() {
        val vector = vectors.first()
        val client = SrpClient(BigInteger(vector.a, 16))
        client.process(
            vector.username,
            vector.password,
            unhex(vector.salt),
            unhex(vector.serverB),
            vector.iterations,
            vector.protocol
        )
        val wrong = unhex(vector.m2).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(client.verifyServerProof(wrong))
    }

    @Test
    fun aWrongPasswordGivesADifferentProof() {
        val vector = vectors.first()
        val client = SrpClient(BigInteger(vector.a, 16))
        val m1 = client.process(
            vector.username,
            vector.password + "!",
            unhex(vector.salt),
            unhex(vector.serverB),
            vector.iterations,
            vector.protocol
        )
        assertFalse(vector.m1 == hex(m1))
    }

    @Test
    fun randomPublicValuesFillAllBytes() {
        val random = SecureRandom()
        repeat(300) {
            assertEquals(256, SrpClient(random).publicABytes.size)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAnUnknownProtocol() {
        val vector = vectors.first()
        SrpClient(BigInteger(vector.a, 16)).process(
            vector.username, vector.password, unhex(vector.salt), unhex(vector.serverB), 1, "s2k_new"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAZeroChallenge() {
        val vector = vectors.first()
        SrpClient(BigInteger(vector.a, 16)).process(
            vector.username, vector.password, unhex(vector.salt), ByteArray(256), 1, "s2k"
        )
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
