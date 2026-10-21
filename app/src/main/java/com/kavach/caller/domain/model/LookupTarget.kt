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
    /**
     * A caller-ID website that has **no** URL that carries a number, so we open
     * it and copy the number to the clipboard for a one-tap paste.
     */
    SITE_WITH_CLIPBOARD,
    /** Launch the app if installed, otherwise offer the store listing. */
    APP_OR_STORE,
    /**
     * Launch the installed app; if it is absent, open its verified web link
     * instead of a store listing. Either way the number is copied, because
     * these apps have no documented link that carries a phone number.
     */
    APP_THEN_SITE,
}

/**
 * A place a user can look a number up to try to learn who it belongs to (F9.9).
 *
 * Only strategies with a documented public link are used for the big apps;
 * anything without one is honestly labelled.
 */
enum class LookupTarget(
    val title: String,
    val subtitle: String,
    val strategy: LookupStrategy,
    /**
     * Package to force / check. For [LookupStrategy.APP_OR_STORE] these are
     * best-effort and unverified — see [com.kavach.caller.util.LookupLauncher].
     */
    val packageName: String? = null,
    /** Only used by [LookupStrategy.SITE_WITH_CLIPBOARD]. */
    val siteUrl: String? = null,
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
        subtitle = "Opens Arattai and copies the number — paste it in the chat search",
        strategy = LookupStrategy.APP_THEN_SITE,
        // Verified package (Arattai Messenger by Zoho). The earlier
        // "com.zoho.arattai" guess was wrong.
        packageName = "com.aratai.chat",
        // Verified link domains, used when the app is not installed.
        siteUrl = "https://web.arattai.in/",
    ),
    HYPED(
        title = "Hyped",
        subtitle = "Opens Hyped and copies the number — Hyped hides numbers by design",
        strategy = LookupStrategy.APP_THEN_SITE,
        // Verified package (Hyped by Anuvadini AI). The earlier
        // "com.hyped.app" guess was wrong.
        packageName = "com.friendsV",
        siteUrl = "https://samvadiniprod.anuvadiniturn.com/",
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
    CALLER_NAME(
        title = "Caller Name (truecallers.in)",
        subtitle = "Opens the site and copies the number — paste it in their search box",
        strategy = LookupStrategy.SITE_WITH_CLIPBOARD,
        siteUrl = "https://truecallers.in/",
    ),
}
