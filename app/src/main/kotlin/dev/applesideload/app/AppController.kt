package dev.applesideload.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import dev.applesideload.apple.AnisetteServers
import dev.applesideload.apple.AnisetteUnavailable
import dev.applesideload.apple.AppleAuth
import dev.applesideload.apple.AppleAuthException
import dev.applesideload.apple.AppleSession
import dev.applesideload.apple.AuthResult
import dev.applesideload.apple.DeveloperServiceException
import dev.applesideload.apple.DeveloperSession
import dev.applesideload.apple.DeveloperTeam
import dev.applesideload.apple.PendingAuth
import dev.applesideload.apple.TrustedPhoneNumber
import dev.applesideload.core.Log
import dev.applesideload.core.LogLine
import dev.applesideload.core.LogTag
import dev.applesideload.device.ConnectionState
import dev.applesideload.device.DeviceException
import dev.applesideload.device.DeviceInfo
import dev.applesideload.device.DeviceSession
import dev.applesideload.device.DiscoveredDevice
import dev.applesideload.device.InstalledApp
import dev.applesideload.device.RemotePairingNetwork
import dev.applesideload.device.TcpTransport
import dev.applesideload.device.UsbChannel
import dev.applesideload.device.WifiChannel
import dev.applesideload.device.remote.RemoteTunnel
import dev.applesideload.device.remote.RpPairingFile
import dev.applesideload.sideload.InstallOutcome
import dev.applesideload.sideload.InstallSource
import dev.applesideload.sideload.REMOTE_PAIRING_ONLY_FROM_IOS
import dev.applesideload.sideload.ReleaseDownloader
import dev.applesideload.sideload.SideStoreFeatures
import dev.applesideload.sideload.SideloadEngine
import dev.applesideload.sideload.SideloadStep
import dev.applesideload.signing.IpaInfo
import dev.applesideload.signing.IpaPackage
import dev.applesideload.signing.SigningException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/** Everything the screens and the web controller draw from. */
data class UiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val device: DeviceInfo? = null,
    val transport: String? = null,
    val pairingHint: String? = null,
    val account: AppleSession? = null,
    val teams: List<DeveloperTeam> = emptyList(),
    val selectedTeam: DeveloperTeam? = null,
    val twoFactor: TwoFactorPrompt? = null,
    val apps: List<InstalledApp> = emptyList(),
    val selectedIpa: SelectedIpa? = null,
    val step: SideloadStep? = null,
    val busy: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val anisetteServers: List<AnisetteServers.Server> = AnisetteServers.bundled,
    /** Set after an install, for the steps left on the iPhone. */
    val lastOutcome: InstallOutcome? = null,
    /** Bumped with every new error or notice, so a browser can tell a repeat from a new one. */
    val messageSerial: Long = 0,
    /** The newest error or notice; unlike [error] and [notice] it is not cleared once shown. */
    val lastMessage: String? = null,
    val lastMessageIsError: Boolean = false,
    /** Bumped whenever a setting changes, so every view redraws it. */
    val settingsRevision: Int = 0,
    /** Set while this phone is offered to an iPhone for Remote Pairing, and after, until dismissed. */
    val remotePairing: RemotePairingPrompt? = null,
    /** iPhones paired with this phone through Remote Pairing (Wi-Fi on iOS 17 and later). */
    val remoteDevices: List<RemoteDeviceSummary> = emptyList(),
    /** The team's App IDs once read from Apple; null until then. */
    val appIds: List<dev.applesideload.apple.DeveloperAppId>? = null
)

/** Where a wireless pairing stands, for the screens and the web page. */
data class RemotePairingPrompt(
    val stage: Stage,
    /** The name the iPhone lists this phone under. */
    val hostName: String,
    /** The six digits to type on the iPhone, once it has asked for them. */
    val pin: String? = null,
    val message: String? = null,
    val pairedWith: String? = null
) {
    enum class Stage { STARTING, ADVERTISING, PIN, PAIRED, FAILED }
}

/** A Remote Pairing record as the screens show it, without any of its keys. */
data class RemoteDeviceSummary(
    val udid: String,
    val name: String,
    val model: String?,
    val lastAddress: String?
)

data class SelectedIpa(val file: File, val info: IpaInfo)

data class TwoFactorPrompt(
    val pending: PendingAuth,
    val phoneNumbers: List<TrustedPhoneNumber> = emptyList(),
    val numberId: Int? = null
)

/** Whether a requested action was started, and why not when it was not. */
sealed class Action {
    data object Started : Action()
    data class Refused(val reason: String) : Action()
}

/** Settings as the web controller shows them. */
data class SettingsSnapshot(
    val anisetteAddress: String,
    val effectiveAnisetteAddress: String,
    val wifiDiscovery: Boolean,
    val lastAppleId: String,
    val lastWirelessAddress: String
)

/**
 * Every operation the app can do, in one place.
 *
 * The phone's screens and the LAN web controller both call this, so the two
 * always show the same state and can never run two device or Apple operations
 * over each other: an operation asked for while another one is running is
 * refused with the name of the one in progress.
 */
interface Controls {
    val state: StateFlow<UiState>
    val logs: StateFlow<List<LogLine>>
    fun settingsSnapshot(): SettingsSnapshot
    fun dismissMessages()
    fun refreshDevices()
    fun connectById(id: String): Action
    fun connectWireless(address: String): Action
    fun startRemotePairing(): Action
    fun stopRemotePairing()
    fun connectRemote(udid: String): Action
    fun forgetRemote(udid: String): Action
    fun disconnect()
    fun loadApps(): Action
    fun uninstall(bundleId: String): Action
    fun signIn(appleId: String, password: String): Action
    fun submitTwoFactorCode(code: String): Action
    fun requestPhoneCode(numberId: Int): Action
    fun selectTeamById(teamId: String): Action
    fun signOut()
    fun revokeCertificate(): Action
    fun selectIpaFile(file: File): Action
    fun installSource(source: InstallSource): Action
    fun install(): Action
    fun setAnisetteAddress(address: String)
    fun setWifiDiscovery(enabled: Boolean)
    fun exportLogs(): String
}

/**
 * Lives as long as the process, not as long as a screen, so the web
 * controller keeps working with the app in the background.
 *
 * Every device and Apple operation runs off the main thread and reports
 * exactly what happened. Failures keep the reason the layer below gave them,
 * which is what makes the Logs screen worth reading.
 */
class AppController(private val app: SideloadApplication) : Controls {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(UiState())
    override val state: StateFlow<UiState> = _state.asStateFlow()

    override val logs: StateFlow<List<LogLine>> get() = Log.lines

    @Volatile
    private var session: DeviceSession? = null

    /** The USB device behind [session], so unplugging it can end the session. */
    @Volatile
    private var sessionUsbName: String? = null

    /** Guards swapping [session] and [sessionUsbName] together. */
    private val sessionLock = Any()

    /** Keeps the device list current as cables come and go, with or without a screen open. */
    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val gone = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    refreshDevices()
                    if (gone != null && gone.deviceName == sessionUsbName) {
                        Log.w(LogTag.USB, "the iPhone was unplugged")
                        disconnect()
                        set { it.copy(error = "The iPhone was unplugged. Plug it in again and connect.") }
                    }
                }
            }
        }
    }

    private fun set(transform: (UiState) -> UiState) = _state.update { old ->
        val next = transform(old)
        when {
            next.error != null && next.error != old.error ->
                next.copy(messageSerial = old.messageSerial + 1, lastMessage = next.error, lastMessageIsError = true)
            next.notice != null && next.notice != old.notice ->
                next.copy(messageSerial = old.messageSerial + 1, lastMessage = next.notice, lastMessageIsError = false)
            else -> next
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Both are system broadcasts, which reach a not-exported receiver.
        ContextCompat.registerReceiver(app, usbEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshDevices()
        if (app.settings.wifiDiscovery) app.discovery.startWifiDiscovery()
        scope.launch {
            app.discovery.devices.collect { devices -> set { it.copy(discovered = devices) } }
        }
        scope.launch(Dispatchers.IO) {
            val servers = AnisetteServers.fetchList()
            set { it.copy(anisetteServers = servers) }
        }
        scope.launch(Dispatchers.IO) { refreshRemoteDevices() }
    }

    override fun settingsSnapshot() = SettingsSnapshot(
        anisetteAddress = app.settings.anisetteAddress,
        effectiveAnisetteAddress = app.settings.effectiveAnisetteAddress,
        wifiDiscovery = app.settings.wifiDiscovery,
        lastAppleId = app.settings.lastAppleId,
        lastWirelessAddress = app.settings.lastWirelessAddress
    )

    override fun dismissMessages() = set { it.copy(error = null, notice = null) }

    override fun refreshDevices() {
        app.discovery.refreshUsb()
        set { it.copy(discovered = app.discovery.devices.value) }
    }

    // MARK: - Connecting

    override fun connectById(id: String): Action {
        val target = app.discovery.devices.value.firstOrNull { it.id == id }
            ?: return refuse("That device is no longer attached or advertised. Refresh the device list.")
        return connect(target)
    }

    fun connect(target: DiscoveredDevice): Action = when (target) {
        is DiscoveredDevice.Usb -> run("Connecting to ${target.displayName}") { openLockdown(target) }
        is DiscoveredDevice.Wifi -> connectOverWifi(target)
    }

    /** Opens a lockdown session over USB or Wi-Fi, pairing it (Trust) when it has to. */
    private suspend fun openLockdown(target: DiscoveredDevice) {
        dropSession()
        set { it.copy(connection = ConnectionState.CONNECTING, device = null, apps = emptyList(), transport = null) }
        val channel = when (target) {
            is DiscoveredDevice.Usb -> {
                if (!app.discovery.hasPermission(target.device)) {
                    set { it.copy(pairingHint = "Allow USB access in the dialog on this Android phone.") }
                }
                if (!app.discovery.requestPermission(target.device)) {
                    throw DeviceException(
                        operation = "connecting over USB",
                        reason = "Android did not grant access to the device",
                        alternative = "plug the cable in again and allow access on the Android phone"
                    )
                }
                set { it.copy(pairingHint = null) }
                UsbChannel(app.discovery.openUsb(target.device))
            }

            is DiscoveredDevice.Wifi -> WifiChannel(target.host.hostAddress ?: target.host.toString())
        }

        val opened = try {
            DeviceSession.open(channel, app.pairingStore)
        } catch (error: Throwable) {
            // Nothing owns the channel yet; close it so the USB interface is
            // released and the next attempt can claim it.
            runCatching { channel.close() }
            // A reset or an immediate close at the first request is how iOS 27
            // turns away lockdown over Wi-Fi, before any Trust prompt.
            if (target is DiscoveredDevice.Wifi && error is IOException) {
                throw DeviceException(
                    operation = "connecting wirelessly",
                    reason = "the iPhone closed the lockdown connection at the first request",
                    limitation = "iOS 27 no longer lets lockdownd answer over Wi-Fi; there the iPhone is " +
                        "reached wirelessly only through a Remote Pairing tunnel",
                    alternative = PAIR_WIRELESSLY,
                    cause = error
                )
            }
            throw error
        }
        adopt(opened, (target as? DiscoveredDevice.Usb)?.device?.deviceName)
        set {
            it.copy(
                connection = ConnectionState.LOCKDOWN_CONNECTED,
                device = opened.info,
                transport = channel.description
            )
        }

        try {
            opened.establish(onWaitingForTrust = {
                set {
                    it.copy(
                        connection = ConnectionState.PAIRING,
                        pairingHint = "Unlock the iPhone and tap Trust, then enter its passcode."
                    )
                }
            })
        } catch (error: Throwable) {
            // A session that never paired is no use; drop it so the screens do
            // not keep showing "waiting for Trust" and a retry starts clean.
            runCatching { opened.close() }
            if (!release(opened)) {
                // Disconnect (Cancel, on the phone or the web page) took the
                // session away while it waited for Trust: stopped, not failed.
                Log.i(LogTag.APP, "stopped waiting for the iPhone to trust this phone")
                return
            }
            set { it.copy(connection = ConnectionState.ERROR, pairingHint = null, apps = emptyList()) }
            throw error
        }
        if (target is DiscoveredDevice.Usb) {
            // Over USB, let the iPhone accept this phone over Wi-Fi from now on,
            // so later installs can be wireless.
            runCatching { opened.enableWirelessLockdown() }
                .onFailure { Log.w(LogTag.LOCKDOWN, "wireless mode could not be enabled: ${it.message}") }
            // iOS 17 and later: pair through Remote Pairing over the cable as
            // well, which is what reaches the iPhone over Wi-Fi without it
            // (the only way on iOS 27) and what SideStore needs there.
            if (opened.info.majorVersion >= REMOTE_PAIRING_FROM) mintRemotePairing(opened)
        } else {
            // Whatever this phone already holds for the iPhone, for SideStore.
            opened.remotePairing = app.pairingStore.loadRemote(opened.info.udid)
            opened.startHeartbeat()
        }
        set { it.copy(connection = opened.state, pairingHint = null, device = opened.info) }
        readApps()
    }

    /**
     * Wireless mode: connects to an iPhone by its Wi-Fi address, no cable.
     *
     * An iPhone paired with this phone through Remote Pairing (iOS 17 and
     * later) is reached through its tunnel, which is the only way iOS 27
     * allows. Otherwise, or if that fails, lockdownd on the iPhone is asked
     * straight at <address>:62078, as SideInstaller's Side by Side does: the
     * iPhone shows the Trust prompt and everything after that goes over Wi-Fi.
     */
    override fun connectWireless(address: String): Action {
        val trimmed = address.trim()
        val valid = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$").matches(trimmed) &&
            trimmed.split('.').all { it.toInt() in 0..255 }
        if (!valid) {
            return refuse(
                "Enter the iPhone's IPv4 address, for example 192.168.1.23. It is under " +
                    "Settings > Wi-Fi > (i) on the iPhone."
            )
        }
        app.settings.lastWirelessAddress = trimmed
        set { it.copy(settingsRevision = it.settingsRevision + 1) }
        // A literal IPv4 address is parsed, never looked up, so this does not
        // touch the network on the calling thread.
        val host = InetAddress.getByName(trimmed)
        return connectOverWifi(DiscoveredDevice.Wifi(trimmed, host, TcpTransport.LOCKDOWN_PORT))
    }

    private fun connectOverWifi(target: DiscoveredDevice.Wifi): Action = run("Connecting to ${target.displayName}") {
        val address = target.host.hostAddress
        val records = app.pairingStore.remoteDevices()
        if (address == null || records.isEmpty()) {
            openLockdown(target)
        } else {
            try {
                openRemoteAt(address, records)
            } catch (tunnelError: DeviceException) {
                // The iPhone at this address may not be one paired through
                // Remote Pairing, and up to iOS 26 lockdown still answers
                // over Wi-Fi. Report both if neither works.
                Log.i(LogTag.TUNNEL, "no Remote Pairing tunnel at $address (${tunnelError.reason}); trying lockdown over Wi-Fi")
                try {
                    openLockdown(target)
                } catch (lockdownError: DeviceException) {
                    throw DeviceException(
                        operation = "connecting to $address over Wi-Fi",
                        reason = "the Remote Pairing tunnel failed (${tunnelError.reason}), and lockdown " +
                            "over Wi-Fi failed too (${lockdownError.reason})",
                        limitation = "an iPhone on iOS 27 is reached wirelessly only through Remote Pairing",
                        alternative = PAIR_WIRELESSLY,
                        cause = lockdownError
                    )
                }
            }
        }
    }

    override fun disconnect() {
        dropSession()
        set {
            it.copy(
                connection = ConnectionState.DISCONNECTED,
                device = null,
                transport = null,
                pairingHint = null,
                apps = emptyList()
            )
        }
    }

    /** Closes the current session, if there is one, before another is opened. */
    private fun dropSession() {
        val old = synchronized(sessionLock) {
            val current = session
            session = null
            sessionUsbName = null
            current
        }
        old?.close()
    }

    private fun adopt(opened: DeviceSession, usbName: String?) = synchronized(sessionLock) {
        session = opened
        sessionUsbName = usbName
    }

    /** Forgets [expected] if it is still the current session, and says whether it was. */
    private fun release(expected: DeviceSession): Boolean = synchronized(sessionLock) {
        if (session !== expected) return false
        session = null
        sessionUsbName = null
        true
    }

    // MARK: - Remote Pairing (Wi-Fi on iOS 17 and later; the only wireless way on iOS 27)

    private val pairingLock = Any()

    /** Guarded by [pairingLock], like [pairingGeneration]. */
    private var advertisement: RemotePairingNetwork.Advertisement? = null

    /** Bumped by every start and stop, so callbacks from an older offer are ignored. */
    private var pairingGeneration = 0

    /** The name the iPhone lists this phone under. */
    private fun hostName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isEmpty()) "AppleSideload" else "AppleSideload ($model)"
    }

    private fun isCurrentOffer(generation: Int) = synchronized(pairingLock) { generation == pairingGeneration }

    override fun startRemotePairing(): Action {
        val generation = synchronized(pairingLock) {
            if (advertisement?.isOpen == true) return Action.Started
            ++pairingGeneration
        }
        val name = hostName()
        set { it.copy(remotePairing = RemotePairingPrompt(RemotePairingPrompt.Stage.STARTING, name)) }
        scope.launch(Dispatchers.IO) {
            val started = try {
                app.remoteNetwork.advertise(name, pairingEvents(generation, name))
            } catch (error: Exception) {
                if (isCurrentOffer(generation)) {
                    val message = error.message ?: "Android could not offer this phone for pairing"
                    set {
                        it.copy(
                            remotePairing = RemotePairingPrompt(RemotePairingPrompt.Stage.FAILED, name, message = message),
                            error = message
                        )
                    }
                }
                return@launch
            }
            val kept = synchronized(pairingLock) {
                (generation == pairingGeneration && started.isOpen).also { if (it) advertisement = started }
            }
            if (!kept) {
                started.close()
                return@launch
            }
            // An open pairing listener is only needed while someone is pairing.
            delay(PAIRING_OFFER_MS)
            val expired = synchronized(pairingLock) {
                (generation == pairingGeneration && advertisement === started).also { if (it) advertisement = null }
            }
            if (expired) {
                started.close()
                set {
                    it.copy(
                        remotePairing = it.remotePairing?.copy(
                            stage = RemotePairingPrompt.Stage.FAILED,
                            pin = null,
                            message = "Stopped offering this phone after ${PAIRING_OFFER_MS / 60_000} minutes. " +
                                "Tap Pair wirelessly to offer it again."
                        )
                    )
                }
            }
        }
        return Action.Started
    }

    private fun pairingEvents(generation: Int, name: String) = object : RemotePairingNetwork.PairingEvents {
        override fun onAdvertised(serviceName: String) {
            if (!isCurrentOffer(generation)) return
            set {
                it.copy(
                    remotePairing = it.remotePairing?.copy(stage = RemotePairingPrompt.Stage.ADVERTISING, message = null)
                )
            }
        }

        override fun onPin(pin: String) {
            if (!isCurrentOffer(generation)) return
            set {
                it.copy(
                    remotePairing = it.remotePairing?.copy(stage = RemotePairingPrompt.Stage.PIN, pin = pin, message = null)
                )
            }
        }

        override fun onPaired(record: RpPairingFile, host: String?) {
            // The record is stored by now, whichever offer it came through.
            refreshRemoteDevices()
            val current = synchronized(pairingLock) {
                (generation == pairingGeneration).also { if (it) advertisement = null }
            }
            if (!current) return
            val label = record.deviceName ?: "the iPhone"
            val udid = record.udid
            set {
                it.copy(
                    remotePairing = RemotePairingPrompt(RemotePairingPrompt.Stage.PAIRED, name, pairedWith = label),
                    notice = "Paired with $label as \"$name\"."
                )
            }
            if (udid == null || session != null) return
            scope.launch(Dispatchers.IO) {
                // The iPhone's tunnel listener can take a moment after pairing.
                delay(1_500)
                if (_state.value.busy == null && session == null) {
                    run("Connecting to $label over Wi-Fi") { openRemote(udid, host) }
                }
            }
        }

        override fun onProblem(message: String, fatal: Boolean) {
            val current = synchronized(pairingLock) {
                (generation == pairingGeneration).also { if (it && fatal) advertisement = null }
            }
            if (!current) return
            set {
                it.copy(
                    remotePairing = it.remotePairing?.copy(
                        stage = if (fatal) RemotePairingPrompt.Stage.FAILED else RemotePairingPrompt.Stage.ADVERTISING,
                        pin = null,
                        message = message
                    ),
                    error = message
                )
            }
        }
    }

    override fun stopRemotePairing() {
        val running = synchronized(pairingLock) {
            pairingGeneration++
            advertisement.also { advertisement = null }
        }
        running?.close()
        set { it.copy(remotePairing = null) }
    }

    override fun connectRemote(udid: String): Action {
        val known = _state.value.remoteDevices.firstOrNull { it.udid == udid }
            ?: return refuse("That iPhone is not paired with this phone any more. Pair it again.")
        return run("Connecting to ${known.name} over Wi-Fi") { openRemote(udid, null) }
    }

    override fun forgetRemote(udid: String): Action {
        val known = _state.value.remoteDevices.firstOrNull { it.udid == udid }
            ?: return refuse("That iPhone is not in the list of paired iPhones.")
        return run("Forgetting ${known.name}") {
            app.pairingStore.forgetRemote(udid)
            refreshRemoteDevices()
            set {
                it.copy(
                    notice = "Forgot ${known.name}. The iPhone may still list this phone; Clear Trusted " +
                        "Computers in its Settings > Developer removes it there."
                )
            }
        }
    }

    private fun refreshRemoteDevices() {
        val devices = app.pairingStore.remoteDevices().mapNotNull { record ->
            val udid = record.udid ?: return@mapNotNull null
            RemoteDeviceSummary(
                udid = udid,
                name = record.deviceName ?: "iPhone",
                model = record.deviceModel,
                lastAddress = app.pairingStore.remoteHost(udid)
            )
        }.sortedBy { it.name.lowercase() }
        set { it.copy(remoteDevices = devices) }
    }

    /**
     * A session through the Remote Pairing tunnel to [udid]'s iPhone, tried
     * at [firstAddress] if given, then where its authenticated Bonjour
     * advertisement says it is, then where it was last reached.
     */
    private fun openRemote(udid: String, firstAddress: String?) {
        val record = app.pairingStore.loadRemote(udid) ?: throw DeviceException(
            operation = "connecting wirelessly",
            reason = "this phone has no readable Remote Pairing record for that iPhone",
            alternative = PAIR_WIRELESSLY
        )
        val label = record.deviceName ?: "the iPhone"
        val addresses = sequence {
            firstAddress?.let { yield(it to RemoteTunnel.REMOTE_PAIRING_PORT) }
            set { it.copy(pairingHint = "Looking for $label on the network") }
            app.remoteNetwork.locate(record)?.let { found -> yield(addressOf(found) to found.port) }
            app.pairingStore.remoteHost(udid)?.let { yield(it to RemoteTunnel.REMOTE_PAIRING_PORT) }
        }.distinct()
        openTunnelSession(label, listOf(record), addresses, expectedUdid = udid)
    }

    /** A session through the tunnel to whichever iPhone paired with this phone answers at [address]. */
    private fun openRemoteAt(address: String, records: List<RpPairingFile>) {
        // Every record this phone makes shares its identity, so one per
        // identity is enough; the one last used here goes first.
        val lastHere = records.filter { record -> record.udid?.let(app.pairingStore::remoteHost) == address }
        val identities = (lastHere + records).distinctBy { it.identifier }
        openTunnelSession(
            label = "the iPhone at $address",
            identities = identities,
            addresses = sequenceOf(address to RemoteTunnel.REMOTE_PAIRING_PORT),
            expectedUdid = null
        )
    }

    /**
     * Opens a tunnel at the first of [addresses] that accepts one of
     * [identities] and makes a session through it the current one. An address
     * that does not answer, an iPhone that does not know the identity, and,
     * with [expectedUdid], a different iPhone than the one asked for, are each
     * skipped, and reported if nothing else works.
     */
    private fun openTunnelSession(
        label: String,
        identities: List<RpPairingFile>,
        addresses: Sequence<Pair<String, Int>>,
        expectedUdid: String?
    ) {
        dropSession()
        set {
            it.copy(
                connection = ConnectionState.CONNECTING, device = null, apps = emptyList(), transport = null,
                pairingHint = "Connecting to $label"
            )
        }
        val problems = mutableListOf<String>()
        var unrecognisedAt: String? = null
        try {
            candidates@ for ((host, port) in addresses) {
                for (identity in identities) {
                    val tunnel = try {
                        RemoteTunnel.open(host, identity, hostName(), port) { step -> set { it.copy(pairingHint = step) } }
                    } catch (error: DeviceException) {
                        Log.w(LogTag.TUNNEL, "no tunnel at $host: ${error.reason}")
                        problems += "at $host, ${error.reason}"
                        continue@candidates
                    }
                    if (tunnel == null) {
                        Log.w(LogTag.TUNNEL, "the iPhone at $host does not accept this phone's pairing")
                        unrecognisedAt = host
                        continue
                    }
                    // openRemote closes the tunnel itself if it fails.
                    val created = DeviceSession.openRemote(tunnel, app.pairingStore, identity)
                    if (expectedUdid != null && created.info.udid != expectedUdid) {
                        Log.w(LogTag.TUNNEL, "$host is ${created.info.name}, not $label")
                        problems += "$host is ${created.info.name}, not $label"
                        created.close()
                        continue@candidates
                    }
                    adoptTunnelSession(created, identity, host)
                    return
                }
            }
        } catch (error: Throwable) {
            set { it.copy(connection = ConnectionState.ERROR, pairingHint = null, apps = emptyList()) }
            throw error
        }
        set { it.copy(connection = ConnectionState.ERROR, pairingHint = null, apps = emptyList()) }
        val unrecognised = unrecognisedAt
        throw when {
            unrecognised != null && problems.isEmpty() -> DeviceException(
                operation = "connecting to $label",
                reason = "the iPhone at $unrecognised no longer accepts this phone's pairing",
                limitation = "the pairing was removed on the iPhone, or the iPhone was reset",
                alternative = PAIR_WIRELESSLY
            )
            unrecognised != null || problems.isNotEmpty() -> DeviceException(
                operation = "connecting to $label over Wi-Fi",
                reason = (listOfNotNull(unrecognised?.let { "the iPhone at $it no longer accepts this phone's pairing" }) +
                    problems).joinToString("; "),
                limitation = "the iPhone has to be unlocked and on the same Wi-Fi network as this phone",
                alternative = "check the iPhone's address under Settings > Wi-Fi > (i) and enter it under Wireless mode"
            )
            else -> DeviceException(
                operation = "finding $label",
                reason = "it is not advertising Remote Pairing on this network, and no address is known for it",
                limitation = "both phones have to be on the same Wi-Fi network, with the iPhone unlocked",
                alternative = "enter the iPhone's address from Settings > Wi-Fi > (i) under Wireless mode"
            )
        }
    }

    private fun adoptTunnelSession(created: DeviceSession, identity: RpPairingFile, host: String) {
        val udid = created.info.udid
        // The record for this iPhone, under the identity that just opened it,
        // with its current name; the iPhone's own key material is kept.
        val known = app.pairingStore.loadRemote(udid)
        val record = identity.copy(
            udid = udid,
            deviceAltIrk = known?.deviceAltIrk ?: identity.deviceAltIrk.takeIf { identity.udid == udid },
            deviceName = created.info.name,
            deviceModel = created.info.productType.ifBlank { null } ?: known?.deviceModel
        )
        created.remotePairing = record
        created.establish()
        created.startHeartbeat()
        adopt(created, null)
        runCatching { app.pairingStore.saveRemote(record, host) }
            .onFailure { Log.w(LogTag.PAIR, "could not update the Remote Pairing record: ${it.message}") }
        set {
            it.copy(
                connection = created.state,
                device = created.info,
                transport = created.transportDescription,
                pairingHint = null
            )
        }
        refreshRemoteDevices()
        watchTunnel(created)
        readApps()
    }

    /** Ends a tunnel session once its tunnel closes, the way unplugging ends a cable one. */
    private fun watchTunnel(opened: DeviceSession) {
        scope.launch(Dispatchers.IO) {
            while (session === opened) {
                delay(TUNNEL_CHECK_MS)
                if (opened.isAlive) continue
                if (release(opened)) {
                    opened.close()
                    Log.w(LogTag.TUNNEL, "the Wi-Fi tunnel to ${opened.info.name} closed")
                    set {
                        it.copy(
                            connection = ConnectionState.DISCONNECTED,
                            device = null,
                            transport = null,
                            pairingHint = null,
                            apps = emptyList(),
                            error = "The Wi-Fi connection to ${opened.info.name} closed. Connect again with " +
                                "both phones on the same network and the iPhone unlocked."
                        )
                    }
                }
                return@launch
            }
        }
    }

    /**
     * Pairs this phone with the iPhone through Remote Pairing over the cable
     * too, so it can be reached over Wi-Fi later and SideStore gets the
     * record iOS 27 needs. Never fatal: the cable session works without it.
     */
    private fun mintRemotePairing(opened: DeviceSession) {
        val existing = app.pairingStore.loadRemote(opened.info.udid)
        opened.remotePairing = existing
        set { it.copy(pairingHint = "Setting up wireless access (Remote Pairing). If the iPhone asks, allow it.") }
        try {
            val record = opened.mintRemotePairing(app.pairingStore.remoteIdentity(), hostName(), existing)
            if (record != existing) app.pairingStore.saveRemote(record, app.pairingStore.remoteHost(opened.info.udid))
            refreshRemoteDevices()
            Log.i(LogTag.PAIR, "${opened.info.name} can be reached over Wi-Fi through Remote Pairing")
        } catch (error: Exception) {
            opened.remotePairing = existing
            Log.w(
                LogTag.PAIR,
                "Remote Pairing over the cable did not complete: ${Log.describe(error)}. The cable connection " +
                    "is unaffected, and the iPhone can still pair from Settings > Privacy & Security > Developer Mode."
            )
        } finally {
            set { it.copy(pairingHint = null) }
        }
    }

    private fun addressOf(found: InetSocketAddress): String = found.address?.hostAddress ?: found.hostString

    override fun loadApps(): Action {
        // Asking with nothing connected is a mistake to point out, not a failure to log.
        if (session == null) return refuse("Connect and pair an iPhone first.")
        return run("Reading the app list") { readApps() }
    }

    override fun uninstall(bundleId: String): Action {
        if (session == null) return refuse("Connect and pair an iPhone first.")
        return removeApp(bundleId)
    }

    private fun removeApp(bundleId: String): Action = run("Removing $bundleId") {
        val active = requireSession("removing $bundleId")
        active.installationProxy().use { it.uninstall(bundleId) }
        set { it.copy(notice = "Removed $bundleId") }
        readApps()
    }

    private fun requireSession(operation: String): DeviceSession = session ?: throw DeviceException(
        operation = operation,
        reason = "no iPhone is connected",
        alternative = "connect and pair the iPhone first"
    )

    private fun readApps() {
        val active = requireSession("listing apps")
        val apps = active.installationProxy().use { it.browse() }
            .filter { it.isSideloaded }
            .sortedBy { it.name.lowercase() }
        set { it.copy(apps = apps) }
    }

    // MARK: - Account

    override fun signIn(appleId: String, password: String): Action {
        if (appleId.isBlank() || password.isEmpty()) return refuse("Enter both the Apple ID and the password.")
        return run("Signing in to Apple") {
            app.settings.lastAppleId = appleId.trim()
            set { it.copy(twoFactor = null, settingsRevision = it.settingsRevision + 1) }
            val auth = AppleAuth(app.anisetteProvider())
            when (val result = auth.signIn(appleId.trim(), password)) {
                is AuthResult.Success -> finishSignIn(auth, result.session)
                is AuthResult.TrustedDeviceCodeRequired ->
                    set { it.copy(twoFactor = TwoFactorPrompt(result.pending)) }

                is AuthResult.PhoneCodeRequired ->
                    set { it.copy(twoFactor = TwoFactorPrompt(result.pending, result.numbers)) }
            }
        }
    }

    override fun submitTwoFactorCode(code: String): Action {
        val prompt = _state.value.twoFactor ?: return refuse("No sign-in is waiting for a code.")
        if (code.isBlank()) return refuse("Enter the verification code.")
        return run("Checking the verification code") {
            val auth = AppleAuth(app.anisetteProvider())
            val result = if (prompt.numberId != null) {
                auth.submitPhoneCode(prompt.pending, prompt.numberId, code.trim())
            } else {
                auth.submitTrustedDeviceCode(prompt.pending, code.trim())
            }
            when (result) {
                is AuthResult.Success -> {
                    set { it.copy(twoFactor = null) }
                    finishSignIn(auth, result.session)
                }
                // Apple can ask again after accepting a code (SideInstaller
                // loops the same way), so the new request replaces the old.
                is AuthResult.TrustedDeviceCodeRequired ->
                    set { it.copy(twoFactor = TwoFactorPrompt(result.pending)) }

                is AuthResult.PhoneCodeRequired ->
                    set { it.copy(twoFactor = TwoFactorPrompt(result.pending, result.numbers)) }
            }
        }
    }

    override fun requestPhoneCode(numberId: Int): Action {
        val prompt = _state.value.twoFactor ?: return refuse("No sign-in is waiting for a code.")
        return run("Sending a code by SMS") {
            AppleAuth(app.anisetteProvider()).requestPhoneCode(prompt.pending, numberId)
            set { it.copy(twoFactor = prompt.copy(numberId = numberId)) }
        }
    }

    /** The password is never kept: a new sign-in asks for it again. */
    private fun finishSignIn(auth: AppleAuth, signedIn: AppleSession) {
        val withToken = auth.fetchAppToken(signedIn)
        val developer = DeveloperSession(withToken, app.anisetteProvider(), auth)
        val teams = developer.listTeams()
        set {
            it.copy(
                account = withToken,
                teams = teams,
                selectedTeam = teams.firstOrNull(),
                notice = "Signed in as ${signedIn.appleId}"
            )
        }
    }

    fun selectTeam(team: DeveloperTeam) = set { it.copy(selectedTeam = team) }

    override fun selectTeamById(teamId: String): Action {
        val team = _state.value.teams.firstOrNull { it.teamId == teamId }
            ?: return refuse("That team is not one of the signed-in account's teams.")
        if (_state.value.busy != null) return refuse("Wait until \"${_state.value.busy}\" finishes.")
        selectTeam(team)
        return Action.Started
    }

    override fun signOut() {
        _state.value.account?.let { app.identityStore.forgetPassword(it.appleId) }
        set { it.copy(account = null, teams = emptyList(), selectedTeam = null, twoFactor = null) }
    }

    override fun revokeCertificate(): Action {
        val account = _state.value.account ?: return refuse("Sign in first.")
        val team = _state.value.selectedTeam ?: return refuse("Select a team first.")
        return run("Revoking the development certificate") {
            val developer = DeveloperSession(account, app.anisetteProvider(), AppleAuth(app.anisetteProvider()))
            developer.listCertificates(team.teamId).forEach {
                developer.revokeCertificate(team.teamId, it.serialNumber)
            }
            app.identityStore.clearSigningIdentity()
            set { it.copy(notice = "The certificate was revoked. Apps signed with it will no longer launch.") }
        }
    }

    /** Reads the App IDs registered on the selected team. */
    fun loadAppIds(): Action {
        val account = _state.value.account ?: return refuse("Sign in first.")
        val team = _state.value.selectedTeam ?: return refuse("Select a team first.")
        return run("Reading the App IDs") {
            val developer = DeveloperSession(account, app.anisetteProvider(), AppleAuth(app.anisetteProvider()))
            val ids = developer.listAppIds(team.teamId)
            set { it.copy(appIds = ids) }
        }
    }

    /** Deletes one App ID from the team, then reads the list again. */
    fun deleteAppId(appIdId: String): Action {
        val account = _state.value.account ?: return refuse("Sign in first.")
        val team = _state.value.selectedTeam ?: return refuse("Select a team first.")
        return run("Deleting the App ID") {
            val developer = DeveloperSession(account, app.anisetteProvider(), AppleAuth(app.anisetteProvider()))
            developer.deleteAppId(team.teamId, appIdId)
            val ids = developer.listAppIds(team.teamId)
            set {
                it.copy(
                    appIds = ids,
                    notice = "The App ID was deleted. The app that used it will not launch until it is installed again."
                )
            }
        }
    }

    /**
     * Puts the chosen IPA into LiveContainer's folder on the iPhone.
     *
     * LiveContainer shows that folder in Files under On My iPhone, and its
     * "+" button imports from there. The app then runs inside LiveContainer
     * and takes no sideload slot and no App ID of its own.
     */
    fun sendToLiveContainer(): Action {
        if (session == null) return refuse("Connect and pair an iPhone first.")
        val selected = _state.value.selectedIpa ?: return refuse("Choose an IPA first.")
        return run("Sending ${selected.info.name} to LiveContainer") {
            val active = requireSession("sending the IPA to LiveContainer")
            val container = active.installationProxy().use { it.browse() }
                .firstOrNull { it.bundleId.startsWith(LIVECONTAINER_BUNDLE_ID) }
                ?: throw DeviceException(
                    operation = "sending the IPA to LiveContainer",
                    reason = "LiveContainer is not installed on ${active.info.name}",
                    alternative = "install SideStore + LiveContainer from the Install tab first"
                )
            val fileName = selected.info.name.filter { it.isLetterOrDigit() || it in " ._-" }
                .trim().ifBlank { "App" } + ".ipa"
            val total = selected.file.length()
            var last = -1
            active.appContainer(container.bundleId, wholeContainer = true).use { afc ->
                afc.makeDirectories("/Documents")
                if (afc.exists("/Documents/$fileName")) afc.removeTree("/Documents/$fileName")
                selected.file.inputStream().use { source ->
                    afc.writeFile("/Documents/$fileName", source, total) { written ->
                        val percent = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (percent != last) {
                            last = percent
                            set { it.copy(step = SideloadStep.Uploading(percent)) }
                        }
                    }
                }
            }
            set {
                it.copy(
                    step = null,
                    notice = "Sent. In LiveContainer tap +, choose IPA files, then pick $fileName " +
                        "under On My iPhone > LiveContainer."
                )
            }
        }
    }

    // MARK: - Installing

    /** Copies a file the user picked on this phone, then inspects it. */
    fun selectIpa(uri: Uri): Action = run("Reading the app") {
        val target = newImportFile()
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: throw SigningException(operation = "importing the IPA", reason = "the file could not be opened")
            inspect(target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** Takes over an IPA that already sits in [importDirectory], e.g. a web upload. */
    override fun selectIpaFile(file: File): Action = run("Reading the app") {
        try {
            inspect(file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Where imported and uploaded IPAs are kept until another one replaces them. */
    val importDirectory: File get() = File(app.cacheDir, "imports").apply { mkdirs() }

    fun newImportFile(): File = File(importDirectory, "import-${System.nanoTime()}.ipa")

    private fun inspect(file: File) {
        val info = IpaPackage.inspect(file)
        val previous = _state.value.selectedIpa?.file
        set { it.copy(selectedIpa = SelectedIpa(file, info)) }
        if (previous != null && previous != file) previous.delete()
    }

    /** Downloads the official build of [source] and installs it. */
    override fun installSource(source: InstallSource): Action {
        if (source == InstallSource.CUSTOM) return install()
        return run("Installing ${source.title}") {
            val (device, account, team) = requireReady("installing ${source.title}")
            // Only SideStore's newest builds can refresh on iOS 27, so there
            // nightly builds count too.
            val ios = device.info.majorVersion
            val newest = ios >= REMOTE_PAIRING_ONLY_FROM_IOS
            set {
                it.copy(
                    step = SideloadStep.Preparing(
                        if (newest) "finding the newest ${source.title}, nightly builds included" else "finding the latest ${source.title}"
                    ),
                    lastOutcome = null
                )
            }
            val downloader = ReleaseDownloader(File(app.filesDir, "downloads"))
            val build = downloader.latest(source, includeNightly = newest)
            Log.i(LogTag.APP, "${source.title} ${build.label} is the ${if (newest) "newest build" else "latest release"}")
            val ipa = downloader.download(build) { percent -> set { it.copy(step = SideloadStep.Downloading(percent)) } }
            val warning = if (newest && !SideStoreFeatures.supportsRemotePairing(ipa)) {
                "This ${source.title} build (${build.label}) is older than SideStore's iOS $ios support, so " +
                    "SideStore cannot refresh on the iPhone with it yet. Install it again from this app " +
                    "before the 7 days run out" +
                    (if (source == InstallSource.SIDESTORE_LIVECONTAINER) ", or install SideStore only, whose newest build has that support." else ".")
            } else {
                null
            }
            warning?.let { Log.w(LogTag.APP, it) }
            val outcome = engine().install(ipa, source, device, account, team, ::onStep)
            finishInstall(outcome.copy(warning = warning), "${source.title} ${build.label}")
        }
    }

    override fun install(): Action {
        val selected = _state.value.selectedIpa ?: return refuse("Choose or upload an IPA first.")
        return run("Installing ${selected.info.name}") {
            val (device, account, team) = requireReady("installing")
            set { it.copy(lastOutcome = null) }
            finishInstall(
                engine().install(selected.file, InstallSource.CUSTOM, device, account, team, ::onStep),
                selected.info.name
            )
        }
    }

    private fun engine() = SideloadEngine(app, app.anisetteProvider(), app.identityStore)

    private fun onStep(step: SideloadStep) = set { it.copy(step = step) }

    private fun requireReady(operation: String): Triple<DeviceSession, AppleSession, DeveloperTeam> {
        val device = requireSession(operation)
        val account = _state.value.account ?: throw SigningException(
            operation = operation,
            reason = "no Apple account is signed in",
            alternative = "sign in on the Account screen"
        )
        val team = _state.value.selectedTeam ?: throw SigningException(
            operation = operation,
            reason = "no development team is selected"
        )
        return Triple(device, account, team)
    }

    private fun finishInstall(outcome: InstallOutcome, label: String) {
        set {
            it.copy(
                notice = "$label was installed",
                step = null,
                lastOutcome = outcome
            )
        }
        runCatching { readApps() }
            .onFailure { Log.w(LogTag.APP, "the app list could not be read after the install: ${it.message}") }
    }

    // MARK: - Settings

    override fun setAnisetteAddress(address: String) {
        app.settings.anisetteAddress = address
        set { it.copy(settingsRevision = it.settingsRevision + 1) }
    }

    override fun setWifiDiscovery(enabled: Boolean) {
        app.settings.wifiDiscovery = enabled
        if (enabled) app.discovery.startWifiDiscovery() else app.discovery.stopWifiDiscovery()
        if (!enabled) app.discovery.refreshUsb()
        set { it.copy(settingsRevision = it.settingsRevision + 1, discovered = app.discovery.devices.value) }
    }

    override fun exportLogs(): String = Log.export()

    // MARK: - Plumbing

    private fun refuse(reason: String): Action {
        set { it.copy(error = reason) }
        return Action.Refused(reason)
    }

    /**
     * Runs work off the main thread and turns any failure into a message
     * that still says which operation failed and why.
     *
     * Only one operation runs at a time: the busy label is claimed
     * atomically, so the phone and a browser asking at the same moment
     * cannot both start one.
     */
    private fun run(label: String, block: suspend () -> Unit): Action {
        var claimed = false
        var running: String? = null
        _state.update { current ->
            running = current.busy
            claimed = current.busy == null
            if (claimed) current.copy(busy = label, error = null) else current
        }
        if (!claimed) return refuse("Wait until \"$running\" finishes.")
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (error: DeviceException) {
                fail(error.message, error)
            } catch (error: SigningException) {
                fail(error.message, error)
            } catch (error: AppleAuthException) {
                fail(error.message, error)
            } catch (error: DeveloperServiceException) {
                fail(error.message, error)
            } catch (error: AnisetteUnavailable) {
                fail(error.message, error)
            } catch (error: Exception) {
                fail("$label failed: ${error.message ?: error::class.java.simpleName}", error)
            } finally {
                set { it.copy(busy = null) }
            }
        }
        return Action.Started
    }

    private fun fail(message: String?, error: Throwable) {
        val text = message ?: "Something failed without saying why"
        Log.e(LogTag.APP, text)
        set {
            it.copy(
                error = text,
                step = null,
                pairingHint = null,
                connection = if (error is DeviceException && session == null) ConnectionState.ERROR else it.connection
            )
        }
    }

    private companion object {
        /** Remote Pairing, and with it the Wi-Fi tunnel, exists from iOS 17. */
        const val REMOTE_PAIRING_FROM = 17

        /** How long this phone stays offered for pairing when nobody pairs. */
        const val PAIRING_OFFER_MS = 10 * 60_000L

        const val TUNNEL_CHECK_MS = 3_000L

        const val PAIR_WIRELESSLY = "tap Pair wirelessly, then on the iPhone open Settings > Privacy & " +
            "Security > Developer Mode and pick this phone; or connect the iPhone once with a USB cable"
    }
}

private const val LIVECONTAINER_BUNDLE_ID = "com.kdt.livecontainer"
