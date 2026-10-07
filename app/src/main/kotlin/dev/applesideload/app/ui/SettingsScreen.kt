package dev.applesideload.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.applesideload.app.SettingsSnapshot
import dev.applesideload.app.UiState
import dev.applesideload.app.web.WebStatus
import dev.applesideload.apple.AnisetteServers

/** The Settings tab. */
@Composable
fun SettingsScreen(
    state: UiState,
    settings: SettingsSnapshot,
    web: WebStatus,
    logLines: Int,
    version: String,
    onAnisetteAddress: (String) -> Unit,
    onWifiDiscovery: (Boolean) -> Unit,
    onWebEnabled: (Boolean) -> Unit,
    onWebPort: (Int) -> Unit,
    onNavigate: (Destination) -> Unit
) = ScreenColumn {
    WebControllerCard(web, onWebEnabled, onWebPort)
    AnisetteCard(state, settings, onAnisetteAddress)
    SectionCard("Discovery", icon = Icons.Filled.Search) {
        SwitchRow(
            title = "Look for iPhones on Wi-Fi",
            checked = settings.wifiDiscovery,
            onCheckedChange = onWifiDiscovery,
            subtitle = "Lists iPhones on this network that have Wi-Fi sync on. Connecting one here " +
                "by USB turns that on."
        )
    }
    SectionCard("Troubleshooting", icon = Icons.Filled.BugReport) {
        NavigationRow(
            title = "Activity log",
            subtitle = if (logLines == 1) "1 line" else "$logLines lines",
            icon = Icons.AutoMirrored.Filled.ReceiptLong,
            onClick = { onNavigate(Destination.LOGS) }
        )
    }
    SectionCard("About", icon = Icons.Filled.Info) {
        InfoRow("Version", version)
        Text(
            "AppleSideload signs and installs iOS apps entirely on this phone. It talks to the " +
                "iPhone with the same protocols as libimobiledevice, and to Apple with the same " +
                "endpoints as Xcode.",
            style = MaterialTheme.typography.bodyMedium
        )
        Hint("Not part of this app: JIT and the developer disk image, which debugging needs.")
    }
}

@Composable
private fun WebControllerCard(web: WebStatus, onWebEnabled: (Boolean) -> Unit, onWebPort: (Int) -> Unit) =
    SectionCard("Web controller", icon = Icons.Filled.Language) {
        SwitchRow(
            title = "Control this app from a browser",
            checked = web.running,
            onCheckedChange = onWebEnabled,
            subtitle = "On by default. Any device on the same network (Wi-Fi, this phone's hotspot " +
                "or USB tethering) can open it, and there is no login, so turn it off on networks " +
                "you do not trust."
        )
        web.error?.let {
            IconLine(
                Icons.Filled.ErrorOutline,
                it,
                tint = MaterialTheme.colorScheme.error,
                textColor = MaterialTheme.colorScheme.error
            )
        }
        if (web.running) {
            if (web.addresses.isEmpty()) {
                Hint("No network is connected. Join Wi-Fi, or turn on a hotspot or USB tethering.")
            } else {
                Text("Open in a browser", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        web.addresses.forEach { address ->
                            ListRow(
                                title = address.url,
                                subtitle = address.label,
                                leading = { IconBadge(Icons.Filled.Link) }
                            )
                        }
                    }
                }
            }
        } else {
            var port by remember(web.port) { mutableStateOf(web.port.toString()) }
            val valid = port.toIntOrNull()?.let { it in 1024..65535 } == true
            OutlinedTextField(
                value = port,
                onValueChange = { typed ->
                    port = typed.filter { it.isDigit() }.take(5)
                    port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let(onWebPort)
                },
                label = { Text("Port") },
                isError = !valid,
                supportingText = { Text("1024 to 65535") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(180.dp)
            )
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnisetteCard(state: UiState, settings: SettingsSnapshot, onAnisetteAddress: (String) -> Unit) =
    SectionCard("Attestation source", icon = Icons.Filled.Dns) {
        var address by rememberSaveable { mutableStateOf(settings.anisetteAddress) }
        val addressValid = address.isBlank() ||
            address.trim().let { it.startsWith("https://") || it.startsWith("http://") }
        Text(
            "Apple's sign-in needs attestation data that only Apple's own code can make, and it " +
                "cannot run on Android. This app asks the server below for it. The server never " +
                "sees your Apple ID or password.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                onAnisetteAddress(it)
            },
            label = { Text("Server address") },
            placeholder = { Text(AnisetteServers.default.address) },
            isError = !addressValid,
            supportingText = {
                Text(
                    when {
                        address.isBlank() -> "Leave it empty to use the default (${AnisetteServers.default.name})."
                        !addressValid -> "Enter the full address, starting with https://"
                        else -> "Or pick one of these public servers:"
                    }
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Text(
            "In use: ${settings.effectiveAnisetteAddress}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
