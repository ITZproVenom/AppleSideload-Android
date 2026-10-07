package dev.applesideload.device.remote

import dev.applesideload.core.Base64
import dev.applesideload.device.Transport
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Both pairing directions against a simulated iPhone. The iPhone's half is
 * written here from the protocol as idevice implements it, independently of
 * the code under test, so a slip in a label, nonce or signed message on
 * either side shows up as a failed proof or decryption.
 */
class PairingFlowTest {

    // ---- this phone as the pairable host (the iPhone pairs from Settings) ---------

    private fun iphoneHandshake(iphone: FakeIphone, attemptPairVerify: Boolean = false) {
        iphone.channel.sendPlain(
            JSONObject().put(
                "request",
                JSONObject().put(
                    "_0",
                    JSONObject().put(
                        "handshake",
                        JSONObject().put(
                            "_0",
                            JSONObject().put("hostOptions", JSONObject().put("attemptPairVerify", attemptPairVerify))
                                .put("wireProtocolVersion", 26)
                        )
                    )
                )
            )
        )
    }

    /** The iPhone's side up to M4; returns the SRP result, or null when the host refused the PIN. */
    private fun iphoneUpToM4(iphone: FakeIphone, pinFor: (String) -> String, shownPin: AtomicReference<String>): Srp.Result? {
        iphoneHandshake(iphone)
        val envelope = iphone.channel.receive(5_000)
        assertEquals(RpChannel.DEVICE, envelope.json.getString("originatedBy"))
        val handshake = assertNotNull(RpChannel.path(envelope.plain, "response", "_1", "handshake", "_0"))
        assertTrue(handshake.getJSONObject("deviceOptions").getBoolean("allowsPairSetup"))
        assertFalse(handshake.getJSONObject("deviceOptions").getBoolean("allowsPinlessPairing"))
        assertEquals("AppleSideload (Test)", handshake.getJSONObject("peerDeviceInfo").getString("name"))
        assertEquals(PairableHost.WIRE_PROTOCOL_VERSION, handshake.getInt("wireProtocolVersion"))

        iphone.sendPairing(
            Tlv8.encode(Tlv8.Item(Tlv8.METHOD, byteArrayOf(0)), Tlv8.Item(Tlv8.STATE, byteArrayOf(1))),
            RemotePairingClient.SETUP
        )
        val (m2Envelope, m2) = iphone.receivePairing()
        assertEquals(RemotePairingClient.SETUP, m2Envelope.getString("kind"))
        assertEquals(2, Tlv8.state(m2))
        val salt = Tlv8.collect(m2, Tlv8.SALT)
        val serverPublic = Tlv8.collect(m2, Tlv8.PUBLIC_KEY)
        assertEquals(16, salt.size)
        assertEquals(384, serverPublic.size)
        val pin = assertNotNull(shownPin.get(), "the PIN is shown before M2 is sent")
        assertTrue(Regex("^[0-9]{6}$").matches(pin))

        val client = Srp.Client()
        val srp = client.process(salt, serverPublic, pinFor(pin))
        iphone.sendPairing(
            Tlv8.encode(
                listOf(Tlv8.Item(Tlv8.STATE, byteArrayOf(3))) +
                    Tlv8.chunked(Tlv8.PUBLIC_KEY, Srp.bytes(client.publicKey)) +
                    Tlv8.Item(Tlv8.PROOF, srp.clientProof)
            ),
            RemotePairingClient.SETUP
        )
        val (_, m4) = iphone.receivePairing()
        assertEquals(4, Tlv8.state(m4))
        if (Tlv8.error(m4) != null) {
            assertEquals(Tlv8.ERROR_AUTHENTICATION, Tlv8.error(m4))
            return null
        }
        assertTrue(srp.checkServer(Tlv8.collect(m4, Tlv8.PROOF)), "the host's SRP proof")
        return srp
    }

    @Test
    fun `an iPhone pairs with this phone from its Settings`() {
        val (phoneEnd, iphoneEnd) = PipeTransport.pair()
        val identity = RpPairingFile.generate()
        val shownPin = AtomicReference<String>()
        val host = Background("pairable host") {
            PairableHost(RpChannel(phoneEnd, RpChannel.DEVICE), "AppleSideload (Test)").accept(identity) { shownPin.set(it) }
        }
        val iphone = FakeIphone(iphoneEnd, RpChannel.HOST)
        val srp = assertNotNull(iphoneUpToM4(iphone, { it }, shownPin))

        // M5: the iPhone's identity, signed with the controller key.
        val setupKey = RpCrypto.hkdf(srp.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val controllerX = RpCrypto.hkdf(srp.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val id = iphone.identifier.toByteArray()
        val m5Inner = Tlv8.encode(
            Tlv8.Item(Tlv8.IDENTIFIER, id),
            Tlv8.Item(Tlv8.PUBLIC_KEY, iphone.longTermPublic),
            Tlv8.Item(Tlv8.SIGNATURE, RpCrypto.ed25519Sign(iphone.longTermKey, controllerX + id + iphone.longTermPublic)),
            Tlv8.Item(Tlv8.INFO, iphone.info())
        )
        iphone.sendPairing(
            Tlv8.encode(
                Tlv8.chunked(Tlv8.ENCRYPTED_DATA, RpCrypto.seal(setupKey, RpCrypto.labelNonce("PS-Msg05"), m5Inner)) +
                    Tlv8.Item(Tlv8.STATE, byteArrayOf(5))
            ),
            RemotePairingClient.SETUP
        )

        // M6: this phone's identity, signed with the accessory key.
        val (_, m6) = iphone.receivePairing()
        assertEquals(6, Tlv8.state(m6))
        val m6Inner = Tlv8.decode(RpCrypto.open(setupKey, RpCrypto.labelNonce("PS-Msg06"), Tlv8.collect(m6, Tlv8.ENCRYPTED_DATA)))
        assertEquals(identity.identifier, String(Tlv8.collect(m6Inner, Tlv8.IDENTIFIER)))
        assertContentEquals(identity.publicKey, Tlv8.collect(m6Inner, Tlv8.PUBLIC_KEY))
        val accessoryX = RpCrypto.hkdf(srp.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        assertTrue(
            RpCrypto.ed25519Verify(
                identity.publicKey,
                accessoryX + identity.identifier.toByteArray() + identity.publicKey,
                Tlv8.collect(m6Inner, Tlv8.SIGNATURE)
            ),
            "this phone's M6 signature"
        )
        val hostInfo = Opack.decode(Tlv8.collect(m6Inner, Tlv8.INFO)) as Map<*, *>
        assertContentEquals(identity.hostAltIrk, hostInfo["altIRK"] as ByteArray)
        assertEquals(identity.identifier, hostInfo["accountID"])
        assertEquals("AppleSideload (Test)", hostInfo["name"])

        val peer = host.await()
        assertEquals(FakeIphone.UDID, peer.udid)
        assertEquals("Test iPhone", peer.name)
        assertEquals("iPhone18,1", peer.model)
        assertContentEquals(iphone.altIrk, peer.altIrk)
        assertContentEquals(iphone.longTermPublic, peer.longTermPublicKey)

        val record = identity.withDevice(peer)
        assertEquals(FakeIphone.UDID, record.udid)
        assertContentEquals(iphone.altIrk, record.deviceAltIrk)
    }

    @Test
    fun `a wrong pin typed on the iPhone is refused`() {
        val (phoneEnd, iphoneEnd) = PipeTransport.pair()
        val shownPin = AtomicReference<String>()
        val host = Background("pairable host") {
            PairableHost(RpChannel(phoneEnd, RpChannel.DEVICE), "AppleSideload (Test)")
                .accept(RpPairingFile.generate()) { shownPin.set(it) }
        }
        val iphone = FakeIphone(iphoneEnd, RpChannel.HOST)
        val result = iphoneUpToM4(iphone, { pin -> if (pin == "000000") "111111" else "000000" }, shownPin)
        kotlin.test.assertNull(result)
        val error = assertFailsWith<RemotePairingException> { host.await() }
        assertTrue(error.message!!.contains("PIN"), error.message)
    }

    @Test
    fun `an iPhone that asks to verify instead of pair is turned away`() {
        val (phoneEnd, iphoneEnd) = PipeTransport.pair()
        val host = Background("pairable host") {
            PairableHost(RpChannel(phoneEnd, RpChannel.DEVICE), "AppleSideload (Test)").accept(RpPairingFile.generate()) { }
        }
        iphoneHandshake(FakeIphone(iphoneEnd, RpChannel.HOST), attemptPairVerify = true)
        assertFailsWith<RemotePairingException> { host.await() }
    }

    // ---- this phone as the host (it dials the iPhone) ---------------------------

    @Test
    fun `pair-verify with a known identity opens a tunnel listener`() {
        val identity = RpPairingFile.generate()
        val (ours, theirs) = PipeTransport.pair()
        val iphone = FakeIphone(theirs, RpChannel.DEVICE)
        val iphoneSide = Background("iPhone") {
            val handshake = iphone.answerHandshake()
            val trusted = iphone.answerVerify(identity)
            // Something unrelated first: the host has to skip it.
            iphone.sendEvent("somethingUnrelated")
            Triple(handshake, trusted, iphone.answerCreateListener(51234))
        }
        val client = RemotePairingClient(RpChannel(ours), "AppleSideload (Test)")
        assertEquals(26, client.handshake().getInt("wireProtocolVersion"))
        assertTrue(client.verify(identity))
        assertEquals(51234, client.createListener())

        val (handshake, trusted, request) = iphoneSide.await()
        assertTrue(handshake.getJSONObject("hostOptions").getBoolean("attemptPairVerify"))
        assertEquals(RemotePairingClient.WIRE_PROTOCOL_VERSION, handshake.getInt("wireProtocolVersion"))
        assertTrue(trusted)
        val create = assertNotNull(RpChannel.path(request, "request", "_0", "createListener"))
        assertEquals("tcp", create.getString("transportProtocolType"))
        assertEquals(Base64.encode(assertNotNull(iphone.shared)), create.getString("key"))
        assertContentEquals(iphone.shared, client.encryptionKey)
    }

    @Test
    fun `pair-verify with an identity the iPhone forgot reports it`() {
        val (ours, theirs) = PipeTransport.pair()
        val iphone = FakeIphone(theirs, RpChannel.DEVICE)
        val iphoneSide = Background("iPhone") {
            iphone.answerHandshake()
            val trusted = iphone.answerVerify(known = null)
            val goodbye = iphone.channel.receivePlain(5_000)
            trusted to (RpChannel.path(goodbye, "event", "_0")?.has("pairVerifyFailed") == true)
        }
        val client = RemotePairingClient(RpChannel(ours), "AppleSideload (Test)")
        client.handshake()
        assertFalse(client.verify(RpPairingFile.generate()))
        val (trusted, toldFailed) = iphoneSide.await()
        assertFalse(trusted)
        assertTrue(toldFailed, "the host says pairVerifyFailed, as the reference does")
        assertFailsWith<IllegalStateException> { client.createListener() }
    }

    @Test
    fun `pair-setup over the cable takes the consent route with the fixed pin`() {
        val identity = RpPairingFile.generate()
        val (ours, theirs) = PipeTransport.pair()
        val iphone = FakeIphone(theirs, RpChannel.DEVICE)
        val iphoneSide = Background("iPhone") {
            iphone.answerHandshake()
            iphone.answerSetup("000000", askConsent = true)
        }
        val client = RemotePairingClient(RpChannel(ours), "AppleSideload (Test)")
        client.handshake()
        val peer = client.setup(identity) { throw AssertionError("no PIN is asked for when the iPhone asks for consent") }
        assertEquals(FakeIphone.UDID, peer.udid)
        assertContentEquals(iphone.altIrk, peer.altIrk)

        val (hostId, hostInfo) = iphoneSide.await()
        assertEquals(identity.identifier, hostId)
        assertEquals("AppleSideload (Test)", hostInfo["name"])
        assertEquals(identity.identifier, hostInfo["accountID"])
        assertContentEquals(identity.hostAltIrk, hostInfo["altIRK"] as ByteArray)
        assertContentEquals(iphone.shared, client.encryptionKey)
    }

    @Test
    fun `pair-setup with a pin shown on the device asks for it`() {
        val (ours, theirs) = PipeTransport.pair()
        val iphone = FakeIphone(theirs, RpChannel.DEVICE)
        val iphoneSide = Background("iPhone") {
            iphone.answerHandshake()
            iphone.answerSetup("246810", askConsent = false)
        }
        val client = RemotePairingClient(RpChannel(ours), "AppleSideload (Test)")
        client.handshake()
        var asked = 0
        val peer = client.setup(RpPairingFile.generate()) { asked++; "246810" }
        assertEquals(1, asked)
        assertEquals("Test iPhone", peer.name)
        iphoneSide.await()
    }

    @Test
    fun `a rejection from the iPhone is reported in its own words`() {
        val (ours, theirs) = PipeTransport.pair()
        val iphone = FakeIphone(theirs, RpChannel.DEVICE)
        val iphoneSide = Background("iPhone") {
            iphone.answerHandshake()
            iphone.receivePairing()
            iphone.channel.sendPlain(
                JSONObject(
                    """{"event":{"_0":{"pairingRejectedWithError":{"wrappedError":{"userInfo":""" +
                        """{"NSLocalizedDescription":"The user declined"}}}}}}"""
                )
            )
        }
        val client = RemotePairingClient(RpChannel(ours), "AppleSideload (Test)")
        client.handshake()
        val error = assertFailsWith<RemotePairingException> { client.setup(RpPairingFile.generate()) { "000000" } }
        assertTrue(error.message!!.contains("The user declined"), error.message)
        iphoneSide.await()
    }

    @Test
    fun `the control channel frames messages with a magic, a length and sequence numbers`() {
        val (ours, theirs) = PipeTransport.pair()
        val channel = RpChannel(ours)
        channel.sendPlain(JSONObject().put("x", 1))
        channel.sendEncrypted(byteArrayOf(1, 2, 3))
        val first = theirs.readFully(9 + 2, 1_000)
        assertEquals("RPPairing", String(first, 0, 9))
        val length = ((first[9].toInt() and 0xFF) shl 8) or (first[10].toInt() and 0xFF)
        val body = JSONObject(String(theirs.readFully(length, 1_000)))
        assertEquals(0L, body.getLong("sequenceNumber"))
        assertEquals("host", body.getString("originatedBy"))
        assertEquals(1, body.getJSONObject("message").getJSONObject("plain").getJSONObject("_0").getInt("x"))
        val second = RpChannel(theirs, RpChannel.DEVICE).receive(1_000)
        assertEquals(1L, second.json.getLong("sequenceNumber"))
        assertContentEquals(byteArrayOf(1, 2, 3), second.encrypted)

        theirs.write("NOTPAIRNG\u0000\u0002{}".toByteArray())
        assertFailsWith<RemotePairingException> { channel.receive(1_000) }
    }

}
