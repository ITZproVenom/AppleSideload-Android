package dev.applesideload.app

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.applesideload.app.web.WebControlService
import dev.applesideload.app.ui.AccountScreen
import dev.applesideload.app.ui.AppleSideloadTheme
import dev.applesideload.app.ui.AppsScreen
import dev.applesideload.app.ui.DeviceScreen
import dev.applesideload.app.ui.DiagnosticsScreen
import dev.applesideload.app.ui.HomeScreen
import dev.applesideload.app.ui.InstallScreen
import dev.applesideload.app.ui.SettingsScreen

/** The seven places the app can be. */
private enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    DEVICE("Device", Icons.Filled.PhoneIphone),
    INSTALL("Install", Icons.Filled.Download),
    APPS("Apps", Icons.Filled.Widgets),
    ACCOUNT("Account", Icons.Filled.Person),
    DIAGNOSTICS("Logs", Icons.Filled.Build),
    SETTINGS("Settings", Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {


    private val sideload: SideloadApplication get() = application as SideloadApplication

    /** Lives with the process, shared with the web controller. */
    private val viewModel: AppController get() = sideload.controller

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

    @androidx.compose.material3.ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppleSideloadTheme {
                val state by viewModel.state.collectAsState()
                val logs by viewModel.logs.collectAsState()
                val web by sideload.webControl.status.collectAsState()
                var destination by remember { mutableStateOf(Destination.HOME) }
                val snackbar = remember { SnackbarHostState() }

                val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::selectIpa) }

                LaunchedEffect(state.error, state.notice) {
                    val message = state.error ?: state.notice
                    if (message != null) {
                        snackbar.showSnackbar(message)
                        viewModel.dismissMessages()
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(destination.label) })
                    },
                    bottomBar = {
                        NavigationBar {
                            Destination.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = destination == entry,
                                    onClick = { destination = entry },
                                    icon = { Icon(entry.icon, contentDescription = entry.label) },
                                    label = { Text(entry.label) }
                                )
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { padding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column {
                            if (state.busy != null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    state.busy ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            when (destination) {
                                Destination.HOME -> HomeScreen(
                                    state = state,
                                    lastWirelessAddress = viewModel.settingsSnapshot().lastWirelessAddress,
                                    onRefresh = viewModel::refreshDevices,
                                    onConnect = viewModel::connect,
                                    onConnectWireless = viewModel::connectWireless,
                                    onDisconnect = viewModel::disconnect,
                                    onStartRemotePairing = viewModel::startRemotePairing,
                                    onStopRemotePairing = viewModel::stopRemotePairing,
                                    onConnectRemote = viewModel::connectRemote,
                                    onForgetRemote = viewModel::forgetRemote
                                )

                                Destination.DEVICE -> DeviceScreen(state)

                                Destination.INSTALL -> InstallScreen(
                                    state = state,
                                    onInstallSource = viewModel::installSource,
                                    onPickFile = { pickFile.launch(arrayOf("*/*")) },
                                    onInstall = viewModel::install
                                )

                                Destination.APPS -> AppsScreen(
                                    state = state,
                                    onRefresh = viewModel::loadApps,
                                    onUninstall = viewModel::uninstall
                                )

                                Destination.ACCOUNT -> AccountScreen(
                                    state = state,
                                    lastAppleId = viewModel.settingsSnapshot().lastAppleId,
                                    onSignIn = viewModel::signIn,
                                    onSubmitCode = viewModel::submitTwoFactorCode,
                                    onRequestPhoneCode = viewModel::requestPhoneCode,
                                    onSignOut = viewModel::signOut,
                                    onRevoke = viewModel::revokeCertificate
                                )

                                Destination.DIAGNOSTICS -> DiagnosticsScreen(
                                    state = state,
                                    logs = logs,
                                    onExport = {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText(
                                                "AppleSideload log",
                                                viewModel.exportLogs()
                                            )
                                        )
                                    }
                                )

                                Destination.SETTINGS -> SettingsScreen(
                                    state = state,
                                    settings = viewModel.settingsSnapshot(),
                                    web = web,
                                    onAnisetteAddress = viewModel::setAnisetteAddress,
                                    onWifiDiscovery = viewModel::setWifiDiscovery,
                                    onWebEnabled = { enabled ->
                                        if (enabled) startWebControl() else WebControlService.stop(this@MainActivity)
                                    },
                                    onWebPort = { sideload.webControl.setPort(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
