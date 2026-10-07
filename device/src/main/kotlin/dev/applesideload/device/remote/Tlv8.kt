package dev.applesideload.device.remote

import java.io.ByteArrayOutputStream

/**
 * The type-length-value encoding HomeKit-style pair-setup and pair-verify use.
 *
 * Each item is one type byte, one length byte and up to 255 bytes of value.
 * A longer value is split across consecutive items of the same type, and a
 * reader joins consecutive items of the same type back together.
 */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val INFO = 0x11

    /** kTLVError_Authentication: the PIN, or a signature, did not check out. */
    const val ERROR_AUTHENTICATION = 0x02

    data class Item(val type: Int, val value: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Item && other.type == type && other.value.contentEquals(value)

        override fun hashCode(): Int = type * 31 + value.contentHashCode()
    }

    /** One item, split into 255-byte pieces when it is longer. */
    fun chunked(type: Int, value: ByteArray, pieceSize: Int = 255): List<Item> {
        require(pieceSize in 1..255) { "a TLV8 piece holds 1 to 255 bytes" }
        if (value.isEmpty()) return listOf(Item(type, value))
        return (value.indices step pieceSize).map { start ->
            Item(type, value.copyOfRange(start, minOf(value.size, start + pieceSize)))
        }
    }

    /**
     * Writes [items] as they are. A value over 255 bytes is split here, so a
     * caller that wants a particular split passes the pieces itself.
     */
    fun encode(items: List<Item>): ByteArray {
        val out = ByteArrayOutputStream()
        for (item in items) {
            require(item.type in 0..255) { "TLV8 type ${item.type} does not fit in a byte" }
            for (piece in chunked(item.type, item.value)) {
                out.write(piece.type)
                out.write(piece.value.size)
                out.write(piece.value)
            }
        }
        return out.toByteArray()
    }

    fun encode(vararg items: Item): ByteArray = encode(items.toList())

    /** Reads every item, as sent: consecutive pieces stay separate here. */
    fun decode(bytes: ByteArray): List<Item> {
        val items = ArrayList<Item>()
        var offset = 0
        while (offset < bytes.size) {
            if (offset + 2 > bytes.size) {
                throw RemotePairingException("a TLV8 item header was cut short at byte $offset")
            }
            val type = bytes[offset].toInt() and 0xFF
            val length = bytes[offset + 1].toInt() and 0xFF
            offset += 2
            if (offset + length > bytes.size) {
                throw RemotePairingException(
                    "a TLV8 item of type $type claims $length bytes but only ${bytes.size - offset} remain"
                )
            }
            items += Item(type, bytes.copyOfRange(offset, offset + length))
            offset += length
        }
        return items
    }

    /**
     * Every byte of [type], joined across pieces. Items of the same type that
     * are not next to each other are joined as well: no message in this
     * protocol repeats a type for a second, separate value.
     */
    fun collect(items: List<Item>, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        items.filter { it.type == type }.forEach { out.write(it.value) }
        return out.toByteArray()
    }

    fun has(items: List<Item>, type: Int): Boolean = items.any { it.type == type }

    fun state(items: List<Item>): Int? =
        items.firstOrNull { it.type == STATE }?.value?.firstOrNull()?.toInt()?.and(0xFF)

    fun error(items: List<Item>): Int? =
        items.firstOrNull { it.type == ERROR }?.value?.firstOrNull()?.toInt()?.and(0xFF)
}

/** The protocol itself went wrong: a malformed message, a failed check. */
class RemotePairingException(message: String, cause: Throwable? = null) : Exception(message, cause)
