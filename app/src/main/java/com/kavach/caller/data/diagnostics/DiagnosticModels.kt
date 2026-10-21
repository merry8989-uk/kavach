package com.kavach.caller.data.diagnostics

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One line in the diagnostics log. */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
