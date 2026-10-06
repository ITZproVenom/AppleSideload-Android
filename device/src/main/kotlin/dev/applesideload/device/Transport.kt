package dev.applesideload.device

import java.io.Closeable
import java.io.IOException

/**
 * A byte stream to the device, whichever way it is attached.
 *
 * USB and Wi-Fi look nothing alike at the bottom - one is a pair of bulk
 * endpoints, the other a TCP socket - but every protocol above this point
 * only needs ordered bytes in both directions. Keeping that line here is what
 * lets lockdown, AFC and installation_proxy be written once.
 */
interface Transport : Closeable {
    /** Reads at least one byte, returning the count, or -1 at the end. */
    fun read(into: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    fun write(from: ByteArray, offset: Int, length: Int, timeoutMs: Int)

    val description: String

    /** Reads exactly [length] bytes, or fails. Protocol framing needs this. */
    fun readFully(length: Int, timeoutMs: Int = 15_000): ByteArray {
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = read(out, filled, length - filled, timeoutMs)
            if (read <= 0) {
                throw IOException(
                    "the connection ended after $filled of $length bytes ($description)"
                )
            }
            filled += read
        }
        return out
    }

    fun write(bytes: ByteArray, timeoutMs: Int = 15_000) =
        write(bytes, 0, bytes.size, timeoutMs)
}

/**
 * Exactly what went wrong, in terms the diagnostics screen can repeat.
 *
 * Every failure in this module carries the operation it was attempting and a
 * technical reason, because "could not connect" is not something a user can
 * act on and not something a bug report can be written from.
 */
class DeviceException(
    val operation: String,
    val reason: String,
    val iosVersion: String? = null,
    val limitation: String? = null,
    val alternative: String? = null,
    cause: Throwable? = null
) : Exception(buildMessage(operation, reason, iosVersion, limitation, alternative), cause) {

    companion object {
        private fun buildMessage(
            operation: String,
            reason: String,
            iosVersion: String?,
            limitation: String?,
            alternative: String?
        ): String = buildString {
            append(operation)
            append(" failed: ")
            append(reason)
            if (iosVersion != null) append(" (iOS $iosVersion)")
            if (limitation != null) {
                append(". Limitation: ")
                append(limitation)
            }
            if (alternative != null) {
                append(". Alternative: ")
                append(alternative)
            }
        }
    }
}
