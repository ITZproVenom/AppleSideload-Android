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
data class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>>,
    /** The reason phrase from the status line, when the server sent one. */
    val message: String = ""
) {
    val text: String get() = String(body)
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /**
     * What the server called this reply, for error messages: an HTML
     * page's title (Apple's edge answers errors with one), else the status
     * line's reason, else the start of the text.
     */
    val reason: String
        get() {
            val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(text)?.groupValues?.get(1)?.trim()
                ?.removePrefix(code.toString())?.trim()
            if (!title.isNullOrBlank()) return title.take(120)
            if (message.isNotBlank()) return message.trim().take(120)
            val start = text.trim().lineSequence().firstOrNull()?.trim().orEmpty()
            return start.take(120).ifBlank { "no reason given" }
        }

    override fun equals(other: Any?): Boolean =
        other is HttpResponse && other.code == code && other.body.contentEquals(body)

    override fun hashCode(): Int = code * 31 + body.contentHashCode()
}

/** A reply whose status was not a success, with what the server said about it. */
class HttpStatusException(val url: String, val code: Int, val reason: String) :
    IOException("${Http.hostOf(url)} answered HTTP $code ($reason)")

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
            // A new connection for every request: Apple's Grand Slam service
            // answers a reused connection with 5xx (SideInstaller found the
            // same and pools nothing).
            if (headers.keys.none { it.equals("Connection", ignoreCase = true) }) {
                setRequestProperty("Connection", "close")
            }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(code, bytes, connection.headerFields.orEmpty(), connection.responseMessage.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun appleSockets() = try {
        AppleTrust.socketFactory
    } catch (error: Exception) {
        throw IOException("Apple's root certificates could not be loaded: ${error.message}", error)
    }

    /** Posts an XML plist and parses the plist of a successful reply. */
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
        return parsePlist(url, response)
    }

    companion object {
        /**
         * The property list in a successful reply.
         *
         * Anything else - an error status, an empty body, an HTML page -
         * becomes an IOException that says what came back, rather than a
         * parser complaint about unexpected tags.
         */
        fun parsePlist(url: String, response: HttpResponse): Plist {
            if (response.code !in 200..299) throw HttpStatusException(url, response.code, response.reason)
            if (response.body.isEmpty()) {
                throw IOException("${hostOf(url)} answered HTTP ${response.code} with an empty body")
            }
            return try {
                PlistReader.parse(response.body)
            } catch (error: Exception) {
                throw IOException(
                    "${hostOf(url)} answered HTTP ${response.code} with something that is not a property list " +
                        "(${response.reason})",
                    error
                )
            }
        }

        fun hostOf(url: String): String = runCatching { URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
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
