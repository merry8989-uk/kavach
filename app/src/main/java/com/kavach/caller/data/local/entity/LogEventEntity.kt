package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One diagnostics event, stored in the app's own encrypted database.
 *
 * This replaced a capped text file, and the reason is the caps themselves.
 * The file kept the last 4,000 lines under 512 KB and the UI read the newest
 * 400, so a busy day silently discarded everything older — which is exactly the
 * day you want to read. Removing the caps without changing the store would have
 * been worse: `readLines()` loads the whole file into memory, so 100,000 events
 * is a phone that runs out of memory instead of one that loses logs.
 *
 * SQLite is what the diagnostics specification asks for, and it makes "keep
 * everything" practical: inserts are appended, reads are indexed and paged, and
 * nothing is ever loaded whole.
 *
 * **Stored in the SQLCipher database**, so the log is encrypted at rest like the
 * contacts and call log. The file it replaces was plain text on disk.
 *
 * The columns are the fields worth *filtering* on. Everything else stays inside
 * `raw` — the complete JSON line, so an export is lossless and the table does not
 * have to mirror the whole event schema.
 */
@Entity(
    tableName = "log_events",
    indices = [
        Index(value = ["ts"]),
        Index(value = ["severity"]),
        Index(value = ["category"]),
        Index(value = ["eventName"]),
        Index(value = ["sessionId"]),
        Index(value = ["requestId"]),
    ],
)
data class LogEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Event time, epoch millis. */
    val ts: Long,
    /** trace | debug | info | warn | error | fatal */
    val severity: String,
    /** One of the twelve categories. */
    val category: String,
    /** e.g. `screen_view`, `api_request`. */
    val eventName: String,
    val sessionId: String,
    val requestId: String? = null,
    val screenOrRoute: String? = null,
    /** The complete JSON line. Export reads this, so nothing is lost. */
    val raw: String,
)
