package dev.applesideload.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.applesideload.app.ui.AccountScreen
import dev.applesideload.app.ui.AppScaffold
import dev.applesideload.app.ui.AppleSideloadTheme
import dev.applesideload.app.ui.AppsScreen
import dev.applesideload.app.ui.Destination
import dev.applesideload.app.ui.InstallScreen
import dev.applesideload.app.ui.IphoneScreen
import dev.applesideload.app.ui.LogsScreen
import dev.applesideload.app.ui.SettingsScreen
import dev.applesideload.app.web.WebControlService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val sideload: SideloadApplication get() = application as SideloadApplication

    /** Lives with the process, shared with the web controller. */
    private val viewModel: AppController get() = sideload.controller

    /** A tab asked for from outside the UI: an IPA opened from another app, or an iPhone plugged in. */
    private val requested = MutableStateFlow<Destination?>(null)

    private val askForNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // The controller runs either way; without the permission its
        // notification (address, Stop) is just not shown.
        WebControlService.start(this)
        if (!granted) {
            dev.applesideload.core.Log.w(
                dev.applesideload.core.LogTag.APP,
                "notifications are off, so the web controller's address only shows in Settings"
            )
        }
    }

    private fun startWebControl() {
        sideload.webControl.clearError()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            WebControlService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has already acted on the intent it was started with.
        if (savedInstanceState == null) handle(intent)
        val version = versionName()
        setContent {
            AppleSideloadTheme {
                Root(version)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** An .ipa opened from a file manager or browser, or an iPhone plugged in. */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                viewModel.selectIpa(uri)
                requested.value = Destination.INSTALL
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> requested.value = Destination.IPHONE
        }
    }

    private fun versionName(): String {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
        return info?.versionName ?: "unknown"
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("AppleSideload log", viewModel.exportLogs()))
    }

    @Composable
    private fun Root(version: String) {
        val state by viewModel.state.collectAsState()
        val web by sideload.webControl.status.collectAsState()
        var destination by rememberSaveable { mutableStateOf(Destination.IPHONE) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val settings = remember(state.settingsRevision) { viewModel.settingsSnapshot() }

        val wanted by requested.collectAsState()
        LaunchedEffect(wanted) {
            wanted?.let {
                destination = it
                requested.value = null
            }
        }

        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::selectIpa)
        }

        // Errors stay longer and lead to the log; notices are brief.
        LaunchedEffect(state.error, state.notice) {
            val error = state.error
            val message = error ?: state.notice ?: return@LaunchedEffect
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = if (error != null) "Details" else null,
                withDismissAction = error != null,
                duration = if (error != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) destination = Destination.LOGS
            viewModel.dismissMessages()
        }

        BackHandler(enabled = destination != Destination.IPHONE) {
            destination = destination.parent ?: Destination.IPHONE
        }

        AppScaffold(
            destination = destination,
            onNavigate = { destination = it },
            busy = state.busy,
            snackbar = snackbar,
            actions = {
                if (destination == Destination.LOGS) {
                    IconButton(onClick = {
                        copyLog()
                        // Android 13 and later confirm a copy themselves.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            scope.launch { snackbar.showSnackbar("Log copied") }
                        }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy the log")
                    }
                }
            }
        ) {
            when (destination) {
                Destination.IPHONE -> IphoneScreen(
                    state = state,
                    lastWirelessAddress = settings.lastWirelessAddress,
                    onRefresh = viewModel::refreshDevices,
                    onConnect = viewModel::connect,
                    onConnectWireless = viewModel::connectWireless,
                    onDisconnect = viewModel::disconnect,
                    onStartPairing = viewModel::startRemotePairing,
                    onStopPairing = viewModel::stopRemotePairing,
                    onConnectRemote = viewModel::connectRemote,
                    onForgetRemote = viewModel::forgetRemote
                )

                Destination.INSTALL -> InstallScreen(
                    state = state,
                    onInstallSource = viewModel::installSource,
                    onPickFile = { pickFile.launch(arrayOf("*/*")) },
                    onInstall = viewModel::install,
                    onNavigate = { destination = it }
                )

                Destination.APPS -> AppsScreen(
                    state = state,
                    onRefresh = viewModel::loadApps,
                    onUninstall = viewModel::uninstall,
                    onNavigate = { destination = it }
                )

                Destination.ACCOUNT -> AccountScreen(
                    state = state,
                    lastAppleId = settings.lastAppleId,
                    onSignIn = viewModel::signIn,
                    onSubmitCode = viewModel::submitTwoFactorCode,
                    onRequestPhoneCode = viewModel::requestPhoneCode,
                    onSelectTeam = viewModel::selectTeamById,
                    onSignOut = viewModel::signOut,
                    onRevoke = viewModel::revokeCertificate
                )

                Destination.SETTINGS -> {
                    val logs by viewModel.logs.collectAsState()
                    SettingsScreen(
                        state = state,
                        settings = settings,
                        web = web,
                        logLines = logs.size,
                        version = version,
                        onAnisetteAddress = viewModel::setAnisetteAddress,
                        onWifiDiscovery = viewModel::setWifiDiscovery,
                        onWebEnabled = { enabled ->
                            if (enabled) startWebControl() else WebControlService.stop(this@MainActivity)
                        },
                        onWebPort = { sideload.webControl.setPort(it) },
                        onNavigate = { destination = it }
                    )
                }

                Destination.LOGS -> {
                    val logs by viewModel.logs.collectAsState()
                    LogsScreen(logs)
                }
            }
        }
    }
}
