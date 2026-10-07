package dev.applesideload.device.remote

import java.io.ByteArrayOutputStream

/**
 * OPACK, Apple's compact binary object encoding, as remote pairing uses it
 * for the device-info dictionaries exchanged in pair-setup.
 *
 * Values are Kotlin types: Map<String, Any?>, List<Any?>, String, ByteArray,
 * Long (or Int), Boolean, Double and null. Decoding understands back-references
 * to strings, data and numbers seen earlier in the same message, which iOS
 * uses when a value repeats.
 */
object Opack {

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(value, out)
        return out.toByteArray()
    }

    private fun write(value: Any?, out: ByteArrayOutputStream) {
        when (value) {
            null -> out.write(0x04)
            is Boolean -> out.write(if (value) 0x01 else 0x02)
            is Int -> writeInteger(value.toLong(), out)
            is Long -> writeInteger(value, out)
            is Double -> {
                // Big-endian, as the reference implementation writes it.
                out.write(0x36)
                val bits = java.lang.Double.doubleToRawLongBits(value)
                for (i in 7 downTo 0) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
            }
            is String -> writeSized(value.toByteArray(Charsets.UTF_8), 0x40, 0x61, out)
            is ByteArray -> writeSized(value, 0x70, 0x91, out)
            is List<*> -> {
                if (value.size < 15) out.write(0xD0 + value.size) else out.write(0xDF)
                value.forEach { write(it, out) }
                if (value.size >= 15) out.write(0x03)
            }
            is Map<*, *> -> {
                if (value.size < 15) out.write(0xE0 + value.size) else out.write(0xEF)
                for ((key, item) in value) {
                    require(key is String) { "OPACK dictionary keys must be strings" }
                    write(key, out)
                    write(item, out)
                }
                if (value.size >= 15) out.write(0x03)
            }
            else -> throw IllegalArgumentException("OPACK cannot encode ${value::class.java.name}")
        }
    }

    private fun writeInteger(value: Long, out: ByteArrayOutputStream) {
        when {
            value in 0..0x27 -> out.write(0x08 + value.toInt())
            value in 0..0xFF -> {
                out.write(0x30)
                out.write(value.toInt())
            }
            value in 0..0xFFFF_FFFFL -> {
                out.write(0x32)
                writeLittleEndian(value, 4, out)
            }
            else -> {
                out.write(0x33)
                writeLittleEndian(value, 8, out)
            }
        }
    }

    private fun writeSized(bytes: ByteArray, inlineBase: Int, sizedBase: Int, out: ByteArrayOutputStream) {
        val length = bytes.size
        when {
            length <= 0x20 -> out.write(inlineBase + length)
            length <= 0xFF -> {
                out.write(sizedBase)
                out.write(length)
            }
            length <= 0xFFFF -> {
                out.write(sizedBase + 1)
                writeLittleEndian(length.toLong(), 2, out)
            }
            else -> {
                out.write(sizedBase + 2)
                writeLittleEndian(length.toLong(), 4, out)
            }
        }
        out.write(bytes)
    }

    private fun writeLittleEndian(value: Long, size: Int, out: ByteArrayOutputStream) {
        for (i in 0 until size) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    fun decode(bytes: ByteArray): Any? {
        val reader = Reader(bytes)
        val value = reader.value()
        if (reader.offset != bytes.size) {
            throw RemotePairingException("OPACK payload has ${bytes.size - reader.offset} trailing bytes")
        }
        return value
    }

    private class Reader(val bytes: ByteArray) {
        var offset = 0
        private val seen = ArrayList<Any>()

        fun value(): Any? {
            val tag = u8()
            return when (tag) {
                0x01 -> true
                0x02 -> false
                0x04 -> null
                in 0x08..0x2F -> (tag - 8).toLong()
                0x30 -> remember(u8().toLong())
                0x31 -> remember(little(2))
                0x32 -> remember(little(4))
                0x33 -> remember(little(8))
                0x35 -> remember(java.lang.Float.intBitsToFloat(big(4).toInt()).toDouble())
                0x36 -> remember(java.lang.Double.longBitsToDouble(big(8)))
                in 0x40..0x60 -> remember(String(take(tag - 0x40), Charsets.UTF_8))
                in 0x61..0x64 -> remember(String(take(sizedLength(tag - 0x61)), Charsets.UTF_8))
                in 0x70..0x90 -> remember(take(tag - 0x70))
                in 0x91..0x94 -> remember(take(sizedLength(tag - 0x91)))
                in 0xA0..0xC0 -> lookup((tag - 0xA0).toLong())
                0xC1 -> lookup(u8().toLong())
                0xC2 -> lookup(little(2))
                0xC3 -> lookup(little(4))
                0xC4 -> lookup(little(8))
                in 0xD0..0xDE -> List(tag - 0xD0) { value() }
                0xDF -> buildList { while (!terminator()) add(value()) }
                in 0xE0..0xEE -> buildMap(tag - 0xE0)
                0xEF -> buildMap(null)
                0x03 -> throw RemotePairingException("unexpected OPACK terminator at byte ${offset - 1}")
                else -> throw RemotePairingException("unsupported OPACK tag 0x%02x at byte %d".format(tag, offset - 1))
            }
        }

        private fun buildMap(count: Int?): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            if (count != null) {
                repeat(count) { entry(map) }
            } else {
                while (!terminator()) entry(map)
            }
            return map
        }

        private fun entry(map: MutableMap<String, Any?>) {
            val key = value() as? String
                ?: throw RemotePairingException("an OPACK dictionary key is not a string")
            map[key] = value()
        }

        /** Consumes the 0x03 that ends an open-ended collection, if it is next. */
        private fun terminator(): Boolean {
            if (offset >= bytes.size) throw RemotePairingException("OPACK collection is not terminated")
            if ((bytes[offset].toInt() and 0xFF) == 0x03) {
                offset++
                return true
            }
            return false
        }

        private fun remember(value: Any): Any {
            val known = seen.any { same(it, value) }
            if (!known) seen += value
            return value
        }

        private fun same(a: Any, b: Any): Boolean =
            if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b

        private fun lookup(index: Long): Any {
            if (index < 0 || index >= seen.size) {
                throw RemotePairingException("OPACK back-reference $index is out of range (${seen.size} seen)")
            }
            val value = seen[index.toInt()]
            return if (value is ByteArray) value.copyOf() else value
        }

        private fun sizedLength(step: Int): Int {
            val length = when (step) {
                0 -> u8().toLong()
                1 -> little(2)
                2 -> little(4)
                else -> little(8)
            }
            if (length < 0 || length > bytes.size - offset) {
                throw RemotePairingException("OPACK value of $length bytes runs past the end")
            }
            return length.toInt()
        }

        private fun u8(): Int {
            if (offset >= bytes.size) throw RemotePairingException("OPACK payload ended early")
            return bytes[offset++].toInt() and 0xFF
        }

        private fun little(size: Int): Long {
            if (offset + size > bytes.size) throw RemotePairingException("OPACK payload ended early")
            var value = 0L
            for (i in 0 until size) value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
            offset += size
            return value
        }

        private fun big(size: Int): Long {
            if (offset + size > bytes.size) throw RemotePairingException("OPACK payload ended early")
            var value = 0L
            for (i in 0 until size) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            offset += size
            return value
        }

        private fun take(length: Int): ByteArray {
            if (offset + length > bytes.size) throw RemotePairingException("OPACK payload ended early")
            val out = bytes.copyOfRange(offset, offset + length)
            offset += length
            return out
        }
    }
}
