package dev.applesideload.signing

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What an IPA says about itself, read before anything is changed. */
data class IpaInfo(
    val bundleId: String,
    val name: String,
    val version: String,
    val shortVersion: String,
    val minimumOsVersion: String,
    val executable: String,
    val supportsIphone: Boolean,
    val hasExtensions: Boolean,
    val frameworkCount: Int,
    val iconFile: String?,
    val sizeBytes: Long
) {
    /** These two need entitlements most signers get wrong, so they are named. */
    val isSideStore: Boolean get() = bundleId.contains("SideStore", ignoreCase = true)
    val isLiveContainer: Boolean get() = bundleId.contains("LiveContainer", ignoreCase = true) ||
        name.equals("LiveContainer", ignoreCase = true)
}

/**
 * Reading and rewriting an IPA.
 *
 * An IPA is a zip with a Payload directory holding one .app. Everything here
 * works on a directory extracted from it, because signing has to read and
 * rewrite individual files and a zip cannot be edited in place.
 */
object IpaPackage {

    /** Reads the metadata without extracting the whole archive. */
    fun inspect(file: File): IpaInfo {
        ZipFile(file).use { zip ->
            val infoEntry = zip.entries().asSequence()
                .firstOrNull {
                    val parts = it.name.split('/')
                    parts.size == 3 && parts[0] == "Payload" &&
                        parts[1].endsWith(".app") && parts[2] == "Info.plist"
                }
                ?: throw SigningException(
                    operation = "reading the IPA",
                    reason = "it has no Payload/<app>/Info.plist",
                    limitation = "the file is not an iOS application archive"
                )
            val appPrefix = infoEntry.name.substringBeforeLast('/') + "/"
            val info = PlistReader.parse(zip.getInputStream(infoEntry).readBytes())
            val names = zip.entries().asSequence().map { it.name }.toList()
            return IpaInfo(
                bundleId = info["CFBundleIdentifier"]?.asString
                    ?: throw SigningException(
                        operation = "reading the IPA",
                        reason = "its Info.plist has no bundle identifier"
                    ),
                name = info["CFBundleDisplayName"]?.asString
                    ?: info["CFBundleName"]?.asString
                    ?: appPrefix.removeSuffix("/").substringAfterLast('/').removeSuffix(".app"),
                version = info["CFBundleVersion"]?.asString.orEmpty(),
                shortVersion = info["CFBundleShortVersionString"]?.asString.orEmpty(),
                minimumOsVersion = info["MinimumOSVersion"]?.asString.orEmpty(),
                executable = info["CFBundleExecutable"]?.asString.orEmpty(),
                supportsIphone = info["UIDeviceFamily"]?.asList
                    ?.any { it.asLong == 1L } ?: true,
                hasExtensions = names.any { it.startsWith("${appPrefix}PlugIns/") },
                frameworkCount = names.count {
                    it.startsWith("${appPrefix}Frameworks/") && it.endsWith(".framework/")
                },
                iconFile = info["CFBundleIcons"]
                    ?.get("CFBundlePrimaryIcon")
                    ?.get("CFBundleIconFiles")
                    ?.asList?.lastOrNull()?.asString,
                sizeBytes = file.length()
            )
        }
    }

    /** Extracts to [destination], refusing any entry that escapes it. */
    fun extract(source: InputStream, destination: File) {
        destination.mkdirs()
        val root = destination.canonicalFile
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                val target = File(destination, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) {
                    throw SigningException(
                        operation = "unpacking the IPA",
                        reason = "an entry named \"${entry.name}\" points outside the archive",
                        limitation = "this archive is malformed or deliberately hostile"
                    )
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { zip.copyTo(it) }
                    // Executables lose their bit through a zip round trip.
                    if (entry.name.contains(".app/") || entry.name.endsWith(".dylib")) {
                        target.setReadable(true, false)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    /** The one .app directly inside Payload. */
    fun appBundle(extracted: File): File {
        val payload = File(extracted, "Payload")
        return payload.listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith(".app") }
            ?: throw SigningException(
                operation = "unpacking the IPA",
                reason = "no application bundle was found in Payload"
            )
    }

    /**
     * Rewrites the identifiers so the app matches the provisioning profile.
     *
     * A free account cannot reuse the original bundle identifier, so it is
     * replaced everywhere it appears: the app, its extensions, and any
     * reference to them in Info.plist keys such as WKAppBundleIdentifier.
     */
    fun rewriteIdentifiers(bundle: File, from: String, to: String, displayName: String?) {
        if (from == to && displayName == null) return
        bundle.walkTopDown()
            .filter { it.isFile && it.name == "Info.plist" }
            .forEach { file ->
                val plist = runCatching { PlistReader.parse(file.readBytes()) }.getOrNull()
                    ?: return@forEach
                val dict = plist.asDict ?: return@forEach
                val updated = LinkedHashMap<String, Plist>(dict)
                var changed = false
                for ((key, value) in dict) {
                    val text = value.asString ?: continue
                    if (text == from || text.startsWith("$from.")) {
                        updated[key] = Plist.Str(text.replaceFirst(from, to))
                        changed = true
                    }
                }
                if (displayName != null && file.parentFile == bundle) {
                    updated["CFBundleDisplayName"] = Plist.Str(displayName)
                    changed = true
                }
                if (changed) file.writeBytes(XmlPlist.write(Plist.Dict(updated)))
            }
        Log.i(LogTag.SIGN, "rewrote the bundle identifier for this account")
    }

    /** Packs the signed bundle back into an IPA. */
    fun pack(extracted: File, output: File, onProgress: (Long) -> Unit = {}) {
        var written = 0L
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            extracted.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(extracted).path }
                .forEach { file ->
                    val name = file.relativeTo(extracted).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    written += file.length()
                    onProgress(written)
                }
        }
    }
}
