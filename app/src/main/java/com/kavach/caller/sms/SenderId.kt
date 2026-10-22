package com.kavach.caller.sms

/**
 * Works out what kind of thing sent a message.
 *
 * In India a message rarely arrives from a plain phone number. Commercial
 * traffic comes from a **sender ID**: an optional two-letter operator prefix, a
 * hyphen, then a six-character header — `VM-HDFCBK`, `AD-AMZNIN`. Those headers
 * are registered, which is why they are a much stronger signal than the text.
 *
 * What this does and does not do, stated plainly:
 *
 *  - It **parses the shape** of a sender ID, which is structural and reliable.
 *  - It **recognises a fixed list of well-known headers**. That list is real and
 *    verifiable, but it is not the whole DLT registry, so an unrecognised header
 *    is reported as [Kind.COMMERCIAL] without a guessed brand.
 *  - It does **not** claim to decode TRAI's category flags, because the letter
 *    that would carry that meaning is not consistent across operators and
 *    guessing it would mislabel real bank messages as spam.
 *
 * The category decision therefore comes mostly from content, in
 * [SmsClassifier], where the evidence is actually in front of us.
 */
object SenderId {

    enum class Kind {
        /** A real phone number, possibly with a country code. */
        PHONE,

        /** A registered alphanumeric header, e.g. `VM-HDFCBK` or `HDFCBK`. */
        COMMERCIAL,

        /** A short code, e.g. `56161`. Used by banks and by scams alike. */
        SHORTCODE,

        /** An email-to-SMS gateway. */
        EMAIL,

        UNKNOWN,
    }

    data class Parsed(
        val kind: Kind,
        val /** The two-letter operator prefix, when present. */ operatorPrefix: String?,
        val /** The header or number itself, without the prefix. */ header: String,
        /** A known organisation name, only when the header is on the list. */
        val knownBrand: String?,
    )

    /**
     * Headers we can name with confidence.
     *
     * Deliberately short and real. A wrong entry here would tell the user a
     * scam is their bank, which is the exact failure this feature exists to
     * prevent — so anything not on this list is reported without a brand.
     */
    private val KNOWN_HEADERS = mapOf(
        "HDFCBK" to "HDFC Bank",
        "ICICIB" to "ICICI Bank",
        "SBININ" to "SBI",
        "SBIINB" to "SBI",
        "AXISBK" to "Axis Bank",
        "KOTAKB" to "Kotak Mahindra Bank",
        "PNBSMS" to "Punjab National Bank",
        "BOBTXN" to "Bank of Baroda",
        "YESBNK" to "Yes Bank",
        "IDFCFB" to "IDFC First Bank",
        "INDUSB" to "IndusInd Bank",
        "UNIONB" to "Union Bank of India",
        "CANBNK" to "Canara Bank",
        "AMZNIN" to "Amazon",
        "FLIPKRT" to "Flipkart",
        "MYNTRA" to "Myntra",
        "SWIGGY" to "Swiggy",
        "ZOMATO" to "Zomato",
        "PAYTM" to "Paytm",
        "PHONEP" to "PhonePe",
        "GOOGLE" to "Google",
        "MSFT" to "Microsoft",
        "IRCTCC" to "IRCTC",
        "DMARTIN" to "DMart",
        "BIGBSK" to "BigBasket",
        "JIOINF" to "Jio",
        "AIRTEL" to "Airtel",
        "VODAFN" to "Vi",
        "BESCOM" to "BESCOM",
        "TATAPW" to "Tata Power",
        "LICIND" to "LIC",
        "NPSIND" to "NPS",
        "NSDLDP" to "NSDL",
    )

    /** Two-letter operator prefixes seen on Indian commercial sender IDs. */
    private val OPERATOR_PREFIXES = setOf(
        "AD", "VM", "ID", "TM", "RL", "BS", "RJ", "AM", "MN", "UN", "VD", "SP", "ST", "BT", "ET", "HT", "MT", "VT", "WT",
    )

    fun parse(address: String?): Parsed {
        val raw = address?.trim().orEmpty()
        if (raw.isEmpty()) return Parsed(Kind.UNKNOWN, null, "", null)

        if (raw.contains('@')) {
            return Parsed(Kind.EMAIL, null, raw.substringBefore('@'), null)
        }

        val upper = raw.uppercase()

        // VM-HDFCBK
        if (upper.length > 3 && upper[2] == '-') {
            val prefix = upper.substring(0, 2)
            val header = upper.substring(3)
            if (prefix in OPERATOR_PREFIXES && header.length in 3..11) {
                return Parsed(Kind.COMMERCIAL, prefix, header, KNOWN_HEADERS[header])
            }
        }

        // HDFCBK — a bare alphanumeric header, no prefix.
        if (upper.all { it.isLetterOrDigit() } && upper.any { it.isLetter() } &&
            upper.length in 4..11 && !upper.all { it.isDigit() }
        ) {
            return Parsed(Kind.COMMERCIAL, null, upper, KNOWN_HEADERS[upper])
        }

        val digits = raw.filter { it.isDigit() || it == '+' }
        val bareDigits = digits.trimStart('+')

        return when {
            // Indian short codes: 3-6 digits, often 5-6.
            bareDigits.length in 3..6 -> Parsed(Kind.SHORTCODE, null, raw, null)
            bareDigits.length in 7..15 -> Parsed(Kind.PHONE, null, raw, null)
            else -> Parsed(Kind.UNKNOWN, null, raw, null)
        }
    }

    /** A display name that never invents a brand. */
    fun displayName(address: String?, contactName: String? = null): String {
        if (!contactName.isNullOrBlank()) return contactName
        val parsed = parse(address)
        return when {
            parsed.knownBrand != null -> "${parsed.knownBrand} (${parsed.header})"
            parsed.kind == Kind.COMMERCIAL -> parsed.header
            else -> address?.takeIf { it.isNotBlank() } ?: "Unknown sender"
        }
    }

    /** True when the sender could plausibly be an organisation. */
    fun looksCommercial(address: String?): Boolean =
        parse(address).kind in setOf(Kind.COMMERCIAL, Kind.SHORTCODE)
}
