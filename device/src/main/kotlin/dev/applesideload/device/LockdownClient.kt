package dev.applesideload.device

import dev.applesideload.core.Bytes
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import java.io.Closeable

/**
 * lockdownd, the service every other service is asked for.
 *
 * The wire format is a four byte big-endian length followed by an XML
 * property list, in both directions, and that does not change when the
 * connection is upgraded to TLS - only the bytes underneath do. Everything a
 * sideloader needs starts here: the device values, pairing, and the port of
 * installation_proxy or AFC.
 */
class LockdownClient private constructor(
    private var transport: Transport,
    private val label: String
) : Closeable {

    private var sessionId: String? = null
    private var secure = false

    /** True once StartSession has upgraded this connection to TLS. */
    val isSecure: Boolean get() = secure

    fun send(request: Plist.Dict): Plist {
        val body = XmlPlist.write(request)
        transport.write(Bytes.u32be(body.size.toLong()) + body)
        val header = transport.readFully(4)
        val length = Bytes.u32be(header, 0).toInt()
        if (length <= 0 || length > MAX_MESSAGE) {
            throw DeviceException(
                operation = "reading a lockdown reply",
                reason = "the length prefix was $length bytes, which cannot be a plist",
                limitation = "the connection is out of step, usually because a previous " +
                    "request was interrupted"
            )
        }
        val payload = transport.readFully(length, 30_000)
        val reply = PlistReader.parse(payload)
        reply["Error"]?.asString?.let { throw lockdownError(it, reply) }
        return reply
    }

    private fun request(type: String, vararg extra: Pair<String, Plist>): Plist {
        val fields = linkedMapOf<String, Plist>(
            "Label" to Plist.Str(label),
            "Request" to Plist.Str(type)
        )
        extra.forEach { (key, value) -> fields[key] = value }
        sessionId?.let { fields["SessionID"] = Plist.Str(it) }
        return send(Plist.Dict(fields))
    }

    /** Should answer "com.apple.mobile.lockdown". Anything else is not a device. */
    fun queryType(): String = request("QueryType")["Type"]?.asString
        ?: throw DeviceException(
            operation = "identifying the device service",
            reason = "lockdown answered QueryType without a Type"
        )

    fun getValue(key: String? = null, domain: String? = null): Plist? {
        val extra = buildList {
            domain?.let { add("Domain" to Plist.Str(it)) }
            key?.let { add("Key" to Plist.Str(it)) }
        }.toTypedArray()
        return request("GetValue", *extra)["Value"]
    }

    /** Sets a lockdown value. Only allowed inside a session. */
    fun setValue(key: String, value: Plist, domain: String? = null) {
        val extra = buildList {
            domain?.let { add("Domain" to Plist.Str(it)) }
            add("Key" to Plist.Str(key))
            add("Value" to value)
        }.toTypedArray()
        request("SetValue", *extra)
    }

    fun stringValue(key: String, domain: String? = null): String? =
        getValue(key, domain)?.asString

    val productVersion: String? get() = stringValue("ProductVersion")
    val deviceName: String? get() = stringValue("DeviceName")
    val udid: String? get() = stringValue("UniqueDeviceID")

    /**
     * Pairs with the device.
     *
     * The device shows the Trust dialog and answers with
     * PairingDialogResponsePending until the user taps it, so this is called
     * repeatedly by the pairing flow rather than blocking here. The escrow bag
     * in the reply is what lets the device be reached while it is locked, and
     * is only issued when the device is unlocked at the moment of pairing.
     */
    fun pair(record: PairingRecord): PairingRecord {
        val reply = request(
            "Pair",
            "PairRecord" to record.toPlist(withPrivateKeys = false),
            "ProtocolVersion" to Plist.Str("2"),
            "PairingOptions" to Plist.dict("ExtendedPairingErrors" to Plist.Bool(true))
        )
        val escrowBag = reply["EscrowBag"]?.asData
        Log.i(LogTag.PAIR, "the device accepted pairing" + if (escrowBag != null) " with an escrow bag" else "")
        return record.copy(escrowBag = escrowBag)
    }

    /** Checks an existing record without showing the Trust dialog again. */
    fun validatePair(record: PairingRecord) {
        request("ValidatePair", "PairRecord" to record.toPlist(withPrivateKeys = false))
    }

    fun unpair(record: PairingRecord) {
        request("Unpair", "PairRecord" to record.toPlist(withPrivateKeys = false))
    }

    /**
     * Starts a session and upgrades the connection to TLS.
     *
     * Everything useful - StartService included - is refused outside a
     * session, and the session is only granted to a host the device has a
     * pairing record for.
     */
    fun startSession(record: PairingRecord) {
        val reply = request(
            "StartSession",
            "HostID" to Plist.Str(record.hostId),
            "SystemBUID" to Plist.Str(record.systemBuid)
        )
        sessionId = reply["SessionID"]?.asString
        if (reply["EnableSessionSSL"]?.asBool == true) {
            transport = TlsTransport.handshake(transport, record)
            secure = true
        } else {
            Log.w(
                LogTag.LOCKDOWN,
                "the device started a session without asking for TLS, which only happens on " +
                    "very old iOS versions"
            )
        }
    }

    fun stopSession() {
        val id = sessionId ?: return
        sessionId = null
        runCatching { send(Plist.dict("Label" to Plist.Str(label), "Request" to Plist.Str("StopSession"), "SessionID" to Plist.Str(id))) }
    }

    /**
     * Asks for a service and reports where it is listening.
     *
     * The port is a device-side port, so it still has to be reached through
     * the same transport the lockdown connection came over - a mux connect
     * over USB, or a TCP connect over Wi-Fi.
     */
    fun startService(name: String): ServiceDescriptor {
        if (sessionId == null) {
            throw DeviceException(
                operation = "starting $name",
                reason = "lockdown only starts services inside a session",
                limitation = "the device must be paired and StartSession must have succeeded first"
            )
        }
        val reply = request("StartService", "Service" to Plist.Str(name))
        val port = reply["Port"]?.asInt
            ?: throw DeviceException(
                operation = "starting $name",
                reason = "lockdown answered without a port"
            )
        val descriptor = ServiceDescriptor(
            name = name,
            port = port,
            requiresTls = reply["EnableServiceSSL"]?.asBool == true
        )
        Log.i(LogTag.LOCKDOWN, "$name is on port $port" + if (descriptor.requiresTls) " over TLS" else "")
        return descriptor
    }

    override fun close() {
        stopSession()
        transport.close()
    }

    private fun lockdownError(code: String, reply: Plist): DeviceException = when (code) {
        "PasswordProtected" -> DeviceException(
            operation = "connecting to lockdown",
            reason = "the device refused the request because it is locked",
            limitation = "iOS will not answer this request while the screen is locked",
            alternative = "unlock the iPhone and try again"
        )

        "PairingDialogResponsePending" -> DeviceException(
            operation = "pairing",
            reason = "the device is still showing the Trust dialog",
            alternative = "tap Trust on the iPhone, then enter its passcode"
        )

        "UserDeniedPairing" -> DeviceException(
            operation = "pairing",
            reason = "the Trust dialog was declined on the device",
            alternative = "disconnect and reconnect the cable to get the dialog back"
        )

        "InvalidHostID" -> DeviceException(
            operation = "starting a lockdown session",
            reason = "the device does not recognise this host's pairing record",
            limitation = "the device was reset, unpaired, or paired by a different install of " +
                "this app",
            alternative = "pair this device again"
        )

        "SetProhibited", "GetProhibited" -> DeviceException(
            operation = "reading a device value",
            reason = "the device refused: $code",
            limitation = "this value is only readable inside a trusted session"
        )

        "MissingValue" -> DeviceException(
            operation = "reading a device value",
            reason = "the device has no such value"
        )

        else -> DeviceException(
            operation = "a lockdown request",
            reason = "the device answered with the error $code" +
                (reply["ExtendedResponse"]?.let { " (extended response present)" } ?: "")
        )
    }

    companion object {
        /** No lockdown reply is anywhere near this large; the cap stops a bad length. */
        private const val MAX_MESSAGE = 16 * 1024 * 1024

        /**
         * Opens lockdown over an already connected transport and checks that
         * whatever is on the other end really is lockdownd.
         */
        fun open(transport: Transport, label: String = "AppleSideload"): LockdownClient {
            val client = LockdownClient(transport, label)
            val type = client.queryType()
            if (type != "com.apple.mobile.lockdown") {
                throw DeviceException(
                    operation = "connecting to lockdown",
                    reason = "the service identified itself as \"$type\""
                )
            }
            return client
        }
    }
}

/** Where a service ended up, and whether it wants TLS of its own. */
data class ServiceDescriptor(
    val name: String,
    val port: Int,
    val requiresTls: Boolean
)
