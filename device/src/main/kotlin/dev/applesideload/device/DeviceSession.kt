package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.core.XmlPlist
import dev.applesideload.device.remote.RemotePairingClient
import dev.applesideload.device.remote.RemoteTunnel
import dev.applesideload.device.remote.RpChannel
import dev.applesideload.device.remote.RpPairingFile
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

/**
 * Wi-Fi through a Remote Pairing tunnel (iOS 17 and later, and the only
 * wireless route on iOS 27). Services are opened by name through the tunnel
 * rather than by asking lockdown for a port.
 */
class TunnelChannel(val tunnel: RemoteTunnel) : DeviceChannel {
    override fun connect(port: Int): Transport = tunnel.connect(port)
    override val description: String get() = tunnel.description
    override fun close() = tunnel.close()
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

    /** How the device is reached, for the screens: "USB", "Wi-Fi (...)" or the tunnel. */
    val transportDescription: String get() = channel.description

    /** True when this session runs through a Remote Pairing tunnel. */
    val isTunnel: Boolean get() = channel is TunnelChannel

    /** False once a tunnel session's tunnel has closed; a cable or lockdown session says true. */
    val isAlive: Boolean get() = (channel as? TunnelChannel)?.tunnel?.isAlive ?: true

    /**
     * The Remote Pairing record for this iPhone, when this phone has one: it
     * is what reaches the iPhone over Wi-Fi on iOS 17 and later, and what
     * SideStore needs on iOS 27.
     */
    @Volatile
    var remotePairing: RpPairingFile? = null

    /**
     * Pairs if needed and opens a session.
     *
     * The Trust dialog is not instant: lockdown answers
     * PairingDialogResponsePending until the user taps it, so this polls for
     * [timeoutMs] and reports exactly why it gave up.
     */
    fun establish(timeoutMs: Long = 60_000, onWaitingForTrust: () -> Unit = {}): Boolean {
        if (isTunnel) {
            // The tunnel itself was opened with pair-verify; there is no
            // lockdown pairing or session to set up inside it.
            state = ConnectionState.READY
            Log.i(
                LogTag.LOCKDOWN,
                "${info.name} is ready through the Wi-Fi tunnel: ${info.productType} on iOS ${info.productVersion}"
            )
            return true
        }
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
        (channel as? TunnelChannel)?.let { return it.tunnel.openService(RemoteTunnel.shimName(name)) }
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

    /**
     * Pairs this phone with the iPhone through Remote Pairing, over this
     * (USB) session: remotepairingdeviced's lockdown service, the same
     * conversation the iPhone has over Wi-Fi, and the consent prompt on the
     * iPhone if it asks. An [existing] record the iPhone still accepts is
     * kept as it is. The result reaches the iPhone over Wi-Fi from then on.
     */
    fun mintRemotePairing(identity: RpPairingFile, hostName: String, existing: RpPairingFile?): RpPairingFile {
        check(!isTunnel) { "a tunnel session is already paired through Remote Pairing" }
        val transport = openService(RemotePairingClient.LOCKDOWN_SERVICE)
        RemotePairingClient(RpChannel(transport), hostName).use { client ->
            client.handshake()
            if (existing != null && client.verify(existing)) {
                Log.i(LogTag.PAIR, "${info.name} still accepts this phone's Remote Pairing")
                remotePairing = existing
                return existing
            }
            // Over the cable the iPhone asks for consent and the PIN is fixed;
            // the same default idevice uses if it asks for one anyway.
            val peer = client.setup(identity) { RemotePairingClient.CABLE_PIN }
            val record = identity.withDevice(peer).let { if (it.udid.isNullOrBlank()) it.copy(udid = info.udid) else it }
            remotePairing = record
            return record
        }
    }

    fun installationProxy(): InstallationProxyClient = InstallationProxyClient(
        PlistService(openService(InstallationProxyClient.SERVICE), "installation_proxy")
    )

    fun misagent(): MisagentClient = MisagentClient(
        PlistService(openService(MisagentClient.SERVICE), "misagent", sendBinary = false)
    )

    @Volatile
    private var heartbeat: Heartbeat? = null

    /**
     * Answers the iPhone's heartbeat for as long as this session is open,
     * over Wi-Fi and through the tunnel: without it iOS closes the service
     * connections of a host it hears nothing from. Over the cable usbmuxd
     * does this, so it is not needed there. Never fatal: if the service will
     * not start, the session goes on without it and says so in the log.
     */
    fun startHeartbeat() {
        if (channel is UsbChannel || heartbeat != null) return
        heartbeat = try {
            Heartbeat.start(PlistService(openService(Heartbeat.SERVICE), "heartbeat", sendBinary = false), info.name)
        } catch (error: Exception) {
            Log.w(
                LogTag.LOCKDOWN,
                "the heartbeat service did not start (${Log.describe(error)}); the iPhone may close a long " +
                    "Wi-Fi session"
            )
            null
        }
    }

    override fun close() {
        state = ConnectionState.DISCONNECTED
        runCatching { heartbeat?.close() }
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
            val info = try {
                readInfo(lockdown)
            } catch (error: Throwable) {
                runCatching { lockdown.close() }
                throw error
            }
            return DeviceSession(channel, store, lockdown, info, store.load(info.udid))
        }

        /**
         * A session through a Remote Pairing tunnel, with lockdown reached as
         * the tunnel's trusted lockdown service. A lockdown pairing record
         * from an earlier cable connection is loaded too, for SideStore.
         * Closes [tunnel] if it fails.
         */
        fun openRemote(tunnel: RemoteTunnel, store: PairingStore, remote: RpPairingFile): DeviceSession {
            val channel = TunnelChannel(tunnel)
            try {
                val lockdown = LockdownClient.openTrusted(tunnel.openService(RemoteTunnel.LOCKDOWN))
                val info = try {
                    readInfo(lockdown)
                } catch (error: Throwable) {
                    runCatching { lockdown.close() }
                    throw error
                }
                return DeviceSession(channel, store, lockdown, info, store.load(info.udid)).also {
                    it.remotePairing = remote
                }
            } catch (error: Throwable) {
                runCatching { channel.close() }
                throw error
            }
        }

        private fun readInfo(lockdown: LockdownClient): DeviceInfo {
            return DeviceInfo(
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
        }
    }
}
