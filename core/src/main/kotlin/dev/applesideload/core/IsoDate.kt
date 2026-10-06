package dev.applesideload.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The one date format a property list uses, in UTC. */
object IsoDate {
    private fun formatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun format(date: Date): String = formatter().format(date)

    fun now(): String = format(Date())

    /** Seconds since 1 January 2001, which is how a plist stores a date. */
    fun parseToAppleSeconds(text: String): Double {
        if (text.isBlank()) return 0.0
        val parsed = runCatching { formatter().parse(text) }.getOrNull() ?: return 0.0
        return parsed.time / 1000.0 - Plist.APPLE_EPOCH
    }
}
