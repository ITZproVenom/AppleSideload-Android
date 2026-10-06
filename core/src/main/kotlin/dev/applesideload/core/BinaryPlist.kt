package dev.applesideload.core

import java.io.ByteArrayOutputStream

/**
 * Binary property lists, read and written.
 *
 * Every Info.plist inside a shipped app bundle is in this form, and so is the
 * entitlements blob this app has to rebuild when it signs. The format is the
 * one Apple documents through its implementation: a header, a flat series of
 * objects, an offset table, and a trailer describing how wide the references
 * in that table are.
 */
object BinaryPlist {
    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    fun parse(bytes: ByteArray): Plist {
        if (bytes.size < 40) throw PlistException("a binary plist cannot be this short")
        if (!bytes.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw PlistException("missing the bplist00 magic")
        }
        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xFF
        val refSize = bytes[trailer + 7].toInt() and 0xFF
        val count = Bytes.u64be(bytes, trailer + 8).toInt()
        val rootRef = Bytes.u64be(bytes, trailer + 16).toInt()
        val tableStart = Bytes.u64be(bytes, trailer + 24).toInt()
        if (offsetSize !in 1..8 || refSize !in 1..8) {
            throw PlistException("the trailer declares an impossible reference width")
        }
        if (count < 0 || tableStart < 8 || tableStart + count * offsetSize > bytes.size) {
            throw PlistException("the offset table does not fit in the file")
        }

        val offsets = IntArray(count)
        for (i in 0 until count) {
            offsets[i] = readSized(bytes, tableStart + i * offsetSize, offsetSize).toInt()
        }
        val reader = Reader(bytes, offsets, refSize)
        return reader.obj(rootRef, 0)
    }

    fun write(value: Plist): ByteArray {
        val objects = ArrayList<Plist>()
        collect(value, objects)
        // References are by index into the object list, so the list has to be
        // complete before anything can be encoded.
        val indexOf = HashMap<Key, Int>()
        objects.forEachIndexed { index, item -> indexOf.putIfAbsent(Key(item), index) }
        val refSize = widthFor(objects.size)

        val bodies = ArrayList<ByteArray>(objects.size)
        for (item in objects) {
            bodies.add(encode(item, indexOf, refSize))
        }

        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        val offsets = IntArray(objects.size)
        var cursor = MAGIC.size
        for (i in bodies.indices) {
            offsets[i] = cursor
            out.write(bodies[i])
            cursor += bodies[i].size
        }
        val tableStart = cursor
        val offsetSize = widthFor(cursor + objects.size * 4 + 32)
        for (offset in offsets) {
            out.write(sized(offset.toLong(), offsetSize))
        }
        // Trailer: six unused bytes, the two widths, the count, the root and
        // the table offset.
        out.write(ByteArray(6))
        out.write(offsetSize)
        out.write(refSize)
        out.write(u64(objects.size.toLong()))
        out.write(u64(0))
        out.write(u64(tableStart.toLong()))
        return out.toByteArray()
    }

    // MARK: - Reading

    private class Reader(
        private val bytes: ByteArray,
        private val offsets: IntArray,
        private val refSize: Int
    ) {
        fun obj(ref: Int, depth: Int): Plist {
            if (depth > 64) throw PlistException("the plist nests too deeply")
            if (ref < 0 || ref >= offsets.size) throw PlistException("a reference is out of range")
            var at = offsets[ref]
            if (at < 0 || at >= bytes.size) throw PlistException("an object offset is out of range")
            val marker = bytes[at].toInt() and 0xFF
            val type = marker and 0xF0
            val low = marker and 0x0F
            at += 1
            return when (type) {
                0x00 -> when (low) {
                    0x00 -> Plist.Str("")           // null, which Apple never writes
                    0x08 -> Plist.Bool(false)
                    0x09 -> Plist.Bool(true)
                    0x0F -> Plist.Str("")           // fill
                    else -> throw PlistException("unknown singleton marker $marker")
                }
                0x10 -> {
                    val size = 1 shl low
                    Plist.Num(readSized(bytes, at, size))
                }
                0x20 -> {
                    val size = 1 shl low
                    val raw = readSized(bytes, at, size)
                    if (size == 4) {
                        Plist.Real(java.lang.Float.intBitsToFloat(raw.toInt()).toDouble())
                    } else {
                        Plist.Real(java.lang.Double.longBitsToDouble(raw))
                    }
                }
                0x30 -> Plist.Stamp(java.lang.Double.longBitsToDouble(Bytes.u64be(bytes, at)))
                0x40 -> {
                    val (length, start) = count(low, at)
                    Plist.Data(bytes.copyOfRange(start, start + length))
                }
                0x50 -> {
                    val (length, start) = count(low, at)
                    Plist.Str(String(bytes, start, length, Charsets.US_ASCII))
                }
                0x60 -> {
                    val (length, start) = count(low, at)
                    Plist.Str(String(bytes, start, length * 2, Charsets.UTF_16BE))
                }
                0x80 -> Plist.Num(readSized(bytes, at, low + 1))   // uid
                0xA0, 0xC0 -> {
                    val (length, start) = count(low, at)
                    val items = ArrayList<Plist>(length)
                    for (i in 0 until length) {
                        items.add(obj(readSized(bytes, start + i * refSize, refSize).toInt(), depth + 1))
                    }
                    Plist.Arr(items)
                }
                0xD0 -> {
                    val (length, start) = count(low, at)
                    val keysAt = start
                    val valuesAt = start + length * refSize
                    val map = LinkedHashMap<String, Plist>(length)
                    for (i in 0 until length) {
                        val keyRef = readSized(bytes, keysAt + i * refSize, refSize).toInt()
                        val valueRef = readSized(bytes, valuesAt + i * refSize, refSize).toInt()
                        val key = obj(keyRef, depth + 1).asString
                            ?: throw PlistException("a dictionary key is not a string")
                        map[key] = obj(valueRef, depth + 1)
                    }
                    Plist.Dict(map)
                }
                else -> throw PlistException("unknown object marker $marker")
            }
        }

        /** Length and the offset of the first byte after it. */
        private fun count(low: Int, at: Int): Pair<Int, Int> {
            if (low != 0x0F) return low to at
            val marker = bytes[at].toInt() and 0xFF
            if (marker and 0xF0 != 0x10) throw PlistException("a length is not an integer")
            val size = 1 shl (marker and 0x0F)
            val value = readSized(bytes, at + 1, size).toInt()
            return value to (at + 1 + size)
        }
    }

    private fun readSized(bytes: ByteArray, at: Int, size: Int): Long {
        if (at < 0 || at + size > bytes.size) {
            throw PlistException("a value runs past the end of the plist")
        }
        var value = 0L
        for (i in 0 until size) {
            value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        }
        return value
    }

    // MARK: - Writing

    /** Identity that treats equal values as one object, as Apple's writer does. */
    private class Key(val value: Plist) {
        override fun equals(other: Any?): Boolean = other is Key && other.value == value
        override fun hashCode(): Int = value.hashCode()
    }

    private fun collect(value: Plist, into: ArrayList<Plist>) {
        into.add(value)
        when (value) {
            is Plist.Arr -> value.value.forEach { collect(it, into) }
            is Plist.Dict -> {
                value.value.keys.forEach { into.add(Plist.Str(it)) }
                value.value.values.forEach { collect(it, into) }
            }
            else -> Unit
        }
    }

    private fun encode(value: Plist, indexOf: Map<Key, Int>, refSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        when (value) {
            is Plist.Bool -> out.write(if (value.value) 0x09 else 0x08)
            is Plist.Num -> {
                // Signed values are written as eight bytes, which is what
                // Apple's own writer does rather than risk a negative value
                // read back as a large positive one.
                if (value.value in 0..0xFF) {
                    out.write(0x10)
                    out.write(sized(value.value, 1))
                } else if (value.value in 0..0xFFFF) {
                    out.write(0x11)
                    out.write(sized(value.value, 2))
                } else if (value.value in 0..0xFFFFFFFFL) {
                    out.write(0x12)
                    out.write(sized(value.value, 4))
                } else {
                    out.write(0x13)
                    out.write(u64(value.value))
                }
            }
            is Plist.Real -> {
                out.write(0x23)
                out.write(u64(java.lang.Double.doubleToLongBits(value.value)))
            }
            is Plist.Stamp -> {
                out.write(0x33)
                out.write(u64(java.lang.Double.doubleToLongBits(value.epochSeconds)))
            }
            is Plist.Data -> {
                writeMarker(out, 0x40, value.value.size)
                out.write(value.value)
            }
            is Plist.Str -> {
                val ascii = value.value.all { it.code in 0..127 }
                if (ascii) {
                    writeMarker(out, 0x50, value.value.length)
                    out.write(value.value.toByteArray(Charsets.US_ASCII))
                } else {
                    val encoded = value.value.toByteArray(Charsets.UTF_16BE)
                    writeMarker(out, 0x60, encoded.size / 2)
                    out.write(encoded)
                }
            }
            is Plist.Arr -> {
                writeMarker(out, 0xA0, value.value.size)
                value.value.forEach { item ->
                    out.write(sized(reference(indexOf, item), refSize))
                }
            }
            is Plist.Dict -> {
                writeMarker(out, 0xD0, value.value.size)
                value.value.keys.forEach { key ->
                    out.write(sized(reference(indexOf, Plist.Str(key)), refSize))
                }
                value.value.values.forEach { item ->
                    out.write(sized(reference(indexOf, item), refSize))
                }
            }
        }
        return out.toByteArray()
    }

    private fun reference(indexOf: Map<Key, Int>, value: Plist): Long =
        (indexOf[Key(value)] ?: throw PlistException("an object was not collected")).toLong()

    private fun writeMarker(out: ByteArrayOutputStream, type: Int, length: Int) {
        if (length < 0x0F) {
            out.write(type or length)
            return
        }
        out.write(type or 0x0F)
        when {
            length <= 0xFF -> {
                out.write(0x10)
                out.write(sized(length.toLong(), 1))
            }
            length <= 0xFFFF -> {
                out.write(0x11)
                out.write(sized(length.toLong(), 2))
            }
            else -> {
                out.write(0x12)
                out.write(sized(length.toLong(), 4))
            }
        }
    }

    private fun widthFor(count: Int): Int = when {
        count <= 0xFF -> 1
        count <= 0xFFFF -> 2
        else -> 4
    }

    private fun sized(value: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) {
            out[size - 1 - i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        return out
    }

    private fun u64(value: Long): ByteArray = sized(value, 8)
}
