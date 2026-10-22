package com.kavach.caller.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The languages Kavach ships in.
 *
 * English and Hindi, chosen deliberately rather than by habit: the app is built
 * for India, and Hindi is the first language of a large share of the people who
 * would use it. More can be added by dropping another `values-<tag>` folder in
 * and adding an entry here — nothing else in the code knows the list.
 */
enum class AppLanguage(
    /** Empty means "follow the phone". */
    val tag: String,
    val label: String,
    /** Shown in the language itself, which is how people actually find theirs. */
    val nativeLabel: String,
) {
    SYSTEM("", "System default", "System default"),
    ENGLISH("en", "English", "English"),
    HINDI("hi", "Hindi", "हिन्दी"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == (tag ?: "") } ?: SYSTEM
    }
}

/**
 * Reads and writes the chosen language.
 *
 * Deliberately backed by **SharedPreferences** rather than the app's DataStore.
 * The language has to be known in `attachBaseContext`, which runs before
 * anything asynchronous can be awaited — and a `runBlocking` read of DataStore
 * there is a deadlock waiting to happen. A synchronous preference read is the
 * right tool for a value needed this early.
 */
object LanguageStore {

    private const val PREFS = "kavach_language"
    private const val KEY = "language_tag"

    fun currentTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun current(context: Context): AppLanguage = AppLanguage.fromTag(currentTag(context))

    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language.tag)
            .apply()
    }

    /**
     * Apply the chosen language to [base].
     *
     * Used from `attachBaseContext`, so every `stringResource` call in Compose
     * resolves against the right resources without any per-composable work.
     */
    fun wrap(base: Context): Context {
        val tag = currentTag(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
