package com.kavach.caller.data.local

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SQLCipher passphrase for the local database, protected by the Android
 * Keystore (spec Phase 13: "encrypted Room DB").
 *
 * How it works: a random 32-byte secret is generated once, encrypted with a
 * non-exportable AES-256-GCM key held in the Keystore, and the ciphertext is
 * kept in private preferences. The passphrase never exists on disk in the clear,
 * and cannot be read out of the Keystore even with root access to the app's
 * files.
 *
 * Honest limitation: this protects data at rest against another app or a pulled
 * backup. It does not protect against a rooted device running as the user, and
 * on Android 10+ the Keystore key is not itself protected by the lock screen
 * unless the device has a secure lock configured.
 */
@Singleton
class DatabaseKey @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun passphrase(): ByteArray = loadOrCreateSecret()

    private fun loadOrCreateSecret(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_BLOB, null)?.let { blob ->
            runCatching { decrypt(blob) }.getOrNull()?.let { return it }
            // Undecryptable (key rotated or data restored elsewhere) — start over.
            // The database is a re-syncable cache, so this costs a refresh, not data.
            prefs.edit().remove(KEY_BLOB).apply()
        }

        val secret = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_BLOB, encrypt(secret)).apply()
        return secret
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        return Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): ByteArray {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.size > IV_BYTES) { "stored key blob is too short" }
        val iv = raw.copyOfRange(0, IV_BYTES)
        val cipherText = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setRandomizedEncryptionRequired(true)
                    }
                }
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "kavach_secure"
        private const val KEY_BLOB = "db_key_blob"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kavach_db_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val SECRET_BYTES = 32

        /**
         * SQLCipher cannot open a plaintext database, and the two file formats
         * are not interchangeable. An install from before encryption shipped
         * would otherwise look like corruption.
         *
         * We rename the old file aside rather than delete it, so nothing is
         * destroyed — and it is only a cache of contacts and call history, both
         * of which re-sync from the device.
         *
         * @return true when a legacy file was moved.
         */
        fun moveLegacyPlaintextAside(context: Context): Boolean {
            val databaseFile = context.getDatabasePath(KavachDatabase.NAME)
            if (!databaseFile.exists()) return false
            val target = File(databaseFile.parentFile, "${KavachDatabase.NAME}.legacy-plaintext")
            if (target.exists()) target.delete()
            return runCatching { databaseFile.renameTo(target) }.getOrDefault(false)
        }
    }
}
