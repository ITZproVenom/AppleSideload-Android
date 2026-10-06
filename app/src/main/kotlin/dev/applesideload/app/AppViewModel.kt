package dev.applesideload.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import dev.applesideload.device.UsbChannel
import dev.applesideload.device.WifiChannel
import dev.applesideload.sideload.SideloadEngine
import dev.applesideload.sideload.SideloadStep
import dev.applesideload.signing.IpaInfo
import dev.applesideload.signing.IpaPackage
import dev.applesideload.signing.SigningException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the screens draw from. */
data class UiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val device: DeviceInfo? = null,
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
    val anisetteServers: List<AnisetteServers.Server> = AnisetteServers.bundled
)

data class SelectedIpa(val file: File, val info: IpaInfo)

data class TwoFactorPrompt(
    val pending: PendingAuth,
    val phoneNumbers: List<TrustedPhoneNumber> = emptyList(),
    val numberId: Int? = null
)

/**
 * The app's one view model.
 *
 * Every device and Apple operation runs off the main thread and reports
 * exactly what happened. Failures keep the reason the layer below gave them,
 * which is what makes the Diagnostics screen worth reading.
 */
class AppViewModel(application: SideloadApplication) : AndroidViewModel(application) {

    private val app: SideloadApplication get() = getApplication()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val logs: StateFlow<List<LogLine>> get() = Log.lines

    private var session: DeviceSession? = null

    init {
        refreshDevices()
        if (app.settings.wifiDiscovery) app.discovery.startWifiDiscovery()
        viewModelScope.launch {
            app.discovery.devices.collect { devices ->
                _state.value = _state.value.copy(discovered = devices)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val servers = AnisetteServers.fetchList()
            _state.value = _state.value.copy(anisetteServers = servers)
        }
    }

    fun dismissMessages() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    fun refreshDevices() {
        app.discovery.refreshUsb()
        _state.value = _state.value.copy(discovered = app.discovery.devices.value)
    }

    // MARK: - Connecting

    fun connect(target: DiscoveredDevice) = run("Connecting") {
        _state.value = _state.value.copy(connection = ConnectionState.CONNECTING)
        val channel = when (target) {
            is DiscoveredDevice.Usb -> {
                if (!app.discovery.requestPermission(target.device)) {
                    throw DeviceException(
                        operation = "connecting over USB",
                        reason = "Android did not grant access to the device",
                        alternative = "plug the cable in again and allow access"
                    )
                }
                UsbChannel(app.discovery.openUsb(target.device))
            }

            is DiscoveredDevice.Wifi -> WifiChannel(target.host.hostAddress ?: target.host.toString())
        }

        val opened = DeviceSession.open(channel, app.pairingStore)
        session = opened
        _state.value = _state.value.copy(
            connection = ConnectionState.LOCKDOWN_CONNECTED,
            device = opened.info
        )

        opened.establish(onWaitingForTrust = {
            _state.value = _state.value.copy(
                connection = ConnectionState.PAIRING,
                pairingHint = "Unlock the iPhone and tap Trust, then enter its passcode."
            )
        })
        _state.value = _state.value.copy(
            connection = opened.state,
            pairingHint = null,
            device = opened.info
        )
        loadApps()
    }

    fun disconnect() {
        session?.close()
        session = null
        _state.value = _state.value.copy(
            connection = ConnectionState.DISCONNECTED,
            device = null,
            apps = emptyList()
        )
    }

    fun loadApps() = run("Reading the app list") {
        val active = session ?: throw DeviceException(
            operation = "listing apps",
            reason = "no device is connected"
        )
        val apps = active.installationProxy().use { it.browse() }
            .filter { it.isSideloaded }
            .sortedBy { it.name.lowercase() }
        _state.value = _state.value.copy(apps = apps)
    }

    fun uninstall(bundleId: String) = run("Removing $bundleId") {
        val active = session ?: return@run
        active.installationProxy().use { it.uninstall(bundleId) }
        _state.value = _state.value.copy(notice = "Removed $bundleId")
        loadAppsBlocking()
    }

    private fun loadAppsBlocking() {
        val active = session ?: return
        val apps = active.installationProxy().use { it.browse() }
            .filter { it.isSideloaded }
            .sortedBy { it.name.lowercase() }
        _state.value = _state.value.copy(apps = apps)
    }

    // MARK: - Account

    fun signIn(appleId: String, password: String) = run("Signing in to Apple") {
        app.settings.lastAppleId = appleId
        val auth = AppleAuth(app.anisetteProvider())
        when (val result = auth.signIn(appleId.trim(), password)) {
            is AuthResult.Success -> finishSignIn(auth, result.session, password)
            is AuthResult.TrustedDeviceCodeRequired ->
                _state.value = _state.value.copy(twoFactor = TwoFactorPrompt(result.pending))

            is AuthResult.PhoneCodeRequired ->
                _state.value = _state.value.copy(
                    twoFactor = TwoFactorPrompt(result.pending, result.numbers)
                )
        }
    }

    fun submitTwoFactorCode(code: String) = run("Checking the verification code") {
        val prompt = _state.value.twoFactor ?: return@run
        val auth = AppleAuth(app.anisetteProvider())
        val result = if (prompt.numberId != null) {
            auth.submitPhoneCode(prompt.pending, prompt.numberId, code.trim())
        } else {
            auth.submitTrustedDeviceCode(prompt.pending, code.trim())
        }
        if (result is AuthResult.Success) {
            _state.value = _state.value.copy(twoFactor = null)
            finishSignIn(auth, result.session, null)
        }
    }

    fun requestPhoneCode(numberId: Int) = run("Sending a code by SMS") {
        val prompt = _state.value.twoFactor ?: return@run
        AppleAuth(app.anisetteProvider()).requestPhoneCode(prompt.pending, numberId)
        _state.value = _state.value.copy(twoFactor = prompt.copy(numberId = numberId))
    }

    private fun finishSignIn(auth: AppleAuth, signedIn: AppleSession, password: String?) {
        val withToken = auth.fetchAppToken(signedIn)
        password?.let { app.identityStore.savePassword(signedIn.appleId, it) }
        val developer = DeveloperSession(withToken, app.anisetteProvider(), auth)
        val teams = developer.listTeams()
        _state.value = _state.value.copy(
            account = withToken,
            teams = teams,
            selectedTeam = teams.firstOrNull(),
            notice = "Signed in as ${signedIn.appleId}"
        )
    }

    fun selectTeam(team: DeveloperTeam) {
        _state.value = _state.value.copy(selectedTeam = team)
    }

    fun signOut() {
        _state.value.account?.let { app.identityStore.forgetPassword(it.appleId) }
        _state.value = _state.value.copy(account = null, teams = emptyList(), selectedTeam = null)
    }

    fun revokeCertificate() = run("Revoking the development certificate") {
        val account = _state.value.account ?: return@run
        val team = _state.value.selectedTeam ?: return@run
        val developer = DeveloperSession(account, app.anisetteProvider(), AppleAuth(app.anisetteProvider()))
        developer.listCertificates(team.teamId).forEach {
            developer.revokeCertificate(team.teamId, it.serialNumber)
        }
        app.identityStore.clearSigningIdentity()
        _state.value = _state.value.copy(
            notice = "The certificate was revoked. Apps signed with it will no longer launch."
        )
    }

    // MARK: - Installing

    fun selectIpa(uri: Uri) = run("Reading the app") {
        val context: Context = app
        val target = File(context.cacheDir, "import-${System.currentTimeMillis()}.ipa")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw SigningException(
            operation = "importing the IPA",
            reason = "the file could not be opened"
        )
        val info = IpaPackage.inspect(target)
        _state.value = _state.value.copy(selectedIpa = SelectedIpa(target, info))
    }

    fun install() = run("Installing") {
        val selected = _state.value.selectedIpa ?: throw SigningException(
            operation = "installing",
            reason = "no app has been chosen"
        )
        val device = session ?: throw DeviceException(
            operation = "installing",
            reason = "no iPhone is connected"
        )
        val account = _state.value.account ?: throw SigningException(
            operation = "installing",
            reason = "no Apple account is signed in",
            alternative = "sign in on the Account screen"
        )
        val team = _state.value.selectedTeam ?: throw SigningException(
            operation = "installing",
            reason = "no development team is selected"
        )
        val engine = SideloadEngine(app, app.anisetteProvider(), app.identityStore)
        engine.install(selected.file, device, account, team) { step ->
            _state.value = _state.value.copy(step = step)
        }
        loadAppsBlocking()
        _state.value = _state.value.copy(
            notice = "${selected.info.name} was installed",
            step = null
        )
    }

    fun exportLogs(): String = Log.export()

    // MARK: - Plumbing

    /**
     * Runs work off the main thread and turns any failure into a message
     * that still says which operation failed and why.
     */
    private fun run(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = label, error = null)
            try {
                withContext(Dispatchers.IO) { block() }
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
                _state.value = _state.value.copy(busy = null)
            }
        }
    }

    private fun fail(message: String?, error: Throwable) {
        val text = message ?: "Something failed without saying why"
        Log.e(LogTag.APP, text)
        _state.value = _state.value.copy(
            error = text,
            connection = if (error is DeviceException && session == null) {
                ConnectionState.ERROR
            } else {
                _state.value.connection
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        session?.close()
        app.discovery.stopWifiDiscovery()
    }
}
