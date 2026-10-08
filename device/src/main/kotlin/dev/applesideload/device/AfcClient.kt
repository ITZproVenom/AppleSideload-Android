package dev.applesideload.device

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AFC, the device's file service.
 *
 * Staging an IPA means writing it into PublicStaging on the device before
 * installation_proxy is asked to install it, and that is what this is for.
 * The protocol is its own thing - a forty byte header with the magic
 * "CFA6LPAA", little-endian throughout, and NUL-separated strings in the
 * payload - and nothing about it resembles the plist services.
 */
class AfcClient(private val transport: Transport) : Closeable {

    private var packetNumber = 0L

    /** Returns the device's free space and filesystem details. */
    fun deviceInfo(): Map<String, String> = pairs(request(OP_GET_DEVINFO, ByteArray(0)))

    fun listDirectory(path: String): List<String> =
        strings(request(OP_READ_DIR, cstring(path))).filter { it != "." && it != ".." }

    fun fileInfo(path: String): Map<String, String> = pairs(request(OP_GET_FILE_INFO, cstring(path)))

    fun exists(path: String): Boolean = runCatching { fileInfo(path) }.isSuccess

    fun isDirectory(path: String): Boolean =
        runCatching { fileInfo(path)["st_ifmt"] == "S_IFDIR" }.getOrDefault(false)

    fun makeDirectory(path: String) {
        request(OP_MAKE_DIR, cstring(path))
    }

    /** Creates every missing component, which mkdir on its own will not do. */
    fun makeDirectories(path: String) {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            if (!exists(current)) makeDirectory(current)
        }
    }

    fun removePath(path: String) {
        request(OP_REMOVE_PATH, cstring(path))
    }

    /** Deletes a whole tree. AFC has no recursive remove of its own. */
    fun removeTree(path: String) {
        if (isDirectory(path)) {
            for (child in listDirectory(path)) removeTree("$path/$child")
        }
        if (exists(path)) removePath(path)
    }

    /**
     * Streams a file onto the device.
     *
     * IPAs run to hundreds of megabytes, so the source is read in chunks and
     * never held whole in memory, and [onProgress] is called with the bytes
     * written so the install screen can show real movement rather than a
     * spinner.
     */
    fun writeFile(
        path: String,
        source: InputStream,
        totalBytes: Long,
        onProgress: (Long) -> Unit = {}
    ) {
        val handle = open(path, MODE_WRITE)
        try {
            val buffer = ByteArray(CHUNK)
            var written = 0L
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                val payload = ByteArray(8 + read)
                ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putLong(handle)
                System.arraycopy(buffer, 0, payload, 8, read)
                request(OP_FILE_WRITE, payload, headerLength = 8)
                written += read
                onProgress(written)
            }
            if (totalBytes > 0 && written != totalBytes) {
                Log.w(
                    LogTag.AFC,
                    "wrote $written bytes to $path but expected $totalBytes"
                )
            }
        } finally {
            close(handle)
        }
    }

    /** Writes a small file in one go. */
    fun writeBytes(path: String, data: ByteArray) =
        writeFile(path, data.inputStream(), data.size.toLong())

    /** Reads a file if it is there, and null if it is not. */
    fun readIfPresent(path: String): ByteArray? =
        if (exists(path)) readFile(path) else null

    /**
     * Renames a path on the device.
     *
     * Settings files are replaced by a rename rather than rewritten in place,
     * which is how cfprefsd saves them: it keys its cache on the inode, so an
     * in-place rewrite can be served stale.
     */
    fun rename(from: String, to: String) {
        request(OP_RENAME_PATH, cstring(from) + cstring(to))
    }

    fun readFile(path: String): ByteArray {
        val handle = open(path, MODE_READ)
        val out = java.io.ByteArrayOutputStream()
        try {
            while (true) {
                val payload = ByteArray(16)
                ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(handle).putLong(CHUNK.toLong())
                val chunk = request(OP_FILE_READ, payload)
                if (chunk.isEmpty()) break
                out.write(chunk)
                if (chunk.size < CHUNK) break
            }
        } finally {
            close(handle)
        }
        return out.toByteArray()
    }

    private fun open(path: String, mode: Long): Long {
        val name = cstring(path)
        val payload = ByteArray(8 + name.size)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putLong(mode)
        System.arraycopy(name, 0, payload, 8, name.size)
        val reply = request(OP_FILE_OPEN, payload, expect = OP_FILE_OPEN_RESULT)
        if (reply.size < 8) {
            throw DeviceException(
                operation = "opening $path on the device",
                reason = "AFC returned no file handle"
            )
        }
        return ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun close(handle: Long) {
        val payload = ByteArray(8)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putLong(handle)
        runCatching { request(OP_FILE_CLOSE, payload) }
    }

    override fun close() = transport.close()

    // MARK: - Framing

    private fun request(
        operation: Long,
        payload: ByteArray,
        headerLength: Int = 0,
        expect: Long? = null
    ): ByteArray {
        val thisLength = HEADER + headerLength
        val entireLength = HEADER + payload.size
        val packet = ByteArray(entireLength)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putLong(entireLength.toLong())
        buffer.putLong(thisLength.toLong())
        buffer.putLong(packetNumber)
        buffer.putLong(operation)
        buffer.put(payload)
        transport.write(packet)

        val reply = readPacket()
        if (reply.operation == OP_STATUS) {
            val status = if (reply.payload.size >= 8) {
                ByteBuffer.wrap(reply.payload).order(ByteOrder.LITTLE_ENDIAN).long
            } else {
                -1L
            }
            if (status != STATUS_SUCCESS) throw afcError(status, operation)
            return ByteArray(0)
        }
        if (expect != null && reply.operation != expect) {
            throw DeviceException(
                operation = "an AFC request",
                reason = "the device answered with operation ${reply.operation}, not $expect"
            )
        }
        return reply.payload
    }

    private class Packet(val operation: Long, val payload: ByteArray)

    private fun readPacket(): Packet {
        val header = transport.readFully(HEADER, 60_000)
        if (!header.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw DeviceException(
                operation = "reading an AFC reply",
                reason = "the packet did not start with the AFC magic",
                limitation = "the connection is out of step with the service"
            )
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(8)
        val entireLength = buffer.long
        buffer.long // this_length, only meaningful when a write is split
        packetNumber = buffer.long + 1
        val operation = buffer.long
        val remaining = (entireLength - HEADER).toInt()
        if (remaining < 0 || remaining > MAX_PAYLOAD) {
            throw DeviceException(
                operation = "reading an AFC reply",
                reason = "the packet claimed a payload of $remaining bytes"
            )
        }
        return Packet(operation, if (remaining == 0) ByteArray(0) else transport.readFully(remaining, 60_000))
    }

    private fun afcError(status: Long, operation: Long): DeviceException = DeviceException(
        operation = "an AFC request (operation $operation)",
        // Status numbers are Apple's AFC error codes (the same ones
        // libimobiledevice names), not errno values.
        reason = when (status) {
            7L -> "the device rejected the request as invalid (AFC error 7)"
            8L -> "the path does not exist on the device"
            9L -> "the path is a directory"
            10L -> "access was denied"
            16L -> "the path already exists"
            17L -> "the path is busy"
            18L -> "the device is out of space"
            33L -> "the folder is not empty"
            else -> "AFC returned error $status"
        },
        limitation = if (status == 18L) "the iPhone does not have room for the app" else null,
        alternative = if (status == 18L) "free space on the iPhone and try again" else null
    )

    private fun cstring(value: String): ByteArray = value.toByteArray() + 0

    private fun strings(payload: ByteArray): List<String> =
        String(payload).split('\u0000').filter { it.isNotEmpty() }

    private fun pairs(payload: ByteArray): Map<String, String> {
        val parts = strings(payload)
        return buildMap {
            var index = 0
            while (index + 1 < parts.size) {
                put(parts[index], parts[index + 1])
                index += 2
            }
        }
    }

    companion object {
        const val SERVICE = "com.apple.afc"
        private val MAGIC = "CFA6LPAA".toByteArray()
        private const val HEADER = 40
        private const val CHUNK = 64 * 1024
        private const val MAX_PAYLOAD = 64 * 1024 * 1024

        private const val STATUS_SUCCESS = 0L
        private const val OP_STATUS = 0x00000001L
        private const val OP_READ_DIR = 0x00000003L
        private const val OP_REMOVE_PATH = 0x00000008L
        private const val OP_MAKE_DIR = 0x00000009L
        private const val OP_GET_FILE_INFO = 0x0000000AL
        private const val OP_GET_DEVINFO = 0x0000000BL
        private const val OP_FILE_OPEN = 0x0000000DL
        private const val OP_FILE_OPEN_RESULT = 0x0000000EL
        private const val OP_FILE_READ = 0x0000000FL
        private const val OP_FILE_WRITE = 0x00000010L
        private const val OP_FILE_CLOSE = 0x00000014L
        private const val OP_RENAME_PATH = 0x00000018L

        /** Read only. */
        private const val MODE_READ = 0x00000001L
        /** Create, truncate, write only - what staging an IPA wants. */
        private const val MODE_WRITE = 0x00000003L
    }
}
