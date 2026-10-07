package dev.applesideload.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The places the app can be: five tabs, and the activity log reached from Settings. */
enum class Destination(
    val label: String,
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    /** Where Back goes; a destination with a parent is not in the bottom bar. */
    val parent: Destination? = null
) {
    IPHONE("iPhone", "iPhone", Icons.Outlined.PhoneIphone, Icons.Filled.PhoneIphone),
    INSTALL("Install", "Install", Icons.Outlined.Download, Icons.Filled.Download),
    APPS("Apps", "Apps on the iPhone", Icons.Outlined.Apps, Icons.Filled.Apps),
    ACCOUNT("Account", "Apple account", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
    SETTINGS("Settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
    LOGS(
        "Log",
        "Activity log",
        Icons.AutoMirrored.Outlined.ReceiptLong,
        Icons.AutoMirrored.Filled.ReceiptLong,
        parent = SETTINGS
    );

    companion object {
        /** The tabs in the bottom bar, in order. */
        val tabs: List<Destination> = entries.filter { it.parent == null }
    }
}

/**
 * The frame around every screen: the title bar, the bar of tabs, a banner
 * while something runs, and the messages at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppScaffold(
    destination: Destination,
    onNavigate: (Destination) -> Unit,
    busy: String?,
    snackbar: SnackbarHostState,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    // Each screen starts at its top, so the bar starts flat on each one too.
    val barState = remember(destination) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(barState)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(destination.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    val parent = destination.parent
                    if (parent != null) {
                        IconButton(onClick = { onNavigate(parent) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.tabs.forEach { tab ->
                    val selected = destination == tab || destination.parent == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onNavigate(tab) },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                        label = { Text(tab.label) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            BusyBanner(busy)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                content()
            }
        }
    }
}

/** Remembers the last label shown, so the banner keeps its text while it slides away. */
private class LastLabel(var text: String = "")

/** What is running right now, under the title bar, on every screen. */
@Composable
private fun BusyBanner(busy: String?) {
    val last = remember { LastLabel() }
    if (busy != null) last.text = busy
    AnimatedVisibility(
        visible = busy != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp
                )
                Text(
                    last.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}