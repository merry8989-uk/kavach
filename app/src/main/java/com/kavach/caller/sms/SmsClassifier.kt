package com.kavach.caller.sms

/**
 * Sorts a message into personal / transactional / promotional / spam, and
 * detects one-time passwords.
 *
 * Everything here runs on the device, on the text as it is read, and nothing is
 * uploaded. The spec is explicit — "do not collect SMS content unnecessarily"
 * and "do not upload messages to the backend by default" — so the classifier
 * returns a **category and reasons**, and the caller is expected to log the
 * category only. The message body must never reach the diagnostics log, and
 * neither must the OTP code.
 *
 * It is pure on purpose: no Android, no IO, no clock. That makes every rule
 * below testable, which matters because a wrong "spam" verdict on a real bank
 * message is the failure this feature must not have.
 */
object SmsClassifier {

    enum class Category {
        /** A one-time password. Handled specially: never shown in a preview. */
        OTP,

        /** Bank, delivery, utility, ticket — a service the user is party to. */
        TRANSACTIONAL,

        /** Marketing. Legitimate, but not wanted by most people. */
        PROMOTIONAL,

        /** A real person. */
        PERSONAL,

        /** Looks like an attempt to defraud. */
        SUSPECTED_SPAM,

        UNKNOWN,
    }

    data class Result(
        val category: Category,
        val /** 0-100. Only meaningful for [Category.SUSPECTED_SPAM]. */ spamScore: Int,
        val reasons: List<String>,
        val links: List<LinkInspector.Finding>,
        /**
         * The extracted one-time code, when one was found.
         *
         * Kept separate from [reasons] so the UI can offer "copy the code"
         * without ever rendering it. It is never logged.
         */
        val otpCode: String?,
        /** Minutes of validity, when the message states it. */
        val otpValidMinutes: Int?,
    ) {
        val isSpam: Boolean get() = category == Category.SUSPECTED_SPAM
        val hasLinkRisk: Boolean get() = links.any { it.risks.isNotEmpty() }

        /** The line shown under a thread. Never includes the code. */
        val preview: String
            get() = when (category) {
                Category.OTP -> "One-time password" + (
                    otpValidMinutes?.let { " · valid $it min" } ?: ""
                    )
                else -> ""
            }
    }

    // ---------------- signals ----------------

    private val OTP_WORDS = listOf(
        "otp", "one time password", "one-time password", "verification code",
        "security code", "login code", "authentication code", "secret code",
        "passcode", "login otp",
    )

    private val OTP_PROTECTORS = listOf(
        "do not share", "don't share", "never share", "not share this", "do not disclose",
        "keep it confidential", "nobody", "no one",
    )

    private val OTP_VALIDITY = Regex(
        """(?i)valid\s+(?:for\s+)?(\d{1,3})\s*(?:min|minute|minutes|mins)""",
    )

    private val TRANSACTIONAL_WORDS = listOf(
        "debited", "credited", "withdrawn", "transaction", "a/c", "acct", "account balance",
        "avl bal", "available balance", "upi", "neft", "imps", "rtgs", "emi", "statement",
        "invoice", "bill due", "payment of", "has been sent", "has been received",
        "dispatch", "out for delivery", "delivered", "shipment", "awb", "order",
        "booked", "ticket", "pnr", "booking confirm", "refund of", "reversed",
        "due date", "minimum due", "policy", "premium", "recharge successful",
    )

    private val PROMOTIONAL_WORDS = listOf(
        "unsubscribe", "opt out", "opt-out", "offer", "sale", "discount", "% off",
        "flat off", "deal", "coupon", "promo", "limited period", "t&c", "buy now",
        "shop now", "cashback offer", "lowest price", "free gift", "exclusive offer",
        "hurry", "last chance", "save up to", "starting at", "new arrival",
    )

    private val PRIZE_WORDS = listOf(
        "you have won", "you've won", "winner", "lottery", "lucky draw", "jackpot",
        "congratulations you", "congrats you", "prize money", "claim your prize",
        "crore", "lakh rupees", "lakhs", "free iphone", "free gift worth",
        "selected as winner", "kbc", "lucky winner",
    )

    private val THREAT_WORDS = listOf(
        "kyc", "blocked", "block your", "will be blocked", "suspended", "deactivated",
        "deactivate", "closed within", "expire today", "expiring today", "last warning",
        "final notice", "immediate action", "urgent action", "avoid suspension",
        "reactivate", "re-activate", "unblock", "penalty", "legal action",
    )

    private val JOB_LOAN_WORDS = listOf(
        "work from home", "earn from home", "part time job", "part-time job",
        "data entry job", "typing job", "instant loan", "pre-approved loan",
        "personal loan approved", "no documents", "no cibil", "guaranteed income",
        "daily income", "earn daily", "online job", "home based job",
    )

    private val CODE_REGEX = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    private val AMOUNT_REGEX = Regex("""(?i)(?:rs\.?|inr|₹)\s?[\d,]+(?:\.\d{1,2})?""")

    /**
     * Classify [body] from [address].
     *
     * @param isContact the sender is in the user's contacts.
     */
    fun classify(
        body: String,
        address: String? = null,
        isContact: Boolean = false,
    ): Result {
        val text = body.lowercase()
        val sender = SenderId.parse(address)
        val links = LinkInspector.inspect(body)
        val reasons = mutableListOf<String>()

        // ---------------- OTP ----------------
        val otpWord = OTP_WORDS.firstOrNull { text.contains(it) }
        val code = CODE_REGEX.find(body)?.groupValues?.get(1)
        val hasProtector = OTP_PROTECTORS.any { text.contains(it) }
        val validity = OTP_VALIDITY.find(body)?.groupValues?.get(1)?.toIntOrNull()

        val otpEvidence = (if (otpWord != null) 1 else 0) +
            (if (hasProtector) 1 else 0) +
            (if (validity != null) 1 else 0)

        // An OTP needs a code and real supporting evidence. A message that merely
        // says "OTP" without a number is not one, and treating it as one would
        // hide a genuine scam behind an OTP label.
        if (code != null && otpWord != null && otpEvidence >= 2) {
            if (hasProtector) reasons += "says the code must not be shared"
            if (validity != null) reasons += "states a $validity minute validity"
            return Result(
                category = Category.OTP,
                spamScore = 0,
                reasons = reasons,
                links = links,
                otpCode = code,
                otpValidMinutes = validity,
            )
        }

        // ---------------- spam ----------------
        var score = 0

        // Each family of signals scales with how many distinct phrases matched.
        // A flat score per family was wrong: a message saying "earn from home",
        // "part time job", "daily income" and "no documents" is four independent
        // pieces of evidence, and scoring it the same as one was the difference
        // between catching it and letting it through.
        val prize = PRIZE_WORDS.filter { text.contains(it) }
        if (prize.isNotEmpty()) {
            score += (35 + (prize.size - 1) * 10).coerceAtMost(55)
            reasons += "uses prize or lottery language"
        }

        val threat = THREAT_WORDS.filter { text.contains(it) }
        if (threat.isNotEmpty()) {
            // A threat plus a link is the classic KYC scam. A threat alone is
            // often a real bank, so it scores much lower on its own.
            val base = if (links.isNotEmpty()) 35 else 18
            score += (base + (threat.size - 1) * 5).coerceAtMost(45)
            reasons += "threatens that an account will be blocked or closed"
        }

        val jobLoan = JOB_LOAN_WORDS.filter { text.contains(it) }
        if (jobLoan.isNotEmpty()) {
            score += (30 + (jobLoan.size - 1) * 8).coerceAtMost(50)
            reasons += "promises income or a loan with no checks"
        }

        val riskyLinks = links.filter { it.risks.isNotEmpty() }
        if (riskyLinks.isNotEmpty()) {
            score += (riskyLinks.size * 15).coerceAtMost(35)
            riskyLinks.flatMap { it.explanations }.distinct().take(2).forEach { reasons += it }
        }

        // Urgency is a signal only when it is combined with something else.
        val urgent = listOf("immediately", "within 24 hours", "today itself", "act now", "urgent")
            .count { text.contains(it) }
        if (urgent > 0 && score > 0) {
            score += 10
            reasons += "presses for immediate action"
        }

        // An unknown commercial sender asking for something is more suspicious
        // than a bank the user already deals with.
        if (!isContact && sender.kind == SenderId.Kind.COMMERCIAL && riskyLinks.isNotEmpty()) {
            score += 10
        }

        // Never call a saved contact's message spam on text alone.
        if (isContact) score = (score - 40).coerceAtLeast(0)

        if (score >= SPAM_THRESHOLD) {
            return Result(Category.SUSPECTED_SPAM, score.coerceIn(0, 100), reasons, links, null, null)
        }

        // ---------------- transactional ----------------
        val transactional = TRANSACTIONAL_WORDS.filter { text.contains(it) }
        val hasAmount = AMOUNT_REGEX.containsMatchIn(body)
        if (transactional.isNotEmpty() || (hasAmount && sender.kind != SenderId.Kind.PHONE)) {
            reasons += "reads like a service message"
            return Result(Category.TRANSACTIONAL, score, reasons, links, null, null)
        }

        // ---------------- promotional ----------------
        val promo = PROMOTIONAL_WORDS.filter { text.contains(it) }
        if (promo.isNotEmpty()) {
            reasons += "contains marketing language"
            return Result(Category.PROMOTIONAL, score, reasons, links, null, null)
        }

        // ---------------- personal ----------------
        if (isContact || sender.kind == SenderId.Kind.PHONE) {
            return Result(Category.PERSONAL, score, reasons, links, null, null)
        }

        // A commercial sender with no matching signal is still not personal.
        if (sender.kind == SenderId.Kind.COMMERCIAL || sender.kind == SenderId.Kind.SHORTCODE) {
            return Result(Category.UNKNOWN, score, reasons, links, null, null)
        }

        return Result(Category.UNKNOWN, score, reasons, links, null, null)
    }

    /** Score at or above which a message is called suspected spam. */
    const val SPAM_THRESHOLD = 50

    /**
     * The text to show in a notification or a thread preview.
     *
     * An OTP body is replaced entirely: the code must not sit on a lock screen,
     * and it must not sit in the app's own log either.
     */
    fun safePreview(body: String, category: Category): String = when (category) {
        Category.OTP -> "One-time password — tap to copy the code"
        else -> body
    }
}
