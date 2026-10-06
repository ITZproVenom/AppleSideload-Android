package dev.applesideload.signing

import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a signature into a Mach-O file.
 *
 * The order matters and is unforgiving. The signature lives at the end of
 * __LINKEDIT, the load command has to point at it before the hashes are
 * computed - because the header is itself hashed - and __LINKEDIT has to be
 * grown to contain it. Getting any of that wrong produces a binary the
 * kernel kills on launch rather than an error at install time.
 */
object MachOSigner {

    /** Space reserved for the signature; the real one is padded to fit. */
    private const val RESERVE_SLACK = 20_000
    private const val SEGMENT_ALIGN = 0x4000L

    interface SignatureBuilder {
        /**
         * How much room the signature will need.
         *
         * The size has to be written into the load command before the file
         * is hashed, so it cannot be discovered by building the signature
         * first. The estimate is deliberately generous and the real
         * signature is padded out to it.
         */
        fun reserve(codeLimit: Int): Int

        /**
         * Builds the signature for a slice whose code ends at [codeLimit].
         * [image] is the slice with the load command already updated.
         */
        fun build(image: ByteArray, codeLimit: Int): ByteArray
    }

    /** Signs every slice of [file] and returns the complete new file. */
    fun sign(file: MachOFile, builder: SignatureBuilder): ByteArray {
        val signed = file.slices.map { signSlice(it, builder) }
        if (!file.isFat) return signed.single()
        return assembleFat(file, signed)
    }

    private fun signSlice(slice: MachOSlice, builder: SignatureBuilder): ByteArray {
        val original = slice.bytes()
        if (slice.codeSignatureCommandOffset < 0) {
            throw SigningException(
                operation = "signing the executable",
                reason = "it has no LC_CODE_SIGNATURE load command and there is no safe way " +
                    "to add one without relinking",
                limitation = "an executable built without a signature placeholder cannot be " +
                    "signed in place",
                alternative = "use an IPA built by Xcode or exported from another signer, " +
                    "which always carries the placeholder"
            )
        }

        // Everything from the old signature onwards goes.
        val codeEnd = slice.codeSignatureOffset.takeIf { it in 1..original.size }
            ?: original.size
        val codeLimit = align(codeEnd, 16)

        // Reserve space, then pad the real signature out to it, so the size
        // written into the header before hashing stays correct.
        val reserved = align(builder.reserve(codeLimit) + RESERVE_SLACK, 16)

        val image = ByteArray(codeLimit + reserved)
        System.arraycopy(original, 0, image, 0, minOf(codeEnd, original.size))

        patchCodeSignature(slice, image, codeLimit, reserved)
        patchLinkedit(slice, image, codeLimit + reserved)

        val signature = builder.build(image, codeLimit)
        if (signature.size > reserved) {
            throw SigningException(
                operation = "signing the executable",
                reason = "the signature needed ${signature.size} bytes but only $reserved " +
                    "were reserved"
            )
        }
        System.arraycopy(signature, 0, image, codeLimit, signature.size)
        Log.i(
            LogTag.SIGN,
            "signed a ${if (slice.is64) "64" else "32"} bit slice: " +
                "$codeLimit bytes of code, ${signature.size} bytes of signature"
        )
        return image
    }

    private fun patchCodeSignature(
        slice: MachOSlice,
        image: ByteArray,
        offset: Int,
        size: Int
    ) {
        val order = if (slice.isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(image).order(order)
        buffer.putInt(slice.codeSignatureCommandOffset + 8, offset)
        buffer.putInt(slice.codeSignatureCommandOffset + 12, size)
    }

    private fun patchLinkedit(slice: MachOSlice, image: ByteArray, fileEnd: Int) {
        val order = if (slice.isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(image).order(order)
        val command = slice.linkeditCommandOffset
        val fileSize = fileEnd - slice.linkeditFileOffset
        if (fileSize <= 0) {
            throw SigningException(
                operation = "signing the executable",
                reason = "__LINKEDIT does not end at the end of the file, so the signature " +
                    "cannot be appended to it"
            )
        }
        val vmSize = alignLong(fileSize, SEGMENT_ALIGN)
        if (slice.is64) {
            // cmd, cmdsize, segname[16], vmaddr, vmsize, fileoff, filesize
            buffer.putLong(command + 8 + 16 + 8, vmSize)
            buffer.putLong(command + 8 + 16 + 24, fileSize)
        } else {
            buffer.putInt(command + 8 + 16 + 4, vmSize.toInt())
            buffer.putInt(command + 8 + 16 + 12, fileSize.toInt())
        }
    }

    /** Rebuilds the fat header around the signed slices. */
    private fun assembleFat(file: MachOFile, signed: List<ByteArray>): ByteArray {
        val header = ByteBuffer.wrap(file.data).order(ByteOrder.BIG_ENDIAN)
        val magic = header.int
        val is64 = magic == MachOFile.FAT_MAGIC_64
        val count = header.int
        val entrySize = if (is64) 32 else 20
        var offset = align(8 + count * entrySize, SEGMENT_ALIGN.toInt())
        val offsets = IntArray(count)
        for (index in 0 until count) {
            offsets[index] = offset
            offset = align(offset + signed[index].size, SEGMENT_ALIGN.toInt())
        }

        val out = ByteArray(offset)
        System.arraycopy(file.data, 0, out, 0, 8 + count * entrySize)
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        for (index in 0 until count) {
            val entry = 8 + index * entrySize
            if (is64) {
                buffer.putLong(entry + 8, offsets[index].toLong())
                buffer.putLong(entry + 16, signed[index].size.toLong())
            } else {
                buffer.putInt(entry + 8, offsets[index])
                buffer.putInt(entry + 12, signed[index].size)
            }
            System.arraycopy(signed[index], 0, out, offsets[index], signed[index].size)
        }
        return out
    }

    private fun align(value: Int, to: Int): Int = (value + to - 1) / to * to

    private fun alignLong(value: Long, to: Long): Long = (value + to - 1) / to * to
}
