package dev.applesideload.sideload

import android.content.Context
import dev.applesideload.apple.AnisetteProvider
import dev.applesideload.apple.AppleAuth
import dev.applesideload.apple.AppleSession
import dev.applesideload.apple.DeveloperSession
import dev.applesideload.apple.DeveloperTeam
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.DeviceSession
import dev.applesideload.device.InstallProgress
import dev.applesideload.signing.BundleSigner
import dev.applesideload.signing.IpaPackage
import dev.applesideload.signing.ProvisioningProfile
import dev.applesideload.signing.SigningException
import dev.applesideload.signing.SigningIdentity
import java.io.File

/** Each step of an install, as it happens. */
sealed class SideloadStep {
    data class Preparing(val detail: String) : SideloadStep()
    data class Account(val detail: String) : SideloadStep()
    data class Signing(val bundle: String) : SideloadStep()
    data class Uploading(val percent: Int) : SideloadStep()
    data class Installing(val percent: Int, val status: String) : SideloadStep()
    data class Finished(val bundleId: String, val expiresInDays: Int) : SideloadStep()
}

/**
 * The whole install, from an IPA on the phone to an app on the iPhone.
 *
 * Every step is real: the account is signed in to Apple, the certificate is
 * issued by Apple, the signature is computed here, and the device installs
 * it through installation_proxy. Nothing reports success it did not get, and
 * each failure says which step it was and why it stopped.
 */
class SideloadEngine(
    private val context: Context,
    private val anisette: AnisetteProvider,
    private val identityStore: IdentityStore
) {

    private val auth = AppleAuth(anisette)

    /**
     * Signs [ipa] for [device] and installs it.
     *
     * [session] must already be signed in. The bundle identifier is rewritten
     * to one this account owns, because a free account cannot claim the
     * original.
     */
    fun install(
        ipa: File,
        device: DeviceSession,
        session: AppleSession,
        team: DeveloperTeam,
        onStep: (SideloadStep) -> Unit
    ) {
        val work = File(context.cacheDir, "sideload-${System.currentTimeMillis()}")
        try {
            onStep(SideloadStep.Preparing("reading the app"))
            val info = IpaPackage.inspect(ipa)

            onStep(SideloadStep.Account("checking the signing certificate"))
            val developer = DeveloperSession(session, anisette, auth)
            val identity = ensureIdentity(developer, team)

            onStep(SideloadStep.Account("registering this iPhone"))
            developer.registerDevice(team.teamId, device.info.udid, device.info.name)

            // A free account's identifiers must be unique to the account, so
            // the original is prefixed rather than reused.
            val newBundleId = uniqueBundleId(info.bundleId, team.teamId)
            onStep(SideloadStep.Account("preparing the app identifier"))
            val appId = developer.listAppIds(team.teamId)
                .firstOrNull { it.identifier == newBundleId }
                ?: developer.addAppId(team.teamId, newBundleId, info.name)

            onStep(SideloadStep.Account("downloading the provisioning profile"))
            val profile = ProvisioningProfile(
                developer.downloadProvisioningProfile(team.teamId, appId.appIdId)
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

            onStep(SideloadStep.Preparing("unpacking the app"))
            work.mkdirs()
            ipa.inputStream().use { IpaPackage.extract(it, work) }
            val bundle = IpaPackage.appBundle(work)
            IpaPackage.rewriteIdentifiers(bundle, info.bundleId, newBundleId, null)

            val signer = BundleSigner(identity, profile)
            signer.onProgress = { onStep(SideloadStep.Signing(it)) }
            signer.sign(bundle)

            onStep(SideloadStep.Preparing("packing the signed app"))
            val signedIpa = File(work.parentFile, "${work.name}.ipa")
            IpaPackage.pack(work, signedIpa)

            upload(device, signedIpa, onStep)
            installStaged(device, signedIpa.name, onStep)

            signedIpa.delete()
            onStep(SideloadStep.Finished(newBundleId, profile.daysRemaining))
            Log.i(LogTag.INSTALL, "installed $newBundleId on ${device.info.name}")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun upload(device: DeviceSession, signedIpa: File, onStep: (SideloadStep) -> Unit) {
        onStep(SideloadStep.Uploading(0))
        device.afc().use { afc ->
            afc.makeDirectories(STAGING)
            val total = signedIpa.length()
            signedIpa.inputStream().use { source ->
                afc.writeFile("$STAGING/${signedIpa.name}", source, total) { written ->
                    val percent = if (total > 0) ((written * 100) / total).toInt() else 0
                    onStep(SideloadStep.Uploading(percent.coerceIn(0, 100)))
                }
            }
        }
    }

    private fun installStaged(
        device: DeviceSession,
        fileName: String,
        onStep: (SideloadStep) -> Unit
    ) {
        device.installationProxy().use { proxy ->
            proxy.install("$STAGING/$fileName") { progress: InstallProgress ->
                onStep(SideloadStep.Installing(progress.percent, progress.status))
            }
        }
    }

    /**
     * Reuses the stored certificate, or has a new one issued.
     *
     * A free account holds one development certificate. If Apple has one
     * this app cannot use - because the private key is on another machine -
     * the only way forward is to revoke it, and that is why the caller is
     * told rather than it happening quietly.
     */
    fun ensureIdentity(developer: DeveloperSession, team: DeveloperTeam): SigningIdentity {
        identityStore.loadSigningIdentity()?.let { existing ->
            val known = developer.listCertificates(team.teamId)
                .any { it.serialNumber == existing.certificate.serialNumber.toString(16).uppercase() }
            if (known && existing.expiresAt > System.currentTimeMillis()) return existing
            Log.i(LogTag.SIGN, "the stored certificate is no longer valid for this account")
        }

        val existingRemote = developer.listCertificates(team.teamId)
        if (existingRemote.isNotEmpty()) {
            throw SigningException(
                operation = "preparing a signing certificate",
                reason = "Apple already holds a development certificate for this account " +
                    "that was issued to another device",
                limitation = "a free account may hold one development certificate, and its " +
                    "private key cannot be copied from the machine that made it",
                alternative = "revoke the existing certificate in the Account screen, which " +
                    "will stop apps signed with it from launching"
            )
        }

        val (keyPair, csr) = SigningIdentity.createCertificateRequest("AppleSideload")
        identityStore.savePendingKey(keyPair.private.encoded)
        val issued = developer.submitCertificateRequest(team.teamId, csr, "AppleSideload")
        identityStore.saveSigningKey(keyPair.private.encoded, issued.data)
        return SigningIdentity.from(keyPair.private.encoded, issued.data)
    }

    /**
     * A bundle identifier this account can own.
     *
     * Apple will not issue a profile for someone else's identifier, so the
     * team prefix is folded in. The result is stable for the same app and
     * account, which matters because changing it would install a second copy
     * rather than updating the first.
     */
    fun uniqueBundleId(original: String, teamId: String): String {
        val suffix = teamId.lowercase().take(6)
        return if (original.endsWith(".$suffix")) original else "$original.$suffix"
    }

    private companion object {
        /** Where installation_proxy expects a staged package. */
        const val STAGING = "/PublicStaging"
    }
}
