package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist

/**
 * house_arrest, which opens an installed app's own container over AFC.
 *
 * This is how a pairing file reaches SideStore: the app is installed first,
 * then its container is vended and the file is written into it, exactly as
 * SideInstaller and iLoader do. Only apps signed with get-task-allow (every
 * development-signed app) can be vended.
 */
object HouseArrestClient {

    const val SERVICE = "com.apple.mobile.house_arrest"

    /**
     * Opens [bundleId]'s container and returns an AFC client rooted in it.
     *
     * [wholeContainer] uses VendContainer, which reaches Library as well as
     * Documents; otherwise VendDocuments is used, which iOS allows more often.
     * Either way the AFC root is the container root, so paths start with
     * "/Documents/" or "/Library/".
     */
    fun open(session: DeviceSession, bundleId: String, wholeContainer: Boolean): AfcClient {
        val transport = session.openService(SERVICE)
        val service = PlistService(transport, "house_arrest", sendBinary = false)
        val command = if (wholeContainer) "VendContainer" else "VendDocuments"
        val reply = try {
            service.request(
                Plist.dict("Command" to Plist.Str(command), "Identifier" to Plist.Str(bundleId))
            )
        } catch (error: DeviceException) {
            runCatching { transport.close() }
            throw error
        }
        reply["Error"]?.asString?.let { code ->
            runCatching { transport.close() }
            throw DeviceException(
                operation = "opening the container of $bundleId",
                reason = "house_arrest refused with $code",
                iosVersion = session.info.productVersion,
                limitation = if (code == "ApplicationLookupFailed") {
                    "the app is not installed, or it is not signed for development"
                } else {
                    "iOS only vends containers of apps signed with get-task-allow"
                },
                alternative = "install the app with this phone first, then try again"
            )
        }
        if (reply["Status"]?.asString != "Complete") {
            runCatching { transport.close() }
            throw DeviceException(
                operation = "opening the container of $bundleId",
                reason = "house_arrest answered without Status Complete"
            )
        }
        Log.i(LogTag.AFC, "opened the container of $bundleId with $command")
        // From here the same connection speaks AFC.
        return AfcClient(transport)
    }
}
