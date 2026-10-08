package dev.applesideload.app

import android.app.Application
import android.content.Context
import dev.applesideload.apple.AnisetteProvider
import dev.applesideload.apple.AnisetteServers
import dev.applesideload.apple.FileAnisetteStore
import dev.applesideload.apple.RemoteAnisetteProvider
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.app.web.WebControl
import dev.applesideload.device.DeviceDiscovery
import dev.applesideload.device.PairingStore
import dev.applesideload.device.RemotePairingNetwork
import dev.applesideload.sideload.IdentityStore
import java.io.File
import java.util.UUID

/** Settings that survive a restart, kept deliberately small. */
class Settings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The anisette source. Empty means the bundled default. */
    var anisetteAddress: String
        get() = preferences.getString(KEY_ANISETTE, "") ?: ""
        set(value) = preferences.edit().putString(KEY_ANISETTE, value.trim().trimEnd('/')).apply()

    val effectiveAnisetteAddress: String
        get() = anisetteAddress.ifBlank { AnisetteServers.default.address }

    var wifiDiscovery: Boolean
        get() = preferences.getBoolean(KEY_WIFI, true)
        set(value) = preferences.edit().putBoolean(KEY_WIFI, value).apply()

    var lastAppleId: String
        get() = preferences.getString(KEY_APPLE_ID, "") ?: ""
        set(value) = preferences.edit().putString(KEY_APPLE_ID, value).apply()

    /** The iPhone address last used in wireless mode. */
    var lastWirelessAddress: String
        get() = preferences.getString(KEY_WIRELESS, "") ?: ""
        set(value) = preferences.edit().putString(KEY_WIRELESS, value).apply()

    var webPort: Int
        get() = preferences.getInt(KEY_WEB_PORT, WebControl.DEFAULT_PORT)
        set(value) = preferences.edit().putInt(KEY_WEB_PORT, value).apply()

    /** Whether the web controller starts with the app: on until the user turns it off. */
    var webEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_WEB_ENABLED, value).apply()

    /** Whether starting the controller by itself has already asked for the notification permission. */
    var askedForNotifications: Boolean
        get() = preferences.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        set(value) = preferences.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, value).apply()

    /** Stable identifier sent with anisette requests for this install. */
    val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString()
            .uppercase().also { preferences.edit().putString(KEY_DEVICE_ID, it).apply() }

    private companion object {
        const val KEY_ANISETTE = "anisette"
        const val KEY_WIFI = "wifi_discovery"
        const val KEY_APPLE_ID = "apple_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_WIRELESS = "wireless_address"
        const val KEY_WEB_PORT = "web_port"
        const val KEY_WEB_ENABLED = "web_enabled"
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}

/** The one place the long lived objects are created. */
class SideloadApplication : Application() {

    lateinit var settings: Settings
        private set
    lateinit var pairingStore: PairingStore
        private set
    lateinit var identityStore: IdentityStore
        private set
    lateinit var discovery: DeviceDiscovery
        private set
    /** Offers this phone for Remote Pairing and finds paired iPhones over Bonjour. */
    lateinit var remoteNetwork: RemotePairingNetwork
        private set
    lateinit var controller: AppController
        private set
    lateinit var webControl: WebControl
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        pairingStore = PairingStore(this)
        identityStore = IdentityStore(this)
        discovery = DeviceDiscovery(this)
        remoteNetwork = RemotePairingNetwork(this, pairingStore)
        controller = AppController(this)
        webControl = WebControl(this)
        Log.i(LogTag.APP, "AppleSideload started")
    }

    /** Built fresh so a changed address in Settings takes effect at once. */
    fun anisetteProvider(): AnisetteProvider = RemoteAnisetteProvider(
        baseUrl = settings.effectiveAnisetteAddress,
        deviceId = settings.deviceId,
        // This phone's own v3 identity, kept so Apple keeps seeing one
        // machine and a two factor code is not asked for every time.
        store = FileAnisetteStore(File(filesDir, "anisette-identity.properties"))
    )
}
