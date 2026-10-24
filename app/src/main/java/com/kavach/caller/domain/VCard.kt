package com.kavach.caller.domain

import com.kavach.caller.data.local.entity.ContactEntity

/**
 * A contact as a vCard.
 *
 * vCard 3.0 rather than 4.0, because 3.0 is what the widest range of phone
 * address books and messaging apps will actually import. A newer version that
 * half the receiving apps ignore is not a better export.
 *
 * Escaping is not optional here. A semicolon or a comma in a name or a note is
 * ordinary, and an unescaped one silently splits the field — so a contact called
 * "Sharma, Amit" arrives as two contacts, or as one called "Sharma".
 */
fun ContactEntity.toVCard(): String = buildString {
    appendLine("BEGIN:VCARD")
    appendLine("VERSION:3.0")

    // N is structured (family;given;middle;prefix;suffix) and FN is the display
    // form. We only hold one name, so it goes in FN and into the family slot,
    // which is what most importers fall back to.
    appendLine("FN:${vcardEscape(displayName)}")
    appendLine("N:${vcardEscape(displayName)};;;;")

    company?.takeIf { it.isNotBlank() }?.let { appendLine("ORG:${vcardEscape(it)}") }
    jobTitle?.takeIf { it.isNotBlank() }?.let { appendLine("TITLE:${vcardEscape(it)}") }
    notes?.takeIf { it.isNotBlank() }?.let { appendLine("NOTE:${vcardEscape(it)}") }

    numbers.forEach { number ->
        val type = when (number.label.lowercase()) {
            "mobile", "cell" -> "CELL"
            "home" -> "HOME"
            "work" -> "WORK"
            "fax" -> "FAX"
            else -> "VOICE"
        }
        appendLine("TEL;TYPE=$type:${vcardEscape(number.number)}")
    }

    appendLine("END:VCARD")
}

/**
 * Escape a value for a vCard field.
 *
 * Backslash first, then the separators — escaping the backslash after the others
 * would double-escape the ones just added.
 */
internal fun vcardEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")
    .replace("\r", "\\n")
