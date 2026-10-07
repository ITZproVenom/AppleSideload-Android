package dev.applesideload.sideload

import dev.applesideload.signing.IpaPackage
import java.io.File

/**
 * Apps that need more than a signature, detected the way isideload does.
 *
 * SideStore and AltStore read their app group and their signing certificate
 * from their own bundle, LiveContainer needs its keychain groups, and the
 * LiveContainer build with SideStore inside carries SideStore as a
 * framework rather than as the main app.
 */
enum class SpecialApp {
    SIDESTORE,
    SIDESTORE_LIVECONTAINER,
    LIVECONTAINER,
    ALTSTORE,
    NONE;

    val isSideStoreFamily: Boolean get() = this == SIDESTORE || this == SIDESTORE_LIVECONTAINER || this == ALTSTORE
    val usesLiveContainerKeychain: Boolean get() = this == SIDESTORE_LIVECONTAINER || this == LIVECONTAINER

    /** SideStore's Documents, relative to the host app's Documents. */
    val sideStoreDocumentsPrefix: String get() = if (this == SIDESTORE_LIVECONTAINER) "SideStore/Documents/" else ""

    /** SideStore's preferences, relative to the host container root. */
    fun sideStorePreferences(hostBundleId: String): String = when (this) {
        SIDESTORE_LIVECONTAINER -> "Documents/SideStore/Library/Preferences/$SIDESTORE_ID.plist"
        else -> "Library/Preferences/$hostBundleId.plist"
    }

    companion object {
        const val SIDESTORE_ID = "com.SideStore.SideStore"
        const val ALTSTORE_ID = "com.rileytestut.AltStore"
        const val LIVECONTAINER_ID = "com.kdt.livecontainer"

        fun detect(bundle: File, bundleId: String): SpecialApp = when {
            bundleId == SIDESTORE_ID -> SIDESTORE
            bundleId == ALTSTORE_ID -> ALTSTORE
            IpaPackage.frameworks(bundle).any {
                runCatching { IpaPackage.readInfo(it)["CFBundleIdentifier"]?.asString }.getOrNull() == SIDESTORE_ID
            } -> SIDESTORE_LIVECONTAINER
            bundleId == LIVECONTAINER_ID -> LIVECONTAINER
            else -> NONE
        }
    }
}
