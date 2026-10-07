package dev.applesideload.sideload

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReleaseSourcesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** GitHub's release lists as they were in September 2026, trimmed. */
    private val sideStore = JSONArray(
        """[
          {"tag_name":"nightly","prerelease":true,"draft":false,"assets":[
            {"name":"SideStore.ipa","size":31135308,"updated_at":"2026-09-20T03:17:34Z","browser_download_url":"https://example/nightly/SideStore.ipa"},
            {"name":"build-logs.zip","size":1,"updated_at":"2026-09-20T03:17:34Z","browser_download_url":"https://example/nightly/build-logs.zip"}]},
          {"tag_name":"0.7.0-alpha","prerelease":false,"draft":false,"assets":[
            {"name":"SideStore.ipa","size":27770821,"updated_at":"2026-09-15T11:12:45Z","browser_download_url":"https://example/0.7.0-alpha/SideStore.ipa"}]},
          {"tag_name":"alpha","prerelease":true,"draft":false,"assets":[
            {"name":"SideStore.ipa","size":27770821,"updated_at":"2026-09-11T21:26:05Z","browser_download_url":"https://example/alpha/SideStore.ipa"}]},
          {"tag_name":"0.6.4","prerelease":false,"draft":false,"assets":[
            {"name":"SideStore.ipa","size":27609918,"updated_at":"2026-09-09T17:59:51Z","browser_download_url":"https://example/0.6.4/SideStore.ipa"}]}
        ]"""
    )

    private val liveContainer = JSONArray(
        """[
          {"tag_name":"nightly","prerelease":true,"draft":false,"assets":[
            {"name":"LiveContainer.ipa","size":1,"updated_at":"2026-09-18T23:13:11Z","browser_download_url":"https://example/nightly/LiveContainer.ipa"},
            {"name":"LiveContainer+SideStore.ipa","size":35926202,"updated_at":"2026-09-18T23:13:11Z","browser_download_url":"https://example/nightly/LiveContainer+SideStore.ipa"}]},
          {"tag_name":"3.8.0","prerelease":false,"draft":false,"assets":[
            {"name":"LiveContainer+SideStore.ipa","size":35403538,"updated_at":"2026-07-17T07:44:08Z","browser_download_url":"https://example/3.8.0/LiveContainer+SideStore.ipa"},
            {"name":"LiveContainer.ipa","size":1,"updated_at":"2026-07-17T07:44:08Z","browser_download_url":"https://example/3.8.0/LiveContainer.ipa"}]}
        ]"""
    )

    @Test
    fun theLatestStableReleaseByDefault() {
        val store = ReleaseDownloader.choose(InstallSource.SIDESTORE, sideStore, includeNightly = false)!!
        assertEquals("0.7.0-alpha", store.tag)
        assertEquals("0.7.0-alpha", store.label)
        val container = ReleaseDownloader.choose(InstallSource.SIDESTORE_LIVECONTAINER, liveContainer, includeNightly = false)!!
        assertEquals("https://example/3.8.0/LiveContainer+SideStore.ipa", container.url)
        assertEquals("3.8.0", container.label)
    }

    @Test
    fun theNewestUploadWhenNightlyBuildsCount() {
        val store = ReleaseDownloader.choose(InstallSource.SIDESTORE, sideStore, includeNightly = true)!!
        assertEquals("https://example/nightly/SideStore.ipa", store.url)
        assertEquals("nightly of 2026-09-20", store.label)
        assertEquals(31135308L, store.size)
        val container = ReleaseDownloader.choose(InstallSource.SIDESTORE_LIVECONTAINER, liveContainer, includeNightly = true)!!
        assertEquals("https://example/nightly/LiveContainer+SideStore.ipa", container.url)
        assertEquals("nightly of 2026-09-18", container.label)
    }

    @Test
    fun nothingWhenNoReleaseCarriesTheFile() {
        val none = JSONArray("""[{"tag_name":"1.0","assets":[{"name":"notes.txt"}]}]""")
        assertNull(ReleaseDownloader.choose(InstallSource.SIDESTORE_LIVECONTAINER, none, includeNightly = true))
        assertNull(ReleaseDownloader.choose(InstallSource.SIDESTORE_LIVECONTAINER, none, includeNightly = false))
    }

    @Test
    fun remotePairingSupportIsReadFromSideStoresExecutable() {
        fun ipa(name: String, entries: Map<String, ByteArray>): File = File(folder.root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        val filler = ByteArray(200_000) { (it % 251).toByte() }
        val marker = "PairingFile_RemoteRP".toByteArray()
        val supported = ipa("new.ipa", mapOf("Payload/SideStore.app/SideStore" to filler + marker + filler))
        val bundled = ipa(
            "lc.ipa",
            mapOf(
                // The name in some other file does not count.
                "Payload/LiveContainer.app/LiveContainer" to marker,
                "Payload/LiveContainer.app/Frameworks/SideStoreApp.framework/SideStore" to filler
            )
        )
        val bundledNew = ipa(
            "lc-new.ipa",
            mapOf("Payload/LiveContainer.app/Frameworks/SideStoreApp.framework/SideStore" to marker + filler)
        )
        assertTrue(SideStoreFeatures.supportsRemotePairing(supported))
        assertFalse(SideStoreFeatures.supportsRemotePairing(bundled))
        assertTrue(SideStoreFeatures.supportsRemotePairing(bundledNew))
    }

    @Test
    fun aMarkerSplitAcrossReadsIsFound() {
        val marker = "PairingFile_RemoteRP".toByteArray()
        for (offset in listOf(0, 1, 990, 999, 65_530, 65_536 - marker.size + 3, 65_536, 131_071)) {
            val data = ByteArray(offset) + marker + ByteArray(10)
            // At most 1000 bytes per read, as inflating streams often hand out.
            val input = object : InputStream() {
                val source = ByteArrayInputStream(data)
                override fun read() = source.read()
                override fun read(b: ByteArray, off: Int, len: Int) = source.read(b, off, minOf(len, 1000))
            }
            assertTrue("offset $offset", SideStoreFeatures.streamContains(input, marker))
        }
        assertFalse(SideStoreFeatures.streamContains(ByteArrayInputStream(ByteArray(300_000)), marker))
        assertFalse(SideStoreFeatures.streamContains(ByteArrayInputStream("PairingFile_Remote".toByteArray()), marker))
    }
}
