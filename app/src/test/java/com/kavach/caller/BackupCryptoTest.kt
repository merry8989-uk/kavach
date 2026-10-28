package com.kavach.caller

import com.kavach.caller.domain.BackupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup encryption.
 *
 * The cases that matter are the failures: a backup that decrypts under the wrong
 * passphrase, or that decrypts a tampered file into garbage the importer then
 * writes into the database, is worse than no backup.
 */
class BackupCryptoTest {

    private val passphrase = "correct horse battery staple"

    @Test
    fun `a backup round-trips`() {
        val text = """{"rules":[{"pattern":"+91","type":"PREFIX"}]}"""
        val encrypted = BackupCrypto.encrypt(text, passphrase)
        val result = BackupCrypto.decrypt(encrypted, passphrase)
        assertTrue(result is BackupCrypto.Result.Ok)
        assertEquals(text, (result as BackupCrypto.Result.Ok).plaintext)
    }

    @Test
    fun `the wrong passphrase fails rather than returning garbage`() {
        val encrypted = BackupCrypto.encrypt("secret", passphrase)
        val result = BackupCrypto.decrypt(encrypted, "not the passphrase")
        assertTrue(result is BackupCrypto.Result.WrongPassphrase)
    }

    @Test
    fun `the file does not contain the plaintext`() {
        // The whole point: a backup in Downloads should not be readable.
        val text = "a very distinctive string"
        val encrypted = BackupCrypto.encrypt(text, passphrase)
        val asText = String(encrypted, Charsets.ISO_8859_1)
        assertTrue(!asText.contains(text))
    }

    @Test
    fun `the same text encrypts differently every time`() {
        // A random salt per backup, so a precomputed table is useless and two
        // backups of the same data cannot be compared.
        val a = BackupCrypto.encrypt("same", passphrase)
        val b = BackupCrypto.encrypt("same", passphrase)
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `a tampered file fails instead of decrypting`() {
        // GCM's tag check. CBC would happily decrypt this into garbage.
        val encrypted = BackupCrypto.encrypt("some data here", passphrase)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0xFF).toByte()
        val result = BackupCrypto.decrypt(encrypted, passphrase)
        assertTrue(result !is BackupCrypto.Result.Ok)
    }

    @Test
    fun `a file that is not a backup is named as such`() {
        val result = BackupCrypto.decrypt("just some text".toByteArray(), passphrase)
        assertTrue(result is BackupCrypto.Result.NotABackup)
    }

    @Test
    fun `an empty file is refused`() {
        assertTrue(BackupCrypto.decrypt(ByteArray(0), passphrase) is BackupCrypto.Result.NotABackup)
    }

    @Test
    fun `a truncated file is refused`() {
        val encrypted = BackupCrypto.encrypt("data", passphrase)
        val truncated = encrypted.copyOfRange(0, 10)
        assertTrue(BackupCrypto.decrypt(truncated, passphrase) is BackupCrypto.Result.NotABackup)
    }

    @Test
    fun `unicode survives`() {
        val text = "अमित — Sharma, Amit ✓"
        val encrypted = BackupCrypto.encrypt(text, passphrase)
        val result = BackupCrypto.decrypt(encrypted, passphrase) as BackupCrypto.Result.Ok
        assertEquals(text, result.plaintext)
    }

    @Test
    fun `an empty backup is still encrypted and readable`() {
        val encrypted = BackupCrypto.encrypt("", passphrase)
        val result = BackupCrypto.decrypt(encrypted, passphrase)
        assertTrue(result is BackupCrypto.Result.Ok)
        assertEquals("", (result as BackupCrypto.Result.Ok).plaintext)
    }
}
