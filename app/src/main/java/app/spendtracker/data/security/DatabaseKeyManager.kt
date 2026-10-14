package app.spendtracker.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.spendtracker.data.db.AppDatabase
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates a random database passphrase once and stores it encrypted with an AES-GCM key
 * that never leaves Android Keystore. The plain passphrase only ever lives in memory.
 */
class DatabaseKeyManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun passphrase(): ByteArray {
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        val iv = prefs.getString(KEY_IV, null)
        if (wrapped != null && iv != null) {
            try {
                return decrypt(Base64.decode(wrapped, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP))
            } catch (e: Exception) {
                // Keystore key lost (e.g. device restore). The old database can no longer be opened,
                // so start fresh rather than crash. SMS can be re-scanned from the inbox.
                context.deleteDatabase(AppDatabase.NAME)
            }
        }
        return createAndStore()
    }

    private fun createAndStore(): ByteArray {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Hex-encode so the passphrase never contains NUL bytes.
        val passphrase = raw.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(passphrase)
        prefs.edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        return passphrase
    }

    private fun decrypt(data: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "spendtracker_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS = "db_key"
        const val KEY_WRAPPED = "wrapped"
        const val KEY_IV = "iv"
    }
}
