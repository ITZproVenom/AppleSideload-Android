package dev.applesideload.device.remote

import dev.applesideload.core.Base64
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import org.json.JSONObject
import java.security.SecureRandom

/**
 * This phone as a "pairable host": the iOS 27 way of pairing with no cable.
 *
 * The phone advertises _remotepairing-pairable-host._tcp. On the iPhone,
 * Settings > Privacy & Security > Developer Mode lists it; tapping it makes
 * the iPhone connect here and run pair-setup with the roles reversed: the
 * iPhone is the SRP client and this phone is the accessory, which picks the
 * PIN and shows it, for the user to type on the iPhone.
 *
 * Follows idevice's PairableHost message for message.
 */
class PairableHost(
    private val channel: RpChannel,
    private val name: String,
    private val model: String = DEFAULT_MODEL
) {

    /**
     * Runs the whole exchange. [showPin] gets the six digits as soon as they
     * are chosen, before the iPhone asks for them.
     */
    fun accept(file: RpPairingFile, showPin: (String) -> Unit): PeerDevice {
        handshake()
        return pairSetup(file, showPin)
    }

    private fun handshake() {
        val request = channel.receivePlain(FIRST_MESSAGE_TIMEOUT_MS)
        val handshake = RpChannel.path(request, "request", "_0", "handshake", "_0")
            ?: throw RemotePairingException("the iPhone did not start with a handshake: ${request.toString().take(200)}")
        if (RpChannel.path(handshake, "hostOptions")?.optBoolean("attemptPairVerify", false) == true) {
            throw RemotePairingException("the iPhone asked to verify an existing pairing; only new pairings are accepted here")
        }
        val peerInfo = JSONObject()
            .put("udid", "")
            .put("deviceKVSIncludesSensitiveInfo", false)
            .put("identifier", "")
            .put("name", name)
            .put("model", model)
        val options = JSONObject()
            .put("allowsPairSetup", true)
            .put("allowsPinlessPairing", false)
            .put("allowsIncomingTunnelConnections", false)
            .put("allowsUpgradeOfLockdownPairings", false)
            .put("allowsSharingSensitiveInfo", false)
        channel.sendPlain(
            JSONObject().put(
                "response",
                JSONObject()
                    .put("forRequestIdentifier", 0)
                    .put(
                        "_1",
                        JSONObject().put(
                            "handshake",
                            JSONObject().put(
                                "_0",
                                JSONObject()
                                    .put("wireProtocolVersion", WIRE_PROTOCOL_VERSION)
                                    .put("minimumSupportedWireProtocolVersion", 8)
                                    .put("deviceOptions", options)
                                    .put("peerDeviceInfo", peerInfo)
                            )
                        )
                    )
            )
        )
    }

    private fun pairSetup(file: RpPairingFile, showPin: (String) -> Unit): PeerDevice {
        val m1 = receiveTlv(PIN_ENTRY_TIMEOUT_MS)
        expectState(m1, 1)

        val salt = ByteArray(16).also { random.nextBytes(it) }
        val pin = "%06d".format(random.nextInt(1_000_000))
        val srp = Srp.Server(salt, pin)
        showPin(pin)
        sendTlv(
            listOf(Tlv8.Item(Tlv8.STATE, byteArrayOf(2)), Tlv8.Item(Tlv8.SALT, salt)) +
                Tlv8.chunked(Tlv8.PUBLIC_KEY, Srp.bytes(srp.publicKey))
        )

        // The iPhone sends M3 once the PIN has been typed, so this wait is the user's.
        val m3 = receiveTlv(PIN_ENTRY_TIMEOUT_MS)
        Tlv8.error(m3)?.let { throw RemotePairingException("the iPhone cancelled pairing (error $it)") }
        expectState(m3, 3)
        val clientPublic = Tlv8.collect(m3, Tlv8.PUBLIC_KEY)
        val clientProof = Tlv8.collect(m3, Tlv8.PROOF)
        if (clientPublic.isEmpty() || clientProof.isEmpty()) {
            throw RemotePairingException("the iPhone's pair-setup M3 has no public key or proof")
        }
        val result = srp.process(clientPublic)
        if (!result.checkClient(clientProof)) {
            sendTlv(listOf(Tlv8.Item(Tlv8.STATE, byteArrayOf(4)), Tlv8.Item(Tlv8.ERROR, byteArrayOf(Tlv8.ERROR_AUTHENTICATION.toByte()))))
            throw RemotePairingException("the PIN typed on the iPhone was not $pin")
        }
        sendTlv(listOf(Tlv8.Item(Tlv8.STATE, byteArrayOf(4)), Tlv8.Item(Tlv8.PROOF, result.serverProof)))

        val setupKey = RpCrypto.hkdf(result.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val m5 = receiveTlv(RpChannel.TIMEOUT_MS)
        Tlv8.error(m5)?.let { throw RemotePairingException("the iPhone stopped pairing (error $it)") }
        expectState(m5, 5)
        val m5Inner = Tlv8.decode(
            RpCrypto.open(setupKey, RpCrypto.labelNonce("PS-Msg05"), Tlv8.collect(m5, Tlv8.ENCRYPTED_DATA))
        )
        val peer = PeerDevice.fromTlv(m5Inner)
        checkControllerSignature(result.key, m5Inner, peer)

        val accessoryX = RpCrypto.hkdf(result.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        val identifier = file.identifier.toByteArray(Charsets.UTF_8)
        val signature = RpCrypto.ed25519Sign(file.privateKey, accessoryX + identifier + file.publicKey)
        val info = Opack.encode(
            linkedMapOf(
                "altIRK" to file.hostAltIrk,
                "btAddr" to "11:22:33:44:55:66",
                "mac" to byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
                "remotepairing_serial_number" to "AAAAAAAAAAAA",
                "accountID" to file.identifier,
                "remotepairing_udid" to "",
                "model" to model,
                "name" to name
            )
        )
        val m6Inner = Tlv8.encode(
            Tlv8.Item(Tlv8.IDENTIFIER, identifier),
            Tlv8.Item(Tlv8.PUBLIC_KEY, file.publicKey),
            Tlv8.Item(Tlv8.SIGNATURE, signature),
            Tlv8.Item(Tlv8.INFO, info)
        )
        val sealed = RpCrypto.seal(setupKey, RpCrypto.labelNonce("PS-Msg06"), m6Inner)
        sendTlv(Tlv8.chunked(Tlv8.ENCRYPTED_DATA, sealed) + Tlv8.Item(Tlv8.STATE, byteArrayOf(6)))
        Log.i(LogTag.PAIR, "${peer.name} (${peer.model}) paired with this phone")
        return peer
    }

    private fun checkControllerSignature(key: ByteArray, items: List<Tlv8.Item>, peer: PeerDevice) {
        val ltpk = peer.longTermPublicKey ?: return
        val controllerX = RpCrypto.hkdf(key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val signed = controllerX + Tlv8.collect(items, Tlv8.IDENTIFIER) + ltpk
        if (!RpCrypto.ed25519Verify(ltpk, signed, Tlv8.collect(items, Tlv8.SIGNATURE))) {
            Log.w(LogTag.PAIR, "the iPhone's pair-setup signature did not verify; continuing as the reference does")
        }
    }

    private fun sendTlv(items: List<Tlv8.Item>) {
        val data = JSONObject()
            .put("data", Base64.encode(Tlv8.encode(items)))
            .put("startNewSession", false)
            .put("kind", RemotePairingClient.SETUP)
        channel.sendPlain(
            JSONObject().put("event", JSONObject().put("_0", JSONObject().put("pairingData", JSONObject().put("_0", data))))
        )
    }

    private fun receiveTlv(timeoutMs: Int): List<Tlv8.Item> {
        while (true) {
            val message = channel.receivePlain(timeoutMs)
            val event = RpChannel.path(message, "event", "_0")
            if (event != null) {
                val data = RpChannel.path(event, "pairingData", "_0")?.optString("data")
                if (!data.isNullOrEmpty()) return Tlv8.decode(Base64.decode(data))
                RpChannel.rejection(event)?.let { throw RemotePairingException("the iPhone stopped pairing: $it") }
            }
            RpChannel.logIgnored(message)
        }
    }

    private fun expectState(items: List<Tlv8.Item>, expected: Int) {
        val state = Tlv8.state(items)
        if (state != expected) {
            throw RemotePairingException("expected pair-setup step $expected, the iPhone sent step $state")
        }
    }

    companion object {
        const val SERVICE_TYPE = "_remotepairing-pairable-host._tcp"
        const val DEFAULT_MODEL = "Mac17,7"
        const val WIRE_PROTOCOL_VERSION = 26
        const val FIRST_MESSAGE_TIMEOUT_MS = 30_000

        /** Time for the user to read the PIN and type it on the iPhone. */
        const val PIN_ENTRY_TIMEOUT_MS = 300_000

        private val random = SecureRandom()

        /** The TXT record the advertisement carries; the instance name is [file]'s identifier. */
        fun txtRecord(file: RpPairingFile, name: String, model: String = DEFAULT_MODEL): Map<String, String> =
            linkedMapOf(
                "name" to name,
                "identifier" to file.identifier,
                "authTag" to Base64.encode(AuthTag.compute(file.hostAltIrk, file.identifier)),
                "model" to model,
                "flags" to "1",
                "ver" to WIRE_PROTOCOL_VERSION.toString(),
                "minVer" to "17"
            )
    }
}
