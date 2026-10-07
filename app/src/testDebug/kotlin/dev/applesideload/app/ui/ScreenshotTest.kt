package dev.applesideload.app.ui

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.applesideload.app.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws every screen with made-up state, in the app's frame, and saves it
 * under build/screenshots, so the UI can be looked at without a phone.
 * Nothing is compared: these are for people to review, not a pass/fail check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp-xhdpi", application = Application::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shoot(
        name: String,
        destination: Destination,
        busy: String? = null,
        dark: Boolean = false,
        content: @Composable () -> Unit
    ) {
        compose.setContent {
            AppleSideloadTheme(dark = dark, dynamic = false) {
                AppScaffold(
                    destination = destination,
                    onNavigate = {},
                    busy = busy,
                    snackbar = remember { SnackbarHostState() }
                ) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Composable
    private fun Iphone(state: UiState) =
        IphoneScreen(state, "192.168.1.23", {}, {}, {}, {}, {}, {}, {}, {})

    @Composable
    private fun Install(state: UiState) = InstallScreen(state, {}, {}, {}, {})

    @Composable
    private fun Apps(state: UiState) = AppsScreen(state, {}, {}, {})

    @Composable
    private fun Account(state: UiState) =
        AccountScreen(state, "alex@example.com", { _, _ -> }, {}, {}, {}, {}, {})

    @Composable
    private fun Settings() = SettingsScreen(
        Fixtures.ready, Fixtures.settings, Fixtures.web, 342, "0.1.0 (build 20)", {}, {}, {}, {}, {}
    )

    @Test
    fun iphone_disconnected() = shoot("iphone_disconnected", Destination.IPHONE) { Iphone(Fixtures.disconnected) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun iphone_disconnected_phone() =
        shoot("iphone_disconnected_phone", Destination.IPHONE) { Iphone(Fixtures.disconnected) }

    @Test
    fun iphone_pin() = shoot("iphone_pin", Destination.IPHONE) { Iphone(Fixtures.pairingPin) }

    @Test
    fun iphone_trust() = shoot("iphone_trust", Destination.IPHONE) { Iphone(Fixtures.waitingForTrust) }

    @Test
    fun iphone_error() = shoot("iphone_error", Destination.IPHONE) { Iphone(Fixtures.failed) }

    @Test
    fun iphone_ready() = shoot("iphone_ready", Destination.IPHONE) { Iphone(Fixtures.ready) }

    @Test
    fun install_checklist() = shoot("install_checklist", Destination.INSTALL) { Install(Fixtures.disconnected) }

    @Test
    fun install_progress() = shoot("install_progress", Destination.INSTALL) { Install(Fixtures.installing) }

    @Test
    fun install_done() = shoot("install_done", Destination.INSTALL) { Install(Fixtures.installed) }

    @Test
    fun install_warning() = shoot("install_warning", Destination.INSTALL) { Install(Fixtures.installedTooOld) }

    @Test
    fun install_ipa() = shoot("install_ipa", Destination.INSTALL) {
        Install(Fixtures.ready.copy(selectedIpa = Fixtures.ipa))
    }

    @Test
    fun apps() = shoot("apps", Destination.APPS) { Apps(Fixtures.ready) }

    @Test
    fun apps_disconnected() = shoot("apps_disconnected", Destination.APPS) { Apps(Fixtures.disconnected) }

    @Test
    fun account_signed_out() = shoot("account_signed_out", Destination.ACCOUNT) { Account(Fixtures.disconnected) }

    @Test
    fun account_code() = shoot("account_code", Destination.ACCOUNT) { Account(Fixtures.twoFactor) }

    @Test
    fun account() = shoot("account", Destination.ACCOUNT) { Account(Fixtures.ready.copy(teams = Fixtures.teams)) }

    @Test
    fun settings() = shoot("settings", Destination.SETTINGS) { Settings() }

    @Test
    fun logs() = shoot("logs", Destination.LOGS) { LogsScreen(Fixtures.logs) }

    @Test
    fun busy_banner() = shoot("busy_banner", Destination.IPHONE, busy = "Connecting to Alex's iPhone over Wi-Fi") {
        Iphone(Fixtures.disconnected)
    }

    @Test
    fun iphone_ready_dark() = shoot("iphone_ready_dark", Destination.IPHONE, dark = true) { Iphone(Fixtures.ready) }

    @Test
    fun iphone_pin_dark() = shoot("iphone_pin_dark", Destination.IPHONE, dark = true) { Iphone(Fixtures.pairingPin) }

    @Test
    fun install_done_dark() = shoot("install_done_dark", Destination.INSTALL, dark = true) { Install(Fixtures.installed) }

    @Test
    fun account_dark() = shoot("account_dark", Destination.ACCOUNT, dark = true) {
        Account(Fixtures.ready.copy(teams = Fixtures.teams))
    }
}
