package dev.applesideload.sideload

import android.content.Context
import dev.applesideload.apple.AnisetteProvider
import dev.applesideload.apple.AppleAuth
import dev.applesideload.apple.AppleSession
import dev.applesideload.apple.DeveloperAppId
import dev.applesideload.apple.DeveloperSession
import dev.applesideload.apple.DeveloperTeam
import dev.applesideload.core.BinaryPlist
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.device.AfcClient
import dev.applesideload.device.DeviceException
import dev.applesideload.device.DeviceSession
import dev.applesideload.device.InstallProgress
import dev.applesideload.signing.BundleSigner
import dev.applesideload.signing.IpaPackage
import dev.applesideload.signing.Pkcs12
import dev.applesideload.signing.ProvisioningProfile
import dev.applesideload.signing.SigningException
import dev.applesideload.signing.SigningIdentity
import java.io.File

/** Each step of an install, as it happens. */
sealed class SideloadStep {
    data class Preparing(val detail: String) : SideloadStep()
    data class Downloading(val percent: Int) : SideloadStep()
    data class Account(val detail: String) : SideloadStep()
    data class Signing(val bundle: String) : SideloadStep()
    data class Uploading(val percent: Int) : SideloadStep()
    data class Installing(val percent: Int, val status: String) : SideloadStep()
    data class HandOff(val detail: String) : SideloadStep()
    data class Finished(val bundleId: String, val expiresInDays: Int) : SideloadStep()
}

/** What an install produced. */
data class InstallOutcome(
    val name: String,
    val bundleId: String,
    val expiresInDays: Int,
    val special: SpecialApp,
    val pairingHandedOff: Boolean,
    /** Something the user must know about what was installed, such as a build too old for the iPhone. */
    val warning: String? = null
)

/**
 * Signs and installs IPAs. The long path is the one-tap SideStore / LiveContainer
 * installs; the short path is a user-supplied IPA.
 */
class SideloadEngine(
    private val context: Context,
    private val identityStore: IdentityStore,
    private val anisette: AnisetteProvider
) {

    fun installSpecial(
        device: DeviceSession,
        special: SpecialApp,
        apple: AppleSession,
        team: DeveloperTeam,
        onStep: (SideloadStep) -> Unit
    ): InstallOutcome {
        onStep(SideloadStep.Preparing("fetching ${special.displayName}"))
        val ipa = AppSources.download(special, context.cacheDir) { percent ->
            onStep(SideloadStep.Downloading(percent))
        }
        return installIpa(device, ipa, apple, team, special, onStep)
    }

    fun installIpa(
        device: DeviceSession,
        ipaFile: File,
        apple: AppleSession,
        team: DeveloperTeam,
        special: SpecialApp = SpecialApp.GENERIC,
        onStep: (SideloadStep) -> Unit
    ): InstallOutcome {
        val work = File(context.cacheDir, "sideload-${System.currentTimeMillis()}").also { it.mkdirs() }
        try {
            onStep(SideloadStep.Preparing("unpacking"))
            val info = IpaPackage.unpack(ipaFile, work)
            val bundle = IpaPackage.appBundle(work)
            val detected = if (special != SpecialApp.GENERIC) special else SpecialApp.detect(bundle, info.bundleId)
            Log.i(LogTag.SIGN, "preparing ${info.name} ($detected)")

            onStep(SideloadStep.Account("checking the signing certificate"))
            val developer = DeveloperSession(apple, anisette)
            val (identity, machineId) = ensureIdentity(developer, team)

            onStep(SideloadStep.Signing(info.bundleId))
            val signedIpa = sign(
                work = work,
                bundle = bundle,
                info = info,
                identity = identity,
                team = team,
                developer = developer,
                special = detected,
                machineId = machineId
            )

            upload(device, signedIpa, onStep)
            installStaged(device, signedIpa.name, onStep)

            val handedOff = if (detected.needsPairingFile) {
                onStep(SideloadStep.HandOff("writing the pairing file into SideStore"))
                handOffPairing(device, detected.hostBundleId(team.teamId), device.udid)
            } else false

            val expiresInDays = ((identity.expiresAt - System.currentTimeMillis()) / (24 * 60 * 60 * 1000L)).toInt().coerceAtLeast(0)
            onStep(SideloadStep.Finished(info.bundleId, expiresInDays))
            return InstallOutcome(
                name = info.name,
                bundleId = uniqueBundleId(info.bundleId, team.teamId),
                expiresInDays = expiresInDays,
                special = detected,
                pairingHandedOff = handedOff
            )
        } finally {
            work.deleteRecursively()
        }
    }

    private fun sign(
        work: File,
        bundle: File,
        info: IpaPackage.Info,
        identity: SigningIdentity,
        team: DeveloperTeam,
        developer: DeveloperSession,
        special: SpecialApp,
        machineId: String?
    ): File {
        val teamId = team.teamId
        val mainBundleId = uniqueBundleId(info.bundleId, teamId)
        Log.i(LogTag.SIGN, "rewrote the bundle identifier for this account")

        // Register App IDs for the main bundle and every extension.
        val appIds = mutableMapOf<String, DeveloperAppId>()
        val main = developer.ensureAppId(teamId, mainBundleId, info.name)
        appIds[mainBundleId] = main

        val extensions = IpaPackage.listExtensions(bundle)
        for (ext in extensions) {
            val extId = uniqueBundleId(ext.bundleId, teamId)
            appIds[extId] = developer.ensureAppId(teamId, extId, ext.name)
        }

        // Shared app group for SideStore / LiveContainer.
        val groupId = special.appGroup(teamId)
        if (groupId != null) {
            developer.ensureAppGroup(teamId, groupId)
            for (appId in appIds.values) {
                developer.addAppGroupToAppId(teamId, appId.appIdId, groupId)
            }
        }

        // Profiles.
        val profiles = mutableMapOf<String, ProvisioningProfile>()
        for ((bundleId, appId) in appIds) {
            profiles[bundleId] = developer.ensureProfile(teamId, appId, identity.certificate)
        }

        // Embed certificate for SideStore.
        if (special.embedsCertificate) {
            val p12 = Pkcs12.encode(identity)
            File(bundle, "ALTCertificate.p12").writeBytes(p12)
            Log.i(LogTag.SIGN, "gave SideStoreApp.framework the signing certificate")
        }

        BundleSigner.sign(
            bundleDir = bundle,
            identity = identity,
            profiles = profiles,
            teamId = teamId,
            mainBundleId = mainBundleId,
            extensionBundleIds = extensions.associate { it.bundleId to uniqueBundleId(it.bundleId, teamId) },
            appGroup = groupId,
            keychainGroups = special.keychainGroups(teamId),
            machineId = machineId
        )

        return IpaPackage.repack(work, File(work, "signed.ipa"))
    }

    private fun handOffPairing(device: DeviceSession, hostBundleId: String, udid: String): Boolean {
        // Pairing files live under the SideStore documents container.
        // See SideStore docs and the constants below.
        return try {
            val pairing = identityStore.loadPairingRecord(udid) ?: return false
            val legacy = pairing.legacyBytes ?: return false
            val completeLockdown = pairing.completeLockdownBytes
            val remote = pairing.remoteBytes
            val prefix = "Documents/"
            device.appContainer(hostBundleId, wholeContainer = true).use { afc ->
                afc.makeDirectories(prefix.trimEnd('/'))
                writeVerified(afc, prefix + LEGACY_PAIRING_FILE, legacy)
                completeLockdown?.let { writeVerified(afc, prefix + LOCKDOWN_PAIRING_FILE, it) }
                remote?.let { writeVerified(afc, prefix + REMOTE_PAIRING_FILE, it) }

                // Point SideStore at the right protocol for this iOS version.
                val prefsPath = "Library/Preferences/com.SideStore.SideStore.plist"
                val existing = afc.readIfPresent(prefsPath)
                val prefs = if (existing != null) {
                    (PlistReader.read(existing) as? Plist.Dict)?.map?.toMutableMap() ?: mutableMapOf()
                } else mutableMapOf()
                val iosMajor = device.productVersion.split('.').firstOrNull()?.toIntOrNull() ?: 0
                prefs["pairingFileProtocol"] = Plist.Str(
                    if (iosMajor >= LOCKDOWN_OVER_VPN_BLOCKED_FROM) PROTOCOL_REMOTE else PROTOCOL_LOCKDOWN
                )
                afc.makeDirectories(prefsPath.substringBeforeLast('/'))
                val staging = "$prefsPath.applesideload"
                writeVerified(afc, staging, BinaryPlist.write(Plist.Dict(prefs)))
                if (afc.exists(prefsPath)) afc.removePath(prefsPath)
                afc.rename(staging, prefsPath)
            }
            true
        } catch (e: Exception) {
            Log.w(LogTag.INSTALL, "could not hand off the pairing file: ${e.message}")
            false
        }
    }

    private fun writeVerified(afc: AfcClient, path: String, data: ByteArray) {
        afc.writeBytes(path, data)
        val back = afc.readFile(path)
        if (!back.contentEquals(data)) {
            throw DeviceException(
                operation = "writing $path",
                reason = "the file read back as ${back.size} bytes instead of ${data.size}"
            )
        }
    }

    private fun upload(device: DeviceSession, signedIpa: File, onStep: (SideloadStep) -> Unit) {
        onStep(SideloadStep.Uploading(0))
        device.afc().use { afc ->
            afc.makeDirectories(STAGING)
            val total = signedIpa.length()
            var last = -1
            signedIpa.inputStream().use { source ->
                afc.writeFile("$STAGING/${signedIpa.name}", source, total) { written ->
                    val percent = if (total > 0) ((written * 100) / total).toInt() else 0
                    if (percent != last) {
                        last = percent
                        onStep(SideloadStep.Uploading(percent.coerceIn(0, 100)))
                    }
                }
            }
        }
    }

    private fun installStaged(device: DeviceSession, fileName: String, onStep: (SideloadStep) -> Unit) {
        device.installationProxy().use { proxy ->
            proxy.install("$STAGING/$fileName") { progress: InstallProgress ->
                onStep(SideloadStep.Installing(progress.percent, progress.status))
            }
        }
        runCatching { device.afc().use { it.removePath("$STAGING/$fileName") } }
    }

    /**
     * Reuses the stored certificate, or has a new one issued.
     *
     * Returns the identity and the machine id Apple holds for it.
     */
    fun ensureIdentity(developer: DeveloperSession, team: DeveloperTeam): Pair<SigningIdentity, String?> {
        val remote = developer.listCertificates(team.teamId)
        identityStore.loadSigningIdentity()?.let { existing ->
            val serial = existing.certificate.serialNumber.toString(16).uppercase()
            val match = remote.firstOrNull { it.serialNumber.equals(serial, ignoreCase = true) }
            if (match != null && existing.expiresAt > System.currentTimeMillis()) {
                val machineId = match.machineId ?: identityStore.loadMachineId()
                machineId?.let { identityStore.saveMachineId(it) }
                return existing to machineId
            }
            Log.i(LogTag.SIGN, "the stored certificate is no longer valid for this account")
        }
        // Issue a new one.
        val keyPair = identityStore.generateKeyPair()
        val csr = identityStore.buildCsr(keyPair)
        val issued = developer.createCertificate(team.teamId, csr)
        identityStore.saveSigningIdentity(keyPair.private.encoded, issued.data)
        issued.machineId?.let { identityStore.saveMachineId(it) }
        return SigningIdentity.from(keyPair.private.encoded, issued.data) to issued.machineId
    }

    /** <original>.<TEAMID>, as isideload names them, stable for refreshes. */
    fun uniqueBundleId(original: String, teamId: String): String =
        if (original.endsWith(".$teamId")) original else "$original.$teamId"

    private companion object {
        const val STAGING = "/PublicStaging"

        /** What SideStore's minimuxer requires in a lockdown pairing file. */
        const val LEGACY_PAIRING_FILE = "ALTPairingFile.mobiledevicepairing"
        const val LOCKDOWN_PAIRING_FILE = "PairingFile_Lockdown.plist"
        const val REMOTE_PAIRING_FILE = "PairingFile_RemoteRP.plist"
        const val PROTOCOL_LOCKDOWN = "lockdown"
        const val PROTOCOL_REMOTE = "rppairing"

        /**
         * From iOS 27 lockdownd resets connections through LocalDevVPN even
         * with a complete record (SideStore issue 1532), so SideStore is
         * pointed at the Remote Pairing record there.
         */
        const val LOCKDOWN_OVER_VPN_BLOCKED_FROM = REMOTE_PAIRING_ONLY_FROM_IOS

        val REQUIRED_PAIRING_KEYS = listOf(
            "WiFiMACAddress", "SystemBUID", "RootPrivateKey", "HostPrivateKey", "HostID",
            "RootCertificate", "UDID", "EscrowBag", "HostCertificate", "DeviceCertificate"
        )
    }
}
