package dev.applesideload.core

/**
 * Base64, written out rather than taken from a platform class.
 *
 * android.util.Base64 is not available to a plain JVM unit test and
 * java.util.Base64 is, but the plist encoder needs to ignore the whitespace
 * Apple puts inside a <data> element and neither one does that by default.
 * Twenty lines here is cheaper than two code paths.
 */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(input: ByteArray): String {
        val out = StringBuilder((input.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < input.size) {
            val chunk = ((input[index].toInt() and 0xFF) shl 16) or
                ((input[index + 1].toInt() and 0xFF) shl 8) or
                (input[index + 2].toInt() and 0xFF)
            out.append(ALPHABET[(chunk shr 18) and 0x3F])
            out.append(ALPHABET[(chunk shr 12) and 0x3F])
            out.append(ALPHABET[(chunk shr 6) and 0x3F])
            out.append(ALPHABET[chunk and 0x3F])
            index += 3
        }
        when (input.size - index) {
            1 -> {
                val chunk = (input[index].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(chunk shr 18) and 0x3F])
                out.append(ALPHABET[(chunk shr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val chunk = ((input[index].toInt() and 0xFF) shl 16) or
                    ((input[index + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(chunk shr 18) and 0x3F])
                out.append(ALPHABET[(chunk shr 12) and 0x3F])
                out.append(ALPHABET[(chunk shr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    fun decode(input: String): ByteArray {
        var accumulator = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream(input.length * 3 / 4 + 3)
        for (c in input) {
            if (c == '=') break
            val value = ALPHABET.indexOf(c)
            if (value < 0) continue // whitespace and newlines inside <data>
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((accumulator shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
