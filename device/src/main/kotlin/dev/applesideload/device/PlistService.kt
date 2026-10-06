package dev.applesideload.device

import dev.applesideload.core.BinaryPlist
import dev.applesideload.core.Bytes
import dev.applesideload.core.Plist
import dev.applesideload.core.PlistReader
import dev.applesideload.core.XmlPlist
import java.io.Closeable

/**
 * The framing every plist-based device service shares.
 *
 * installation_proxy, mobile_image_mounter, misagent and the rest all speak
 * the same shape as lockdown - four byte big-endian length, then a property
 * list - and differ only in the dictionaries inside. Replies arrive in binary
 * form from some services and XML from others, so the reader sniffs rather
 * than assumes.
 */
class PlistService(
    private val transport: Transport,
    private val name: String,
    /** Binary is smaller and is what installation_proxy expects for big lists. */
    private val sendBinary: Boolean = true
) : Closeable {

    fun send(request: Plist) {
        val body = if (sendBinary) BinaryPlist.write(request) else XmlPlist.write(request)
        transport.write(Bytes.u32be(body.size.toLong()) + body)
    }

    fun receive(timeoutMs: Int = 60_000): Plist {
        val header = transport.readFully(4, timeoutMs)
        val length = Bytes.u32be(header, 0).toInt()
        if (length <= 0 || length > MAX_MESSAGE) {
            throw DeviceException(
                operation = "reading a reply from $name",
                reason = "the length prefix was $length bytes, which cannot be a plist"
            )
        }
        return PlistReader.parse(transport.readFully(length, timeoutMs))
    }

    fun request(request: Plist, timeoutMs: Int = 60_000): Plist {
        send(request)
        return receive(timeoutMs)
    }

    override fun close() = transport.close()

    private companion object {
        const val MAX_MESSAGE = 64 * 1024 * 1024
    }
}
