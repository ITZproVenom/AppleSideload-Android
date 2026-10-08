package dev.applesideload.app

import android.os.Build
import dev.applesideload.apple.Http
import dev.applesideload.core.Log
import dev.applesideload.core.LogLine
import dev.applesideload.core.PersonalData
import dev.applesideload.core.Redaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Anonymous log collection, on unless it is turned off in Settings.
 *
 * New Activity log lines go to the developer's Supabase project once the log
 * has been quiet for a moment, so a problem can be looked at without asking
 * anyone for a copy. What leaves the phone: the lines, scrubbed of
 * credentials as they were written ([Redaction]) and of the names of
 * iPhones, teams and Apple IDs on the way out ([PersonalData]); the app
 * build; the Android version and device model; and a random ID made for
 * this install alone. The key below can only add rows: it cannot read,
 * change or delete anything, including what this phone sent.
 *
 * Lines written while collection is off are never sent. Nothing here writes
 * to the log, so sending cannot cause more sending; the outcome is shown in
 * Settings instead.
 */
class LogUploader(
    private val settings: Settings,
    private val appBuild: Long,
    private val http: Http = Http(timeoutMs = 20_000)
) {
    private val session = UUID.randomUUID().toString()
    private var part = 0

    @Volatile
    private var sentThrough = 0L

    private val _status = MutableStateFlow("Nothing sent yet.")
    val status: StateFlow<String> = _status.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var failures = 0
            while (true) {
                Log.lines.first { (it.lastOrNull()?.sequence ?: 0L) > sentThrough }
                settle()
                val pending = Log.lines.value.filter { it.sequence > sentThrough }
                if (pending.isEmpty()) continue
                if (!settings.shareLogs) {
                    sentThrough = pending.last().sequence
                    continue
                }
                if (send(pending)) {
                    failures = 0
                } else {
                    failures++
                    delay(RETRY_MS[minOf(failures, RETRY_MS.size) - 1])
                }
            }
        }
    }

    /** Waits until no line has arrived for [QUIET_MS], or [MAX_WAIT_MS] in all. */
    private suspend fun settle() {
        val started = System.currentTimeMillis()
        var seen = Log.lines.value.lastOrNull()?.sequence
        while (System.currentTimeMillis() - started < MAX_WAIT_MS) {
            delay(QUIET_MS)
            val latest = Log.lines.value.lastOrNull()?.sequence
            if (latest == seen) return
            seen = latest
        }
    }

    private fun send(lines: List<LogLine>): Boolean {
        for (chunk in chunked(lines)) {
            val body = PersonalData.scrub(Redaction.apply(chunk.joinToString("\n") { it.format() }))
            val row = JSONObject()
                .put("install_id", settings.logsId)
                .put("session_id", session)
                .put("part", part)
                .put("app_build", appBuild)
                .put("android_release", Build.VERSION.RELEASE.orEmpty().take(20))
                .put("android_sdk", Build.VERSION.SDK_INT)
                .put("device", "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().take(100))
                .put("first_line", chunk.first().sequence)
                .put("last_line", chunk.last().sequence)
                .put("body", body.take(MAX_BODY))
            val problem = try {
                val response = http.request(
                    ENDPOINT,
                    "POST",
                    mapOf("apikey" to KEY, "Content-Type" to "application/json", "Prefer" to "return=minimal"),
                    row.toString().toByteArray(Charsets.UTF_8)
                )
                if (response.code in 200..299) null else "HTTP ${response.code}"
            } catch (error: Exception) {
                error.message ?: error::class.java.simpleName
            }
            if (problem != null) {
                _status.value = "Could not send the latest lines ($problem); trying again later."
                return false
            }
            part++
            sentThrough = chunk.last().sequence
        }
        _status.value = "Last sent at ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}."
        return true
    }

    private fun chunked(lines: List<LogLine>): List<List<LogLine>> {
        val chunks = mutableListOf<MutableList<LogLine>>()
        var size = 0
        for (line in lines) {
            val length = line.message.length + LINE_OVERHEAD
            if (chunks.isEmpty() || size + length > CHUNK_CHARS) {
                chunks += mutableListOf<LogLine>()
                size = 0
            }
            chunks.last() += line
            size += length
        }
        return chunks
    }

    private companion object {
        const val ENDPOINT = "https://vmmkjagqihyvlirlxyuj.supabase.co/rest/v1/app_logs"

        /** Supabase's publishable key: public by design; the table's rules only let it add rows. */
        const val KEY = "sb_publishable_Kymorg7_duIk4PVjzHfnuQ_gOt3QI17"

        const val QUIET_MS = 15_000L
        const val MAX_WAIT_MS = 60_000L
        const val CHUNK_CHARS = 200_000
        const val LINE_OVERHEAD = 40
        const val MAX_BODY = 250_000
        val RETRY_MS = longArrayOf(30_000, 120_000, 600_000, 1_800_000)
    }
}
