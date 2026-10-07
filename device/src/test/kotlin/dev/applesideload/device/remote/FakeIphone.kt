package dev.applesideload.device.remote

import dev.applesideload.core.Base64
import dev.applesideload.device.Transport
import org.json.JSONObject
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The iPhone's half of Remote Pairing, written from the protocol as idevice
 * implements it and independently of the code under test.
 */
internal class FakeIphone(transport: Transport, role: String) {
    val channel = RpChannel(transport, role)
    val longTermKey = RpCrypto.ed25519Generate()
    val longTermPublic = RpCrypto.ed25519Public(longTermKey)
    val identifier = "8A2B3C4D-0000-4000-8000-00000000AB12"
    val altIrk = ByteArray(16) { (it + 5).toByte() }
    var shared: ByteArray? = null

    fun info(): ByteArray = Opack.encode(
        linkedMapOf(
            "accountID" to identifier,
            "altIRK" to altIrk,
            "model" to "iPhone18,1",
            "name" to "Test iPhone",
            "remotepairing_udid" to UDID
        )
    )

    fun sendPairing(tlv: ByteArray, kind: String) {
        channel.sendPlain(
            JSONObject().put(
                "event",
                JSONObject().put(
                    "_0",
                    JSONObject().put(
                        "pairingData",
                        JSONObject().put(
                            "_0",
                            JSONObject().put("data", Base64.encode(tlv)).put("kind", kind).put("startNewSession", false)
                        )
                    )
                )
            )
        )
    }

    /** The next pairingData event: its envelope fields and the TLV inside. */
    fun receivePairing(): Pair<JSONObject, List<Tlv8.Item>> {
        val message = channel.receivePlain(5_000)
        val data = assertNotNull(RpChannel.path(message, "event", "_0", "pairingData", "_0"), message.toString())
        return data to Tlv8.decode(Base64.decode(data.getString("data")))
    }

    fun sendEvent(name: String) {
        channel.sendPlain(JSONObject().put("event", JSONObject().put("_0", JSONObject().put(name, JSONObject()))))
    }

    /** Answers the host's handshake request; returns what the host asked with. */
    fun answerHandshake(): JSONObject {
        val request = channel.receivePlain(5_000)
        val handshake = assertNotNull(RpChannel.path(request, "request", "_0", "handshake", "_0"))
        channel.sendPlain(
            JSONObject().put(
                "response",
                JSONObject().put("forRequestIdentifier", 0).put(
                    "_1",
                    JSONObject().put(
                        "handshake",
                        JSONObject().put(
                            "_0",
                            JSONObject().put("wireProtocolVersion", 26)
                                .put("peerDeviceInfo", JSONObject().put("name", "Test iPhone"))
                        )
                    )
                )
            )
        )
        return handshake
    }

    /** Pair-verify from the iPhone's side; returns whether the host proved it is [known]. */
    fun answerVerify(known: RpPairingFile?): Boolean {
        val (m1Envelope, m1) = receivePairing()
        assertEquals(RemotePairingClient.VERIFY, m1Envelope.getString("kind"))
        assertTrue(m1Envelope.getBoolean("startNewSession"))
        assertEquals(1, Tlv8.state(m1))
        val hostEphemeral = Tlv8.collect(m1, Tlv8.PUBLIC_KEY)
        val ours = RpCrypto.X25519KeyPair()
        val secret = ours.agree(hostEphemeral)
        val key = RpCrypto.hkdf(secret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val proof = Tlv8.encode(
            Tlv8.Item(Tlv8.IDENTIFIER, identifier.toByteArray()),
            Tlv8.Item(Tlv8.SIGNATURE, RpCrypto.ed25519Sign(longTermKey, ours.publicKey + identifier.toByteArray() + hostEphemeral))
        )
        sendPairing(
            Tlv8.encode(
                Tlv8.Item(Tlv8.STATE, byteArrayOf(2)),
                Tlv8.Item(Tlv8.PUBLIC_KEY, ours.publicKey),
                Tlv8.Item(Tlv8.ENCRYPTED_DATA, RpCrypto.seal(key, RpCrypto.labelNonce("PV-Msg02"), proof))
            ),
            RemotePairingClient.VERIFY
        )
        val (_, m3) = receivePairing()
        assertEquals(3, Tlv8.state(m3))
        val inner = Tlv8.decode(RpCrypto.open(key, RpCrypto.labelNonce("PV-Msg03"), Tlv8.collect(m3, Tlv8.ENCRYPTED_DATA)))
        val hostId = String(Tlv8.collect(inner, Tlv8.IDENTIFIER))
        val trusted = known != null && known.identifier == hostId && RpCrypto.ed25519Verify(
            known.publicKey, hostEphemeral + hostId.toByteArray() + ours.publicKey, Tlv8.collect(inner, Tlv8.SIGNATURE)
        )
        val m4 = mutableListOf(Tlv8.Item(Tlv8.STATE, byteArrayOf(4)))
        if (!trusted) m4 += Tlv8.Item(Tlv8.ERROR, byteArrayOf(Tlv8.ERROR_AUTHENTICATION.toByte()))
        sendPairing(Tlv8.encode(m4), RemotePairingClient.VERIFY)
        if (trusted) shared = secret
        return trusted
    }

    /** Encrypted requests answered so far: each one's nonce is this counter, in both directions. */
    private var encryptedCounter = 0L

    /** The encrypted createListener exchange; returns the request it received. */
    fun answerCreateListener(port: Int): JSONObject {
        val secret = assertNotNull(shared)
        val clientKey = RpCrypto.hkdf(secret, null, "ClientEncrypt-main")
        val serverKey = RpCrypto.hkdf(secret, null, "ServerEncrypt-main")
        val envelope = channel.receive(5_000)
        val sealed = assertNotNull(envelope.encrypted, envelope.json.toString())
        val nonce = RpCrypto.counterNonce(encryptedCounter)
        val request = JSONObject(String(RpCrypto.open(clientKey, nonce, sealed)))
        val reply = JSONObject().put(
            "response",
            JSONObject().put("_1", JSONObject().put("createListener", JSONObject().put("port", port)))
        )
        channel.sendEncrypted(RpCrypto.seal(serverKey, nonce, reply.toString().toByteArray()))
        encryptedCounter++
        return request
    }

    /**
     * Pair-setup as the accessory (the side that knows the PIN), the way
     * an iPhone answers a host over USB. Returns the host's identifier and info.
     */
    fun answerSetup(pin: String, askConsent: Boolean): Pair<String, Map<*, *>> {
        val (m1Envelope, m1) = receivePairing()
        assertEquals(RemotePairingClient.SETUP, m1Envelope.getString("kind"))
        assertTrue(m1Envelope.getBoolean("startNewSession"))
        assertEquals("AppleSideload (Test)", m1Envelope.getString("sendingHost"))
        assertEquals(1, Tlv8.state(m1))
        assertContentEquals(byteArrayOf(0), Tlv8.collect(m1, Tlv8.METHOD))
        if (askConsent) sendEvent("awaitingUserConsent")

        val salt = ByteArray(16) { 7 }
        val server = Srp.Server(salt, pin)
        sendPairing(
            Tlv8.encode(
                listOf(Tlv8.Item(Tlv8.STATE, byteArrayOf(2)), Tlv8.Item(Tlv8.SALT, salt)) +
                    Tlv8.chunked(Tlv8.PUBLIC_KEY, Srp.bytes(server.publicKey))
            ),
            RemotePairingClient.SETUP
        )
        val (_, m3) = receivePairing()
        assertEquals(3, Tlv8.state(m3))
        val result = server.process(Tlv8.collect(m3, Tlv8.PUBLIC_KEY))
        if (!result.checkClient(Tlv8.collect(m3, Tlv8.PROOF))) {
            sendPairing(
                Tlv8.encode(Tlv8.Item(Tlv8.STATE, byteArrayOf(4)), Tlv8.Item(Tlv8.ERROR, byteArrayOf(2))),
                RemotePairingClient.SETUP
            )
            throw AssertionError("the host's SRP proof did not check out")
        }
        sendPairing(
            Tlv8.encode(Tlv8.Item(Tlv8.STATE, byteArrayOf(4)), Tlv8.Item(Tlv8.PROOF, result.serverProof)),
            RemotePairingClient.SETUP
        )

        val (_, m5) = receivePairing()
        assertEquals(5, Tlv8.state(m5))
        val setupKey = RpCrypto.hkdf(result.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val inner = Tlv8.decode(RpCrypto.open(setupKey, RpCrypto.labelNonce("PS-Msg05"), Tlv8.collect(m5, Tlv8.ENCRYPTED_DATA)))
        val hostId = String(Tlv8.collect(inner, Tlv8.IDENTIFIER))
        val hostKey = Tlv8.collect(inner, Tlv8.PUBLIC_KEY)
        val controllerX = RpCrypto.hkdf(result.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        assertTrue(
            RpCrypto.ed25519Verify(hostKey, controllerX + hostId.toByteArray() + hostKey, Tlv8.collect(inner, Tlv8.SIGNATURE)),
            "the host's M5 signature"
        )
        val hostInfo = Opack.decode(Tlv8.collect(inner, Tlv8.INFO)) as Map<*, *>

        val accessoryX = RpCrypto.hkdf(result.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        val m6Inner = Tlv8.encode(
            Tlv8.Item(Tlv8.IDENTIFIER, identifier.toByteArray()),
            Tlv8.Item(Tlv8.PUBLIC_KEY, longTermPublic),
            Tlv8.Item(Tlv8.SIGNATURE, RpCrypto.ed25519Sign(longTermKey, accessoryX + identifier.toByteArray() + longTermPublic)),
            Tlv8.Item(Tlv8.INFO, info())
        )
        sendPairing(
            Tlv8.encode(
                Tlv8.chunked(Tlv8.ENCRYPTED_DATA, RpCrypto.seal(setupKey, RpCrypto.labelNonce("PS-Msg06"), m6Inner)) +
                    Tlv8.Item(Tlv8.STATE, byteArrayOf(6))
            ),
            RemotePairingClient.SETUP
        )
        shared = result.key
        return hostId to hostInfo
    }

    companion object {
        const val UDID = "00008150-000A1B2C3D4E5F60"
    }
}
