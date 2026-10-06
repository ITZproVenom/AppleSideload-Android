package dev.applesideload.app

import android.app.Application
import android.content.Context
import dev.applesideload.apple.AnisetteProvider
import dev.applesideload.apple.AnisetteServers
import dev.applesideload.apple.RemoteAnisetteProvider
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.DeviceDiscovery
import dev.applesideload.device.PairingStore
import dev.applesideload.sideload.IdentityStore
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

    /** Stable identifier sent with anisette requests for this install. */
    val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString()
            .uppercase().also { preferences.edit().putString(KEY_DEVICE_ID, it).apply() }

    private companion object {
        const val KEY_ANISETTE = "anisette"
        const val KEY_WIFI = "wifi_discovery"
        const val KEY_APPLE_ID = "apple_id"
        const val KEY_DEVICE_ID = "device_id"
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

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        pairingStore = PairingStore(this)
        identityStore = IdentityStore(this)
        discovery = DeviceDiscovery(this)
        Log.i(LogTag.APP, "AppleSideload started")
    }

    /** Built fresh so a changed address in Settings takes effect at once. */
    fun anisetteProvider(): AnisetteProvider = RemoteAnisetteProvider(
        baseUrl = settings.effectiveAnisetteAddress,
        deviceId = settings.deviceId
    )
}
