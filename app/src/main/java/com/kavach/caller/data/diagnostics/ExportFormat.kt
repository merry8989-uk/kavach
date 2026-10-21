package com.kavach.caller.data.diagnostics

/**
 * The shapes a diagnostics log can be exported in.
 *
 * Only formats that something can actually open are offered. `ExportFormat` is
 * deliberately not a free-text extension field: writing a file called
 * `logs.odn` or `logs.notes` would produce something no app on the phone can
 * read, which looks like a feature but is not one. CSV is included precisely
 * because it is what "export to Excel / Sheets" means in practice.
 */
enum class ExportFormat(
    val label: String,
    val detail: String,
    val extension: String,
    val mimeType: String,
) {
    TXT(
        label = "Plain text (.txt)",
        detail = "Readable anywhere. One line per event.",
        extension = "txt",
        mimeType = "text/plain",
    ),
    JSON(
        label = "JSON (.json)",
        detail = "Structured — best for pasting into a bug report or a script.",
        extension = "json",
        mimeType = "application/json",
    ),
    CSV(
        label = "Spreadsheet (.csv)",
        detail = "Opens in Excel, Google Sheets or Numbers.",
        extension = "csv",
        mimeType = "text/csv",
    ),
    MARKDOWN(
        label = "Report (.md)",
        detail = "A written summary followed by the entries.",
        extension = "md",
        mimeType = "text/markdown",
    ),
    ZIP(
        label = "Bundle (.zip)",
        detail = "Everything at once: log, JSON, CSV and a device summary.",
        extension = "zip",
        mimeType = "application/zip",
    ),
}
