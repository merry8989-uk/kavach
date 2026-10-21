package com.kavach.caller.domain.model

/** How a lookup target is opened. */
enum class LookupStrategy {
    /** WhatsApp universal link (also opens WhatsApp Business when forced). */
    WHATSAPP,
    /** Telegram universal link for a phone number. */
    TELEGRAM,
    /** Mail composer pre-filled with the number. */
    EMAIL,
    /** A web search for the number. */
    WEB_SEARCH,
    /** Truecaller's public web search. */
    TRUECALLER,
    /** Launch the app if installed, otherwise offer the store listing. */
    APP_OR_STORE,
}

/**
 * A place a user can look a number up to try to learn who it belongs to (F9.7).
 *
 * Only strategies with a documented public link are used for the big apps;
 * anything without one is honestly labelled "if installed".
 */
enum class LookupTarget(
    val title: String,
    val subtitle: String,
    val strategy: LookupStrategy,
    /**
     * Package to force / check. For [LookupStrategy.APP_OR_STORE] these are
     * best-effort and unverified — see the note in [com.kavach.caller.util.LookupLauncher].
     */
    val packageName: String? = null,
) {
    WHATSAPP(
        title = "WhatsApp",
        subtitle = "Opens a chat if this number is on WhatsApp",
        strategy = LookupStrategy.WHATSAPP,
        packageName = "com.whatsapp",
    ),
    WHATSAPP_BUSINESS(
        title = "WhatsApp Business",
        subtitle = "Same lookup, forced through WhatsApp Business",
        strategy = LookupStrategy.WHATSAPP,
        packageName = "com.whatsapp.w4b",
    ),
    TELEGRAM(
        title = "Telegram",
        subtitle = "Opens the number in Telegram",
        strategy = LookupStrategy.TELEGRAM,
        packageName = "org.telegram.messenger",
    ),
    ARATTAI(
        title = "Arattai",
        subtitle = "Opens Arattai if it is installed",
        strategy = LookupStrategy.APP_OR_STORE,
        packageName = "com.zoho.arattai",
    ),
    HYPED(
        title = "Hyped",
        subtitle = "Opens Hyped if it is installed",
        strategy = LookupStrategy.APP_OR_STORE,
        packageName = "com.hyped.app",
    ),
    EMAIL(
        title = "Email",
        subtitle = "Compose a mail with this number in it",
        strategy = LookupStrategy.EMAIL,
    ),
    GOOGLE(
        title = "Search on Google",
        subtitle = "Web search for this number",
        strategy = LookupStrategy.WEB_SEARCH,
    ),
    TRUECALLER(
        title = "Search on Truecaller",
        subtitle = "Truecaller's public web search for this number",
        strategy = LookupStrategy.TRUECALLER,
    ),
}
