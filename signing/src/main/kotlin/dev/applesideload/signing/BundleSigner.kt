package dev.applesideload.signing

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import java.io.File
import java.security.MessageDigest

/**
 * Signs an unpacked .app, and everything inside it.
 *
 * iOS checks nested code separately, so frameworks, app extensions and
 * watch apps are signed first and the app itself last - its CodeResources
 * covers their signatures, and changing one afterwards would invalidate the
 * outer signature.
 */
class BundleSigner(
    private val identity: SigningIdentity,
    private val profile: ProvisioningProfile
) {

    /** Reports each bundle as it is signed, for the install screen. */
    var onProgress: (String) -> Unit = {}

    fun sign(appBundle: File) {
        if (!appBundle.isDirectory) {
            throw SigningException(
                operation = "signing the app",
                reason = "${appBundle.name} is not a bundle directory"
            )
        }
        signNested(appBundle)
        writeProfile(appBundle)
        signBundle(appBundle, isMain = true)
    }

    private fun signNested(bundle: File) {
        // Order matters: the deepest code is signed first.
        listOf("Frameworks", "PlugIns", "Watch", "Extensions", "SystemExtensions")
            .map { File(bundle, it) }
            .filter { it.isDirectory }
            .forEach { directory ->
                directory.listFiles()?.sortedBy { it.name }?.forEach { entry ->
                    when {
                        entry.isDirectory && entry.name.endsWith(".app") -> {
                            signNested(entry)
                            signBundle(entry, isMain = false)
                        }

                        entry.isDirectory -> {
                            signNested(entry)
                            signBundle(entry, isMain = false)
                        }

                        entry.isFile && entry.name.endsWith(".dylib") -> signLooseBinary(entry)
                    }
                }
            }
    }

    private fun writeProfile(bundle: File) {
        File(bundle, "embedded.mobileprovision").writeBytes(profile.raw)
    }

    /** Signs one bundle: its resources, then its executable. */
    private fun signBundle(bundle: File, isMain: Boolean) {
        val infoFile = File(bundle, "Info.plist")
        if (!infoFile.exists()) {
            // A framework directory without an Info.plist is not code.
            return
        }
        val info = PlistReader.parse(infoFile.readBytes())
        val executableName = info["CFBundleExecutable"]?.asString
            ?: throw SigningException(
                operation = "signing ${bundle.name}",
                reason = "its Info.plist has no CFBundleExecutable"
            )
        val executable = File(bundle, executableName)
        if (!executable.exists()) {
            throw SigningException(
                operation = "signing ${bundle.name}",
                reason = "the executable $executableName named in Info.plist is missing"
            )
        }
        val identifier = info["CFBundleIdentifier"]?.asString ?: bundle.nameWithoutExtension

        onProgress(bundle.name)

        // Stale signature directories would be hashed into the new one.
        File(bundle, "_CodeSignature").deleteRecursively()

        val resources = CodeResources.build(bundle, executableName)
        File(bundle, "_CodeSignature").mkdirs()
        File(bundle, "_CodeSignature/CodeResources").writeBytes(resources)

        val entitlements = if (isMain) {
            profile.entitlementsXml()
        } else {
            // Nested code gets the identifier-only subset; claiming the full
            // set on an extension is what produces a mismatch at install.
            XmlPlist.write(nestedEntitlements(identifier))
        }

        signExecutable(
            executable = executable,
            identifier = identifier,
            infoPlist = infoFile.readBytes(),
            resources = resources,
            entitlements = entitlements,
            isMain = isMain
        )
    }

    /** A bare dylib has no bundle, so it is signed with no resource slots. */
    private fun signLooseBinary(file: File) {
        onProgress(file.name)
        signExecutable(
            executable = file,
            identifier = file.nameWithoutExtension,
            infoPlist = null,
            resources = null,
            entitlements = null,
            isMain = false
        )
    }

    private fun nestedEntitlements(identifier: String): Plist {
        val team = profile.teamIdentifier
        return Plist.dict(
            "application-identifier" to Plist.Str("$team.$identifier"),
            "com.apple.developer.team-identifier" to Plist.Str(team),
            "get-task-allow" to Plist.Bool(true),
            "keychain-access-groups" to Plist.Arr(listOf(Plist.Str("$team.*")))
        )
    }

    private fun signExecutable(
        executable: File,
        identifier: String,
        infoPlist: ByteArray?,
        resources: ByteArray?,
        entitlements: ByteArray?,
        isMain: Boolean
    ) {
        val original = executable.readBytes()
        val machO = try {
            MachOFile(original)
        } catch (error: MachOException) {
            throw SigningException(
                operation = "signing ${executable.name}",
                reason = error.message ?: "the file is not a Mach-O executable",
                cause = error
            )
        }

        val requirements = Blobs.emptyRequirements()
        val entitlementsBlob = entitlements?.let { Blobs.entitlements(it) }
        val derBlob = entitlements?.let {
            Blobs.derEntitlements(DerEntitlements.encode(PlistReader.parse(it)))
        }

        val builder = object : MachOSigner.SignatureBuilder {
            override fun reserve(codeLimit: Int): Int {
                val pages = (codeLimit + 4095) / 4096
                // Two code directories, the blobs, and room for the CMS
                // signature with its certificate chain.
                return pages * (32 + 20) + 2 * (identifier.length + 600) +
                    requirements.size + (entitlementsBlob?.size ?: 0) +
                    (derBlob?.size ?: 0) + 12_000
            }

            override fun build(image: ByteArray, codeLimit: Int): ByteArray {
                val directories = listOf(Blobs.HASH_SHA256, Blobs.HASH_SHA1).map { hashType ->
                    val special = buildMap {
                        infoPlist?.let { put(Blobs.SLOT_INFOSLOT, Blobs.digest(hashType, it)) }
                        put(Blobs.SLOT_REQUIREMENTS, Blobs.digest(hashType, requirements))
                        resources?.let { put(Blobs.SLOT_RESOURCEDIR, Blobs.digest(hashType, it)) }
                        entitlementsBlob?.let {
                            put(Blobs.SLOT_ENTITLEMENTS, Blobs.digest(hashType, it))
                        }
                        derBlob?.let {
                            put(Blobs.SLOT_DER_ENTITLEMENTS, Blobs.digest(hashType, it))
                        }
                    }
                    CodeDirectory(
                        identifier = identifier,
                        teamId = identity.teamId,
                        hashType = hashType,
                        executableLength = codeLimit,
                        fileBytes = image,
                        fileStart = 0,
                        specialSlots = special
                    ).apply { isMainExecutable = isMain }.build()
                }

                val entries = buildList {
                    add(Blobs.SLOT_CODEDIRECTORY to directories[0])
                    add(Blobs.SLOT_REQUIREMENTS to requirements)
                    entitlementsBlob?.let { add(Blobs.SLOT_ENTITLEMENTS to it) }
                    derBlob?.let { add(Blobs.SLOT_DER_ENTITLEMENTS to it) }
                    add(Blobs.SLOT_ALTERNATE_CODEDIRECTORY to directories[1])
                    add(
                        Blobs.SLOT_SIGNATURESLOT to Blobs.blob(
                            Blobs.MAGIC_BLOBWRAPPER,
                            Cms.sign(directories[0], directories, identity)
                        )
                    )
                }
                return Blobs.superBlob(entries)
            }
        }

        val signed = MachOSigner.sign(machO, builder)
        executable.writeBytes(signed)
        executable.setExecutable(true, false)
    }

    companion object {
        /** The hash the device reports for an installed app. */
        fun cdHash(codeDirectory: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(codeDirectory)
                .copyOfRange(0, 20)
                .joinToString("") { "%02x".format(it) }
    }
}
