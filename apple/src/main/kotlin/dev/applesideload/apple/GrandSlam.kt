package dev.applesideload.apple

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.core.Plist
import dev.applesideload.core.XmlPlist
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Apple's Grand Slam identity service, spoken the way SideInstaller's
 * isideload speaks it (auth/grandslam.rs).
 *
 * Every request carries the same base headers, presenting the client as
 * macOS's akd. Endpoints come from Apple's URL bag rather than being
 * hard-coded, a busy service (HTTP 429) is retried twice for sign-in
 * requests, and each request uses a new connection (see [Http]).
 */
class GrandSlam(
    private val http: Http = Http(),
    private val bagUrl: String = URL_BAG,
    private val retryDelaysMs: List<Long> = RETRY_429_DELAYS_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {

    /** An endpoint from the URL bag, which is fetched once per process. */
    fun url(key: String): String {
        val urls = BAGS[bagUrl] ?: fetchBag().also { BAGS[bagUrl] = it }
        return urls[key] ?: throw IOException("Apple's URL bag has no \"$key\" entry")
    }

    private fun fetchBag(): Map<String, String> {
        val response = http.request(bagUrl, headers = baseHeaders())
        val urls = Http.parsePlist(bagUrl, response)["urls"]?.asDict
            ?: throw IOException("Apple's URL bag came without its list of addresses")
        return urls.mapNotNull { (key, value) -> value.asString?.let { key to it } }.toMap()
    }

    /**
     * Posts a property list and returns its "Response" dictionary.
     *
     * Sign-in requests pass [retry429] = true: Apple rate-limits the
     * sign-in steps, and waiting two and then five seconds is usually
     * enough. Provisioning requests are not retried, as in isideload.
     */
    fun plistRequest(
        url: String,
        body: Plist,
        headers: Map<String, String> = emptyMap(),
        retry429: Boolean
    ): Plist {
        val payload = XmlPlist.write(body)
        val delays = (if (retry429) retryDelaysMs else emptyList()).iterator()
        while (true) {
            val response = http.request(url, "POST", baseHeaders() + headers, payload)
            if (response.code == 429 && delays.hasNext()) {
                val delay = delays.next()
                Log.w(LogTag.APPLE, "Apple answered 429 Too Many Requests; trying again in ${delay / 1000.0} s")
                sleep(delay)
                continue
            }
            return Http.parsePlist(url, response)["Response"]?.takeIf { it is Plist.Dict }
                ?: throw IOException("${Http.hostOf(url)} answered without a Response dictionary")
        }
    }

    /** A request with the base headers; [json] selects the JSON variant used for text-message codes. */
    fun send(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        json: Boolean = false
    ): HttpResponse = http.request(url, method, baseHeaders(json) + headers, body)

    companion object {
        const val URL_BAG = "https://gsa.apple.com/grandslam/GsService2/lookup"

        /**
         * The client Apple sees, exactly as SideInstaller sends it.
         *
         * Public anisette servers still describe themselves as Xcode
         * (com.apple.dt.Xcode/3594.4.19), and since September 2026 Apple's
         * Grand Slam edge answers any request presented that way with
         * HTTP 503. macOS's own akd is still accepted.
         */
        const val CLIENT_INFO = "<Mac15,7> <macOS;27.0;26A5378j> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"
        const val USER_AGENT = "akd/1.0 CFNetwork/808.1.4"
        const val XCODE_VERSION = "14.2 (14C18)"
        const val APP_INFO = "com.apple.gs.xcode.auth"

        val RETRY_429_DELAYS_MS = listOf(2_000L, 5_000L)

        /** The headers every Grand Slam request carries. */
        fun baseHeaders(json: Boolean = false): Map<String, String> {
            val type = if (json) "application/json" else "text/x-xml-plist"
            return linkedMapOf(
                "Content-Type" to type,
                "Accept" to type,
                "X-Mme-Client-Info" to CLIENT_INFO,
                "User-Agent" to USER_AGENT,
                "X-Xcode-Version" to XCODE_VERSION,
                "X-Apple-App-Info" to APP_INFO
            )
        }

        private val BAGS = ConcurrentHashMap<String, Map<String, String>>()
    }
}
