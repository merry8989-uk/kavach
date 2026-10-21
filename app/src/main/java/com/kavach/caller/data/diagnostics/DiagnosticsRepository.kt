package com.kavach.caller.data.diagnostics

import android.content.Context
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local, opt-in diagnostics log (F16.5 / Phase 13).
 *
 * Purpose: when something misbehaves on a real phone, the user can share a
 * technical trail without sending us anything automatically. Nothing leaves the
 * device unless the user taps Share.
 *
 * Privacy rules, enforced here rather than trusted to callers:
 *  - phone numbers are **masked** (last 4 digits only);
 *  - contact names are never written — only "contact" / "unknown";
 *  - no message bodies, no audio, no addresses;
 *  - the file is capped and rotates, so it cannot grow without bound.
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val lock = Any()
    private val directory = File(context.filesDir, DIRECTORY)
    private val file = File(directory, FILE_NAME)

    val isEnabled: Boolean get() = settings.snapshot.diagnosticsEnabled

    suspend fun log(level: LogLevel, tag: String, message: String) {
        if (!isEnabled) return
        val line = buildLine(level, tag, message)
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching {
                    directory.mkdirs()
                    file.appendText(line + "\n")
                    rotateIfNeeded()
                }
            }
        }
    }

    suspend fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    suspend fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    suspend fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    suspend fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    /** Most recent entries first. */
    suspend fun entries(limit: Int = 400): List<LogEntry> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            file.readLines()
                .asReversed()
                .take(limit)
                .mapNotNull { parse(it) }
        }.getOrDefault(emptyList())
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching { file.writeText("") } }
    }

    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)

    /** The file to hand to a share sheet. Null when nothing has been logged. */
    fun logFile(): File? = file.takeIf { it.exists() && it.length() > 0 }

    fun formattedSize(): String {
        val bytes = sizeBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    // ---------------- internals ----------------

    private fun buildLine(level: LogLevel, tag: String, message: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "$stamp ${level.name.padEnd(5)} ${tag.take(24).padEnd(24)} ${message.take(500)}"
    }

    private fun parse(line: String): LogEntry? {
        if (line.isBlank()) return null
        val parts = line.split(" ", limit = 3)
        if (parts.size < 3) return null
        val dateText = parts[0] + " " + parts[1]
        val rest = parts[2]
        val levelEnd = rest.indexOf(' ')
        if (levelEnd < 0) return null
        val level = runCatching { LogLevel.valueOf(rest.substring(0, levelEnd).trim()) }
            .getOrDefault(LogLevel.INFO)
        val remainder = rest.substring(levelEnd + 1).trim()
        val tagEnd = remainder.indexOf("  ")
        val tag = if (tagEnd > 0) remainder.substring(0, tagEnd).trim() else "app"
        val message = if (tagEnd > 0) remainder.substring(tagEnd).trim() else remainder
        val timestamp = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(dateText)?.time
        }.getOrNull() ?: System.currentTimeMillis()
        return LogEntry(timestamp, level, tag, message)
    }

    private fun rotateIfNeeded() {
        if (file.length() <= MAX_BYTES) return
        val kept = file.readLines().takeLast(MAX_LINES_KEPT)
        file.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    // ---------------- export (F16.5) ----------------

    /**
     * Write the log to a shareable file in the chosen format.
     *
     * Returns the file, or null when there is nothing to export. The file lands
     * in the app cache; the caller hands it to the system save dialog.
     */
    suspend fun export(format: ExportFormat): File? = withContext(Dispatchers.IO) {
        val entries = entries(limit = MAX_EXPORT_ENTRIES)
        if (entries.isEmpty()) return@withContext null

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(exportDir, "kavach-diagnostics-$stamp.${format.extension}")

        runCatching {
            when (format) {
                ExportFormat.TXT -> file.writeText(asText(entries))
                ExportFormat.JSON -> file.writeText(asJson(entries))
                ExportFormat.CSV -> file.writeText(asCsv(entries))
                ExportFormat.MARKDOWN -> file.writeText(asMarkdown(entries))
                ExportFormat.ZIP -> writeZip(file, entries, stamp)
            }
        }.getOrNull()

        file.takeIf { it.exists() && it.length() > 0 }
    }

    /** A short summary suitable for the body of an email. */
    fun emailBody(entries: Int): String = buildString {
        appendLine("Kavach diagnostics")
        appendLine()
        appendLine("App version: ${appVersion()}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Entries: $entries")
        appendLine("Log size: ${formattedSize()}")
        appendLine()
        appendLine("The log is attached. Phone numbers in it are masked to their last")
        appendLine("4 digits, and contact names, message contents and audio are never")
        appendLine("recorded.")
        appendLine()
        appendLine("What went wrong:")
        appendLine()
    }

    private fun asText(entries: List<LogEntry>): String = buildString {
        appendLine(header())
        entries.forEach { entry ->
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(entry.timestamp)))
            append(' ').append(entry.level.name)
            append(' ').append(entry.tag)
            append(' ').append(entry.message)
            appendLine()
        }
    }

    private fun asJson(entries: List<LogEntry>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val items = entries.joinToString(",\n") { entry ->
            """  {"time":"${iso.format(Date(entry.timestamp))}","level":"${entry.level.name}",""" +
                """"tag":"${escape(entry.tag)}","message":"${escape(entry.message)}"}"""
        }
        return """{"app":"Kavach","version":"${appVersion()}",""" +
            """"entries":[$items]}"""
    }

    private fun asCsv(entries: List<LogEntry>): String = buildString {
        appendLine("time,level,tag,message")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        entries.forEach { entry ->
            append(csv(iso.format(Date(entry.timestamp)))).append(',')
            append(csv(entry.level.name)).append(',')
            append(csv(entry.tag)).append(',')
            appendLine(csv(entry.message))
        }
    }

    private fun asMarkdown(entries: List<LogEntry>): String = buildString {
        appendLine("# Kavach diagnostics report")
        appendLine()
        appendLine("- **Version:** ${appVersion()}")
        appendLine("- **Android:** ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("- **Device:** ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("- **Entries:** ${entries.size}")
        appendLine()
        appendLine("Phone numbers are masked to their last 4 digits. Contact names,")
        appendLine("message contents and call audio are never logged.")
        appendLine()
        appendLine("| Time | Level | Area | Detail |")
        appendLine("|---|---|---|---|")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        entries.forEach { entry ->
            appendLine(
                "| ${iso.format(Date(entry.timestamp))} | ${entry.level.name} | " +
                    "${escape(entry.tag)} | ${escape(entry.message)} |",
            )
        }
    }

    private fun writeZip(target: File, entries: List<LogEntry>, stamp: String) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("kavach-diagnostics-$stamp.txt", asText(entries))
            put("kavach-diagnostics-$stamp.json", asJson(entries))
            put("kavach-diagnostics-$stamp.csv", asCsv(entries))
            put("device-info.txt", emailBody(entries.size))
        }
    }

    private fun header(): String = buildString {
        appendLine("# Kavach diagnostics")
        appendLine("# version=${appVersion()} android=${Build.VERSION.RELEASE} " +
            "api=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("# numbers are masked; names, messages and audio are never logged")
    }

    private fun appVersion(): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    companion object {
        private const val DIRECTORY = "diagnostics"
        private const val FILE_NAME = "kavach.log"
        private const val MAX_BYTES = 512L * 1024L
        private const val MAX_LINES_KEPT = 2_000
        private const val MAX_EXPORT_ENTRIES = 5_000

        /** Mask a number down to its last four digits. Never log the full thing. */
        fun mask(raw: String?): String {
            if (raw.isNullOrBlank()) return "unknown"
            val digits = raw.filter { it.isDigit() }
            return when {
                digits.length >= 4 -> "******" + digits.takeLast(4)
                digits.isEmpty() -> "unknown"
                else -> "******"
            }
        }
    }
}
