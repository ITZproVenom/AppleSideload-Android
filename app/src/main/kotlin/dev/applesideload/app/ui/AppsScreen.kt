package dev.applesideload.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.applesideload.app.UiState
import dev.applesideload.device.ConnectionState
import dev.applesideload.device.InstalledApp

/**
 * How iOS signs what comes from the App Store (and TestFlight); everything
 * else on the list was signed by a developer, which is what sideloading is.
 */
private const val APP_STORE_SIGNER = "Apple iPhone OS Application Signing"

private val InstalledApp.fromAppStore: Boolean
    get() = signerIdentity?.startsWith(APP_STORE_SIGNER) == true

/** The Apps tab: what is installed on the connected iPhone, and removing it. */
@Composable
fun AppsScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onUninstall: (String) -> Unit,
    onNavigate: (Destination) -> Unit
) = ScreenColumn {
    if (state.connection != ConnectionState.READY) {
        EmptyState(
            icon = Icons.Filled.PhonelinkOff,
            title = "No iPhone connected",
            body = "Connect the iPhone to see the apps on it and remove the ones you sideloaded."
        ) {
            Button(onClick = { onNavigate(Destination.IPHONE) }) { Text("Connect an iPhone") }
        }
        return@ScreenColumn
    }
    val idle = state.busy == null
    var removing by remember { mutableStateOf<InstalledApp?>(null) }
    val (fromStore, sideloaded) = state.apps.partition { it.fromAppStore }

    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "On ${state.device?.name ?: "the iPhone"}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRefresh, enabled = idle) {
            Icon(Icons.Filled.Refresh, contentDescription = "Read the list again")
        }
    }

    if (sideloaded.isEmpty()) {
        SectionCard(null) {
            EmptyState(
                icon = Icons.Outlined.Apps,
                title = "Nothing sideloaded yet",
                body = "Apps you install with your Apple ID show up here."
            ) {
                Button(onClick = { onNavigate(Destination.INSTALL) }) { Text("Install an app") }
            }
        }
    } else {
        SectionCard("Sideloaded", icon = Icons.Filled.VerifiedUser) {
            AppList(sideloaded, idle) { removing = it }
            Hint("Signed by a developer, not by Apple. A free Apple ID can have 3 apps of its own installed at a time.")
        }
    }

    if (fromStore.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(false) }
        SectionCard(
            "From the App Store",
            icon = Icons.Filled.Storefront,
            action = {
                TextButton(onClick = { open = !open }) {
                    Text(if (open) "Hide" else "Show ${fromStore.size}")
                    Icon(
                        if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        ) {
            if (open) {
                AppList(fromStore, idle) { removing = it }
            } else {
                Hint("Apps Apple signed. They do not count towards the free account's limit.")
            }
        }
    }

    removing?.let { app ->
        ConfirmDialog(
            title = "Remove ${app.name}?",
            text = "It is deleted from the iPhone together with its data.",
            confirmLabel = "Remove",
            onConfirm = { onUninstall(app.bundleId) },
            onDismiss = { removing = null }
        )
    }
}

@Composable
private fun AppList(apps: List<InstalledApp>, idle: Boolean, onRemove: (InstalledApp) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        apps.forEachIndexed { index, app ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ListRow(
                title = app.name,
                subtitle = "${versionText(app.shortVersion, app.version)}\n${app.bundleId}",
                monospaceSubtitle = false,
                leading = { LetterAvatar(app.name, app.bundleId) },
                trailing = {
                    IconButton(onClick = { onRemove(app) }, enabled = idle) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove ${app.name}")
                    }
                }
            )
        }
    }
}
