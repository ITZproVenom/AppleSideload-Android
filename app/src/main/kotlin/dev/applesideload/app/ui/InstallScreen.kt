package dev.applesideload.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.applesideload.app.UiState
import dev.applesideload.device.ConnectionState
import dev.applesideload.signing.IpaInfo
import dev.applesideload.sideload.InstallOutcome
import dev.applesideload.sideload.InstallSource
import dev.applesideload.sideload.REMOTE_PAIRING_ONLY_FROM_IOS
import dev.applesideload.sideload.SideloadStep
import dev.applesideload.sideload.SpecialApp

/** The Install tab: SideStore in one tap, or any IPA. */
@Composable
fun InstallScreen(
    state: UiState,
    onInstallSource: (InstallSource) -> Unit,
    onPickFile: () -> Unit,
    onInstall: () -> Unit,
    onNavigate: (Destination) -> Unit
) = ScreenColumn {
    val connected = state.connection == ConnectionState.READY
    val signedIn = state.account != null
    val ready = connected && signedIn && state.busy == null

    state.step?.let { ProgressCard(it, state.busy) }
    state.lastOutcome?.let { outcome ->
        DoneCard(outcome)
        outcome.warning?.let { StatusCard(Tone.WARNING, Icons.Filled.WarningAmber, "Read this first", it) }
        FinishCard(outcome)
    }
    if (!connected || !signedIn) ChecklistCard(state, connected, signedIn, onNavigate)
    SideStoreCard(ready, state.device?.takeIf { connected }?.majorVersion, onInstallSource)
    CustomIpaCard(state, ready, onPickFile, onInstall)
    Hint(
        "A free Apple ID signs each app for 7 days, keeps up to 3 sideloaded apps on the iPhone " +
            "at a time, and can make 10 new app identifiers a week. Those limits are Apple's.",
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun ProgressCard(step: SideloadStep, busy: String?) {
    val (label, percent) = describe(step)
    StatusCard(
        Tone.WORKING,
        Icons.Filled.Download,
        busy ?: "Installing",
        if (percent != null) "$label · $percent%" else label
    ) {
        val onCard = LocalContentColor.current
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = onCard,
                trackColor = onCard.copy(alpha = 0.2f)
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = onCard,
                trackColor = onCard.copy(alpha = 0.2f)
            )
        }
        Hint("Keep this app open until it finishes.", color = onCard.copy(alpha = 0.8f))
    }
}

/** What a step is doing in words, and how far along it is when that is known. */
private fun describe(step: SideloadStep): Pair<String, Int?> = when (step) {
    is SideloadStep.Preparing -> sentence(step.detail) to null
    is SideloadStep.Downloading -> "Downloading" to step.percent.coerceIn(0, 100)
    is SideloadStep.Account -> sentence(step.detail) to null
    is SideloadStep.Signing -> "Signing ${step.bundle}" to null
    is SideloadStep.Uploading -> "Copying to the iPhone" to step.percent.coerceIn(0, 100)
    is SideloadStep.Installing ->
        (humanize(step.status).ifEmpty { "Installing" }) to step.percent.coerceIn(0, 100)
    is SideloadStep.HandOff -> sentence(step.detail) to null
    is SideloadStep.Finished -> "Installed" to 100
}

private fun sentence(text: String): String = text.replaceFirstChar { it.uppercaseChar() }

/** installation_proxy reports steps as "VerifyingApplication"; this makes them "Verifying application". */
private fun humanize(status: String): String =
    sentence(status.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().trim())

@Composable
private fun DoneCard(outcome: InstallOutcome) {
    val days = if (outcome.expiresInDays == 1) "1 day" else "${outcome.expiresInDays} days"
    StatusCard(Tone.SUCCESS, Icons.Filled.CheckCircle, "${outcome.name} is installed", "Signed for $days") {
        Text(
            outcome.bundleId,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = LocalContentColor.current.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun FinishCard(outcome: InstallOutcome) = SectionCard("Finish on the iPhone", icon = Icons.Filled.PhoneIphone) {
    NumberedStep(1, "Settings › General › VPN & Device Management: trust your Apple ID's developer app.")
    NumberedStep(2, "Settings › Privacy & Security › Developer Mode: turn it on and restart, if it is off.")
    if (outcome.special.isSideStoreFamily) {
        val inLiveContainer = outcome.special == SpecialApp.SIDESTORE_LIVECONTAINER
        val store = if (inLiveContainer) "SideStore" else outcome.name
        NumberedStep(3, "Install LocalDevVPN from the App Store and connect it.")
        NumberedStep(
            4,
            if (inLiveContainer) {
                "Open LiveContainer, then SideStore inside it, and sign in with the same Apple ID."
            } else {
                "Open $store and sign in with the same Apple ID."
            }
        )
        NumberedStep(5, "Refresh in $store with LocalDevVPN connected, ideally every day, so nothing expires.")
        if (!outcome.pairingHandedOff) {
            IconLine(
                Icons.Filled.ErrorOutline,
                "The pairing file could not be given to $store, so it cannot refresh apps by itself " +
                    "yet. The activity log says why.",
                tint = MaterialTheme.colorScheme.error,
                textColor = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ChecklistCard(
    state: UiState,
    connected: Boolean,
    signedIn: Boolean,
    onNavigate: (Destination) -> Unit
) = SectionCard("Before you install", icon = Icons.Filled.Checklist) {
    CheckRow(
        done = connected,
        title = if (connected) "${state.device?.name ?: "The iPhone"} is connected" else "Connect the iPhone",
        subtitle = if (connected) null else "By USB cable or over Wi-Fi.",
        actionLabel = "Connect",
        onAction = { onNavigate(Destination.IPHONE) }
    )
    CheckRow(
        done = signedIn,
        title = if (signedIn) "Signed in as ${state.account?.appleId}" else "Sign in with your Apple ID",
        subtitle = if (signedIn) null else "Apps are signed with a certificate Apple issues to your account.",
        actionLabel = "Sign in",
        onAction = { onNavigate(Destination.ACCOUNT) }
    )
}

@Composable
private fun CheckRow(done: Boolean, title: String, subtitle: String?, actionLabel: String, onAction: () -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            if (done) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = AppColors.status.success)
            } else {
                Icon(
                    Icons.Filled.RadioButtonUnchecked,
                    contentDescription = "Not done",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailing = if (done) null else {
            { FilledTonalButton(onClick = onAction) { Text(actionLabel) } }
        }
    )
}

@Composable
private fun SideStoreCard(ready: Boolean, ios: Int?, onInstallSource: (InstallSource) -> Unit) =
    SectionCard("SideStore + LiveContainer", icon = Icons.Filled.Storefront) {
        val newest = ios != null && ios >= REMOTE_PAIRING_ONLY_FROM_IOS
        Text(
            if (newest) {
                "The newest build on GitHub, nightly builds included, because on iOS $ios only " +
                    "SideStore's newest builds can refresh on the iPhone. It is signed with your Apple ID, " +
                    "and SideStore also gets the pairing file it needs with LocalDevVPN."
            } else {
                "The latest release from GitHub, signed with your Apple ID. SideStore also gets the " +
                    "pairing file, so it can refresh itself and your apps on the iPhone with LocalDevVPN, " +
                    "with no computer."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        if (newest) {
            Hint("Each build is checked after it downloads, and you are told if its SideStore cannot refresh on iOS $ios yet.")
        }
        Button(
            onClick = { onInstallSource(InstallSource.SIDESTORE_LIVECONTAINER) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Install SideStore + LiveContainer") }
        OutlinedButton(
            onClick = { onInstallSource(InstallSource.SIDESTORE) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Install SideStore only") }
    }

@Composable
private fun CustomIpaCard(state: UiState, ready: Boolean, onPickFile: () -> Unit, onInstall: () -> Unit) =
    SectionCard("Your own IPA", icon = Icons.Filled.FolderOpen) {
        val idle = state.busy == null
        val selected = state.selectedIpa
        if (selected == null) {
            Text(
                "Pick an .ipa file on this phone, or open one here from another app. It is checked " +
                    "before anything is signed.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = onPickFile, enabled = idle) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose IPA")
            }
            return@SectionCard
        }
        val info = selected.info
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LetterAvatar(info.name, info.bundleId, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    versionText(info.shortVersion, info.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Bundle ID", info.bundleId, monospace = true)
                InfoRow(
                    "Needs",
                    if (info.minimumOsVersion.isBlank()) "No iOS version stated" else "iOS ${info.minimumOsVersion} or later"
                )
                InfoRow("Size", formatSize(info.sizeBytes))
                InfoRow("Contains", contents(info))
            }
        }
        val warning = AppColors.status.warning
        if (!info.supportsIphone) {
            IconLine(
                Icons.Filled.WarningAmber,
                "This app does not list iPhone among its devices, so iOS will most likely refuse it.",
                tint = warning
            )
        }
        val device = state.device
        if (device != null && info.minimumOsVersion.isNotBlank() &&
            compareVersions(device.productVersion, info.minimumOsVersion) < 0
        ) {
            IconLine(
                Icons.Filled.WarningAmber,
                "It needs iOS ${info.minimumOsVersion}, and ${device.name} has iOS ${device.productVersion}.",
                tint = warning
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickFile, enabled = idle) { Text("Choose another") }
            Button(onClick = onInstall, enabled = ready) { Text("Sign & install") }
        }
    }

/** "2.1 (12)", or whichever half the app states. */
fun versionText(shortVersion: String, version: String): String = when {
    shortVersion.isBlank() && version.isBlank() -> "No version stated"
    shortVersion.isBlank() -> "Version $version"
    version.isBlank() || version == shortVersion -> "Version $shortVersion"
    else -> "Version $shortVersion ($version)"
}

private fun contents(info: IpaInfo): String {
    val parts = mutableListOf<String>()
    if (info.frameworkCount > 0) {
        parts += if (info.frameworkCount == 1) "1 framework" else "${info.frameworkCount} frameworks"
    }
    if (info.hasExtensions) parts += "app extensions"
    return if (parts.isEmpty()) "The app alone" else parts.joinToString(", ").replaceFirstChar { it.uppercaseChar() }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1e6)
    else -> "${maxOf(1L, bytes / 1000)} KB"
}

/** Compares dotted versions number by number, so 16.10 is after 16.9. */
private fun compareVersions(a: String, b: String): Int {
    val x = a.split('.').map { it.trim().toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.trim().toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val difference = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (difference != 0) return difference
    }
    return 0
}
