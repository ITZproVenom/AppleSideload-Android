package dev.applesideload.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.applesideload.app.SelectedIpa
import dev.applesideload.app.SettingsSnapshot
import dev.applesideload.app.web.WebStatus
import dev.applesideload.app.UiState
import dev.applesideload.core.LogLine
import dev.applesideload.device.ConnectionState
import dev.applesideload.device.DiscoveredDevice
import dev.applesideload.sideload.InstallSource
import dev.applesideload.sideload.SideloadStep
import dev.applesideload.sideload.SpecialApp

/** The screen scaffolding every destination uses. */
@Composable
private fun Screen(content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() } }
    }
}

@Composable
fun HomeScreen(
    state: UiState,
    lastWirelessAddress: String,
    onRefresh: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onConnectWireless: (String) -> Unit,
    onDisconnect: () -> Unit
) = Screen {
    Panel("Connection") {
        Field("State", state.connection.name.lowercase().replace('_', ' '))
        state.device?.let {
            Field("Device", it.name)
            Field("iOS", it.productVersion)
        }
        state.pairingHint?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) { Text("Scan") }
            if (state.connection != ConnectionState.DISCONNECTED) {
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }

    Panel("Devices") {
        if (state.discovered.isEmpty()) {
            Text(
                "No iPhone found. Connect one with a USB cable and allow access when " +
                    "Android asks, or use wireless mode below.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        state.discovered.forEach { device ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(device.displayName, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { onConnect(device) }) { Text("Connect") }
            }
        }
    }

    Panel("Wireless mode (no cable)") {
        var address by rememberSaveable { mutableStateOf(lastWirelessAddress) }
        Text(
            "Put both phones on the same Wi-Fi network, unlock the iPhone, and enter its address " +
                "from Settings > Wi-Fi > (i). The iPhone asks to Trust this phone; after that, " +
                "installs go over Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("iPhone IP address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onConnectWireless(address) },
            enabled = state.busy == null && address.isNotBlank()
        ) { Text("Connect wirelessly") }
    }

    Panel("What this does") {
        Text(
            "Signs an iOS app with your own Apple account and installs it on your iPhone " +
                "over the cable or Wi-Fi. Nothing is sent to a PC or a Mac. A free account gives a " +
                "seven day signature, three installed apps at a time, and ten new app " +
                "identifiers a week; those are Apple's limits, not this app's.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun DeviceScreen(state: UiState) = Screen {
    val device = state.device
    if (device == null) {
        Panel("Device") { Text("Connect an iPhone to see its details.") }
        return@Screen
    }
    Panel("Identity") {
        Field("Name", device.name)
        Field("Model", device.productType)
        Field("iOS", "${device.productVersion} (${device.buildVersion})")
        Field("Architecture", device.cpuArchitecture)
        Field("UDID", device.udid, monospace = true)
        device.serialNumber?.let { Field("Serial", it, monospace = true) }
    }
    Panel("Pairing") {
        Field("State", state.connection.name.lowercase().replace('_', ' '))
        Text(
            "The pairing record is stored on this phone under a key in the Android Keystore " +
                "and is what lets the iPhone be reached again without tapping Trust each time.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
    if (device.needsRemoteTunnel) {
        Panel("iOS 17 and later") {
            Text(
                "Installing works normally on this version. The developer services that used " +
                    "to need a mounted developer disk image now sit behind an encrypted " +
                    "tunnel that requires a connection this app does not implement, so " +
                    "debugging features are unavailable here.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun InstallScreen(
    state: UiState,
    onInstallSource: (InstallSource) -> Unit,
    onPickFile: () -> Unit,
    onInstall: () -> Unit
) = Screen {
    val ready = state.connection == ConnectionState.READY && state.account != null && state.busy == null
    if (state.connection != ConnectionState.READY || state.account == null) {
        Panel("Before installing") {
            if (state.connection != ConnectionState.READY) {
                Text("Connect and pair the iPhone on the Device screen.")
            }
            if (state.account == null) {
                Text("Sign in with your Apple ID on the Account screen; the signature comes from a real certificate Apple issues to it.")
            }
        }
    }

    Panel("SideStore + LiveContainer") {
        Text(
            "Downloads the latest LiveContainer + SideStore release from LiveContainer's GitHub, signs it with " +
                "your Apple ID, installs it, and gives SideStore the pairing file so it can refresh " +
                "itself and your apps on the iPhone with LocalDevVPN, the same way SideInstaller sets it up."
        )
        Button(onClick = { onInstallSource(InstallSource.SIDESTORE_LIVECONTAINER) }, enabled = ready) {
            Text("Install SideStore + LiveContainer")
        }
        TextButton(onClick = { onInstallSource(InstallSource.SIDESTORE) }, enabled = ready) {
            Text("Install SideStore only")
        }
    }

    Panel("Custom IPA") {
        val selected: SelectedIpa? = state.selectedIpa
        if (selected == null) {
            Text("Choose an .ipa file to inspect and install.")
        } else {
            Field("Name", selected.info.name)
            Field("Bundle id", selected.info.bundleId, monospace = true)
            Field("Version", "${selected.info.shortVersion} (${selected.info.version})")
            Field("Minimum iOS", selected.info.minimumOsVersion)
            Field("Frameworks", selected.info.frameworkCount.toString())
            Field("Extensions", if (selected.info.hasExtensions) "yes" else "no")
            Field("Size", "%.1f MB".format(selected.info.sizeBytes / 1_000_000.0))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickFile, enabled = state.busy == null) { Text("Choose file") }
            Button(onClick = onInstall, enabled = ready && state.selectedIpa != null) { Text("Sign and install") }
        }
    }

    state.step?.let { step ->
        Panel("Progress") {
            val (label, fraction) = when (step) {
                is SideloadStep.Preparing -> step.detail to null
                is SideloadStep.Downloading -> "downloading" to step.percent / 100f
                is SideloadStep.Account -> step.detail to null
                is SideloadStep.Signing -> "signing ${step.bundle}" to null
                is SideloadStep.Uploading -> "copying to the iPhone" to step.percent / 100f
                is SideloadStep.Installing -> step.status.lowercase() to step.percent / 100f
                is SideloadStep.HandOff -> step.detail to null
                is SideloadStep.Finished -> "installed, valid for ${step.expiresInDays} days" to 1f
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else if (state.busy != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    state.lastOutcome?.let { outcome ->
        Panel("Finish on the iPhone") {
            Text("${outcome.name} is installed as ${outcome.bundleId}, valid for ${outcome.expiresInDays} days.")
            Text("1. Settings > General > VPN & Device Management: trust your Apple ID's developer app.")
            Text("2. Settings > Privacy & Security > Developer Mode: turn it on and restart (iOS 16 and later).")
            if (outcome.special.isSideStoreFamily) {
                val where = if (outcome.special == SpecialApp.SIDESTORE_LIVECONTAINER) {
                    "Open LiveContainer, then SideStore inside it"
                } else {
                    "Open SideStore"
                }
                Text("3. Install LocalDevVPN from the App Store and connect it.")
                Text("4. $where and sign in with the same Apple ID.")
                Text("5. Refresh in SideStore with LocalDevVPN connected, ideally every day, so nothing expires.")
                if (!outcome.pairingHandedOff) {
                    Text(
                        "The pairing file could not be handed to SideStore; see Diagnostics.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun AppsScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onUninstall: (String) -> Unit
) = Screen {
    Panel("Installed by you") {
        if (state.apps.isEmpty()) {
            Text("No sideloaded apps were reported by the device.")
        }
        state.apps.forEach { installed ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(installed.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        installed.bundleId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                TextButton(onClick = { onUninstall(installed.bundleId) }) { Text("Remove") }
            }
            Divider()
        }
        OutlinedButton(onClick = onRefresh) { Text("Refresh") }
    }
}

@Composable
fun AccountScreen(
    state: UiState,
    lastAppleId: String,
    onSignIn: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onRequestPhoneCode: (Int) -> Unit,
    onSignOut: () -> Unit,
    onRevoke: () -> Unit
) = Screen {
    val account = state.account
    if (account == null) {
        var appleId by remember { mutableStateOf(lastAppleId) }
        var password by remember { mutableStateOf("") }
        Panel("Apple account") {
            Text(
                "The password is proved to Apple without being sent: the exchange is SRP, " +
                    "the same one Xcode uses. It is stored on this phone under a key in the " +
                    "Android Keystore and nowhere else.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = appleId,
                onValueChange = { appleId = it },
                label = { Text("Apple ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSignIn(appleId, password) },
                enabled = appleId.isNotBlank() && password.isNotBlank() && state.busy == null
            ) { Text("Sign in") }
        }

        state.twoFactor?.let { prompt ->
            var code by remember { mutableStateOf("") }
            Panel("Verification code") {
                if (prompt.phoneNumbers.isNotEmpty() && prompt.numberId == null) {
                    Text("Choose a number to receive the code.")
                    prompt.phoneNumbers.forEach { number ->
                        OutlinedButton(onClick = { onRequestPhoneCode(number.id) }) {
                            Text(number.maskedNumber)
                        }
                    }
                } else {
                    Text("Enter the code shown on your trusted device.")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("Code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onSubmitCode(code) },
                        enabled = code.length == 6 && state.busy == null
                    ) { Text("Continue") }
                }
            }
        }
        return@Screen
    }

    Panel("Signed in") {
        Field("Apple ID", account.appleId)
        Field("Account", account.adsid, monospace = true)
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
    }

    Panel("Team") {
        state.teams.forEach { team ->
            Field(team.name, team.teamId)
        }
        if (state.teams.isEmpty()) Text("Apple returned no teams for this account.")
    }

    Panel("Development certificate") {
        Text(
            "A free account may hold one certificate at a time. Revoking it stops every app " +
                "signed with it from launching, including apps installed from another machine.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = onRevoke) { Text("Revoke certificate") }
    }
}

@Composable
fun DiagnosticsScreen(
    state: UiState,
    logs: List<LogLine>,
    onExport: () -> Unit
) = Screen {
    Panel("Status") {
        Field("Connection", state.connection.name.lowercase().replace('_', ' '))
        Field("Account", if (state.account == null) "signed out" else "signed in")
        Field("Device", state.device?.name ?: "none")
        Field("Log lines", logs.size.toString())
        OutlinedButton(onClick = onExport) { Text("Copy log") }
        Text(
            "Exported logs have identifiers, tokens and account names replaced before they " +
                "leave the app.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    Panel("Recent activity") {
        logs.takeLast(120).reversed().forEach { line ->
            Text(
                line.format(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
        if (logs.isEmpty()) Text("Nothing has been logged yet.")
    }
}

@Composable
fun SettingsScreen(
    state: UiState,
    settings: SettingsSnapshot,
    web: WebStatus,
    onAnisetteAddress: (String) -> Unit,
    onWifiDiscovery: (Boolean) -> Unit,
    onWebEnabled: (Boolean) -> Unit,
    onWebPort: (Int) -> Unit
) = Screen {
    var address by remember { mutableStateOf(settings.anisetteAddress) }
    var port by remember(web.port) { mutableStateOf(web.port.toString()) }

    Panel("Web controller") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Control this app from a browser", modifier = Modifier.weight(1f))
            Switch(checked = web.running, onCheckedChange = onWebEnabled)
        }
        Text(
            "Open the address below in a browser on any device on the same network - Wi-Fi, this " +
                "phone's hotspot, or USB/Ethernet tethering. Everything this app does can be done " +
                "from there, with no login.",
            style = MaterialTheme.typography.bodySmall
        )
        web.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (web.running) {
            if (web.addresses.isEmpty()) {
                Text("No network is connected. Join Wi-Fi or turn on a hotspot or USB tethering.")
            }
            web.addresses.forEach { Field(it.label, it.url, monospace = true) }
        } else {
            OutlinedTextField(
                value = port,
                onValueChange = { typed ->
                    port = typed.filter { it.isDigit() }.take(5)
                    port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let(onWebPort)
                },
                label = { Text("Port") },
                isError = port.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                supportingText = { Text("1024 to 65535") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    Panel("Attestation source") {
        Text(
            "Apple's sign-in requires attestation data that only Apple's own closed code can " +
                "produce, and it cannot run on an Android phone. This app asks a source of " +
                "your choosing for those headers; it never sees your Apple ID or password.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                onAnisetteAddress(it)
            },
            label = { Text("Address") },
            placeholder = { Text(dev.applesideload.apple.AnisetteServers.default.address) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        state.anisetteServers.forEach { server ->
            FilterChip(
                selected = settings.effectiveAnisetteAddress == server.address,
                onClick = {
                    address = server.address
                    onAnisetteAddress(server.address)
                },
                label = { Text(server.name) }
            )
        }
    }

    Panel("Discovery") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Look for devices over Wi-Fi")
            Switch(checked = settings.wifiDiscovery, onCheckedChange = onWifiDiscovery)
        }
        Text(
            "Only devices already paired with this app and with Wi-Fi sync enabled advertise " +
                "themselves on the network.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    Panel("About") {
        Text(
            "AppleSideload signs and installs iOS apps entirely on this phone. It talks to " +
                "the iPhone with the same protocols as libimobiledevice, and to Apple with " +
                "the same endpoints as Xcode.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
