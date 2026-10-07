package dev.applesideload.device.remote

import dev.applesideload.core.Base64
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.Transport
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException

/**
 * The remote-pairing control channel: "RPPairing", a two-byte big-endian
 * length, then a JSON envelope.
 *
 * Every envelope carries who sent it and a sequence number, and either a plain
 * message or a ChaCha20-Poly1305 encrypted one, base64 encoded. The side that
 * started the conversation is "host"; the side that was dialled is "device",
 * which is what this phone is when an iPhone pairs to it.
 */
class RpChannel(
    private val transport: Transport,
    private val originatedBy: String = HOST
) : Closeable {

    private var sequence = 0L

    val description: String get() = transport.description

    fun sendPlain(message: JSONObject) {
        send(JSONObject().put("plain", JSONObject().put("_0", message)))
    }

    fun sendEncrypted(ciphertext: ByteArray) {
        send(JSONObject().put("streamEncrypted", JSONObject().put("_0", Base64.encode(ciphertext))))
    }

    private fun send(message: JSONObject) {
        val envelope = JSONObject()
            .put("message", message)
            .put("originatedBy", originatedBy)
            .put("sequenceNumber", sequence)
        val body = envelope.toString().toByteArray(Charsets.UTF_8)
        if (body.size > 0xFFFF) {
            throw RemotePairingException("a control message of ${body.size} bytes does not fit the two-byte length")
        }
        val frame = ByteArray(MAGIC.size + 2 + body.size)
        MAGIC.copyInto(frame)
        frame[MAGIC.size] = (body.size ushr 8).toByte()
        frame[MAGIC.size + 1] = body.size.toByte()
        body.copyInto(frame, MAGIC.size + 2)
        transport.write(frame, TIMEOUT_MS)
        sequence++
    }

    /** One envelope, whole. */
    fun receive(timeoutMs: Int = TIMEOUT_MS): Envelope {
        val magic = transport.readFully(MAGIC.size, timeoutMs)
        if (!magic.contentEquals(MAGIC)) {
            throw RemotePairingException("the peer is not speaking RPPairing (it sent ${String(magic, Charsets.ISO_8859_1).trim()})")
        }
        val header = transport.readFully(2, timeoutMs)
        val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val body = transport.readFully(length, timeoutMs)
        val json = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (error: JSONException) {
            throw RemotePairingException("a control message is not JSON: ${error.message}", error)
        }
        return Envelope(json)
    }

    /** The next plain message, skipping nothing: an encrypted one here is a protocol error. */
    fun receivePlain(timeoutMs: Int = TIMEOUT_MS): JSONObject {
        val envelope = receive(timeoutMs)
        return envelope.plain
            ?: throw RemotePairingException("expected a plain control message, got ${envelope.kind}")
    }

    override fun close() = transport.close()

    class Envelope(val json: JSONObject) {
        private val message: JSONObject? = json.optJSONObject("message")
        val plain: JSONObject? get() = message?.optJSONObject("plain")?.optJSONObject("_0")
        val encrypted: ByteArray?
            get() = message?.optJSONObject("streamEncrypted")?.optString("_0", "")
                ?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it) }
        val kind: String get() = message?.keys()?.asSequence()?.joinToString() ?: "nothing"
    }

    companion object {
        const val HOST = "host"
        const val DEVICE = "device"
        val MAGIC = "RPPairing".toByteArray(Charsets.US_ASCII)
        const val TIMEOUT_MS = 30_000

        /** Walks a chain of keys, returning null at the first one missing. */
        fun path(root: JSONObject?, vararg keys: String): JSONObject? {
            var node = root
            for (key in keys) node = node?.optJSONObject(key) ?: return null
            return node
        }

        /** The text iOS put in a pairingRejectedWithError, if there is any. */
        fun rejection(event: JSONObject): String? {
            val error = event.optJSONObject("pairingRejectedWithError") ?: return null
            val description = path(error, "wrappedError", "userInfo")?.optString("NSLocalizedDescription")
            return description?.takeIf { it.isNotBlank() } ?: error.toString()
        }

        internal fun logIgnored(what: JSONObject) {
            Log.d(LogTag.PAIR, "ignoring a control message: ${what.toString().take(300)}")
        }

        internal fun ioFailure(operation: String, error: IOException): RemotePairingException =
            RemotePairingException("$operation: ${Log.describe(error)}", error)
    }
}
