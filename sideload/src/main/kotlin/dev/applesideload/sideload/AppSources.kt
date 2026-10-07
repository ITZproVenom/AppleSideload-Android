package dev.applesideload.sideload

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * What can be installed.
 *
 * SideStore + LiveContainer is the build LiveContainer publishes with
 * SideStore built in as a framework - the same one SideInstaller installs -
 * so one App ID slot holds both, and SideStore inside it refreshes them on
 * the iPhone. SideStore alone is SideStore's own release.
 */
enum class InstallSource(
    val title: String,
    val repo: String?,
    val assetName: String?
) {
    SIDESTORE_LIVECONTAINER("SideStore + LiveContainer", "LiveContainer/LiveContainer", "LiveContainer+SideStore.ipa"),
    SIDESTORE("SideStore", "SideStore/SideStore", "SideStore.ipa"),
    CUSTOM("Custom IPA", null, null);

    companion object {
        fun from(name: String?): InstallSource = entries.firstOrNull { it.name == name } ?: CUSTOM
    }
}

/** A published build, before it is downloaded. */
data class ReleaseBuild(
    val source: InstallSource,
    val tag: String,
    val url: String,
    val size: Long,
    /** When the file was uploaded. A nightly release keeps its tag and replaces its file. */
    val updatedAt: String = "",
    val prerelease: Boolean = false
) {
    /** "3.8.0", or "nightly of 2026-09-20" for a build published under a moving tag. */
    val label: String
        get() = if ((prerelease || tag == "nightly") && updatedAt.length >= 10) "$tag of ${updatedAt.take(10)}" else tag
}

/**
 * Downloads the official IPAs from their GitHub releases.
 *
 * Like SideInstaller, the latest stable release is used, and if it does not
 * carry the asset under its usual name the recent releases are scanned for
 * the newest that does. On iOS 27 the newest upload of all is used instead,
 * nightly builds included, because only SideStore's newest builds can refresh
 * there (see [SideStoreFeatures]). A file already downloaded for the same
 * upload is reused, so trying again does not download it again.
 */
class ReleaseDownloader(private val directory: File) {

    init {
        directory.mkdirs()
    }

    fun latest(source: InstallSource, includeNightly: Boolean = false): ReleaseBuild {
        val repo = source.repo ?: throw IOException("${source.title} is not downloadable")
        val releases = JSONArray(get("https://api.github.com/repos/$repo/releases?per_page=20"))
        return choose(source, releases, includeNightly)
            ?: throw IOException("no release of $repo carries ${source.assetName}")
    }

    /** Downloads [build], or returns the copy already on the phone. */
    fun download(build: ReleaseBuild, onProgress: (Int) -> Unit): File {
        val safeTag = build.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val stamp = build.updatedAt.filter { it.isDigit() }
        val target = File(directory, "${build.source.name}-$safeTag${if (stamp.isEmpty()) "" else "-$stamp"}.ipa")
        if (target.exists() && (build.size <= 0 || target.length() == build.size)) {
            Log.i(LogTag.APP, "reusing the downloaded ${build.source.title} ${build.label}")
            return target
        }
        val partial = File(directory, target.name + ".part")
        Log.i(LogTag.APP, "downloading ${build.source.title} ${build.label}")
        val connection = open(build.url)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: build.size
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    if (total > 0 && written != total) {
                        throw IOException("the download stopped at $written of $total bytes")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        // Older builds of the same source are not needed any more.
        directory.listFiles()?.filter {
            it.name.startsWith("${build.source.name}-") && it.name.endsWith(".ipa") && it != target
        }?.forEach { it.delete() }
        if (!partial.renameTo(target)) throw IOException("could not store the download")
        return target
    }

    private fun get(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "AppleSideload-Android")
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException(
                if (code == 403 || code == 429) {
                    "GitHub is limiting requests from this network (HTTP $code); try again in a few minutes"
                } else {
                    "GitHub answered HTTP $code for $url"
                }
            )
        }
        return connection
    }

    companion object {
        /**
         * Picks the build of [source] from GitHub's release list, newest
         * first. [includeNightly] takes the most recently uploaded file of
         * all; otherwise the newest stable release carrying it, or failing
         * that any release that does.
         */
        fun choose(source: InstallSource, releases: JSONArray, includeNightly: Boolean): ReleaseBuild? {
            val published = (0 until releases.length()).map { releases.getJSONObject(it) }
                .filterNot { it.optBoolean("draft") }
            if (includeNightly) return published.mapNotNull { pick(source, it) }.maxByOrNull { it.updatedAt }
            return published
                .filterNot { it.optBoolean("prerelease") || it.optString("tag_name") == "nightly" }
                .firstNotNullOfOrNull { pick(source, it) }
                ?: published.firstNotNullOfOrNull { pick(source, it) }
        }

        private fun pick(source: InstallSource, release: JSONObject): ReleaseBuild? {
            val assets = release.optJSONArray("assets") ?: return null
            var fallback: JSONObject? = null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                if (name == source.assetName) return build(source, release, asset)
                if (source == InstallSource.SIDESTORE_LIVECONTAINER &&
                    name.endsWith(".ipa") && name.contains("SideStore", ignoreCase = true)
                ) {
                    fallback = asset
                }
                if (source == InstallSource.SIDESTORE && name.endsWith(".ipa") && fallback == null) {
                    fallback = asset
                }
            }
            return fallback?.let { build(source, release, it) }
        }

        private fun build(source: InstallSource, release: JSONObject, asset: JSONObject) = ReleaseBuild(
            source = source,
            tag = release.optString("tag_name"),
            url = asset.getString("browser_download_url"),
            size = asset.optLong("size"),
            updatedAt = asset.optString("updated_at"),
            prerelease = release.optBoolean("prerelease")
        )
    }
}

/**
 * What a downloaded SideStore build can do, read from the build itself.
 *
 * On iOS 27 SideStore refreshes only with a Remote Pairing file,
 * PairingFile_RemoteRP.plist. Builds that read it carry that name in
 * SideStore's executable; older ones, including every LiveContainer build up
 * to September 2026, do not.
 */
object SideStoreFeatures {
    private val REMOTE_PAIRING_MARKER = "PairingFile_RemoteRP".toByteArray(Charsets.US_ASCII)
    private const val CHUNK = 64 * 1024

    /** Whether the SideStore in [ipa], on its own or inside LiveContainer, reads a Remote Pairing file. */
    fun supportsRemotePairing(ipa: File): Boolean = ZipFile(ipa).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("Payload/") && it.name.endsWith("/SideStore") }
            .any { entry -> zip.getInputStream(entry).use { streamContains(it, REMOTE_PAIRING_MARKER) } }
    }

    /** Whether [marker] occurs in [input], read in chunks so a large executable is never held whole. */
    fun streamContains(input: InputStream, marker: ByteArray): Boolean {
        require(marker.isNotEmpty())
        val buffer = ByteArray(CHUNK + marker.size)
        var kept = 0
        while (true) {
            val read = input.read(buffer, kept, buffer.size - kept)
            if (read < 0) return false
            val end = kept + read
            if (occurs(buffer, end, marker)) return true
            kept = minOf(marker.size - 1, end)
            System.arraycopy(buffer, end - kept, buffer, 0, kept)
        }
    }

    private fun occurs(buffer: ByteArray, end: Int, marker: ByteArray): Boolean {
        var start = 0
        while (start <= end - marker.size) {
            var matched = 0
            while (matched < marker.size && buffer[start + matched] == marker[matched]) matched++
            if (matched == marker.size) return true
            start++
        }
        return false
    }
}
