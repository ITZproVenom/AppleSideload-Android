package dev.applesideload.signing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MachOSignerTest {

    /** A small arm64 executable linked without a signature: padding after the commands, no LC_CODE_SIGNATURE. */
    private fun unsignedExecutable(): ByteArray {
        val data = ByteArray(0x1100)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0xFEEDFACF.toInt())
        b.putInt(4, 0x0100000C) // arm64
        b.putInt(12, 2) // executable
        b.putInt(16, 2) // ncmds
        val textSize = 72 + 80
        val linkeditSize = 72
        b.putInt(20, textSize + linkeditSize)
        var at = 32
        // __TEXT with one section whose contents start at 0x400
        b.putInt(at, 0x19); b.putInt(at + 4, textSize)
        "__TEXT".toByteArray().copyInto(data, at + 8)
        b.putLong(at + 40, 0L); b.putLong(at + 48, 0x1000L)
        b.putInt(at + 64, 1)
        val section = at + 72
        "__text".toByteArray().copyInto(data, section)
        b.putLong(section + 40, 0x100L) // size
        b.putInt(section + 48, 0x400) // file offset
        at += textSize
        b.putInt(at, 0x19); b.putInt(at + 4, linkeditSize)
        "__LINKEDIT".toByteArray().copyInto(data, at + 8)
        b.putLong(at + 24, 0x4000L) // vmsize
        b.putLong(at + 32, 0x1000L) // fileoff
        b.putLong(at + 40, 0x100L) // filesize
        return data
    }

    private val builder = object : MachOSigner.SignatureBuilder {
        override fun reserve(codeLimit: Int) = 64
        override fun build(image: ByteArray, codeLimit: Int) = ByteArray(32) { 7 }
    }

    @Test
    fun `header space stops at the first section, not at the segments`() {
        val slice = MachOFile(unsignedExecutable()).slices.single()
        assertEquals(0x400 - (32 + 152 + 72), slice.freeHeaderSpace())
    }

    @Test
    fun `an unsigned executable gets a code signature command and a grown LINKEDIT`() {
        val signed = MachOSigner.sign(MachOFile(unsignedExecutable()), builder)
        val b = ByteBuffer.wrap(signed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3, b.getInt(16))
        assertEquals(152 + 72 + 16, b.getInt(20))
        val command = 32 + 152 + 72
        assertEquals(0x1D, b.getInt(command))
        assertEquals(16, b.getInt(command + 4))
        assertEquals(0x1100, b.getInt(command + 8))
        val size = b.getInt(command + 12)
        assertTrue(size >= 64)
        assertEquals(0x1100 + size, signed.size)
        val linkedit = 32 + 152
        assertEquals((0x1100 + size - 0x1000).toLong(), b.getLong(linkedit + 40))
        // the result re-reads as a signed executable
        val reread = MachOFile(signed).slices.single()
        assertEquals(command, reread.codeSignatureCommandOffset)
    }
}
