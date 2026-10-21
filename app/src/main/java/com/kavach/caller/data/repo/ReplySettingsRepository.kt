package com.kavach.caller.data.repo

import com.kavach.caller.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suggested replies and the auto-reply text.
 *
 * Stored as a JSON array in preferences so the user's order is preserved — a
 * set would lose it, and order is the whole point of a quick-reply row.
 */
@Singleton
class ReplySettingsRepository @Inject constructor(
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    val replies: Flow<List<String>> = settings.settings.map { parse(it.suggestedReplies) }

    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return DEFAULT_REPLIES
        return runCatching { json.decodeFromString(listSerializer, raw) }
            .getOrDefault(DEFAULT_REPLIES)
    }

    suspend fun save(replies: List<String>) {
        val cleaned = replies.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        settings.setSuggestedReplies(json.encodeToString(listSerializer, cleaned))
    }

    suspend fun addReply(text: String) = save(parse(settings.snapshot.suggestedReplies) + text)

    suspend fun removeReply(text: String) =
        save(parse(settings.snapshot.suggestedReplies) - text)

    /** Move a reply up or down; order is what the user arranges. */
    suspend fun move(from: Int, to: Int) {
        val current = parse(settings.snapshot.suggestedReplies).toMutableList()
        if (from !in current.indices || to !in current.indices) return
        val item = current.removeAt(from)
        current.add(to, item)
        save(current)
    }

    companion object {
        val DEFAULT_REPLIES = listOf(
            "I'll call you back in a bit.",
            "In a meeting — will reply soon.",
            "Can't talk right now.",
            "Please send it on email.",
            "On my way.",
            "Thanks, noted.",
        )
    }
}
