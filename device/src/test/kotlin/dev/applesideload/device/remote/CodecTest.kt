package dev.applesideload.device.remote

import dev.applesideload.core.Base64
import dev.applesideload.core.PlistReader
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {

    // ---- TLV8 ---------------------------------------------------------------

    @Test
    fun `tlv8 splits long values and joins them back`() {
        val long = ByteArray(600) { it.toByte() }
        val encoded = Tlv8.encode(Tlv8.Item(Tlv8.PUBLIC_KEY, long), Tlv8.Item(Tlv8.STATE, byteArrayOf(3)))
        // 255 + 255 + 90 bytes of key, each piece with a two-byte header, then the state item.
        assertEquals(600 + 3 * 2 + 3, encoded.size)
        val items = Tlv8.decode(encoded)
        assertEquals(4, items.size)
        assertEquals(listOf(255, 255, 90, 1), items.map { it.value.size })
        assertContentEquals(long, Tlv8.collect(items, Tlv8.PUBLIC_KEY))
        assertEquals(3, Tlv8.state(items))
        assertNull(Tlv8.error(items))
    }

    @Test
    fun `tlv8 keeps empty values and rejects truncated items`() {
        val encoded = Tlv8.encode(Tlv8.Item(Tlv8.SIGNATURE, ByteArray(0)))
        assertContentEquals(byteArrayOf(0x0A, 0), encoded)
        assertTrue(Tlv8.has(Tlv8.decode(encoded), Tlv8.SIGNATURE))
        assertFailsWith<RemotePairingException> { Tlv8.decode(byteArrayOf(0x06, 5, 1)) }
        assertFailsWith<RemotePairingException> { Tlv8.decode(byteArrayOf(0x06)) }
        assertEquals(2, Tlv8.error(Tlv8.decode(byteArrayOf(0x07, 1, 2))))
    }

    @Test
    fun `the reference split puts 254 bytes in the first piece`() {
        val pieces = RemotePairingClient.splitLikeReference(Tlv8.PUBLIC_KEY, ByteArray(384))
        assertEquals(listOf(254, 130), pieces.map { it.value.size })
        assertEquals(1, RemotePairingClient.splitLikeReference(Tlv8.PROOF, ByteArray(64)).size)
    }

    // ---- OPACK --------------------------------------------------------------

    @Test
    fun `opack round trip covers every size class`() {
        val value = linkedMapOf<String, Any?>(
            "altIRK" to ByteArray(16) { (it * 7).toByte() },
            "name" to "Living Room",
            "inline" to 39L,
            "byte" to 40L,
            "word" to 70_000L,
            "huge" to (1L shl 40),
            "yes" to true,
            "no" to false,
            "nothing" to null,
            "list" to List(20) { it.toLong() },
            "text" to "x".repeat(300),
            "blob" to ByteArray(70_000) { it.toByte() },
            "real" to 3.25,
            "many" to (0 until 16).associate { "k$it" to it.toLong() }
        )
        val decoded = Opack.decode(Opack.encode(value)) as Map<*, *>
        assertEquals(value.keys, decoded.keys)
        for ((key, expected) in value) {
            val actual = decoded[key]
            if (expected is ByteArray) assertContentEquals(expected, actual as ByteArray, key)
            else assertEquals(expected, actual, key)
        }
    }

    @Test
    fun `opack encodes small values the way the reference does`() {
        assertEquals("08", Opack.encode(0L).toHex())
        assertEquals("2f", Opack.encode(39L).toHex())
        assertEquals("3028", Opack.encode(40L).toHex())
        assertEquals("3200010000", Opack.encode(256L).toHex())
        assertEquals("4161", Opack.encode("a").toHex())
        assertEquals("7101", Opack.encode(byteArrayOf(1)).toHex())
        assertEquals("e14161d0", Opack.encode(mapOf("a" to emptyList<Any>())).toHex())
        assertEquals("01", Opack.encode(true).toHex())
        assertEquals("04", Opack.encode(null).toHex())
    }

    @Test
    fun `opack back references resolve and repeats take no new slot`() {
        // ["abc", ref 0]
        assertEquals(listOf("abc", "abc"), Opack.decode(hex("d2 43616263 a0")))
        // ["a", "a", "b", ref 1]: the second "a" is not appended, so ref 1 is "b".
        assertEquals(listOf("a", "a", "b", "b"), Opack.decode(hex("d4 4161 4161 4162 a1")))
        assertFailsWith<RemotePairingException> { Opack.decode(hex("d2 4161 a4")) }
        assertFailsWith<RemotePairingException> { Opack.decode(hex("df 4161")) }
        assertFailsWith<RemotePairingException> { Opack.decode(hex("4161 00")) }
    }

    @Test
    fun `opack decodes a real iPad pair record with a back-referenced name`() {
        val bytes = hex(
            "e946616c7449524b80eb5231c545" +
            "75ca469fd23ca59e2f080e527265" +
            "6d6f746570616972696e675f6563" +
            "6964331c00853e36111200466274" +
            "416464725133343a32623a36653a" +
            "32323a36363a38615b72656d6f74" +
            "6570616972696e675f7365726961" +
            "6c5f6e756d6265724a5232435148" +
            "5133365936496163636f756e7449" +
            "44612439393031463534422d4433" +
            "36302d344544382d423444392d38" +
            "3043353535353135353337456d6f" +
            "64656c486950616431362c335272" +
            "656d6f746570616972696e675f75" +
            "6469645930303030383133322d30" +
            "3031323131333633453835303031" +
            "43446e616d65ab5b6c6173745365" +
            "656e5769726550726f746f636f6c" +
            "56657273696f6e22"
        )
        val dict = Opack.decode(bytes) as Map<*, *>
        assertEquals("iPad16,3", dict["model"])
        assertEquals("iPad16,3", dict["name"])
        assertEquals("00008132-001211363E85001C", dict["remotepairing_udid"])
        assertEquals(5085474255601692L, dict["remotepairing_ecid"])
        assertEquals(16, (dict["altIRK"] as ByteArray).size)
        assertEquals(26L, dict["lastSeenWireProtocolVersion"])

        // And PeerDevice reads it from a pair-setup TLV the same way.
        val peer = PeerDevice.fromTlv(Tlv8.decode(Tlv8.encode(Tlv8.Item(Tlv8.INFO, bytes))))
        assertEquals("00008132-001211363E85001C", peer.udid)
        assertEquals("iPad16,3", peer.name)
    }

    // ---- authTag / SipHash --------------------------------------------------

    @Test
    fun `siphash matches the reference vectors`() {
        val k0 = 0x0706050403020100L
        val k1 = 0x0f0e0d0c0b0a0908L
        assertEquals(0x726fdb47dd0e0e31L, AuthTag.sipHash24(k0, k1, ByteArray(0)))
        assertEquals(0x93f5f5799a932462uL.toLong(), AuthTag.sipHash24(k0, k1, ByteArray(8) { it.toByte() }))
        assertEquals(0xa129ca6149be45e5uL.toLong(), AuthTag.sipHash24(k0, k1, ByteArray(15) { it.toByte() }))
    }

    @Test
    fun `authTag matches the reference vector`() {
        val irk = Base64.decode("Mgp6ZGPzXM2ku9br46vsiw==")
        val id = "2BE6E510-0325-4365-923E-B14C6F57DB3A"
        assertEquals("kXjlTr2l", Base64.encode(AuthTag.compute(irk, id)))
        assertTrue(AuthTag.matches(irk, id, "kXjlTr2l"))
        assertTrue(AuthTag.matches(irk, id, " kXjlTr2l "))
        assertFalse(AuthTag.matches(irk, id.replace('A', 'B'), "kXjlTr2l"))
        assertFalse(AuthTag.matches(irk, id, "AAAAAAAA"))
        assertFalse(AuthTag.matches(irk, id, "not base64!"))
        assertFalse(AuthTag.matches(ByteArray(8), id, "kXjlTr2l"))
    }

    @Test
    fun `the pairable-host advertisement carries a tag for its own identifier`() {
        val file = RpPairingFile.generate()
        val txt = PairableHost.txtRecord(file, "AppleSideload (Pixel)")
        assertEquals(file.identifier, txt["identifier"])
        assertEquals("AppleSideload (Pixel)", txt["name"])
        assertTrue(AuthTag.matches(file.hostAltIrk, file.identifier, txt.getValue("authTag")))
        assertEquals(listOf("name", "identifier", "authTag", "model", "flags", "ver", "minVer"), txt.keys.toList())
    }

    // ---- SRP and the primitives ----------------------------------------------

    @Test
    fun `srp client and server agree on the key and both proofs`() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val server = Srp.Server(salt, "123456")
        assertEquals(384, Srp.bytes(server.publicKey).size)
        val client = Srp.Client()
        val clientSide = client.process(salt, Srp.bytes(server.publicKey), "123456")
        val serverSide = server.process(Srp.bytes(client.publicKey))
        assertContentEquals(serverSide.key, clientSide.key)
        assertTrue(serverSide.checkClient(clientSide.clientProof))
        assertTrue(clientSide.checkServer(serverSide.serverProof))
    }

    @Test
    fun `srp with the wrong pin fails both proofs`() {
        val salt = ByteArray(16) { 9 }
        val server = Srp.Server(salt, "123456")
        val client = Srp.Client()
        val clientSide = client.process(salt, Srp.bytes(server.publicKey), "654321")
        val serverSide = server.process(Srp.bytes(client.publicKey))
        assertFalse(serverSide.checkClient(clientSide.clientProof))
        assertFalse(clientSide.checkServer(serverSide.serverProof))
        assertFailsWith<RemotePairingException> { server.process(Srp.bytes(Srp.N)) }
    }

    @Test
    fun `chacha seals and refuses tampering`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = RpCrypto.labelNonce("PS-Msg05")
        assertEquals("00000000" + "PS-Msg05".toByteArray().toHex(), nonce.toHex())
        assertEquals("010000000000000000000000", RpCrypto.counterNonce(1).toHex())
        val sealed = RpCrypto.seal(key, nonce, "hello".toByteArray())
        assertEquals(5 + 16, sealed.size)
        assertEquals("hello", String(RpCrypto.open(key, nonce, sealed)))
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertFailsWith<RemotePairingException> { RpCrypto.open(key, nonce, sealed) }
        assertFailsWith<RemotePairingException> { RpCrypto.open(key, nonce, ByteArray(3)) }
    }

    @Test
    fun `ed25519 and x25519 work together`() {
        val private = RpCrypto.ed25519Generate()
        val public = RpCrypto.ed25519Public(private)
        val signature = RpCrypto.ed25519Sign(private, "message".toByteArray())
        assertTrue(RpCrypto.ed25519Verify(public, "message".toByteArray(), signature))
        assertFalse(RpCrypto.ed25519Verify(public, "massage".toByteArray(), signature))
        assertFalse(RpCrypto.ed25519Verify(public, "message".toByteArray(), ByteArray(10)))
        val a = RpCrypto.X25519KeyPair()
        val b = RpCrypto.X25519KeyPair()
        assertContentEquals(a.agree(b.publicKey), b.agree(a.publicKey))
        assertFailsWith<RemotePairingException> { a.agree(ByteArray(31)) }
        assertFailsWith<RemotePairingException> { a.agree(ByteArray(32)) }
    }

    @Test
    fun `hkdf is deterministic and label dependent`() {
        val ikm = ByteArray(32) { 1 }
        assertContentEquals(RpCrypto.hkdf(ikm, null, "ClientEncrypt-main"), RpCrypto.hkdf(ikm, null, "ClientEncrypt-main"))
        assertFalse(RpCrypto.hkdf(ikm, null, "ClientEncrypt-main").contentEquals(RpCrypto.hkdf(ikm, null, "ServerEncrypt-main")))
        assertEquals(32, RpCrypto.hkdf(ikm, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info").size)
    }

    // ---- pairing records -----------------------------------------------------

    @Test
    fun `a pairing record survives storage and gives apps only what they read`() {
        val identity = RpPairingFile.generate()
        assertEquals(36, identity.identifier.length)
        assertEquals(identity, RpPairingFile.fromStoredPlist(identity.toStoredPlist()))

        val peer = PeerDevice("acct", ByteArray(16) { 3 }, "iPhone18,1", "Test iPhone", "00008150-000000000000001E", null, null)
        val paired = identity.withDevice(peer)
        val restored = RpPairingFile.fromStoredPlist(paired.toStoredPlist())
        assertEquals(paired, restored)
        assertEquals("00008150-000000000000001E", restored.udid)
        assertEquals("Test iPhone", restored.deviceName)

        val app = PlistReader.parse(paired.toAppPlist())
        assertContentEquals(paired.publicKey, app["public_key"]?.asData)
        assertContentEquals(paired.privateKey, app["private_key"]?.asData)
        assertEquals(paired.identifier, app["identifier"]?.asString)
        assertContentEquals(ByteArray(16) { 3 }, app["alt_irk"]?.asData)
        assertNull(app["host_alt_irk"])
        assertNull(app["udid"])
        // SideStore reads the file as text, so it has to be XML.
        assertTrue(String(paired.toAppPlist()).startsWith("<?xml"))
        assertNull(PlistReader.parse(identity.toAppPlist())["alt_irk"])
    }

    @Test
    fun `a damaged stored record is refused`() {
        val broken = dev.applesideload.core.XmlPlist.write(
            dev.applesideload.core.Plist.dict("identifier" to dev.applesideload.core.Plist.Str("x"))
        )
        assertFailsWith<RemotePairingException> { RpPairingFile.fromStoredPlist(broken) }
    }

    // ---- CDTunnel --------------------------------------------------------------

    @Test
    fun `ipv6 literals parse without a lookup`() {
        assertEquals("fd000000000000000000000000000001", CdTunnel.ipv6("fd00::1").toHex())
        assertEquals("fe800000000000000000000000000001", CdTunnel.ipv6("fe80::1%en0").toHex())
        assertEquals("00010002000300040005000600070008", CdTunnel.ipv6("1:2:3:4:5:6:7:8").toHex())
        assertEquals("00000000000000000000000000000000", CdTunnel.ipv6("::").toHex())
        assertEquals("abcd0000000000000000000000000000", CdTunnel.ipv6("ABCD::").toHex())
        for (bad in listOf("1.2.3.4", "1:2:3", "1::2::3", "12345::1", "1:2:3:4:5:6:7:8:9", "::g", ":::")) {
            assertFailsWith<IllegalArgumentException>(bad) { CdTunnel.ipv6(bad) }
        }
    }

    @Test
    fun `the CDTunnel reply gives addresses, mtu and the RSD port`() {
        val reply = JSONObject(
            """{"type":"serverHandshakeResponse","clientParameters":{"address":"fd7b:6d8a:5f43::2",""" +
                """"netmask":"ffff:ffff:ffff:ffff::","mtu":1500},"serverAddress":"fd7b:6d8a:5f43::1","serverRSDPort":58783}"""
        )
        val parameters = CdTunnel.parse(reply)
        assertEquals(1500, parameters.mtu)
        assertEquals(58783, parameters.rsdPort)
        assertEquals("fd7b6d8a5f4300000000000000000002", parameters.clientAddress.toHex())
        assertEquals("fd7b6d8a5f4300000000000000000001", parameters.serverAddress.toHex())
        assertFailsWith<IOException> { CdTunnel.parse(JSONObject("""{"serverAddress":"fd00::1","serverRSDPort":1}""")) }
        assertFailsWith<IOException> {
            CdTunnel.parse(JSONObject("""{"clientParameters":{"address":"fd00::2"},"serverAddress":"fd00::1"}"""))
        }
        // An MTU too small for IPv6 is raised to the minimum.
        val tiny = CdTunnel.parse(
            JSONObject("""{"clientParameters":{"address":"fd00::2","mtu":100},"serverAddress":"fd00::1","serverRSDPort":5}""")
        )
        assertEquals(1280, tiny.mtu)
    }

    @Test
    fun `the CDTunnel handshake frames its request and reads the reply`() {
        val (ours, theirs) = PipeTransport.pair()
        val device = Background("device") {
            val request = CdTunnel.readMessage(theirs, 5_000)
            theirs.write(
                CdTunnel.encode(
                    JSONObject("""{"type":"serverHandshakeResponse","clientParameters":{"address":"fd00::2","mtu":16000},""" +
                        """"serverAddress":"fd00::1","serverRSDPort":60000}""")
                )
            )
            request
        }
        val parameters = CdTunnel.handshake(ours, timeoutMs = 5_000)
        val request = device.await()
        assertEquals("clientHandshakeRequest", request.getString("type"))
        assertEquals(CdTunnel.REQUESTED_MTU, request.getInt("mtu"))
        assertEquals(60000, parameters.rsdPort)
        assertEquals(16000, parameters.mtu)
        assertEquals("43445475 6e6e656c".replace(" ", ""), CdTunnel.encode(JSONObject()).copyOf(8).toHex())
    }

    // ---- XPC -----------------------------------------------------------------

    @Test
    fun `xpc dictionary layout matches the wire format`() {
        assertEquals(
            "42371342 05000000 00f00000 14000000 01000000 61000000 00900000 02000000 62000000".replace(" ", ""),
            XpcCodec.encode(mapOf("a" to "b")).toHex()
        )
    }

    @Test
    fun `xpc round trip keeps every type`() {
        val uuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        val value = linkedMapOf<String, Any?>(
            "MessageType" to "Handshake",
            "MessagingProtocolVersion" to XpcUInt64(7),
            "UUID" to uuid,
            "negative" to -5L,
            "real" to 1.5,
            "when" to XpcDate(1_700_000_000_000_000_000L),
            "data" to byteArrayOf(1, 2, 3),
            "flag" to true,
            "none" to null,
            "list" to listOf("x", 1L, listOf<Any?>()),
            "Properties" to linkedMapOf<String, Any?>("abc" to "defg", "" to "")
        )
        val decoded = XpcCodec.decode(XpcCodec.encode(value)) as Map<*, *>
        assertEquals(value.keys, decoded.keys)
        for ((key, expected) in value) {
            val actual = decoded[key]
            if (expected is ByteArray) assertContentEquals(expected, actual as ByteArray)
            else assertEquals(expected, actual, key)
        }
        // UUIDs are sent in network byte order.
        assertTrue(XpcCodec.encode(uuid).toHex().endsWith("0123456789abcdef0123456789abcdef"))
        assertFailsWith<IOException> { XpcCodec.decode(ByteArray(8)) }
        assertFailsWith<IOException> { XpcCodec.decode(XpcCodec.encode(value).copyOf(40)) }
    }

    @Test
    fun `xpc messages decode only once whole`() {
        val message = XpcMessage(XpcMessage.DATA or XpcMessage.ALWAYS_SET, 1, mapOf("k" to "v"), true).encode()
        assertNull(XpcMessage.decode(message, XpcMessage.WRAPPER - 1))
        assertNull(XpcMessage.decode(message, message.size - 1))
        val (decoded, used) = assertNotNull(XpcMessage.decode(message + byteArrayOf(9, 9), message.size + 2))
        assertEquals(message.size, used)
        assertEquals(mapOf("k" to "v"), decoded.body)
        assertEquals(1L, decoded.messageId)
        val empty = XpcMessage(0x201, 1, null, false).encode()
        assertEquals(XpcMessage.WRAPPER, empty.size)
        assertFalse(assertNotNull(XpcMessage.decode(empty, empty.size)).first.hasBody)
        assertFailsWith<IOException> { XpcMessage.decode(ByteArray(24), 24) }
    }

    @Test
    fun `rsd parsing reads ports, entitlements and properties`() {
        val reply = mapOf(
            "MessageType" to "Handshake",
            "UUID" to UUID(1, 2),
            "Properties" to mapOf("ProductType" to "iPhone18,1", "BoardId" to XpcUInt64(12)),
            "Services" to mapOf(
                "com.apple.mobile.lockdown.remote.trusted" to mapOf(
                    "Port" to "50123", "Entitlement" to "com.apple.mobile.lockdown.remote.trusted",
                    "Properties" to mapOf("UsesRemoteXPC" to false)
                ),
                "com.apple.coredevice.appservice" to mapOf("Port" to "50124", "Properties" to mapOf("UsesRemoteXPC" to true)),
                "broken" to mapOf("Port" to "not a number")
            )
        )
        val rsd = RsdHandshake.parse(reply)
        assertEquals(50123, rsd.port(RemoteTunnel.LOCKDOWN))
        assertEquals(50124, rsd.port("com.apple.coredevice.appservice"))
        assertTrue(rsd.services.getValue("com.apple.coredevice.appservice").usesRemoteXpc)
        assertNull(rsd.port("broken"))
        assertEquals("iPhone18,1", rsd.property("ProductType"))
        assertEquals("12", rsd.property("BoardId"))
        assertEquals(UUID(1, 2).toString(), rsd.uuid)
        assertEquals("com.apple.afc.shim.remote", RemoteTunnel.shimName("com.apple.afc"))
        assertFailsWith<IOException> { RsdHandshake.parse(mapOf("MessageType" to "Handshake")) }
    }
}
