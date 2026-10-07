package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.core.XmlPlist
import java.io.Closeable

/** Where the connection to the iPhone currently stands. */
enum class ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    PAIRING_REQUIRED,
    PAIRING,
    PAIRED,
    LOCKDOWN_CONNECTED,
    READY,
    ERROR
}

/** What the device says about itself once lockdown will talk. */
data class DeviceInfo(
    val udid: String,
    val name: String,
    val productType: String,
    val productVersion: String,
    val buildVersion: String,
    val cpuArchitecture: String,
    val deviceClass: String,
    val serialNumber: String?,
    val wifiAddress: String?
) {
    val majorVersion: Int get() = productVersion.substringBefore('.').toIntOrNull() ?: 0

    /**
     * iOS 17 moved the developer services behind an encrypted tunnel.
     *
     * installation_proxy and AFC still answer over lockdown on these
     * versions, so sideloading works, but anything that used to need the
     * developer disk image does not.
     */
    val needsRemoteTunnel: Boolean get() = majorVersion >= 17
}

/**
 * How the device is reached, opened fresh for every service.
 *
 * lockdown hands back a port number and expects a brand new connection to
 * it, so the session needs to be able to make more connections of the same
 * kind rather than reusing the one it has.
 */
interface DeviceChannel : Closeable {
    fun connect(port: Int): Transport
    val description: String
}

/** USB: every connection is a mux channel on the one claimed interface. */
class UsbChannel(private val transport: UsbTransport) : DeviceChannel {
    private val mux = try {
        MuxDevice(transport).apply { start() }
    } catch (error: Throwable) {
        // Nothing else owns the transport yet. Releasing it here is what lets
        // the next attempt claim the interface instead of finding it taken.
        runCatching { transport.close() }
        throw error
    }
    override fun connect(port: Int): Transport = mux.connect(port)
    override val description: String get() = "USB"
    override fun close() {
        mux.close()
        transport.close()
    }
}

/** Wi-Fi: every connection is a plain TCP connection to the same host. */
class WifiChannel(private val host: String) : DeviceChannel {
    override fun connect(port: Int): Transport = TcpTransport.connect(host, port)
    override val description: String get() = "Wi-Fi ($host)"
    override fun close() = Unit
}

/**
 * One connected iPhone: lockdown, the pairing record, and the services.
 *
 * Nothing here reports success it did not get. If the device refuses to pair
 * the session stays in PAIRING_REQUIRED, and if a service will not start the
 * failure carries the reason lockdown gave.
 */
class DeviceSession private constructor(
    private val channel: DeviceChannel,
    private val store: PairingStore,
    val lockdown: LockdownClient,
    val info: DeviceInfo,
    var record: PairingRecord?
) : Closeable {

    var state: ConnectionState = ConnectionState.LOCKDOWN_CONNECTED
        private set

    /**
     * Pairs if needed and opens a session.
     *
     * The Trust dialog is not instant: lockdown answers
     * PairingDialogResponsePending until the user taps it, so this polls for
     * [timeoutMs] and reports exactly why it gave up.
     */
    fun establish(timeoutMs: Long = 60_000, onWaitingForTrust: () -> Unit = {}): Boolean {
        val existing = record ?: store.load(info.udid)
        if (existing != null) {
            try {
                lockdown.validatePair(existing)
                record = existing
                return startSession(existing)
            } catch (error: DeviceException) {
                Log.w(LogTag.PAIR, "the stored pairing record is no longer valid: ${error.reason}")
                store.forget(info.udid)
            }
        }

        state = ConnectionState.PAIRING_REQUIRED
        val publicKey = lockdown.getValue("DevicePublicKey")?.asData
            ?: throw DeviceException(
                operation = "pairing",
                reason = "the device did not return its public key",
                limitation = "lockdown only returns DevicePublicKey while the device is unlocked",
                alternative = "unlock the iPhone and try again"
            )
        val fresh = PairingRecord.generate(publicKey, info.udid, store.systemBuid)

        state = ConnectionState.PAIRING
        val deadline = System.currentTimeMillis() + timeoutMs
        var announced = false
        while (true) {
            try {
                val paired = lockdown.pair(fresh)
                store.save(paired)
                record = paired
                state = ConnectionState.PAIRED
                return startSession(paired)
            } catch (error: DeviceException) {
                val pending = error.reason.contains("Trust dialog")
                if (!pending) {
                    state = ConnectionState.ERROR
                    throw error
                }
                if (!announced) {
                    announced = true
                    onWaitingForTrust()
                }
                if (System.currentTimeMillis() > deadline) {
                    state = ConnectionState.PAIRING_REQUIRED
                    throw DeviceException(
                        operation = "pairing",
                        reason = "the Trust dialog was not answered in time",
                        iosVersion = info.productVersion,
                        alternative = "unlock the iPhone, tap Trust, and enter its passcode"
                    )
                }
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun startSession(stored: PairingRecord): Boolean {
        // SideStore's minimuxer refuses a pairing file without WiFiMACAddress,
        // so the device's own value is folded in once and kept.
        var record = stored
        if (record.wifiMacAddress.isNullOrBlank() && !info.wifiAddress.isNullOrBlank()) {
            record = record.copy(wifiMacAddress = info.wifiAddress)
            store.save(record)
            this.record = record
        }
        lockdown.startSession(record)
        state = ConnectionState.READY
        Log.i(
            LogTag.LOCKDOWN,
            "${info.name} is ready: ${info.productType} on iOS ${info.productVersion}"
        )
        return true
    }

    /** Starts a service and returns a transport already wrapped in TLS if it asked for it. */
    fun openService(name: String): Transport {
        val descriptor = lockdown.startService(name)
        val transport = channel.connect(descriptor.port)
        if (!descriptor.requiresTls) return transport
        val pairing = record ?: throw DeviceException(
            operation = "starting $name",
            reason = "the service wants TLS but no pairing record is loaded"
        )
        return TlsTransport.handshake(transport, pairing)
    }

    fun afc(): AfcClient = AfcClient(openService(AfcClient.SERVICE))

    /** An installed app's container, over AFC. */
    fun appContainer(bundleId: String, wholeContainer: Boolean): AfcClient =
        HouseArrestClient.open(this, bundleId, wholeContainer)

    /**
     * The pairing record as AltStore-family apps read it.
     *
     * This is the classic lockdown record usbmuxd keeps, with the UDID, the
     * host keys and the escrow bag, as an XML plist. SideStore's minimuxer
     * uses it to reach lockdownd through LocalDevVPN, which is what lets it
     * refresh apps on the iPhone by itself afterwards.
     */
    fun pairingFileForApps(): ByteArray {
        val current = record ?: throw DeviceException(
            operation = "preparing the pairing file",
            reason = "the device is not paired"
        )
        return XmlPlist.write(current.toPlist(withPrivateKeys = true))
    }

    /**
     * Lets lockdownd accept connections that do not come over USB.
     *
     * SideStore talks to lockdownd over the LocalDevVPN loopback, which
     * lockdownd treats as a network connection, so this is set the way
     * SideInstaller sets it.
     */
    fun enableWirelessLockdown() {
        lockdown.setValue("EnableWifiDebugging", Plist.Bool(true), WIRELESS_LOCKDOWN)
        Log.i(LogTag.LOCKDOWN, "wireless lockdown enabled")
        // Also what lets this phone reach the iPhone over Wi-Fi later, the
        // way Finder's "show this iPhone when on Wi-Fi" does.
        runCatching { lockdown.setValue("EnableWifiConnections", Plist.Bool(true), WIRELESS_LOCKDOWN) }
            .onFailure { Log.w(LogTag.LOCKDOWN, "could not enable Wi-Fi connections: ${it.message}") }
    }

    fun installationProxy(): InstallationProxyClient = InstallationProxyClient(
        PlistService(openService(InstallationProxyClient.SERVICE), "installation_proxy")
    )

    fun misagent(): MisagentClient = MisagentClient(
        PlistService(openService(MisagentClient.SERVICE), "misagent", sendBinary = false)
    )

    override fun close() {
        state = ConnectionState.DISCONNECTED
        runCatching { lockdown.close() }
        runCatching { channel.close() }
    }

    companion object {
        private const val POLL_MS = 1_000L
        private const val WIRELESS_LOCKDOWN = "com.apple.mobile.wireless_lockdown"

        /**
         * Opens lockdown over [channel] and reads the device's own account of
         * itself. Nothing is paired yet at this point.
         */
        fun open(channel: DeviceChannel, store: PairingStore): DeviceSession {
            val lockdown = LockdownClient.open(channel.connect(TcpTransport.LOCKDOWN_PORT))
            val info = DeviceInfo(
                udid = lockdown.udid ?: throw DeviceException(
                    operation = "identifying the device",
                    reason = "lockdown did not return a UniqueDeviceID"
                ),
                name = lockdown.deviceName ?: "iPhone",
                productType = lockdown.stringValue("ProductType").orEmpty(),
                productVersion = lockdown.productVersion.orEmpty(),
                buildVersion = lockdown.stringValue("BuildVersion").orEmpty(),
                cpuArchitecture = lockdown.stringValue("CPUArchitecture").orEmpty(),
                deviceClass = lockdown.stringValue("DeviceClass").orEmpty(),
                serialNumber = lockdown.stringValue("SerialNumber"),
                wifiAddress = lockdown.stringValue("WiFiAddress")
            )
            return DeviceSession(channel, store, lockdown, info, store.load(info.udid))
        }
    }
}
