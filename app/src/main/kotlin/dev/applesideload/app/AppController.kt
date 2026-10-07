package dev.applesideload.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
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
import dev.applesideload.device.TcpTransport
import dev.applesideload.device.UsbChannel
import dev.applesideload.device.WifiChannel
import dev.applesideload.sideload.InstallOutcome
import dev.applesideload.sideload.InstallSource
import dev.applesideload.sideload.ReleaseDownloader
import dev.applesideload.sideload.SideloadEngine
import dev.applesideload.sideload.SideloadStep
import dev.applesideload.signing.IpaInfo
import dev.applesideload.signing.IpaPackage
import dev.applesideload.signing.SigningException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress

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
    val settingsRevision: Int = 0
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

    fun connect(target: DiscoveredDevice): Action = run("Connecting to ${target.displayName}") {
        session?.close()
        session = null
        sessionUsbName = null
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
            if (target is DiscoveredDevice.Wifi && error is java.io.IOException) {
                throw DeviceException(
                    operation = "connecting wirelessly",
                    reason = "the iPhone closed the lockdown connection at the first request",
                    limitation = "newer iOS versions (iOS 27) no longer let lockdownd pair over Wi-Fi; " +
                        "they only pair wirelessly through Remote Pairing, which this app does not " +
                        "implement yet",
                    alternative = "pair once over a USB cable; after that wireless mode works",
                    cause = error
                )
            }
            throw error
        }
        session = opened
        sessionUsbName = (target as? DiscoveredDevice.Usb)?.device?.deviceName
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
            if (session === opened) {
                session = null
                sessionUsbName = null
            }
            set { it.copy(connection = ConnectionState.ERROR, pairingHint = null, apps = emptyList()) }
            throw error
        }
        // Over USB, let the iPhone accept this phone over Wi-Fi from now on,
        // so later installs can be wireless.
        if (target is DiscoveredDevice.Usb) {
            runCatching { opened.enableWirelessLockdown() }
                .onFailure { Log.w(LogTag.LOCKDOWN, "wireless mode could not be enabled: ${it.message}") }
        }
        set { it.copy(connection = opened.state, pairingHint = null, device = opened.info) }
        readApps()
    }

    /**
     * Wireless mode: connects to an iPhone by its Wi-Fi address, no cable.
     *
     * lockdownd on the iPhone listens on the network as well, and as
     * SideInstaller's Side by Side does, pairing runs straight against
     * <address>:62078 - the iPhone shows the Trust prompt and everything after
     * that, signing and installing included, goes over Wi-Fi.
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
        return connect(DiscoveredDevice.Wifi(trimmed, host, TcpTransport.LOCKDOWN_PORT))
    }

    override fun disconnect() {
        session?.close()
        session = null
        sessionUsbName = null
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
            if (result is AuthResult.Success) {
                set { it.copy(twoFactor = null) }
                finishSignIn(auth, result.session)
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
            set { it.copy(step = SideloadStep.Preparing("finding the latest ${source.title}"), lastOutcome = null) }
            val downloader = ReleaseDownloader(File(app.filesDir, "downloads"))
            val build = downloader.latest(source)
            Log.i(LogTag.APP, "${source.title} ${build.tag} is the latest release")
            val ipa = downloader.download(build) { percent -> set { it.copy(step = SideloadStep.Downloading(percent)) } }
            finishInstall(engine().install(ipa, source, device, account, team, ::onStep), "${source.title} ${build.tag}")
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
}
