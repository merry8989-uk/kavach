package com.kavach.caller.data.telemetry

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outbound queue for diagnostics events.
 *
 * Every destination drains this same queue, so switching one off and another on
 * loses nothing: an event is appended once and only removed once a destination
 * has accepted it.
 *
 * This used to live inside the Notion repository, which meant the queue was
 * owned by one destination rather than by the feature. Notion is gone; the queue
 * is not, so it now stands on its own.
 */
@Singleton
class UploadQueue @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "upload-queue").apply { mkdirs() }
    private val file: File get() = File(dir, QUEUE_FILE)

    /** Append one encoded event line. */
    suspend fun add(line: String) = withContext(Dispatchers.IO) {
        runCatching {
            val target = file
            target.appendText(line + "\n")
            // Bound the queue; the diagnostics log itself is the long-term record.
            if (target.length() > MAX_BYTES) {
                val kept = target.readLines().takeLast(MAX_LINES)
                target.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    suspend fun lines(): List<String> = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) file.readLines() else emptyList() }
            .getOrDefault(emptyList())
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) file.readLines().size else 0 }.getOrDefault(0)
    }

    /** Replace the queue with whatever is left after a flush. */
    suspend fun keep(remaining: List<String>) = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(remaining.joinToString("\n", postfix = if (remaining.isEmpty()) "" else "\n"))
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.writeText("") }
    }

    /**
     * Queue everything already logged.
     *
     * Called when a destination is switched on, so turning it on does something
     * visible instead of waiting for the next event to be written.
     */
    suspend fun backfillFromLog(logLines: List<String>): Int {
        val existing = lines().toHashSet()
        val fresh = logLines.filterNot { it in existing }
        if (fresh.isEmpty()) return 0
        withContext(Dispatchers.IO) {
            runCatching { file.appendText(fresh.joinToString("\n", postfix = "\n")) }
        }
        return fresh.size
    }

    private companion object {
        const val QUEUE_FILE = "pending.log"
        const val MAX_BYTES = 2L * 1024 * 1024
        const val MAX_LINES = 8_000
    }
}
