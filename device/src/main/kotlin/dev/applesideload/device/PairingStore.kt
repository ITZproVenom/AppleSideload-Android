package dev.applesideload.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.applesideload.core.BinaryPlist
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.device.remote.RpPairingFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pairing records on disk, encrypted by a key the app cannot export.
 *
 * A pairing record is as good as trusted access to the iPhone, so it is kept
 * under an AES-GCM key held in the Android Keystore: the key material lives
 * in the TEE or StrongBox where the hardware supports it and never reaches
 * this process. Records are stored as binary property lists so they stay the
 * same shape the device services expect.
 */
class PairingStore(context: Context) {

    private val directory = File(context.filesDir, "pairing").apply { mkdirs() }
    private val appContext = context.applicationContext

    /** Stable per-install identifier, the same value for every device. */
    val systemBuid: String by lazy {
        val file = File(directory, "system-buid")
        if (file.exists()) {
            file.readText().trim()
        } else {
            UUID.randomUUID().toString().uppercase().also { writeAtomically(file, it.toByteArray()) }
        }
    }

    fun load(udid: String): PairingRecord? {
        val file = fileFor(udid)
        if (!file.exists()) return null
        return try {
            PairingRecord.fromPlist(BinaryPlist.parse(decrypt(file.readBytes())))
        } catch (error: Exception) {
            Log.w(LogTag.PAIR, "the stored pairing record for this device could not be read: ${error.message}")
            null
        }
    }

    fun save(record: PairingRecord) {
        require(record.udid.isNotEmpty()) { "a pairing record must know its device" }
        writeAtomically(fileFor(record.udid), encrypt(BinaryPlist.write(record.toPlist(withPrivateKeys = true))))
        Log.i(LogTag.PAIR, "stored the pairing record for this device")
    }

    fun forget(udid: String) {
        if (fileFor(udid).delete()) {
            Log.i(LogTag.PAIR, "removed the stored pairing record for this device")
        }
    }

    fun knownDevices(): List<String> = directory.listFiles()
        ?.filter { it.name.endsWith(SUFFIX) }
        ?.map { it.name.removeSuffix(SUFFIX) }
        .orEmpty()

    private fun fileFor(udid: String) = File(directory, sanitise(udid) + SUFFIX)

    // MARK: - Remote Pairing (iOS 17 and later; the only wireless route on iOS 27)

    private val remoteDirectory = File(directory, "remote").apply { mkdirs() }

    /**
     * This phone's Remote Pairing identity: made the first time it is needed,
     * then kept, because every iPhone paired with this phone (and the copy
     * SideStore is given) knows it by this identifier and key.
     */
    @Synchronized
    fun remoteIdentity(): RpPairingFile {
        val file = File(remoteDirectory, IDENTITY_FILE)
        if (file.exists()) {
            try {
                val stored = RpPairingFile.fromStoredPlist(decrypt(file.readBytes()))
                return RpPairingFile(stored.identifier, stored.privateKey, stored.publicKey, stored.hostAltIrk)
            } catch (error: Exception) {
                // Kept aside rather than deleted, in case it can be read later.
                file.renameTo(File(remoteDirectory, "$IDENTITY_FILE.unreadable"))
                Log.w(
                    LogTag.PAIR,
                    "this phone's Remote Pairing identity could not be read (${error.message}); " +
                        "making a new one. iPhones paired with the old one keep working through their own records."
                )
            }
        }
        val fresh = RpPairingFile.generate()
        writeAtomically(file, encrypt(fresh.toStoredPlist()))
        Log.i(LogTag.PAIR, "made this phone's Remote Pairing identity")
        return fresh
    }

    /** The Remote Pairing record for one iPhone, or null if there is none or it cannot be read. */
    fun loadRemote(udid: String): RpPairingFile? {
        val file = remoteFile(udid)
        if (!file.exists()) return null
        return try {
            RpPairingFile.fromStoredPlist(decrypt(file.readBytes()))
        } catch (error: Exception) {
            Log.w(LogTag.PAIR, "the Remote Pairing record for this iPhone could not be read: ${error.message}")
            null
        }
    }

    /** Stores [record], which must know its iPhone, and the address it was reached at, if known. */
    fun saveRemote(record: RpPairingFile, host: String?) {
        val udid = record.udid?.takeIf { it.isNotBlank() }
        require(udid != null) { "a Remote Pairing record must know its iPhone" }
        writeAtomically(remoteFile(udid), encrypt(record.toStoredPlist()))
        if (!host.isNullOrBlank()) writeAtomically(hostFile(udid), host.toByteArray())
        Log.i(LogTag.PAIR, "stored the Remote Pairing record for ${record.deviceName ?: "this iPhone"}")
    }

    /** Where the iPhone was last reached through its tunnel, as an IP address. */
    fun remoteHost(udid: String): String? =
        hostFile(udid).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Every iPhone paired with this phone through Remote Pairing whose record can be read. */
    fun remoteDevices(): List<RpPairingFile> = remoteDirectory.listFiles()
        ?.filter { it.name.endsWith(REMOTE_SUFFIX) }
        ?.mapNotNull { loadRemote(it.name.removeSuffix(REMOTE_SUFFIX)) }
        .orEmpty()

    fun forgetRemote(udid: String) {
        val removed = remoteFile(udid).delete()
        hostFile(udid).delete()
        if (removed) Log.i(LogTag.PAIR, "removed the Remote Pairing record for this iPhone")
    }

    private fun remoteFile(udid: String) = File(remoteDirectory, sanitise(udid) + REMOTE_SUFFIX)

    private fun hostFile(udid: String) = File(remoteDirectory, sanitise(udid) + HOST_SUFFIX)

    private fun sanitise(udid: String) = udid.filter { it.isLetterOrDigit() || it == '-' }

    /**
     * Writes through a temporary file and a rename, so a crash never leaves
     * half a record. Synchronized because the temporary file's name is fixed:
     * two writers of one record (a pairing finishing while a connection
     * updates it) would otherwise interleave in it.
     */
    @Synchronized
    private fun writeAtomically(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not store ${file.name}")
        }
    }

    // MARK: - Keystore

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), SecureRandom())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decrypt(stored: ByteArray): ByteArray {
        if (stored.isEmpty()) throw DeviceException(
            operation = "reading a pairing record",
            reason = "the stored record is empty"
        )
        val ivLength = stored[0].toInt()
        if (ivLength <= 0 || ivLength + 1 >= stored.size) {
            throw DeviceException(
                operation = "reading a pairing record",
                reason = "the stored record has no usable nonce"
            )
        }
        val iv = stored.copyOfRange(1, 1 + ivLength)
        val body = stored.copyOfRange(1 + ivLength, stored.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    /** Kept so the context is not silently unused if storage moves later. */
    fun describe(): String = "pairing records in ${directory.path} for ${appContext.packageName}"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "applesideload.pairing"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SUFFIX = ".pairing"
        const val REMOTE_SUFFIX = ".rppairing"
        const val HOST_SUFFIX = ".host"
        const val IDENTITY_FILE = "identity.rppairing-host"
    }
}
