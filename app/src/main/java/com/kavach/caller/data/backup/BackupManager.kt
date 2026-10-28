package com.kavach.caller.data.backup

import android.content.Context
import android.net.Uri
import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.domain.BackupCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backing up what Kavach knows, and nothing else.
 *
 * ## What is in a backup
 *
 * - the block rules
 * - Kavach's own contact fields (company, notes, tags, favourites)
 * - the settings that change behaviour
 *
 * ## What is deliberately left out
 *
 * - **Call history.** A backup is not an archive of who called.
 * - **Recordings and transcripts.** Those are the most sensitive thing the app
 *   holds; bundling them into a file the user then emails themselves is how they
 *   end up somewhere they should not be.
 * - **The server token and the API keys.** A backup restored onto another phone
 *   should not carry the credential that lets it act as this install.
 *
 * A backup is for getting set up again quickly, not for keeping everything.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blocks: BlockRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
) {

    @Serializable
    private data class BackupContact(
        val name: String,
        val numbers: List<BackupNumber> = emptyList(),
        val company: String? = null,
        val jobTitle: String? = null,
        val notes: String? = null,
        val tags: List<String> = emptyList(),
        val favourite: Boolean = false,
    )

    @Serializable
    private data class BackupNumber(val label: String, val number: String)

    @Serializable
    private data class BackupRule(
        val pattern: String,
        val type: String,
        val action: String,
        val label: String? = null,
        val enabled: Boolean = true,
    )

    @Serializable
    private data class Document(
        val app: String = "kavach",
        val kind: String = "backup",
        val version: Int = 1,
        val createdAt: Long = 0,
        val contacts: List<BackupContact> = emptyList(),
        val rules: List<BackupRule> = emptyList(),
        // The settings that change how the app behaves, so a new phone behaves the
        // same on the first call rather than the tenth.
        val quietHoursEnabled: Boolean = false,
        val quietHoursStartHour: Int = 22,
        val quietHoursEndHour: Int = 7,
        val quietHoursAction: String = "SILENCE",
        val autoBlockScore: Int = 0,
    )

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Build the plaintext backup. Separated so it can be inspected and tested. */
    suspend fun buildPlaintext(): String {
        val snapshot = settings.settings.first()
        val rules = blocks.observeAll().first()
        val people = contacts.observeAll().first()

        return json.encodeToString(
            Document.serializer(),
            Document(
                createdAt = System.currentTimeMillis(),
                contacts = people.map {
                    BackupContact(
                        name = it.displayName,
                        numbers = it.numbers.map { n -> BackupNumber(n.label, n.number) },
                        company = it.company,
                        jobTitle = it.jobTitle,
                        notes = it.notes,
                        tags = it.tags,
                        favourite = it.isFavourite,
                    )
                },
                rules = rules.map {
                    BackupRule(
                        pattern = it.pattern,
                        type = it.type.name,
                        action = it.action.name,
                        label = it.label,
                        enabled = it.enabled,
                    )
                },
                quietHoursEnabled = snapshot.quietHoursEnabled,
                quietHoursStartHour = snapshot.quietHoursStartHour,
                quietHoursEndHour = snapshot.quietHoursEndHour,
                quietHoursAction = snapshot.quietHoursAction,
                autoBlockScore = snapshot.autoBlockScore,
            ),
        )
    }

    /** Encrypt and write to the file the user chose. */
    suspend fun writeTo(uri: Uri, passphrase: String): Result<Int> = runCatching {
        val plaintext = buildPlaintext()
        val encrypted = BackupCrypto.encrypt(plaintext, passphrase)
        context.contentResolver.openOutputStream(uri)?.use { it.write(encrypted) }
            ?: error("The file could not be opened for writing.")
        val document = json.decodeFromString(Document.serializer(), plaintext)
        document.contacts.size + document.rules.size
    }

    sealed interface Restore {
        data class Ok(val contacts: Int, val rules: Int) : Restore
        data object WrongPassphrase : Restore
        data object NotABackup : Restore
        data class Failed(val reason: String) : Restore
    }

    /**
     * Read a file and put it back.
     *
     * **Merges rather than replaces.** A restore onto a phone that already has
     * contacts should not delete them; a backup is additive by nature and a
     * destructive restore is the kind of thing that loses data on a mistyped
     * passphrase.
     */
    suspend fun restoreFrom(uri: Uri, passphrase: String): Restore {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return Restore.Failed("That file could not be opened.")

        val plaintext = when (val result = BackupCrypto.decrypt(bytes, passphrase)) {
            is BackupCrypto.Result.Ok -> result.plaintext
            BackupCrypto.Result.WrongPassphrase -> return Restore.WrongPassphrase
            BackupCrypto.Result.NotABackup -> return Restore.NotABackup
            is BackupCrypto.Result.Failed -> return Restore.Failed(result.reason)
        }

        val document = runCatching {
            json.decodeFromString(Document.serializer(), plaintext)
        }.getOrNull() ?: return Restore.Failed("The backup decrypted but could not be read.")

        if (document.kind != "backup") {
            return Restore.Failed("That file is not a Kavach backup.")
        }

        document.rules.forEach { rule ->
            val type = com.kavach.caller.data.local.entity.RuleType.entries
                .firstOrNull { it.name.equals(rule.type, ignoreCase = true) }
            val action = com.kavach.caller.data.local.entity.BlockAction.entries
                .firstOrNull { it.name.equals(rule.action, ignoreCase = true) }
            if (type != null && action != null && rule.pattern.isNotBlank()) {
                blocks.upsert(
                    BlockRuleEntity(
                        pattern = rule.pattern,
                        type = type,
                        action = action,
                        label = rule.label,
                        enabled = rule.enabled,
                    ),
                )
            }
        }

        document.contacts.forEach { person ->
            if (person.name.isBlank()) return@forEach
            contacts.save(
                ContactEntity(
                    displayName = person.name,
                    numbers = person.numbers.map {
                        com.kavach.caller.data.local.entity.PhoneNumber(it.label, it.number)
                    },
                    company = person.company,
                    jobTitle = person.jobTitle,
                    notes = person.notes,
                    tags = person.tags,
                    isFavourite = person.favourite,
                ),
            )
        }

        runCatching {
            settings.setQuietHours(document.quietHoursEnabled)
            settings.setQuietHoursStart(document.quietHoursStartHour, 0)
            settings.setQuietHoursEnd(document.quietHoursEndHour, 0)
            settings.setQuietHoursAction(document.quietHoursAction)
        }

        return Restore.Ok(document.contacts.size, document.rules.size)
    }
}
