package dev.applesideload.signing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Anything that goes wrong while reading or rewriting an executable. */
class MachOException(message: String) : Exception(message)

/**
 * One architecture slice of a Mach-O file.
 *
 * Signing has to work on the bytes of the executable itself: the hashes in
 * the code directory cover every page of the file up to the signature, so
 * the signature's position and the size of __LINKEDIT have to be adjusted
 * before a single hash is computed.
 */
class MachOSlice(
    /** The whole file this slice came from. */
    val container: ByteArray,
    /** Where the slice starts inside [container]; zero unless the file is fat. */
    val offset: Int,
    val size: Int
) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(container, offset, size).slice()

    val is64: Boolean
    val isLittleEndian: Boolean
    val cpuType: Int
    val cpuSubtype: Int
    val fileType: Int
    val commandCount: Int
    val commandsSize: Int
    val headerSize: Int

    /** Where LC_CODE_SIGNATURE sits in the slice, or -1 when there is none. */
    var codeSignatureCommandOffset: Int = -1
        private set
    var codeSignatureOffset: Int = 0
        private set
    var codeSignatureSize: Int = 0
        private set

    /** The __LINKEDIT segment command, which has to grow with the signature. */
    var linkeditCommandOffset: Int = -1
        private set
    var linkeditFileOffset: Long = 0
        private set
    var linkeditFileSize: Long = 0
        private set
    var linkeditVmSize: Long = 0
        private set

    /** The bundle identifier the binary was built with, if it has one. */
    var existingIdentifier: String? = null
        private set

    init {
        val magic = ByteBuffer.wrap(container, offset, 4).order(ByteOrder.BIG_ENDIAN).int
        when (magic) {
            MAGIC_64 -> { is64 = true; isLittleEndian = false }
            MAGIC_32 -> { is64 = false; isLittleEndian = false }
            CIGAM_64 -> { is64 = true; isLittleEndian = true }
            CIGAM_32 -> { is64 = false; isLittleEndian = true }
            else -> throw MachOException(
                "this file does not start with a Mach-O magic (found 0x%08x)".format(magic)
            )
        }
        buffer.order(if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        cpuType = buffer.int
        cpuSubtype = buffer.int
        fileType = buffer.int
        commandCount = buffer.int
        commandsSize = buffer.int
        buffer.int // flags
        if (is64) buffer.int // reserved
        headerSize = if (is64) 32 else 28
        readCommands()
    }

    private fun readCommands() {
        var position = headerSize
        repeat(commandCount) {
            if (position + 8 > size) throw MachOException("the load commands run past the file")
            buffer.position(position)
            val command = buffer.int
            val commandSize = buffer.int
            if (commandSize < 8) throw MachOException("a load command claims a size of $commandSize")
            when (command) {
                LC_CODE_SIGNATURE -> {
                    codeSignatureCommandOffset = position
                    codeSignatureOffset = buffer.int
                    codeSignatureSize = buffer.int
                }

                LC_SEGMENT_64, LC_SEGMENT_32 -> {
                    val name = ByteArray(16)
                    buffer.get(name)
                    if (String(name).trimEnd('\u0000') == "__LINKEDIT") {
                        linkeditCommandOffset = position
                        if (command == LC_SEGMENT_64) {
                            buffer.long // vmaddr
                            linkeditVmSize = buffer.long
                            linkeditFileOffset = buffer.long
                            linkeditFileSize = buffer.long
                        } else {
                            buffer.int
                            linkeditVmSize = buffer.int.toLong() and 0xFFFFFFFFL
                            linkeditFileOffset = buffer.int.toLong() and 0xFFFFFFFFL
                            linkeditFileSize = buffer.int.toLong() and 0xFFFFFFFFL
                        }
                    }
                }
            }
            position += commandSize
        }
        if (linkeditCommandOffset < 0) {
            throw MachOException("the executable has no __LINKEDIT segment")
        }
    }

    /** The bytes of this slice as they stand. */
    fun bytes(): ByteArray = container.copyOfRange(offset, offset + size)

    /** Room left in the header for one more load command. */
    fun freeHeaderSpace(): Int {
        val used = headerSize + commandsSize
        // The first section's file offset is the earliest byte that is not
        // header padding, and overwriting it would corrupt the binary.
        var firstSection = Int.MAX_VALUE
        var position = headerSize
        repeat(commandCount) {
            buffer.position(position)
            val command = buffer.int
            val commandSize = buffer.int
            if (command == LC_SEGMENT_64 || command == LC_SEGMENT_32) {
                val name = ByteArray(16)
                buffer.get(name)
                val segment = String(name).trimEnd('\u0000')
                val fileOffset = if (command == LC_SEGMENT_64) {
                    buffer.long // vmaddr
                    buffer.long // vmsize
                    buffer.long
                } else {
                    buffer.int
                    buffer.int
                    buffer.int.toLong() and 0xFFFFFFFFL
                }
                if (segment != "__PAGEZERO" && fileOffset > 0) {
                    firstSection = minOf(firstSection, fileOffset.toInt())
                }
            }
            position += commandSize
        }
        val ceiling = if (firstSection == Int.MAX_VALUE) size else firstSection
        return (ceiling - used).coerceAtLeast(0)
    }

    private companion object {
        const val MAGIC_64 = 0xFEEDFACF.toInt()
        const val MAGIC_32 = 0xFEEDFACE.toInt()
        const val CIGAM_64 = 0xCFFAEDFE.toInt()
        const val CIGAM_32 = 0xCEFAEDFE.toInt()
        const val LC_SEGMENT_32 = 0x01
        const val LC_SEGMENT_64 = 0x19
        const val LC_CODE_SIGNATURE = 0x1D
    }
}

/**
 * A Mach-O file, fat or thin.
 *
 * Every slice has to be signed separately, and a fat file's slice offsets
 * have to stay aligned afterwards, so the rewrite happens here rather than
 * in the signer.
 */
class MachOFile(val data: ByteArray) {

    val slices: List<MachOSlice>
    val isFat: Boolean

    init {
        if (data.size < 8) throw MachOException("the file is too short to be an executable")
        val magic = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        isFat = magic == FAT_MAGIC || magic == FAT_MAGIC_64
        slices = if (isFat) readFat(magic == FAT_MAGIC_64) else listOf(MachOSlice(data, 0, data.size))
    }

    private fun readFat(is64: Boolean): List<MachOSlice> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val count = buffer.int
        if (count <= 0 || count > 32) throw MachOException("a fat header claims $count slices")
        return (0 until count).map {
            buffer.int // cputype
            buffer.int // cpusubtype
            val offset: Long
            val size: Long
            if (is64) {
                offset = buffer.long
                size = buffer.long
                buffer.int // align
                buffer.int // reserved
            } else {
                offset = buffer.int.toLong() and 0xFFFFFFFFL
                size = buffer.int.toLong() and 0xFFFFFFFFL
                buffer.int // align
            }
            if (offset + size > data.size) {
                throw MachOException("a fat slice runs past the end of the file")
            }
            MachOSlice(data, offset.toInt(), size.toInt())
        }
    }

    companion object {
        const val FAT_MAGIC = 0xCAFEBABE.toInt()
        const val FAT_MAGIC_64 = 0xCAFEBABF.toInt()

        /** True when these bytes begin like an executable this signer handles. */
        fun looksLikeMachO(head: ByteArray): Boolean {
            if (head.size < 4) return false
            val magic = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            return magic == FAT_MAGIC || magic == FAT_MAGIC_64 ||
                magic == 0xFEEDFACF.toInt() || magic == 0xFEEDFACE.toInt() ||
                magic == 0xCFFAEDFE.toInt() || magic == 0xCEFAEDFE.toInt()
        }
    }
}
