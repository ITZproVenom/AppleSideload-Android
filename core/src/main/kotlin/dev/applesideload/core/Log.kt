package dev.applesideload.core

import android.util.Log as AndroidLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where every line of the log comes from.
 *
 * The tag is part of the contract rather than a free string: a diagnostics
 * report is only readable if the same operation always announces itself the
 * same way, and the user needs to be able to tell a USB fault from a signing
 * fault without reading code.
 */
enum class LogTag(val label: String) {
    USB("USB"),
    USBMUX("USBMUX"),
    LOCKDOWN("LOCKDOWN"),
    PAIR("PAIR"),
    APPLE("APPLE"),
    SIGN("SIGN"),
    INSTALL("INSTALL"),
    AFC("AFC"),
    TUNNEL("TUNNEL"),
    APP("APP")
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogLine(
    val at: Long,
    val level: LogLevel,
    val tag: LogTag,
    val message: String,
    /** Increases by one per line, so a reader can ask for what came after it. */
    val sequence: Long = 0
) {
    fun format(): String {
        val stamp = STAMP.format(Date(at))
        return "$stamp [${level.name}] [${tag.label}] $message"
    }

    private companion object {
        val STAMP = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * The app's log.
 *
 * Nothing here is written to a crash reporter or sent anywhere. It is held in
 * memory for the diagnostics screen and scrubbed on the way in, so a line
 * that reaches the screen or an exported report cannot carry a password, a
 * token, a pairing key or a device serial.
 */
object Log {
    private const val LIMIT = 2000

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private var sequence = 0L

    @Volatile
    var debugEnabled: Boolean = false

    fun d(tag: LogTag, message: String) {
        if (!debugEnabled) return
        record(LogLevel.DEBUG, tag, message)
    }

    fun i(tag: LogTag, message: String) = record(LogLevel.INFO, tag, message)
    fun w(tag: LogTag, message: String) = record(LogLevel.WARN, tag, message)
    fun e(tag: LogTag, message: String) = record(LogLevel.ERROR, tag, message)

    fun e(tag: LogTag, message: String, error: Throwable) {
        record(LogLevel.ERROR, tag, "$message: ${describe(error)}")
    }

    fun describe(error: Throwable): String {
        val name = error::class.simpleName ?: "error"
        val detail = error.message?.takeIf { it.isNotBlank() }
        return if (detail == null) name else "$name: $detail"
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun export(header: List<String> = emptyList()): String {
        val body = _lines.value.joinToString("\n") { it.format() }
        val top = if (header.isEmpty()) "" else header.joinToString("\n") + "\n\n"
        return Redaction.apply(top + body)
    }

    private fun record(level: LogLevel, tag: LogTag, message: String) {
        val scrubbed = Redaction.apply(message)
        val line = synchronized(this) {
            val created = LogLine(System.currentTimeMillis(), level, tag, scrubbed, ++sequence)
            val next = _lines.value + created
            _lines.value = if (next.size > LIMIT) next.subList(next.size - LIMIT, next.size).toList() else next
            created
        }
        when (level) {
            LogLevel.DEBUG -> AndroidLog.d(tag.label, line.message)
            LogLevel.INFO -> AndroidLog.i(tag.label, line.message)
            LogLevel.WARN -> AndroidLog.w(tag.label, line.message)
            LogLevel.ERROR -> AndroidLog.e(tag.label, line.message)
        }
    }
}
