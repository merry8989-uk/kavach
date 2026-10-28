package com.kavach.caller.domain

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypting a backup.
 *
 * ## Why this is not optional
 *
 * A backup of Kavach is a list of the people the user blocks and the notes they
 * keep about them. Written to a file in the clear and dropped in Downloads, it is
 * readable by any app with storage access — which is a worse disclosure than the
 * database it came from, because the database is encrypted.
 *
 * So the file is encrypted with a passphrase the user chooses, and the passphrase
 * is **not** stored anywhere. Losing it loses the backup. That is the deal, and
 * the UI says so.
 *
 * ## The construction
 *
 * PBKDF2-HMAC-SHA256 at 210,000 iterations (the current OWASP figure) to turn the
 * passphrase into a key, then AES-256-GCM.
 *
 * - **A random salt per backup**, so the same passphrase produces a different key
 *   every time and a precomputed table is useless.
 * - **GCM, not CBC**, so a tampered file fails to decrypt instead of decrypting
 *   into garbage that the importer then writes into the database.
 * - **The salt and IV are stored in the file, in the clear.** They are not secrets;
 *   they only have to be unique.
 */
object BackupCrypto {

    /** Bumped only if the format changes incompatibly. */
    private const val MAGIC = "KVBK1"

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    sealed interface Result {
        data class Ok(val plaintext: String) : Result
        data object WrongPassphrase : Result
        data object NotABackup : Result
        data class Failed(val reason: String) : Result
    }

    /** Encrypt [plaintext] under [passphrase]. The passphrase is never stored. */
    fun encrypt(plaintext: String, passphrase: String): ByteArray {
        require(passphrase.isNotEmpty()) { "a backup needs a passphrase" }

        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return MAGIC.toByteArray(Charsets.UTF_8) + salt + iv + ciphertext
    }

    /**
     * Decrypt a file produced by [encrypt].
     *
     * A wrong passphrase and a corrupted file are reported differently, because
     * "wrong passphrase" is something the user can fix and "not a backup" is not.
     */
    fun decrypt(bytes: ByteArray, passphrase: String): Result {
        val magicLength = MAGIC.length
        val headerLength = magicLength + SALT_BYTES + IV_BYTES
        if (bytes.size <= headerLength) return Result.NotABackup

        val magic = String(bytes.copyOfRange(0, magicLength), Charsets.UTF_8)
        if (magic != MAGIC) return Result.NotABackup

        val salt = bytes.copyOfRange(magicLength, magicLength + SALT_BYTES)
        val iv = bytes.copyOfRange(magicLength + SALT_BYTES, headerLength)
        val ciphertext = bytes.copyOfRange(headerLength, bytes.size)

        return runCatching {
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            Result.Ok(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        }.getOrElse {
            // GCM cannot tell a wrong key from a tampered file — both fail the tag
            // check. Saying "wrong passphrase" is the useful guess and the only
            // one the user can act on.
            Result.WrongPassphrase
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val encoded = factory.generateSecret(spec).encoded
        return SecretKeySpec(encoded, "AES")
    }
}
