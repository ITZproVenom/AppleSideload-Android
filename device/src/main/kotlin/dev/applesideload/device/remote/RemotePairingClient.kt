package dev.applesideload.device.remote

import dev.applesideload.core.Base64
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import org.json.JSONObject
import java.io.Closeable

/**
 * This phone as the remote-pairing host: it dials the iPhone's
 * _remotepairing._tcp listener (or its remotepairingdeviced lockdown service
 * over USB), proves who it is with pair-verify, and asks for a tunnel.
 *
 * Follows idevice's RemotePairingClient message for message.
 */
class RemotePairingClient(
    private val channel: RpChannel,
    private val hostName: String
) : Closeable {

    /** The X25519 secret from pair-verify (or the SRP key after pair-setup): the tunnel's PSK. */
    var encryptionKey: ByteArray = ByteArray(32)
        private set

    private var clientKey: ByteArray = RpCrypto.hkdf(encryptionKey, null, "ClientEncrypt-main")
    private var serverKey: ByteArray = RpCrypto.hkdf(encryptionKey, null, "ServerEncrypt-main")
    private var encryptedCounter = 0L
    private var verified = false

    /**
     * Opens the conversation. Returns the device's handshake answer, which
     * says what it allows (deviceOptions) and who it is (peerDeviceInfo).
     */
    fun handshake(): JSONObject {
        channel.sendPlain(
            JSONObject().put(
                "request",
                JSONObject().put(
                    "_0",
                    JSONObject().put(
                        "handshake",
                        JSONObject().put(
                            "_0",
                            JSONObject()
                                .put("hostOptions", JSONObject().put("attemptPairVerify", true))
                                .put("wireProtocolVersion", WIRE_PROTOCOL_VERSION)
                        )
                    )
                )
            )
        )
        while (true) {
            val reply = channel.receivePlain()
            RpChannel.path(reply, "response", "_1", "handshake", "_0")?.let { return it }
            RpChannel.path(reply, "event", "_0")?.let { event ->
                RpChannel.rejection(event)?.let { throw RemotePairingException("the device refused the connection: $it") }
            }
            RpChannel.logIgnored(reply)
        }
    }

    /**
     * Pair-verify with a stored identity. Returns false, having told the
     * device so, when the device does not know this identity any more.
     */
    fun verify(file: RpPairingFile): Boolean {
        val ephemeral = RpCrypto.X25519KeyPair()
        sendPairingData(
            Tlv8.encode(Tlv8.Item(Tlv8.STATE, byteArrayOf(1)), Tlv8.Item(Tlv8.PUBLIC_KEY, ephemeral.publicKey)),
            kind = VERIFY,
            startNewSession = true
        )
        val m2 = Tlv8.decode(receivePairingData())
        if (Tlv8.has(m2, Tlv8.ERROR)) {
            Log.w(LogTag.PAIR, "the device refused pair-verify at the first step (error ${Tlv8.error(m2)})")
            sendVerifyFailed()
            return false
        }
        val devicePublic = Tlv8.collect(m2, Tlv8.PUBLIC_KEY)
        val shared = ephemeral.agree(devicePublic)
        val key = RpCrypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")

        // The device's own proof, for the log: its identifier, signed together
        // with both ephemeral keys. The reference does not check it either; a
        // device that fails it is not trusted any further than the next step.
        val deviceProof = Tlv8.collect(m2, Tlv8.ENCRYPTED_DATA)
        if (deviceProof.isNotEmpty()) {
            runCatching { Tlv8.decode(RpCrypto.open(key, RpCrypto.labelNonce("PV-Msg02"), deviceProof)) }
                .onSuccess { inner ->
                    val id = Tlv8.collect(inner, Tlv8.IDENTIFIER).toString(Charsets.UTF_8)
                    Log.d(LogTag.PAIR, "pair-verify: the device identified itself as $id")
                }
                .onFailure { Log.w(LogTag.PAIR, "pair-verify: could not read the device's proof: ${it.message}") }
        }

        val signed = ephemeral.publicKey + file.identifier.toByteArray(Charsets.UTF_8) + devicePublic
        val signature = RpCrypto.ed25519Sign(file.privateKey, signed)
        val inner = Tlv8.encode(
            Tlv8.Item(Tlv8.IDENTIFIER, file.identifier.toByteArray(Charsets.UTF_8)),
            Tlv8.Item(Tlv8.SIGNATURE, signature)
        )
        val sealed = RpCrypto.seal(key, RpCrypto.labelNonce("PV-Msg03"), inner)
        sendPairingData(
            Tlv8.encode(Tlv8.Item(Tlv8.STATE, byteArrayOf(3)), Tlv8.Item(Tlv8.ENCRYPTED_DATA, sealed)),
            kind = VERIFY,
            startNewSession = false
        )
        val m4 = Tlv8.decode(receivePairingData())
        if (Tlv8.has(m4, Tlv8.ERROR)) {
            Log.w(LogTag.PAIR, "the device did not accept this phone's pairing (error ${Tlv8.error(m4)})")
            sendVerifyFailed()
            return false
        }
        useKey(shared)
        verified = true
        Log.i(LogTag.PAIR, "pair-verify succeeded")
        return true
    }

    /**
     * Pair-setup, with this phone as the controller. Over USB the device
     * answers with awaitingUserConsent and the PIN is the fixed "000000";
     * a device that shows a PIN on screen gets it from [pin].
     */
    fun setup(file: RpPairingFile, pin: () -> String): PeerDevice {
        sendPairingData(
            Tlv8.encode(Tlv8.Item(Tlv8.METHOD, byteArrayOf(0)), Tlv8.Item(Tlv8.STATE, byteArrayOf(1))),
            kind = SETUP,
            startNewSession = true,
            withHost = true
        )
        val (m2Data, fixedPin) = receiveSetupM2()
        val m2 = Tlv8.decode(m2Data)
        Tlv8.error(m2)?.let { throw RemotePairingException("the device answered pair-setup with error $it") }
        val salt = Tlv8.collect(m2, Tlv8.SALT)
        val serverPublic = Tlv8.collect(m2, Tlv8.PUBLIC_KEY)
        if (salt.isEmpty() || serverPublic.isEmpty()) {
            throw RemotePairingException("the device's pair-setup reply has no salt or public key")
        }
        val code = fixedPin ?: pin()
        if (!Regex("^[0-9]{6}$").matches(code)) throw RemotePairingException("the PIN must be six digits")

        val srp = Srp.Client()
        val result = srp.process(salt, serverPublic, code)
        val a = Srp.bytes(srp.publicKey)
        sendPairingData(
            Tlv8.encode(
                listOf(Tlv8.Item(Tlv8.STATE, byteArrayOf(3))) +
                    splitLikeReference(Tlv8.PUBLIC_KEY, a) +
                    Tlv8.Item(Tlv8.PROOF, result.clientProof)
            ),
            kind = SETUP,
            startNewSession = false,
            withHost = true
        )
        val m4 = Tlv8.decode(receivePairingData())
        Tlv8.error(m4)?.let {
            throw RemotePairingException(
                if (it == Tlv8.ERROR_AUTHENTICATION) "the device did not accept the PIN" else "pair-setup failed with error $it"
            )
        }
        if (!result.checkServer(Tlv8.collect(m4, Tlv8.PROOF))) {
            throw RemotePairingException("the device's SRP proof is wrong, so it is not the device that showed the PIN")
        }

        val setupKey = RpCrypto.hkdf(result.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val controllerX = RpCrypto.hkdf(result.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val identifier = file.identifier.toByteArray(Charsets.UTF_8)
        val signature = RpCrypto.ed25519Sign(file.privateKey, controllerX + identifier + file.publicKey)
        val info = Opack.encode(
            linkedMapOf(
                "altIRK" to file.hostAltIrk,
                "btAddr" to "11:22:33:44:55:66",
                "mac" to byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
                "remotepairing_serial_number" to "AAAAAAAAAAAA",
                "accountID" to file.identifier,
                "model" to "computer-model",
                "name" to hostName
            )
        )
        val m5Inner = Tlv8.encode(
            Tlv8.Item(Tlv8.IDENTIFIER, identifier),
            Tlv8.Item(Tlv8.PUBLIC_KEY, file.publicKey),
            Tlv8.Item(Tlv8.SIGNATURE, signature),
            Tlv8.Item(Tlv8.INFO, info)
        )
        val m5Sealed = RpCrypto.seal(setupKey, RpCrypto.labelNonce("PS-Msg05"), m5Inner)
        sendPairingData(
            Tlv8.encode(
                splitLikeReference(Tlv8.ENCRYPTED_DATA, m5Sealed) + Tlv8.Item(Tlv8.STATE, byteArrayOf(5))
            ),
            kind = SETUP,
            startNewSession = false,
            withHost = true
        )
        val m6 = Tlv8.decode(receivePairingData())
        Tlv8.error(m6)?.let { throw RemotePairingException("the device refused to save this phone's pairing (error $it)") }
        val m6Inner = Tlv8.decode(
            RpCrypto.open(setupKey, RpCrypto.labelNonce("PS-Msg06"), Tlv8.collect(m6, Tlv8.ENCRYPTED_DATA))
        )
        val peer = PeerDevice.fromTlv(m6Inner)
        checkAccessorySignature(result.key, m6Inner, peer)
        useKey(result.key)
        Log.i(LogTag.PAIR, "paired with ${peer.name} (${peer.model})")
        return peer
    }

    /** Asks the device to open a TLS-PSK tunnel listener; returns its port. */
    fun createListener(): Int {
        check(verified) { "createListener needs a verified connection" }
        val request = JSONObject().put(
            "request",
            JSONObject().put(
                "_0",
                JSONObject().put(
                    "createListener",
                    JSONObject()
                        .put("key", Base64.encode(encryptionKey))
                        .put("transportProtocolType", "tcp")
                )
            )
        )
        val response = encryptedRequest(request)
        val listener = RpChannel.path(response, "createListener")
            ?: throw RemotePairingException("the device did not open a tunnel listener: ${response.toString().take(300)}")
        val port = listener.optInt("port", -1)
        if (port !in 1..65535) throw RemotePairingException("the device named tunnel port $port")
        return port
    }

    /** Sends one encrypted request and returns response._1 of the encrypted answer. */
    fun encryptedRequest(request: JSONObject): JSONObject {
        val nonce = RpCrypto.counterNonce(encryptedCounter)
        channel.sendEncrypted(RpCrypto.seal(clientKey, nonce, request.toString().toByteArray(Charsets.UTF_8)))
        while (true) {
            val envelope = channel.receive()
            val sealed = envelope.encrypted
            if (sealed == null) {
                envelope.plain?.let { plain ->
                    RpChannel.path(plain, "event", "_0")?.let { event ->
                        RpChannel.rejection(event)?.let { throw RemotePairingException("the device ended the session: $it") }
                    }
                    RpChannel.logIgnored(plain)
                }
                continue
            }
            val plain = RpCrypto.open(serverKey, nonce, sealed)
            encryptedCounter++
            val json = JSONObject(String(plain, Charsets.UTF_8))
            return RpChannel.path(json, "response", "_1")
                ?: throw RemotePairingException("the device's encrypted answer has no response: ${json.toString().take(300)}")
        }
    }

    /** The device's M2, and the PIN to use when it asked for consent instead of showing one. */
    private fun receiveSetupM2(): Pair<ByteArray, String?> {
        while (true) {
            val reply = channel.receivePlain()
            val event = RpChannel.path(reply, "event", "_0")
            if (event == null) {
                RpChannel.logIgnored(reply)
                continue
            }
            RpChannel.rejection(event)?.let { throw RemotePairingException("the device refused to pair: $it") }
            if (event.has("awaitingUserConsent")) {
                Log.i(LogTag.PAIR, "the device is asking for consent; answer the prompt on the iPhone")
                return receivePairingData(CONSENT_TIMEOUT_MS) to "000000"
            }
            val data = RpChannel.path(event, "pairingData", "_0")?.optString("data")
            if (!data.isNullOrEmpty()) return Base64.decode(data) to null
            RpChannel.logIgnored(reply)
        }
    }

    private fun useKey(key: ByteArray) {
        encryptionKey = key
        clientKey = RpCrypto.hkdf(key, null, "ClientEncrypt-main")
        serverKey = RpCrypto.hkdf(key, null, "ServerEncrypt-main")
        encryptedCounter = 0
    }

    private fun checkAccessorySignature(key: ByteArray, items: List<Tlv8.Item>, peer: PeerDevice) {
        val ltpk = peer.longTermPublicKey ?: return
        val identifier = Tlv8.collect(items, Tlv8.IDENTIFIER)
        val signature = Tlv8.collect(items, Tlv8.SIGNATURE)
        val accessoryX = RpCrypto.hkdf(key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        if (!RpCrypto.ed25519Verify(ltpk, accessoryX + identifier + ltpk, signature)) {
            Log.w(LogTag.PAIR, "the device's pair-setup signature did not verify; continuing as the reference does")
        }
    }

    private fun sendVerifyFailed() {
        channel.sendPlain(JSONObject().put("event", JSONObject().put("_0", JSONObject().put("pairVerifyFailed", JSONObject()))))
    }

    private fun sendPairingData(tlv: ByteArray, kind: String, startNewSession: Boolean, withHost: Boolean = false) {
        val data = JSONObject()
            .put("data", Base64.encode(tlv))
            .put("kind", kind)
            .put("startNewSession", startNewSession)
        if (withHost) data.put("sendingHost", hostName)
        channel.sendPlain(
            JSONObject().put("event", JSONObject().put("_0", JSONObject().put("pairingData", JSONObject().put("_0", data))))
        )
    }

    private fun receivePairingData(timeoutMs: Int = RpChannel.TIMEOUT_MS): ByteArray {
        while (true) {
            val reply = channel.receivePlain(timeoutMs)
            val event = RpChannel.path(reply, "event", "_0")
            if (event == null) {
                RpChannel.logIgnored(reply)
                continue
            }
            RpChannel.path(event, "pairingData", "_0")?.optString("data")?.takeIf { it.isNotEmpty() }
                ?.let { return Base64.decode(it) }
            RpChannel.rejection(event)?.let { throw RemotePairingException("the device refused to pair: $it") }
            if (event.has("pairVerifyFailed")) throw RemotePairingException("the device ended pair-verify")
            RpChannel.logIgnored(reply)
        }
    }

    override fun close() = channel.close()

    companion object {
        const val WIRE_PROTOCOL_VERSION = 19
        const val SETUP = "setupManualPairing"
        const val VERIFY = "verifyManualPairing"

        /** remotepairingdeviced's lockdown service: the same conversation, over the cable. */
        const val LOCKDOWN_SERVICE = "com.apple.dt.remotepairingdeviced.lockdown"

        /** The PIN for pair-setup over the cable, where the iPhone asks for consent instead. */
        const val CABLE_PIN = "000000"

        /** The user may take a while to answer a consent prompt. */
        const val CONSENT_TIMEOUT_MS = 120_000

        /** The reference sends these values as a 254-byte piece and the rest. */
        fun splitLikeReference(type: Int, value: ByteArray): List<Tlv8.Item> =
            if (value.size <= 254) listOf(Tlv8.Item(type, value))
            else listOf(Tlv8.Item(type, value.copyOfRange(0, 254)), Tlv8.Item(type, value.copyOfRange(254, value.size)))
    }
}
