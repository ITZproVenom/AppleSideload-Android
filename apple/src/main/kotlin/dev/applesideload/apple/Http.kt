package dev.applesideload.apple

import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** One HTTP reply, kept whole so a failure can still be explained. */
data class HttpResponse(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
    val text: String get() = String(body)
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun equals(other: Any?): Boolean =
        other is HttpResponse && other.code == code && other.body.contentEquals(body)

    override fun hashCode(): Int = code * 31 + body.contentHashCode()
}

/**
 * The small amount of HTTP the Apple services need.
 *
 * Apple's identity and developer endpoints speak property lists over HTTPS
 * with a handful of required headers, which is not enough to justify a
 * networking library. Everything here is plain HttpsURLConnection, and TLS
 * is never relaxed: Apple's hosts may additionally chain to Apple's own
 * published roots ([AppleTrust]), and every other check still applies.
 */
class Http(private val timeoutMs: Int = 30_000) {

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            if (this !is HttpsURLConnection && !url.startsWith("http://")) {
                throw IOException("refusing to send credentials over a plain connection")
            }
            if (this is HttpsURLConnection && AppleTrust.appliesTo(this.url.host)) {
                // gsa.apple.com chains to Apple's own root, which Android does
                // not ship; see AppleTrust.
                sslSocketFactory = appleSockets()
            }
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(code, bytes, connection.headerFields.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun appleSockets() = try {
        AppleTrust.socketFactory
    } catch (error: Exception) {
        throw IOException("Apple's root certificates could not be loaded: ${error.message}", error)
    }

    /** Posts an XML plist and parses whatever plist comes back. */
    fun plist(
        url: String,
        request: Plist,
        headers: Map<String, String> = emptyMap(),
        method: String = "POST"
    ): Plist {
        val response = request(
            url = url,
            method = method,
            headers = headers + mapOf("Content-Type" to "text/x-xml-plist"),
            body = XmlPlist.write(request)
        )
        if (response.body.isEmpty()) {
            throw IOException("$url answered ${response.code} with an empty body")
        }
        return PlistReader.parse(response.body)
    }

    fun getJson(url: String): Map<String, String> {
        val response = request(url)
        if (response.code !in 200..299) {
            throw IOException("$url answered ${response.code}")
        }
        val json = JSONObject(response.text)
        return buildMap {
            json.keys().forEach { key -> put(key, json.optString(key)) }
        }
    }
}
