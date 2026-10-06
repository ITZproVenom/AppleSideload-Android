package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import java.io.Closeable

/** One app as the device reports it. */
data class InstalledApp(
    val bundleId: String,
    val name: String,
    val version: String,
    val shortVersion: String,
    val applicationType: String,
    val path: String?,
    val signerIdentity: String?,
    val executable: String?,
    val expiresAt: Long? = null
) {
    /** Sideloaded apps are the ones this app can manage; system apps are not. */
    val isSideloaded: Boolean get() = applicationType.equals("User", ignoreCase = true)
}

/** Progress as installation_proxy reports it, straight through to the UI. */
data class InstallProgress(val percent: Int, val status: String)

/**
 * installation_proxy, which installs, lists and removes applications.
 *
 * Install is asynchronous: the request is sent once and the service then
 * streams status dictionaries until one of them says Complete or carries an
 * error. Those errors are the ones users actually hit - a bad signature, a
 * provisioning profile the device will not accept, a device limit reached -
 * so they are translated rather than swallowed.
 */
class InstallationProxyClient(private val service: PlistService) : Closeable {

    fun browse(): List<InstalledApp> {
        service.send(
            Plist.dict(
                "Command" to Plist.Str("Browse"),
                "ClientOptions" to Plist.dict(
                    "ApplicationType" to Plist.Str("Any"),
                    "ReturnAttributes" to Plist.Arr(
                        ATTRIBUTES.map { Plist.Str(it) }
                    )
                )
            )
        )
        val apps = mutableListOf<InstalledApp>()
        while (true) {
            val reply = service.receive()
            reply["Error"]?.asString?.let { throw installError(it, reply) }
            reply["CurrentList"]?.asList?.forEach { entry -> parse(entry)?.let(apps::add) }
            val status = reply["Status"]?.asString
            if (status == "Complete") break
            if (status == null && reply["CurrentList"] == null) break
        }
        Log.i(LogTag.INSTALL, "the device reports ${apps.size} applications")
        return apps
    }

    fun lookup(bundleId: String): InstalledApp? {
        val reply = service.request(
            Plist.dict(
                "Command" to Plist.Str("Lookup"),
                "ClientOptions" to Plist.dict(
                    "BundleIDs" to Plist.Arr(listOf(Plist.Str(bundleId))),
                    "ReturnAttributes" to Plist.Arr(ATTRIBUTES.map { Plist.Str(it) })
                )
            )
        )
        reply["Error"]?.asString?.let { throw installError(it, reply) }
        return reply["LookupResult"]?.get(bundleId)?.let(::parse)
    }

    /**
     * Installs an IPA that has already been staged over AFC.
     *
     * [stagedPath] is the path inside PublicStaging, relative to the AFC root,
     * which is what the service expects - not a path on Android.
     */
    fun install(stagedPath: String, onProgress: (InstallProgress) -> Unit) =
        run("Install", stagedPath, onProgress)

    fun upgrade(stagedPath: String, onProgress: (InstallProgress) -> Unit) =
        run("Upgrade", stagedPath, onProgress)

    fun uninstall(bundleId: String, onProgress: (InstallProgress) -> Unit = {}) {
        service.send(
            Plist.dict(
                "Command" to Plist.Str("Uninstall"),
                "ApplicationIdentifier" to Plist.Str(bundleId)
            )
        )
        drain("Uninstall", onProgress)
    }

    private fun run(command: String, stagedPath: String, onProgress: (InstallProgress) -> Unit) {
        service.send(
            Plist.dict(
                "Command" to Plist.Str(command),
                "PackagePath" to Plist.Str(stagedPath),
                "ClientOptions" to Plist.dict(
                    "PackageType" to Plist.Str("Developer"),
                    "CFBundleIdentifier" to Plist.Str("")
                )
            )
        )
        drain(command, onProgress)
    }

    private fun drain(command: String, onProgress: (InstallProgress) -> Unit) {
        while (true) {
            val reply = service.receive(timeoutMs = 300_000)
            reply["Error"]?.asString?.let { throw installError(it, reply) }
            val status = reply["Status"]?.asString ?: continue
            val percent = reply["PercentComplete"]?.asInt ?: 0
            onProgress(InstallProgress(percent, status))
            Log.i(LogTag.INSTALL, "$command: $status ($percent%)")
            if (status == "Complete") return
        }
    }

    override fun close() = service.close()

    private fun parse(entry: Plist): InstalledApp? {
        val bundleId = entry["CFBundleIdentifier"]?.asString ?: return null
        return InstalledApp(
            bundleId = bundleId,
            name = entry["CFBundleDisplayName"]?.asString
                ?: entry["CFBundleName"]?.asString ?: bundleId,
            version = entry["CFBundleVersion"]?.asString.orEmpty(),
            shortVersion = entry["CFBundleShortVersionString"]?.asString.orEmpty(),
            applicationType = entry["ApplicationType"]?.asString.orEmpty(),
            path = entry["Path"]?.asString,
            signerIdentity = entry["SignerIdentity"]?.asString,
            executable = entry["CFBundleExecutable"]?.asString
        )
    }

    private fun installError(code: String, reply: Plist): DeviceException {
        val detail = reply["ErrorDescription"]?.asString ?: code
        return when (code) {
            "ApplicationVerificationFailed" -> DeviceException(
                operation = "installing the app",
                reason = "the device rejected the code signature ($detail)",
                limitation = "iOS verifies that every executable in the bundle is signed by " +
                    "the certificate in the embedded provisioning profile, and that the " +
                    "profile allows this device",
                alternative = "sign the IPA again with an account that has a free development " +
                    "certificate and this device registered"
            )

            "MismatchedApplicationIdentifierEntitlement" -> DeviceException(
                operation = "installing the app",
                reason = "the application-identifier entitlement does not match the " +
                    "provisioning profile ($detail)",
                limitation = "the bundle identifier must match the one the profile was issued " +
                    "for, including the team prefix"
            )

            "DeviceOSVersionTooLow" -> DeviceException(
                operation = "installing the app",
                reason = "the app requires a newer version of iOS than this device runs ($detail)"
            )

            "MaximumFreeAppsInstalled", "TooManyApplications" -> DeviceException(
                operation = "installing the app",
                reason = detail,
                limitation = "a free Apple developer account may have at most three apps " +
                    "installed on a device at once",
                alternative = "remove one of the apps installed by this account and try again"
            )

            "PackageInspectionFailed" -> DeviceException(
                operation = "installing the app",
                reason = "the device could not read the package ($detail)",
                limitation = "the staged IPA is incomplete or is not a valid zip archive"
            )

            else -> DeviceException(
                operation = "installing the app",
                reason = "installation_proxy returned $code: $detail"
            )
        }
    }

    companion object {
        const val SERVICE = "com.apple.mobile.installation_proxy"

        private val ATTRIBUTES = listOf(
            "CFBundleIdentifier",
            "CFBundleDisplayName",
            "CFBundleName",
            "CFBundleVersion",
            "CFBundleShortVersionString",
            "CFBundleExecutable",
            "ApplicationType",
            "Path",
            "SignerIdentity"
        )
    }
}
