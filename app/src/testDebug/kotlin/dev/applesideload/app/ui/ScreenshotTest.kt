package dev.applesideload.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * Draws every screen with made-up state and saves it under
 * build/screenshots, so the UI can be looked at without a phone. Nothing is
 * compared: these are for people to review, not a pass/fail check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xxhdpi", application = Application::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            AppleSideloadTheme {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun home_disconnected() = shoot("home_disconnected") {
        HomeScreen(Fixtures.pairingPin, "192.168.1.23", {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun home_ready() = shoot("home_ready") {
        HomeScreen(Fixtures.ready, "192.168.1.23", {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun device() = shoot("device") { DeviceScreen(Fixtures.ready) }

    @Test
    fun install() = shoot("install") { InstallScreen(Fixtures.installing, {}, {}, {}) }

    @Test
    fun install_done() = shoot("install_done") { InstallScreen(Fixtures.installed, {}, {}, {}) }

    @Test
    fun apps() = shoot("apps") { AppsScreen(Fixtures.ready, {}, {}) }

    @Test
    fun account_signed_out() = shoot("account_signed_out") {
        AccountScreen(Fixtures.disconnected, "alex@example.com", { _, _ -> }, {}, {}, {}, {})
    }

    @Test
    fun account() = shoot("account") {
        AccountScreen(Fixtures.ready, "alex@example.com", { _, _ -> }, {}, {}, {}, {})
    }

    @Test
    fun logs() = shoot("logs") { DiagnosticsScreen(Fixtures.ready, Fixtures.logs, {}) }

    @Test
    fun settings() = shoot("settings") {
        SettingsScreen(Fixtures.ready, Fixtures.settings, Fixtures.web, {}, {}, {}, {})
    }

    @Test
    @Config(qualifiers = "+night")
    fun home_dark() = shoot("home_dark") {
        HomeScreen(Fixtures.pairingPin, "192.168.1.23", {}, {}, {}, {}, {}, {}, {}, {})
    }
}
