package dev.applesideload.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.applesideload.app.RemoteDeviceSummary
import dev.applesideload.app.RemotePairingPrompt
import dev.applesideload.app.UiState
import dev.applesideload.device.ConnectionState
import dev.applesideload.device.DeviceInfo
import dev.applesideload.device.DiscoveredDevice

/** The iPhone tab: how the connection stands, and every way to make one. */
@Composable
fun IphoneScreen(
    state: UiState,
    lastWirelessAddress: String,
    onRefresh: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onConnectWireless: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartPairing: () -> Unit,
    onStopPairing: () -> Unit,
    onConnectRemote: (String) -> Unit,
    onForgetRemote: (String) -> Unit
) = ScreenColumn {
    ConnectionCard(state, onDisconnect)
    state.device?.let { DetailsCard(it) }
    if (state.remoteDevices.isNotEmpty()) PairedCard(state, onConnectRemote, onForgetRemote)
    NearbyCard(state, onRefresh, onConnect)
    WirelessPairingCard(state.remotePairing, onStartPairing, onStopPairing)
    AddressCard(state.busy == null, lastWirelessAddress, onConnectWireless)
}

private class Look(val tone: Tone, val icon: ImageVector, val title: String, val subtitle: String?)

@Composable
private fun ConnectionCard(state: UiState, onDisconnect: () -> Unit) {
    val device = state.device
    val look = when (state.connection) {
        ConnectionState.DISCONNECTED -> Look(
            Tone.NEUTRAL,
            Icons.Filled.PhonelinkOff,
            "No iPhone connected",
            "Plug it in with a USB cable, or reach it over Wi-Fi once it is paired."
        )
        ConnectionState.DISCOVERING, ConnectionState.CONNECTING ->
            Look(Tone.WORKING, Icons.Filled.Sync, "Connecting", device?.name)
        ConnectionState.CONNECTED, ConnectionState.LOCKDOWN_CONNECTED, ConnectionState.PAIRED ->
            Look(Tone.WORKING, Icons.Filled.Sync, device?.name?.let { "Setting up $it" } ?: "Setting up", "Starting a secure session")
        ConnectionState.PAIRING_REQUIRED, ConnectionState.PAIRING ->
            Look(Tone.WARNING, Icons.Filled.PhonelinkLock, "Waiting for Trust", device?.name)
        ConnectionState.READY -> Look(
            Tone.SUCCESS,
            Icons.Filled.CheckCircle,
            device?.name ?: "iPhone",
            device?.let { "Ready · iOS ${it.productVersion}" } ?: "Ready"
        )
        ConnectionState.ERROR -> Look(Tone.ERROR, Icons.Filled.ErrorOutline, "Could not connect", null)
    }
    StatusCard(look.tone, look.icon, look.title, look.subtitle) {
        val onCard = LocalContentColor.current
        val hint = state.pairingHint
        val problem = state.lastMessage?.takeIf { state.connection == ConnectionState.ERROR && state.lastMessageIsError }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium)
        } else if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodyMedium)
        }
        val transport = state.transport
        if (state.connection == ConnectionState.READY && transport != null) {
            IconLine(
                if (transport.startsWith("USB")) Icons.Filled.Usb else Icons.Filled.Wifi,
                "Connected over $transport",
                tint = onCard,
                textColor = onCard
            )
        }
        // Only where nothing is half-done: while waiting for the user, once
        // ready, and after a failure.
        val action = when (state.connection) {
            ConnectionState.READY -> "Disconnect"
            ConnectionState.PAIRING, ConnectionState.PAIRING_REQUIRED -> "Cancel"
            ConnectionState.ERROR -> "Dismiss"
            else -> null
        }
        if (action != null) {
            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = onCard),
                border = BorderStroke(1.dp, onCard.copy(alpha = 0.4f))
            ) { Text(action) }
        }
    }
}

@Composable
private fun DetailsCard(device: DeviceInfo) = SectionCard("About this iPhone", icon = Icons.Outlined.Info) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow("Model", modelLabel(device.productType))
            InfoRow("iOS", "${device.productVersion} (${device.buildVersion})")
            InfoRow("UDID", device.udid, monospace = true)
            device.serialNumber?.let { InfoRow("Serial number", it, monospace = true) }
            InfoRow("Architecture", device.cpuArchitecture)
        }
    }
}

/** iPhones paired through Remote Pairing, reachable over Wi-Fi. */
@Composable
private fun PairedCard(state: UiState, onConnect: (String) -> Unit, onForget: (String) -> Unit) {
    var forgetting by remember { mutableStateOf<RemoteDeviceSummary?>(null) }
    val idle = state.busy == null
    SectionCard("Paired for Wi-Fi", icon = Icons.Filled.Wifi) {
        state.remoteDevices.forEach { paired ->
            val current = state.connection == ConnectionState.READY && state.device?.udid == paired.udid
            val details = listOfNotNull(
                paired.model?.let { marketingName(it) ?: it },
                paired.lastAddress?.let { "last seen at $it" }
            ).joinToString(" · ")
            ListRow(
                title = paired.name,
                subtitle = details.ifEmpty { null },
                leading = { IconBadge(Icons.Filled.PhoneIphone) },
                trailing = {
                    IconButton(onClick = { forgetting = paired }, enabled = idle) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Forget ${paired.name}")
                    }
                    if (current) {
                        ConnectedPill()
                    } else {
                        FilledTonalButton(onClick = { onConnect(paired.udid) }, enabled = idle) { Text("Connect") }
                    }
                }
            )
        }
        Hint("Reached through an encrypted tunnel while both phones are on the same network.")
    }
    forgetting?.let { paired ->
        ConfirmDialog(
            title = "Forget ${paired.name}?",
            text = "This phone will have to pair with it again before it can reach it over Wi-Fi.",
            confirmLabel = "Forget",
            onConfirm = { onForget(paired.udid) },
            onDismiss = { forgetting = null }
        )
    }
}

@Composable
private fun ConnectedPill() {
    val status = AppColors.status
    StatusPill("Connected", container = status.successContainer, content = status.onSuccessContainer)
}

/** What Android sees right now: iPhones on the cable and on the network. */
@Composable
private fun NearbyCard(state: UiState, onRefresh: () -> Unit, onConnect: (DiscoveredDevice) -> Unit) {
    val idle = state.busy == null
    val ready = state.connection == ConnectionState.READY
    SectionCard(
        "Nearby",
        icon = Icons.Filled.Devices,
        action = {
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Look again") }
        }
    ) {
        if (state.discovered.isEmpty()) {
            Hint("No iPhone found. Plug one in with a USB cable and allow access when Android asks.")
        }
        val usbCount = state.discovered.count { it is DiscoveredDevice.Usb }
        state.discovered.forEach { found ->
            val icon: ImageVector
            val title: String
            val subtitle: String
            val current: Boolean
            when (found) {
                is DiscoveredDevice.Usb -> {
                    icon = Icons.Filled.Usb
                    title = found.displayName
                    subtitle = "USB cable"
                    current = ready && state.transport == "USB" && usbCount == 1
                }
                is DiscoveredDevice.Wifi -> {
                    val address = found.host.hostAddress ?: found.serviceName
                    icon = Icons.Filled.Wifi
                    title = wifiName(found, address, state)
                    subtitle = address
                    current = ready && state.transport?.endsWith("($address)") == true
                }
            }
            ListRow(
                title = title,
                subtitle = subtitle,
                leading = { IconBadge(icon) },
                trailing = {
                    if (current) {
                        ConnectedPill()
                    } else {
                        FilledTonalButton(onClick = { onConnect(found) }, enabled = idle) { Text("Connect") }
                    }
                }
            )
        }
    }
}

/**
 * A Wi-Fi iPhone is advertised under its MAC address; show the name it was
 * paired or connected under instead, when this phone knows it.
 */
private fun wifiName(found: DiscoveredDevice.Wifi, address: String, state: UiState): String {
    val mac = found.serviceName.substringBefore('@')
    return state.remoteDevices.firstOrNull { it.lastAddress == address }?.name
        ?: state.device?.takeIf { it.wifiAddress.equals(mac, ignoreCase = true) }?.name
        ?: "iPhone on Wi-Fi"
}

/** Remote Pairing: offering this phone to the iPhone, with the PIN to type there. */
@Composable
private fun WirelessPairingCard(prompt: RemotePairingPrompt?, onStart: () -> Unit, onStop: () -> Unit) {
    SectionCard("Pair wirelessly", icon = Icons.Filled.WifiTethering) {
        if (prompt == null) {
            Text(
                "For iOS 17 and later. On iOS 27 it is the only way to reach an iPhone over Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium
            )
            NumberedStep(1, "Tap Pair wirelessly.")
            NumberedStep(2, "On the iPhone, open Settings › Privacy & Security › Developer Mode.")
            NumberedStep(3, "Pick this phone there and type the PIN that appears here.")
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Pair wirelessly") }
            Hint(
                "Developer Mode not listed? Connect the iPhone once with a USB cable instead; " +
                    "that sets up Wi-Fi access by itself."
            )
            return@SectionCard
        }
        when (prompt.stage) {
            RemotePairingPrompt.Stage.STARTING -> ProgressLine("Getting ready")
            RemotePairingPrompt.Stage.ADVERTISING -> {
                ProgressLine("Waiting for the iPhone")
                NumberedStep(1, "On the iPhone, open Settings › Privacy & Security › Developer Mode.")
                NumberedStep(2, "Pick \u201c${prompt.hostName}\u201d.")
            }
            RemotePairingPrompt.Stage.PIN -> {
                Text("Type this PIN on the iPhone", style = MaterialTheme.typography.titleSmall)
                prompt.pin?.let { PinBoxes(it) }
            }
            RemotePairingPrompt.Stage.PAIRED -> IconLine(
                Icons.Filled.CheckCircle,
                "Paired with ${prompt.pairedWith ?: "the iPhone"} as \u201c${prompt.hostName}\u201d.",
                tint = AppColors.status.success
            )
            RemotePairingPrompt.Stage.FAILED -> IconLine(
                Icons.Filled.ErrorOutline,
                prompt.message ?: "Pairing stopped.",
                tint = MaterialTheme.colorScheme.error,
                textColor = MaterialTheme.colorScheme.error
            )
        }
        val note = prompt.message
        if (note != null && prompt.stage != RemotePairingPrompt.Stage.FAILED) {
            IconLine(Icons.Filled.WarningAmber, note, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (prompt.stage) {
                RemotePairingPrompt.Stage.FAILED -> {
                    Button(onClick = onStart) { Text("Try again") }
                    TextButton(onClick = onStop) { Text("Close") }
                }
                RemotePairingPrompt.Stage.PAIRED -> FilledTonalButton(onClick = onStop) { Text("Done") }
                else -> OutlinedButton(onClick = onStop) { Text("Cancel") }
            }
        }
    }
}

/** Wireless mode by address, for an iPhone that is not advertised. */
@Composable
private fun AddressCard(idle: Boolean, lastAddress: String, onConnect: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf(lastAddress) }
    val submit = { if (idle && address.isNotBlank()) onConnect(address.trim()) }
    SectionCard("Connect by IP address", icon = Icons.Filled.Lan) {
        Hint(
            "Both phones on the same Wi-Fi, the iPhone unlocked. Its address is under Settings › " +
                "Wi-Fi › (i). A paired iPhone is reached through its tunnel; any other one is asked " +
                "to Trust this phone, which iOS 27 no longer allows over Wi-Fi."
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("iPhone IP address") },
            placeholder = { Text("192.168.1.23") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = submit, enabled = idle && address.isNotBlank()) { Text("Connect") }
    }
}
