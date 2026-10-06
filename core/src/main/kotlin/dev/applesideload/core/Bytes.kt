package dev.applesideload.core

/** Little and big endian reads and writes, without a ByteBuffer at each call. */
object Bytes {
    fun u16le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    fun u32le(source: ByteArray, offset: Int): Long =
        (source[offset].toLong() and 0xFF) or
            ((source[offset + 1].toLong() and 0xFF) shl 8) or
            ((source[offset + 2].toLong() and 0xFF) shl 16) or
            ((source[offset + 3].toLong() and 0xFF) shl 24)

    fun u16be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

    fun u32be(source: ByteArray, offset: Int): Long =
        ((source[offset].toLong() and 0xFF) shl 24) or
            ((source[offset + 1].toLong() and 0xFF) shl 16) or
            ((source[offset + 2].toLong() and 0xFF) shl 8) or
            (source[offset + 3].toLong() and 0xFF)

    fun u64be(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
        }
        return value
    }

    fun putU32le(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun putU16le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    fun putU16be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    fun putU32be(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    fun u32be(value: Long): ByteArray {
        val out = ByteArray(4)
        putU32be(out, 0, value)
        return out
    }

    fun u32le(value: Long): ByteArray {
        val out = ByteArray(4)
        putU32le(out, 0, value)
        return out
    }

    fun hex(source: ByteArray): String {
        val builder = StringBuilder(source.size * 2)
        for (byte in source) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4])
            builder.append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    fun fromHex(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex string has an odd length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((digit(clean[i * 2]) shl 4) or digit(clean[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int {
        val value = Character.digit(c, 16)
        require(value >= 0) { "not a hex digit" }
        return value
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
