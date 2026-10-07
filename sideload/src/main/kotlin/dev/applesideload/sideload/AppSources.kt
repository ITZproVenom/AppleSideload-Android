package dev.applesideload.sideload

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
data class ReleaseBuild(val source: InstallSource, val tag: String, val url: String, val size: Long)

/**
 * Downloads the official IPAs from their GitHub releases.
 *
 * Like SideInstaller, the latest stable release is used, and if it does not
 * carry the asset under its usual name the recent releases are scanned for
 * the newest that does. A file already downloaded for the same release and
 * size is reused, so trying again does not download it again.
 */
class ReleaseDownloader(private val directory: File) {

    init {
        directory.mkdirs()
    }

    fun latest(source: InstallSource): ReleaseBuild {
        val repo = source.repo ?: throw IOException("${source.title} is not downloadable")
        val releases = JSONArray(get("https://api.github.com/repos/$repo/releases?per_page=20"))
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            if (release.optString("tag_name") == "nightly") continue
            pick(source, release)?.let { return it }
        }
        // Nothing stable carries it; fall back to any release that does.
        for (index in 0 until releases.length()) {
            pick(source, releases.getJSONObject(index))?.let { return it }
        }
        throw IOException("no release of $repo carries ${source.assetName}")
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
        size = asset.optLong("size")
    )

    /** Downloads [build], or returns the copy already on the phone. */
    fun download(build: ReleaseBuild, onProgress: (Int) -> Unit): File {
        val safeTag = build.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, "${build.source.name}-$safeTag.ipa")
        if (target.exists() && (build.size <= 0 || target.length() == build.size)) {
            Log.i(LogTag.APP, "reusing the downloaded ${build.source.title} ${build.tag}")
            return target
        }
        val partial = File(directory, target.name + ".part")
        Log.i(LogTag.APP, "downloading ${build.source.title} ${build.tag}")
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
}
