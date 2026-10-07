package dev.applesideload.sideload

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.applesideload.core.Log
import dev.applesideload.core.LogTag
import dev.applesideload.signing.SigningIdentity
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The signing key, the certificate and the account password, encrypted.
 *
 * All three are as sensitive as a password, so they sit under a key in the
 * Android Keystore that this process cannot read out. The signing key in
 * particular must survive restarts: Apple only issues one development
 * certificate per free account, and losing the key means revoking and
 * reissuing it, which breaks every app already signed with it.
 */
class IdentityStore(context: Context) {

    private val directory = File(context.filesDir, "identity").apply { mkdirs() }

    fun saveSigningKey(privateKeyDer: ByteArray, certificateDer: ByteArray) {
        write("signing.key", privateKeyDer)
        write("signing.cer", certificateDer)
        Log.i(LogTag.SIGN, "stored the signing identity for this account")
    }

    fun loadSigningIdentity(): SigningIdentity? {
        val key = read("signing.key") ?: return null
        val certificate = read("signing.cer") ?: return null
        return runCatching { SigningIdentity.from(key, certificate) }.getOrNull()
    }

    /** The key kept while waiting for Apple to return the certificate. */
    fun savePendingKey(privateKeyDer: ByteArray) = write("pending.key", privateKeyDer)

    fun loadPendingKey(): ByteArray? = read("pending.key")

    /** The machine id the certificate was requested with, used as SideStore's p12 password. */
    fun saveMachineId(machineId: String) = write("signing.mid", machineId.toByteArray())

    fun loadMachineId(): String? = read("signing.mid")?.let { String(it) }

    fun clearSigningIdentity() {
        listOf("signing.key", "signing.cer", "pending.key", "signing.mid").forEach {
            File(directory, it).delete()
        }
    }

    fun savePassword(appleId: String, password: String) =
        write("password-${appleId.hashCode()}", password.toByteArray())

    fun loadPassword(appleId: String): String? =
        read("password-${appleId.hashCode()}")?.let { String(it) }

    fun forgetPassword(appleId: String) {
        File(directory, "password-${appleId.hashCode()}").delete()
    }

    // MARK: - Keystore

    private fun write(name: String, plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), SecureRandom())
        File(directory, name).writeBytes(
            byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
        )
    }

    private fun read(name: String): ByteArray? {
        val file = File(directory, name)
        if (!file.exists()) return null
        return runCatching {
            val stored = file.readBytes()
            val ivLength = stored[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, stored.copyOfRange(1, 1 + ivLength))
            )
            cipher.doFinal(stored.copyOfRange(1 + ivLength, stored.size))
        }.onFailure {
            Log.w(LogTag.SIGN, "a stored secret could not be read back: ${it.message}")
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ALIAS = "applesideload.identity"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
