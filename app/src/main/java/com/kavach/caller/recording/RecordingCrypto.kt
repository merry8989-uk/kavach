package com.kavach.caller.recording

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts call recordings at rest (spec Phase 9: "local encrypted storage").
 *
 * The database was already encrypted, but the audio files were sitting in app
 * storage in the clear — and `RecordingEntity.encrypted` claimed otherwise,
 * which made the data model lie about the app's own security.
 *
 * Format: a small header, then a single AES-256-GCM stream.
 *
 *     "KVEN" | version(1) | iv(12) | ciphertext+tag
 *
 * Streaming rather than read-it-all-into-memory, so a long recording does not
 * need to fit in RAM, and GCM authenticates the whole file — a truncated or
 * edited recording fails to decrypt instead of playing back garbage.
 */
@Singleton
class RecordingCrypto @Inject constructor() {

    private val key: SecretKey
        get() = keystoreKey()

    /** True when encryption genuinely works on this device. */
    fun isAvailable(): Boolean = runCatching { key; true }.getOrDefault(false)

    /**
     * Encrypt [plain] into [target], then delete the plaintext.
     *
     * @return the number of bytes written, or null on failure. On failure the
     *   plaintext is left alone so the recording is not lost.
     */
    fun encryptTo(plain: File, target: File): Long? = runCatching {
        if (!plain.exists() || plain.length() == 0L) return null

        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }

        target.outputStream().buffered().use { out ->
            out.write(MAGIC)
            out.write(FORMAT_VERSION)
            out.write(iv)
            CipherOutputStream(out, cipher).use { encrypted ->
                plain.inputStream().buffered().use { it.copyTo(encrypted) }
            }
        }

        if (target.length() > 0) {
            plain.delete()
            target.length()
        } else {
            null
        }
    }.getOrNull()

    /** Open an encrypted recording for playback. */
    fun openForRead(encrypted: File): InputStream? = runCatching {
        val input = encrypted.inputStream().buffered()
        val magic = ByteArray(MAGIC.size)
        if (input.read(magic) != MAGIC.size || !magic.contentEquals(MAGIC)) {
            input.close()
            return null
        }
        val version = input.read()
        if (version != FORMAT_VERSION) {
            input.close()
            return null
        }
        val iv = ByteArray(IV_LENGTH)
        if (input.read(iv) != IV_LENGTH) {
            input.close()
            return null
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        CipherInputStream(input, cipher)
    }.getOrNull()

    /** Decrypt to a throwaway file, for players that need a path. */
    fun decryptToTemp(encrypted: File, target: File): Boolean = runCatching {
        openForRead(encrypted)?.use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: return false
        target.length() > 0
    }.getOrDefault(false)

    /** Write [source] out as a plain file, for the user's own export. */
    fun exportTo(source: File, target: OutputStream): Boolean = runCatching {
        val input = if (isEncrypted(source)) openForRead(source) else source.inputStream()
        input?.use { it.copyTo(target) } ?: return false
        true
    }.getOrDefault(false)

    fun isEncrypted(file: File): Boolean = runCatching {
        if (!file.exists() || file.length() < MAGIC.size + 1) return false
        val head = ByteArray(MAGIC.size)
        file.inputStream().use { it.read(head) }
        head.contentEquals(MAGIC)
    }.getOrDefault(false)

    // ---------------- key ----------------

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

    companion object {
        private val MAGIC = byteArrayOf('K'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte())
        private const val FORMAT_VERSION = 1
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kavach_recording"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128

        /** Suffix for encrypted recordings. */
        const val EXTENSION = "kven"
    }
}
