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
 * From this iOS version lockdownd resets connections that come through
 * LocalDevVPN (SideStore issue 1532), so SideStore can refresh only with a
 * Remote Pairing file, which only its newest builds read.
 */
const val REMOTE_PAIRING_ONLY_FROM_IOS = 27

/**
 * The whole install, from an IPA on the phone to an app on the iPhone.
 *
 * The pipeline follows SideInstaller and isideload: the bundle identifiers
 * get the team id appended, every extension gets its own App ID, all of them
 * share one app group, the team profile for the main App ID is embedded in
 * the app and each extension, and SideStore gets its certificate and, after
 * the install, the pairing file it needs to refresh apps on the iPhone by
 * itself over LocalDevVPN. Nothing reports success it did not get.
 */
class SideloadEngine(
    private val context: Context,
    private val anisette: AnisetteProvider,
    private val identityStore: IdentityStore
) {

    private val auth = AppleAuth(anisette)

    fun install(
        ipa: File,
        source: InstallSource,
        device: DeviceSession,
        session: AppleSession,
        team: DeveloperTeam,
        onStep: (SideloadStep) -> Unit
    ): InstallOutcome {
        val work = File(context.cacheDir, "sideload-${System.currentTimeMillis()}")
        try {
            onStep(SideloadStep.Preparing("reading the app"))
            val info = IpaPackage.inspect(ipa)

            onStep(SideloadStep.Preparing("unpacking the app"))
            work.mkdirs()
            ipa.inputStream().use { IpaPackage.extract(it, work) }
            val bundle = IpaPackage.appBundle(work)
            val special = SpecialApp.detect(bundle, info.bundleId)
            Log.i(LogTag.SIGN, "preparing ${info.name} ($special)")

            onStep(SideloadStep.Account("checking the signing certificate"))
            val developer = DeveloperSession(session, anisette, auth)
            val (identity, machineId) = ensureIdentity(developer, team)

            onStep(SideloadStep.Account("registering this iPhone"))
            developer.registerDevice(team.teamId, device.info.udid, device.info.name)

            // Identifiers, as isideload makes them: <original>.<TEAMID>, and
            // every extension keeps its suffix under the new main identifier.
            val newBundleId = uniqueBundleId(info.bundleId, team.teamId)
            val extensions = IpaPackage.extensions(bundle)
            val extensionIds = extensions.map { appex ->
                val id = IpaPackage.readInfo(appex)["CFBundleIdentifier"]?.asString
                    ?: throw SigningException(
                        operation = "preparing ${appex.name}",
                        reason = "the extension has no bundle identifier"
                    )
                if (!id.startsWith("${info.bundleId}.")) {
                    throw SigningException(
                        operation = "preparing ${appex.name}",
                        reason = "the extension identifier $id is not under ${info.bundleId}",
                        limitation = "a free account can only provision extensions named under the app",
                        alternative = "use a build of the app whose extensions follow its identifier"
                    )
                }
                newBundleId + id.removePrefix(info.bundleId)
            }
            IpaPackage.rewriteIdentifiers(bundle, info.bundleId, newBundleId, null)

            onStep(SideloadStep.Account("preparing ${1 + extensionIds.size} app identifier(s)"))
            val appIds = ensureAppIds(developer, team, listOf(newBundleId to info.name) +
                extensionIds.zip(extensions.map { it.nameWithoutExtension }))
            val mainAppId = appIds.first { it.identifier == newBundleId }

            val groupId = "group." + if (special == SpecialApp.SIDESTORE_LIVECONTAINER) {
                "${SpecialApp.SIDESTORE_ID}.${team.teamId}"
            } else {
                newBundleId
            }
            onStep(SideloadStep.Account("preparing the app group"))
            val group = developer.ensureAppGroup(team.teamId, groupId, info.name)
            appIds.forEach { appId ->
                developer.enableAppGroups(team.teamId, appId.appIdId)
                developer.assignAppGroup(team.teamId, appId.appIdId, group.groupId)
            }

            onStep(SideloadStep.Account("downloading the provisioning profile"))
            val profile = ProvisioningProfile(
                developer.downloadProvisioningProfile(team.teamId, mainAppId.appIdId)
            )
            if (!profile.covers(device.info.udid)) {
                throw SigningException(
                    operation = "preparing the provisioning profile",
                    reason = "Apple returned a profile that does not list this iPhone",
                    limitation = "a profile only covers devices registered to the account " +
                        "at the time it was issued",
                    alternative = "try again, which asks Apple for a fresh profile"
                )
            }

            applySpecialBehaviour(bundle, extensions, special, groupId, identity, machineId, device.info.udid)

            val extra = if (special.usesLiveContainerKeychain) {
                mapOf(
                    "keychain-access-groups" to Plist.Arr(
                        listOf(Plist.Str("${team.teamId}.com.kdt.livecontainer.shared")) +
                            (1 until 128).map { Plist.Str("${team.teamId}.com.kdt.livecontainer.shared.$it") }
                    )
                )
            } else {
                emptyMap()
            }
            val signer = BundleSigner(identity, profile, extra)
            signer.onProgress = { onStep(SideloadStep.Signing(it)) }
            signer.sign(bundle)

            onStep(SideloadStep.Preparing("packing the signed app"))
            val signedIpa = File(work.parentFile, "${work.name}.ipa")
            IpaPackage.pack(work, signedIpa)

            val staged = upload(device, signedIpa, onStep)
            installStaged(device, staged, onStep)
            signedIpa.delete()

            val handedOff = if (special == SpecialApp.SIDESTORE || special == SpecialApp.SIDESTORE_LIVECONTAINER) {
                handOffPairing(device, newBundleId, special, onStep)
                true
            } else {
                false
            }

            onStep(SideloadStep.Finished(newBundleId, profile.daysRemaining))
            Log.i(LogTag.INSTALL, "installed $newBundleId ($source) on ${device.info.name}")
            return InstallOutcome(info.name, newBundleId, profile.daysRemaining, special, handedOff)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun ensureAppIds(
        developer: DeveloperSession,
        team: DeveloperTeam,
        wanted: List<Pair<String, String>>
    ): List<DeveloperAppId> {
        val existing = developer.listAppIds(team.teamId)
        return wanted.map { (identifier, name) ->
            existing.firstOrNull { it.identifier == identifier }
                ?: developer.addAppId(team.teamId, identifier, name)
        }
    }

    /**
     * What AltServer and isideload write into SideStore-family bundles.
     *
     * ALTAppGroups tells SideStore and its widget which group to share data
     * through. ALTCertificateID and ALTCertificate.p12 give SideStore the
     * certificate that signed it, so it can sign and refresh apps on the
     * iPhone with the same certificate instead of revoking it.
     */
    private fun applySpecialBehaviour(
        bundle: File,
        extensions: List<File>,
        special: SpecialApp,
        groupId: String,
        identity: SigningIdentity,
        machineId: String?,
        udid: String
    ) {
        if (special == SpecialApp.NONE || special == SpecialApp.LIVECONTAINER) return
        val groups = Plist.Arr(listOf(Plist.Str(groupId)))
        IpaPackage.editInfo(bundle) { it["ALTAppGroups"] = groups }
        extensions.forEach { appex -> IpaPackage.editInfo(appex) { it["ALTAppGroups"] = groups } }
        if (special == SpecialApp.ALTSTORE) {
            IpaPackage.editInfo(bundle) { it["ALTDeviceID"] = Plist.Str(udid) }
        }

        val target = if (special == SpecialApp.SIDESTORE_LIVECONTAINER) {
            IpaPackage.frameworks(bundle).first {
                IpaPackage.readInfo(it)["CFBundleIdentifier"]?.asString == SpecialApp.SIDESTORE_ID
            }
        } else {
            bundle
        }
        if (machineId.isNullOrBlank()) {
            Log.w(
                LogTag.SIGN,
                "the certificate has no machine id, so SideStore cannot be given it; it will " +
                    "ask to sign in and make its own"
            )
            return
        }
        val serial = identity.certificate.serialNumber.toString(16).uppercase()
        IpaPackage.editInfo(target) { it["ALTCertificateID"] = Plist.Str(serial) }
        File(target, "ALTCertificate.p12").writeBytes(Pkcs12.export(identity, machineId))
        Log.i(LogTag.SIGN, "gave ${target.name} the signing certificate")
    }

    /**
     * Gives SideStore its pairing files, as SideInstaller does.
     *
     * With them SideStore reaches the iPhone through LocalDevVPN and refreshes
     * itself, LiveContainer and every app it installed, on the iPhone, every
     * day, with no computer and without this phone.
     *
     * Older builds and the SideStore inside LiveContainer read the classic
     * lockdown record from ALTPairingFile.mobiledevicepairing. Nightlies from
     * September 2026 read PairingFile_Lockdown.plist or
     * PairingFile_RemoteRP.plist instead, only once isPairingReset is off,
     * and load the one activePairingProtocol names. On iOS 27 lockdownd
     * resets connections that arrive through LocalDevVPN, so there the
     * Remote Pairing record is the one SideStore can use.
     */
    private fun handOffPairing(
        device: DeviceSession,
        hostBundleId: String,
        special: SpecialApp,
        onStep: (SideloadStep) -> Unit
    ) {
        onStep(SideloadStep.HandOff("allowing SideStore to reach the iPhone over LocalDevVPN"))
        runCatching { device.enableWirelessLockdown() }.onFailure {
            Log.w(LogTag.LOCKDOWN, "could not enable wireless lockdown: ${it.message}; SideStore may refuse the pairing file")
        }

        // A session through the Wi-Fi tunnel has a lockdown record only if
        // the iPhone was connected by cable to this phone before.
        val lockdown = device.record?.let { device.pairingFileForApps() }
        val missing = lockdown?.let { file ->
            val keys = PlistReader.parse(file).asDict.orEmpty().keys
            REQUIRED_PAIRING_KEYS.filterNot { it in keys }
        }.orEmpty()
        if (missing.isNotEmpty()) {
            Log.w(
                LogTag.PAIR,
                "the pairing record lacks ${missing.joinToString()}; SideStore needs them. " +
                    "Unpair and pair again with the iPhone unlocked so the escrow bag is issued."
            )
        }
        val completeLockdown = lockdown?.takeIf { missing.isEmpty() }
        val remote = device.remotePairing?.toAppPlist()
        val legacy = lockdown ?: remote ?: throw DeviceException(
            operation = "giving SideStore its pairing file",
            reason = "this phone has no pairing record for ${device.info.name}",
            limitation = "SideStore is installed, but cannot refresh apps by itself without one",
            alternative = "connect the iPhone by USB or pair it wirelessly, then install SideStore again"
        )
        val lockdownBlocked = device.info.majorVersion >= LOCKDOWN_OVER_VPN_BLOCKED_FROM
        val protocol = when {
            remote != null && (lockdownBlocked || completeLockdown == null) -> PROTOCOL_REMOTE
            completeLockdown != null -> PROTOCOL_LOCKDOWN
            else -> null
        }
        if (lockdownBlocked && remote == null) {
            Log.w(
                LogTag.PAIR,
                "on iOS ${device.info.majorVersion} SideStore can only reach the iPhone with a Remote Pairing " +
                    "record, and this phone has none for ${device.info.name}, so SideStore will ask for a " +
                    "pairing file. Pair wirelessly from this app, then install SideStore again."
            )
        }

        onStep(SideloadStep.HandOff("writing the pairing file into SideStore"))
        val prefix = "/Documents/" + special.sideStoreDocumentsPrefix
        device.appContainer(hostBundleId, wholeContainer = true).use { afc ->
            afc.makeDirectories(prefix.trimEnd('/'))
            writeVerified(afc, prefix + LEGACY_PAIRING_FILE, legacy)
            completeLockdown?.let { writeVerified(afc, prefix + LOCKDOWN_PAIRING_FILE, it) }
            remote?.let { writeVerified(afc, prefix + REMOTE_PAIRING_FILE, it) }
            if (protocol != null) {
                val prefsPath = "/" + special.sideStorePreferences(hostBundleId)
                val existing = afc.readIfPresent(prefsPath)
                val prefs = LinkedHashMap(
                    existing?.takeIf { it.isNotEmpty() }?.let { PlistReader.parse(it).asDict }.orEmpty()
                )
                // What SideStore's own import sets. A protocol picked in its
                // settings lives in preferredPairingProtocol and is left alone.
                prefs["isPairingReset"] = Plist.Bool(false)
                prefs["activePairingProtocol"] = Plist.Str(protocol)
                afc.makeDirectories(prefsPath.substringBeforeLast('/'))
                val staging = "$prefsPath.applesideload"
                writeVerified(afc, staging, BinaryPlist.write(Plist.Dict(prefs)))
                if (afc.exists(prefsPath)) afc.removePath(prefsPath)
                afc.rename(staging, prefsPath)
            }
        }
        when (protocol) {
            PROTOCOL_REMOTE -> Log.i(LogTag.PAIR, "SideStore has its Remote Pairing file and can refresh on the iPhone")
            PROTOCOL_LOCKDOWN -> Log.i(LogTag.PAIR, "SideStore has its pairing file and can refresh on the iPhone")
            else -> Log.w(LogTag.PAIR, "SideStore has only an incomplete pairing file; it will ask for a new one")
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

    /** Stages the IPA under a plain, fresh name and returns that name. */
    private fun upload(device: DeviceSession, signedIpa: File, onStep: (SideloadStep) -> Unit): String {
        onStep(SideloadStep.Uploading(0))
        // The IPA's own name can carry spaces or symbols AFC will not open, and
        // an earlier attempt can leave a file or folder behind, so each upload
        // gets its own simple name and old ones of ours are swept first.
        val stagedName = "AppleSideload-${System.currentTimeMillis()}.ipa"
        device.afc().use { afc ->
            afc.makeDirectories(STAGING)
            runCatching {
                for (leftover in afc.listDirectory(STAGING)) {
                    if (leftover.startsWith("AppleSideload-") || leftover == signedIpa.name) {
                        afc.removeTree("$STAGING/$leftover")
                    }
                }
            }
            val total = signedIpa.length()
            var last = -1
            signedIpa.inputStream().use { source ->
                afc.writeFile("$STAGING/$stagedName", source, total) { written ->
                    val percent = if (total > 0) ((written * 100) / total).toInt() else 0
                    if (percent != last) {
                        last = percent
                        onStep(SideloadStep.Uploading(percent.coerceIn(0, 100)))
                    }
                }
            }
        }
        return stagedName
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

        // A free account holds a small number of development certificates.
        // One made by SideStore on the iPhone, or by another computer, is
        // fine to keep alongside ours, so only a full quota is a problem.
        val (keyPair, csr) = SigningIdentity.createCertificateRequest("AppleSideload")
        identityStore.savePendingKey(keyPair.private.encoded)
        val issued = try {
            developer.submitCertificateRequest(team.teamId, csr, "AppleSideload")
        } catch (error: Exception) {
            if (remote.isNotEmpty()) {
                throw SigningException(
                    operation = "preparing a signing certificate",
                    reason = "Apple would not issue another development certificate: ${error.message}",
                    limitation = "a free account may hold only a few development certificates, and " +
                        "their private keys cannot be copied from the machines that made them",
                    alternative = "revoke the existing certificates in the Account screen; SideStore " +
                        "will ask you to sign in again afterwards"
                )
            }
            throw error
        }
        identityStore.saveSigningKey(keyPair.private.encoded, issued.data)
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
