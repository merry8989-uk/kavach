package com.kavach.caller.domain

/**
 * Reading a vCard back.
 *
 * The export side is [toVCard]; this is the other direction, and it is written
 * against the awkward parts of the format rather than against our own output —
 * because the file being imported came from somewhere else. A parser that only
 * round-trips its own writer works exactly once, on files this app made.
 *
 * ## What this handles, and why each one matters
 *
 * - **Line folding.** vCard wraps long lines with a leading space on the
 *   continuation. A note of 200 characters arrives as four physical lines, and a
 *   parser that ignores folding turns one note into four broken properties.
 * - **Escaping.** `\,` `\;` `\n` `\\` are literal. "Sharma, Amit" is one contact;
 *   unescaped, the comma splits the field.
 * - **CRLF.** The format specifies it, and some writers emit bare LF. Both are
 *   handled, because a trailing `\r` inside a name is invisible until it is not.
 * - **Several cards in one file.** Exporting a phonebook produces one file with
 *   hundreds of `BEGIN:VCARD` blocks.
 * - **Quoted-printable.** vCard 2.1, which older phones still emit, encodes
 *   non-ASCII names this way. A Hindi name in 2.1 is unreadable without it.
 * - **Group prefixes.** `item1.TEL:` is legal; the property is `TEL`.
 * - **Parameters before the colon.** `TEL;TYPE=CELL;PREF:...` — the value is after
 *   the first colon that is not inside a parameter.
 */
object VCardReader {

    /** One contact parsed out of a file. */
    data class Parsed(
        val displayName: String,
        val numbers: List<Pair<String, String>> = emptyList(), // number to label
        val company: String? = null,
        val jobTitle: String? = null,
        val notes: String? = null,
    )

    /**
     * Parse every card in [text].
     *
     * Returns what it could read and skips what it could not. A malformed card in
     * a file of three hundred should not lose the other 299.
     */
    fun parse(text: String): List<Parsed> {
        val cards = mutableListOf<Parsed>()

        for (block in splitCards(text)) {
            val unfolded = unfold(block)
            if (unfolded.isEmpty()) continue

            var name = ""
            var company: String? = null
            var title: String? = null
            var notes: String? = null
            val numbers = mutableListOf<Pair<String, String>>()

            for (line in unfolded) {
                val property = propertyOf(line) ?: continue
                val (key, params, rawValue) = property
                val value = decodeValue(rawValue, params)
                if (value.isEmpty()) continue

                when (key) {
                    // FN is the display form and wins. N is structured and used
                    // only when FN is absent, because a card without FN is legal
                    // and its name lives in N.
                    "FN" -> if (name.isEmpty()) name = unescape(value)
                    "N" -> if (name.isEmpty()) name = familyFromStructured(value)
                    "ORG" -> company = unescape(value).trim().ifEmpty { null }
                    "TITLE" -> title = unescape(value).trim().ifEmpty { null }
                    "NOTE" -> notes = unescape(value).trim().ifEmpty { null }
                    "TEL" -> {
                        val number = normalisePhone(unescape(value))
                        if (number.isNotEmpty()) numbers += number to labelOf(params)
                    }
                }
            }

            if (name.isBlank() && numbers.isEmpty()) continue

            // A card with a number but no name is real, and worth keeping: the
            // number is the thing the user wanted. `continue` cannot live inside
            // an argument's lambda, so the fallback is resolved here.
            val display = name.trim().ifEmpty { numbers.firstOrNull()?.first.orEmpty() }
            if (display.isEmpty()) continue

            cards += Parsed(
                displayName = display,
                numbers = numbers.distinctBy { it.first },
                company = company,
                jobTitle = title,
                notes = notes,
            )
        }
        return cards
    }

    // ------------------------------------------------------------------ parts --

    /** Every `BEGIN:VCARD` … `END:VCARD` block, or the whole text if untagged. */
    private fun splitCards(text: String): List<String> {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = mutableListOf<String>()
        var current: StringBuilder? = null
        var sawBegin = false

        for (line in normalised.split('\n')) {
            val upper = line.trim().uppercase()
            when {
                upper == "BEGIN:VCARD" -> {
                    sawBegin = true
                    current = StringBuilder()
                }
                upper == "END:VCARD" -> {
                    current?.let { blocks += it.toString() }
                    current = null
                }
                current != null -> current.append(line).append('\n')
            }
        }
        // A file with no BEGIN/END is still worth attempting: some exporters drop
        // the markers, and refusing the whole file over that helps nobody.
        if (blocks.isEmpty()) {
            if (sawBegin) return emptyList()
            return listOf(normalised)
        }
        return blocks
    }

    /**
     * Join continuation lines.
     *
     * A folded line starts with a single space or tab, which is removed. The space
     * is a marker, not content — keeping it inserts a stray space into the middle
     * of a name.
     */
    private fun unfold(block: String): List<String> {
        val out = mutableListOf<String>()
        for (line in block.split('\n')) {
            if (line.isEmpty()) continue
            if ((line.startsWith(' ') || line.startsWith('\t')) && out.isNotEmpty()) {
                out[out.lastIndex] = out.lastIndex.let { out[it] + line.substring(1) }
            } else {
                out += line
            }
        }
        return out
    }

    /** Split `GROUP.NAME;PARAM=x:VALUE` into its parts. */
    private fun propertyOf(line: String): Triple<String, String, String>? {
        // The value starts after the first colon that is not inside a quoted
        // parameter. `TEL;TYPE="a:b":123` is legal.
        var inQuotes = false
        var colon = -1
        for ((i, c) in line.withIndex()) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ':' && !inQuotes -> { colon = i; break }
            }
        }
        if (colon <= 0) return null

        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)

        val semicolon = head.indexOf(';')
        val rawName = if (semicolon >= 0) head.substring(0, semicolon) else head
        val params = if (semicolon >= 0) head.substring(semicolon + 1) else ""

        // `item1.TEL` — the group is not part of the property name.
        val name = rawName.substringAfterLast('.').trim().uppercase()
        if (name.isEmpty()) return null
        return Triple(name, params.uppercase(), value)
    }

    private fun labelOf(params: String): String = when {
        params.contains("CELL") -> "Mobile"
        params.contains("HOME") -> "Home"
        params.contains("WORK") -> "Work"
        params.contains("FAX") -> "Fax"
        else -> "Mobile"
    }

    /** Undo vCard escaping. Order matters: `\\n` must not become a newline. */
    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    '\\' -> out.append('\\')
                    ',' -> out.append(',')
                    ';' -> out.append(';')
                    else -> out.append(value[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }

    /**
     * vCard 2.1 quoted-printable, which older phones still emit for non-ASCII.
     *
     * Without this a Hindi name arrives as `=E0=A4=85=E0=A4=AE=E0=A4=BF=E0=A4=A4`
     * — present, and unreadable.
     */
    private fun decodeValue(value: String, params: String): String {
        if (!params.contains("QUOTED-PRINTABLE")) return value
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            if (value[i] == '=' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val parsed = hex.toIntOrNull(16)
                if (parsed != null) {
                    bytes += parsed.toByte()
                    i += 3
                    continue
                }
            }
            bytes += value[i].code.toByte()
            i += 1
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** `N` is `family;given;middle;prefix;suffix`; take the non-empty parts. */
    private fun familyFromStructured(value: String): String =
        value.split(';')
            .map { unescape(it).trim() }
            .filter { it.isNotEmpty() }
            .reversed() // "Sharma;Amit" reads as "Amit Sharma"
            .joinToString(" ")

    /** Keep digits and a leading plus; a TEL value can carry spaces and dashes. */
    private fun normalisePhone(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val plus = trimmed.startsWith('+')
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 6) return ""
        return if (plus) "+$digits" else digits
    }
}
