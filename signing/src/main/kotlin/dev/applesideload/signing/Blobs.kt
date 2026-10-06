package dev.applesideload.signing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The code signing blob formats, as the kernel reads them.
 *
 * Everything in a signature is a blob: a four byte magic, a four byte total
 * length, and a body. They nest inside a SuperBlob with an index. All of it
 * is big-endian regardless of the architecture.
 */
object Blobs {
    const val MAGIC_REQUIREMENT = 0xFADE0C00.toInt()
    const val MAGIC_REQUIREMENTS = 0xFADE0C01.toInt()
    const val MAGIC_CODEDIRECTORY = 0xFADE0C02.toInt()
    const val MAGIC_EMBEDDED_SIGNATURE = 0xFADE0CC0.toInt()
    const val MAGIC_DETACHED_SIGNATURE = 0xFADE0CC1.toInt()
    const val MAGIC_ENTITLEMENTS = 0xFADE7171.toInt()
    const val MAGIC_DER_ENTITLEMENTS = 0xFADE7172.toInt()
    const val MAGIC_BLOBWRAPPER = 0xFADE0B01.toInt()

    const val SLOT_CODEDIRECTORY = 0
    const val SLOT_INFOSLOT = 1
    const val SLOT_REQUIREMENTS = 2
    const val SLOT_RESOURCEDIR = 3
    const val SLOT_APPLICATION = 4
    const val SLOT_ENTITLEMENTS = 5
    const val SLOT_DER_ENTITLEMENTS = 7
    const val SLOT_ALTERNATE_CODEDIRECTORY = 0x1000
    const val SLOT_SIGNATURESLOT = 0x10000

    const val HASH_SHA1 = 1
    const val HASH_SHA256 = 2

    /** Wraps a body in the generic blob header. */
    fun blob(magic: Int, body: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + body.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(magic)
        out.putInt(8 + body.size)
        out.put(body)
        return out.array()
    }

    /**
     * An empty requirements set.
     *
     * A development signature does not need a designated requirement: the
     * device checks the certificate chain and the provisioning profile
     * instead. An empty set is what codesign itself produces for an ad hoc
     * development signature, and it has to be present because the code
     * directory hashes the slot.
     */
    fun emptyRequirements(): ByteArray {
        val body = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        body.putInt(0)
        return blob(MAGIC_REQUIREMENTS, body.array())
    }

    fun entitlements(xml: ByteArray): ByteArray = blob(MAGIC_ENTITLEMENTS, xml)

    fun derEntitlements(der: ByteArray): ByteArray = blob(MAGIC_DER_ENTITLEMENTS, der)

    /** Assembles the SuperBlob the LC_CODE_SIGNATURE command points at. */
    fun superBlob(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val indexSize = entries.size * 8
        var offset = 12 + indexSize
        val index = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        for ((slot, blob) in entries) {
            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            header.putInt(slot)
            header.putInt(offset)
            index.write(header.array())
            body.write(blob)
            offset += blob.size
        }
        val total = 12 + indexSize + body.size()
        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        out.putInt(MAGIC_EMBEDDED_SIGNATURE)
        out.putInt(total)
        out.putInt(entries.size)
        out.put(index.toByteArray())
        out.put(body.toByteArray())
        return out.array()
    }

    fun digest(type: Int, data: ByteArray): ByteArray = when (type) {
        HASH_SHA1 -> MessageDigest.getInstance("SHA-1").digest(data)
        else -> MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun digestLength(type: Int): Int = if (type == HASH_SHA1) 20 else 32
}

/**
 * The CodeDirectory: the hash of every page of the executable.
 *
 * This is the blob the kernel actually enforces. Each page of the file up to
 * the signature gets a hash, and a handful of negative "special" slots hold
 * the hashes of the Info.plist, the requirements, CodeResources and the
 * entitlements. The CMS signature at the end signs this blob and nothing
 * else, which is why every byte of it has to be right.
 */
class CodeDirectory(
    private val identifier: String,
    private val teamId: String,
    private val hashType: Int,
    private val pageSize: Int = 4096,
    private val executableLength: Int,
    private val fileBytes: ByteArray,
    private val fileStart: Int,
    private val specialSlots: Map<Int, ByteArray>,
    /** 0x2400 is a development signature with the runtime flag clear. */
    private val flags: Int = 0
) {

    fun build(): ByteArray {
        val hashSize = Blobs.digestLength(hashType)
        val pageShift = Integer.numberOfTrailingZeros(pageSize)
        val codeSlots = (executableLength + pageSize - 1) / pageSize
        val specialCount = specialSlots.keys.maxOrNull() ?: 0

        val identifierBytes = identifier.toByteArray() + 0
        val teamBytes = if (teamId.isEmpty()) ByteArray(0) else teamId.toByteArray() + 0

        // Version 0x20400 carries the team identifier and the exec segment
        // fields, which is what current iOS expects from a signature.
        val version = 0x20400
        val fixedLength = 13 * 4 + 4 + 4 + 8 + 8 + 8 + 8
        val identifierOffset = fixedLength
        val teamOffset = if (teamBytes.isEmpty()) 0 else identifierOffset + identifierBytes.size
        val hashesStart = identifierOffset + identifierBytes.size + teamBytes.size +
            specialCount * hashSize
        val hashOffset = hashesStart
        val total = hashesStart + codeSlots * hashSize

        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        out.putInt(Blobs.MAGIC_CODEDIRECTORY)
        out.putInt(total)
        out.putInt(version)
        out.putInt(flags)
        out.putInt(hashOffset)
        out.putInt(identifierOffset)
        out.putInt(specialCount)
        out.putInt(codeSlots)
        out.putInt(executableLength)
        out.put(hashSize.toByte())
        out.put(hashType.toByte())
        out.put(0) // platform: zero for a development signature
        out.put(pageShift.toByte())
        out.putInt(0) // spare2
        out.putInt(0) // scatterOffset, unused
        out.putInt(teamOffset)
        out.putInt(0) // spare3
        out.putLong(0) // codeLimit64, only for files over 4 GB
        out.putLong(0) // execSegBase
        out.putLong(executableLength.toLong()) // execSegLimit
        out.putLong(if (isMainExecutable) 1L else 0L) // execSegFlags

        out.position(identifierOffset)
        out.put(identifierBytes)
        if (teamBytes.isNotEmpty()) out.put(teamBytes)

        // Special slots count backwards from the hash offset.
        for (slot in 1..specialCount) {
            val hash = specialSlots[slot] ?: ByteArray(hashSize)
            out.position(hashOffset - slot * hashSize)
            out.put(hash)
        }

        out.position(hashOffset)
        var position = 0
        while (position < executableLength) {
            val length = minOf(pageSize, executableLength - position)
            val page = fileBytes.copyOfRange(fileStart + position, fileStart + position + length)
            out.put(Blobs.digest(hashType, page))
            position += length
        }
        return out.array()
    }

    /** Set for the app's own executable; cleared for frameworks and plugins. */
    var isMainExecutable: Boolean = false
}
