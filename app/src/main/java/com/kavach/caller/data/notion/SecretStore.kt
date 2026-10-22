package com.kavach.caller.data.notion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores a secret in preferences, encrypted with a key that lives in the
 * Android Keystore and cannot be exported.
 *
 * Used for the Notion integration token. A plain preference would be readable by
 * anything that can read app storage; the Keystore key cannot leave the device,
 * so a copied preferences file is useless on another phone.
 */
@Singleton
class SecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        context.getSharedPreferences("kavach_secrets", Context.MODE_PRIVATE)
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        runCatching { prefs.edit().putString(name, encrypt(value)).apply() }
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    fun has(name: String): Boolean = !prefs.getString(name, null).isNullOrBlank()

    // ---------------- crypto ----------------

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
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_LENGTH)
        val body = raw.copyOfRange(IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body))
    }

    companion object {
        /** Key names used by this app. */
        const val NOTION_TOKEN = "notion_token"

        /**
         * The Supabase anon key. It is public by design — row-level security in
         * supabase/schema.sql is what protects the table, not this value.
         */
        const val SUPABASE_ANON_KEY = "supabase_anon_key"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kavach_secret_store"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }
}
