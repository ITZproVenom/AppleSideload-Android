package dev.applesideload.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.applesideload.core.BinaryPlist
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import java.io.File
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
            UUID.randomUUID().toString().uppercase().also { file.writeText(it) }
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
        fileFor(record.udid).writeBytes(
            encrypt(BinaryPlist.write(record.toPlist(withPrivateKeys = true)))
        )
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

    private fun sanitise(udid: String) = udid.filter { it.isLetterOrDigit() || it == '-' }

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
    }
}
