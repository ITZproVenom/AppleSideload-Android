package dev.applesideload.device.remote

import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import java.util.UUID

/**
 * This phone's remote-pairing identity, and what it learned about one iPhone.
 *
 * The identity (identifier and Ed25519 key pair) is made once and kept: the
 * iPhone remembers it, and the copy handed to SideStore uses it too, so making
 * a new one would quietly break both. [hostAltIrk] is the key this phone's
 * own Bonjour advertisement is tagged with, given to the iPhone in pair-setup.
 * [deviceAltIrk] is the iPhone's, which tags its _remotepairing._tcp
 * advertisement.
 */
data class RpPairingFile(
    val identifier: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val hostAltIrk: ByteArray,
    val deviceAltIrk: ByteArray? = null,
    val udid: String? = null,
    val deviceName: String? = null,
    val deviceModel: String? = null
) {
    init {
        require(privateKey.size == 32 && publicKey.size == 32) { "an Ed25519 key is 32 bytes" }
        require(hostAltIrk.size == 16) { "an altIRK is 16 bytes" }
    }

    fun withDevice(peer: PeerDevice): RpPairingFile = copy(
        deviceAltIrk = peer.altIrk,
        udid = peer.udid.ifBlank { udid },
        deviceName = peer.name,
        deviceModel = peer.model
    )

    /**
     * The record as AltStore-family apps and idevice read it: public_key,
     * private_key, identifier and alt_irk, as an XML plist (SideStore reads
     * the file as text).
     */
    fun toAppPlist(): ByteArray {
        val fields = linkedMapOf<String, Plist>(
            "public_key" to Plist.Data(publicKey),
            "private_key" to Plist.Data(privateKey),
            "identifier" to Plist.Str(identifier)
        )
        deviceAltIrk?.let { fields["alt_irk"] = Plist.Data(it) }
        return XmlPlist.write(Plist.Dict(fields))
    }

    /** Everything, for this app's own store. */
    fun toStoredPlist(): ByteArray {
        val fields = linkedMapOf<String, Plist>(
            "public_key" to Plist.Data(publicKey),
            "private_key" to Plist.Data(privateKey),
            "identifier" to Plist.Str(identifier),
            "host_alt_irk" to Plist.Data(hostAltIrk)
        )
        deviceAltIrk?.let { fields["alt_irk"] = Plist.Data(it) }
        udid?.let { fields["udid"] = Plist.Str(it) }
        deviceName?.let { fields["device_name"] = Plist.Str(it) }
        deviceModel?.let { fields["device_model"] = Plist.Str(it) }
        return XmlPlist.write(Plist.Dict(fields))
    }

    override fun equals(other: Any?): Boolean = other is RpPairingFile &&
        identifier == other.identifier && privateKey.contentEquals(other.privateKey) &&
        publicKey.contentEquals(other.publicKey) && hostAltIrk.contentEquals(other.hostAltIrk) &&
        (deviceAltIrk?.contentEquals(other.deviceAltIrk ?: ByteArray(0)) ?: (other.deviceAltIrk == null)) &&
        udid == other.udid && deviceName == other.deviceName && deviceModel == other.deviceModel

    override fun hashCode(): Int = identifier.hashCode() * 31 + publicKey.contentHashCode()

    companion object {
        /** A new identity: a random lowercase UUID, as the reference uses. */
        fun generate(): RpPairingFile {
            val privateKey = RpCrypto.ed25519Generate()
            val irk = ByteArray(16).also { RpCrypto.random.nextBytes(it) }
            return RpPairingFile(
                identifier = UUID.randomUUID().toString().lowercase(),
                privateKey = privateKey,
                publicKey = RpCrypto.ed25519Public(privateKey),
                hostAltIrk = irk
            )
        }

        fun fromStoredPlist(bytes: ByteArray): RpPairingFile {
            val dict = PlistReader.parse(bytes)
            fun data(key: String) = dict[key]?.asData
            val privateKey = data("private_key")?.takeIf { it.size == 32 }
                ?: throw RemotePairingException("the stored remote pairing has no private key")
            val publicKey = data("public_key")?.takeIf { it.size == 32 } ?: RpCrypto.ed25519Public(privateKey)
            return RpPairingFile(
                identifier = dict["identifier"]?.asString?.takeIf { it.isNotBlank() }
                    ?: throw RemotePairingException("the stored remote pairing has no identifier"),
                privateKey = privateKey,
                publicKey = publicKey,
                hostAltIrk = data("host_alt_irk")?.takeIf { it.size == 16 }
                    ?: ByteArray(16).also { RpCrypto.random.nextBytes(it) },
                deviceAltIrk = data("alt_irk")?.takeIf { it.size == 16 },
                udid = dict["udid"]?.asString,
                deviceName = dict["device_name"]?.asString,
                deviceModel = dict["device_model"]?.asString
            )
        }
    }
}

/** What the iPhone said about itself in pair-setup. */
data class PeerDevice(
    val accountId: String,
    val altIrk: ByteArray,
    val model: String,
    val name: String,
    val udid: String,
    val identifier: String?,
    val longTermPublicKey: ByteArray?
) {
    override fun equals(other: Any?): Boolean = other is PeerDevice && udid == other.udid &&
        accountId == other.accountId && altIrk.contentEquals(other.altIrk)

    override fun hashCode(): Int = udid.hashCode()

    companion object {
        /** Reads the Identifier, PublicKey and the OPACK Info dictionary from a pair-setup TLV. */
        fun fromTlv(items: List<Tlv8.Item>): PeerDevice {
            Tlv8.error(items)?.let { throw RemotePairingException("the device answered pair-setup with error $it") }
            val info = Tlv8.collect(items, Tlv8.INFO)
            if (info.isEmpty()) throw RemotePairingException("the device sent no info about itself")
            val dict = Opack.decode(info) as? Map<*, *>
                ?: throw RemotePairingException("the device's info is not a dictionary")
            fun text(key: String) = dict[key] as? String
                ?: throw RemotePairingException("the device's info has no $key")
            val irk = dict["altIRK"] as? ByteArray
                ?: throw RemotePairingException("the device's info has no altIRK")
            if (irk.size != 16) throw RemotePairingException("the device's altIRK is ${irk.size} bytes, not 16")
            return PeerDevice(
                accountId = text("accountID"),
                altIrk = irk,
                model = text("model"),
                name = text("name"),
                udid = text("remotepairing_udid"),
                identifier = Tlv8.collect(items, Tlv8.IDENTIFIER).takeIf { it.isNotEmpty() }
                    ?.toString(Charsets.UTF_8),
                longTermPublicKey = Tlv8.collect(items, Tlv8.PUBLIC_KEY).takeIf { it.size == 32 }
            )
        }
    }
}
