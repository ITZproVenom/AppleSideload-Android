package dev.applesideload.app.ui

import dev.applesideload.app.RemoteDeviceSummary
import dev.applesideload.app.RemotePairingPrompt
import dev.applesideload.app.SelectedIpa
import dev.applesideload.app.SettingsSnapshot
import dev.applesideload.app.TwoFactorPrompt
import dev.applesideload.app.UiState
import dev.applesideload.app.web.LanAddress
import dev.applesideload.app.web.WebStatus
import dev.applesideload.apple.AppleSession
import dev.applesideload.apple.DeveloperTeam
import dev.applesideload.apple.PendingAuth
import dev.applesideload.core.LogLevel
import dev.applesideload.core.LogLine
import dev.applesideload.core.LogTag
import dev.applesideload.device.ConnectionState
import dev.applesideload.device.DeviceInfo
import dev.applesideload.device.DiscoveredDevice
import dev.applesideload.device.InstalledApp
import dev.applesideload.signing.IpaInfo
import dev.applesideload.sideload.InstallOutcome
import dev.applesideload.sideload.SideloadStep
import dev.applesideload.sideload.SpecialApp
import java.io.File
import java.net.InetAddress

/** Made-up but realistic states for the screenshot tests. */
object Fixtures {
    val device = DeviceInfo(
        udid = "00008110-001A2B3C4D5E801E",
        name = "Alex's iPhone",
        productType = "iPhone15,2",
        productVersion = "27.0",
        buildVersion = "24A5300a",
        cpuArchitecture = "arm64e",
        deviceClass = "iPhone",
        serialNumber = "F2LXK0ABCD12",
        wifiAddress = "a4:c3:f0:12:34:56"
    )

    val account = AppleSession(
        appleId = "alex@example.com",
        adsid = "000123-08-6f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b",
        idmsToken = "",
        sessionKey = ByteArray(0)
    )

    val team = DeveloperTeam("A1B2C3D4E5", "Alex Example (Personal Team)", "Individual", "active")

    val teams = listOf(team, DeveloperTeam("F6G7H8J9K0", "Example Studio Ltd", "Company", "active"))

    val wifiDevice = DiscoveredDevice.Wifi(
        "a4:c3:f0:12:34:56@fe80::1c2b:3a4d:5e6f:7081._apple-mobdev2._tcp",
        InetAddress.getByName("192.168.1.23"),
        62078
    )

    private const val DEVELOPER = "Apple Development: alex@example.com (A1B2C3D4E5)"
    private const val APP_STORE = "Apple iPhone OS Application Signing"

    val apps = listOf(
        InstalledApp("com.SideStore.SideStore.A1B2C3D4E5", "SideStore", "1", "0.6.2", "User", null, DEVELOPER, null),
        InstalledApp("com.kdt.livecontainer.A1B2C3D4E5", "LiveContainer", "1", "3.4.1", "User", null, DEVELOPER, null),
        InstalledApp("com.example.notes.A1B2C3D4E5", "Notes Plus", "12", "2.1", "User", null, DEVELOPER, null),
        InstalledApp("com.burbn.instagram", "Instagram", "409", "409.0", "User", null, APP_STORE, null),
        InstalledApp("com.spotify.client", "Spotify", "9.0.48", "9.0.48", "User", null, APP_STORE, null)
    )

    val ipa = SelectedIpa(
        File("Notes-Plus.ipa"),
        IpaInfo(
            bundleId = "com.example.notes",
            name = "Notes Plus",
            version = "12",
            shortVersion = "2.1",
            minimumOsVersion = "16.0",
            executable = "NotesPlus",
            supportsIphone = true,
            hasExtensions = true,
            frameworkCount = 4,
            iconFile = null,
            sizeBytes = 48_200_000
        )
    )

    val remoteDevices = listOf(RemoteDeviceSummary(device.udid, device.name, device.productType, "192.168.1.23"))

    val disconnected = UiState(discovered = listOf(wifiDevice), remoteDevices = remoteDevices)

    val pairingPin = disconnected.copy(
        remotePairing = RemotePairingPrompt(RemotePairingPrompt.Stage.PIN, "AppleSideload (Pixel 8)", pin = "482913")
    )

    val waitingForTrust = disconnected.copy(
        connection = ConnectionState.PAIRING,
        device = device,
        transport = "USB",
        pairingHint = "Unlock the iPhone and tap Trust, then enter its passcode."
    )

    val failed = disconnected.copy(
        connection = ConnectionState.ERROR,
        lastMessage = "Connecting to 192.168.1.40 over Wi-Fi failed: the Remote Pairing tunnel failed " +
            "(nothing answered within 8 seconds), and lockdown over Wi-Fi failed too.",
        lastMessageIsError = true
    )

    /** PendingAuth is internal to the apple module, so the test reaches its constructor directly. */
    private fun pendingAuth(): PendingAuth {
        val constructor = PendingAuth::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance("alex@example.com", "adsid", "token", "") as PendingAuth
    }

    val twoFactor = UiState(twoFactor = TwoFactorPrompt(pendingAuth()))

    val ready = UiState(
        connection = ConnectionState.READY,
        device = device,
        transport = "Wi-Fi tunnel (192.168.1.23)",
        account = account,
        teams = listOf(team),
        selectedTeam = team,
        apps = apps,
        remoteDevices = remoteDevices
    )

    val installing = ready.copy(
        selectedIpa = ipa,
        step = SideloadStep.Uploading(42),
        busy = "Installing Notes Plus"
    )

    val installed = ready.copy(
        lastOutcome = InstallOutcome("SideStore", "com.SideStore.SideStore.A1B2C3D4E5", 7, SpecialApp.SIDESTORE_LIVECONTAINER, true)
    )

    /** What iOS 27 shows when the LiveContainer build predates SideStore's Remote Pairing support. */
    val installedTooOld = ready.copy(
        lastOutcome = InstallOutcome(
            "SideStore", "com.SideStore.SideStore.A1B2C3D4E5", 7, SpecialApp.SIDESTORE_LIVECONTAINER, true,
            warning = "This SideStore + LiveContainer build (nightly of 2026-09-18) is older than SideStore's " +
                "iOS 27 support, so SideStore cannot refresh on the iPhone with it yet. Install it again from " +
                "this app before the 7 days run out, or install SideStore only, whose newest build has that support."
        )
    )

    val settings = SettingsSnapshot(
        anisetteAddress = "",
        effectiveAnisetteAddress = "https://ani.sidestore.io",
        wifiDiscovery = true,
        lastAppleId = "alex@example.com",
        lastWirelessAddress = "192.168.1.23"
    )

    val web = WebStatus(
        running = true,
        port = 8080,
        addresses = listOf(LanAddress("Wi-Fi", "192.168.1.50", "http://192.168.1.50:8080"))
    )

    val logs = listOf(
        LogLine(1_760_000_000_000, LogLevel.INFO, LogTag.TUNNEL, "found Alex's iPhone at 192.168.1.23:49152", 1),
        LogLine(1_760_000_001_000, LogLevel.INFO, LogTag.TUNNEL, "tunnel to 192.168.1.23 is up", 2),
        LogLine(1_760_000_002_000, LogLevel.INFO, LogTag.LOCKDOWN, "Alex's iPhone is ready through the Wi-Fi tunnel: iPhone15,2 on iOS 27.0", 3),
        LogLine(1_760_000_003_000, LogLevel.WARN, LogTag.PAIR, "the pairing record lacks EscrowBag; SideStore needs them", 4),
        LogLine(1_760_000_004_000, LogLevel.ERROR, LogTag.APP, "Connecting to 192.168.1.40 over Wi-Fi failed: nothing answered within 8 seconds", 5)
    )
}
