package com.kavach.caller.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kavach.caller.domain.model.LookupStrategy
import com.kavach.caller.domain.model.LookupTarget

/**
 * Opens a number in another app or a web search, so the user can try to find out
 * who is calling (F9.7).
 *
 * Honesty notes:
 *  - WhatsApp, Telegram, mail, Google and Truecaller use their documented public
 *    links, so they work without Kavach knowing anything about the target app.
 *  - Arattai and Hyped have no documented public deep link, so they are handled
 *    as "open if installed, else show the store listing". Their package names are
 *    best-effort and **need checking on a real device**.
 *  - Every failure is reported back so the UI can say what happened instead of
 *    appearing to do nothing.
 */
object LookupLauncher {

    sealed interface Result {
        data object Opened : Result
        data class Failed(val reason: String) : Result
    }

    /** ISO country code for Truecaller's search path. Defaults to India. */
    private val countryForCallingCode = mapOf(
        "1" to "us", "7" to "ru", "20" to "eg", "27" to "za", "33" to "fr", "34" to "es",
        "39" to "it", "44" to "gb", "49" to "de", "60" to "my", "61" to "au", "62" to "id",
        "63" to "ph", "65" to "sg", "66" to "th", "81" to "jp", "82" to "kr", "84" to "vn",
        "86" to "cn", "91" to "in", "92" to "pk", "94" to "lk", "95" to "mm", "977" to "np",
        "880" to "bd", "966" to "sa", "971" to "ae", "973" to "bh", "974" to "qa",
        "965" to "kw", "968" to "om", "234" to "ng", "254" to "ke",
    )

    fun open(context: Context, target: LookupTarget, rawNumber: String): Result {
        val digits = PhoneUtils.toE164(rawNumber).trimStart('+')
        if (digits.isEmpty()) return Result.Failed("No number to look up.")

        val intent = when (target.strategy) {
            LookupStrategy.WHATSAPP -> view("https://wa.me/$digits").withPackage(target.packageName)
            LookupStrategy.TELEGRAM -> view("https://t.me/+$digits").withPackage(target.packageName)
            LookupStrategy.EMAIL -> mailIntent(rawNumber)
            LookupStrategy.WEB_SEARCH -> view(
                "https://www.google.com/search?q=" + Uri.encode(PhoneUtils.pretty(rawNumber)),
            )
            LookupStrategy.TRUECALLER -> view(truecallerUrl(digits))
            LookupStrategy.APP_OR_STORE -> appOrStore(context, target)
        } ?: return Result.Failed("Could not build a link for ${target.title}.")

        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (t: Throwable) {
            Result.Failed("${target.title} isn't installed on this phone.")
        }
    }

    private fun view(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun Intent.withPackage(pkg: String?): Intent = apply {
        // Forcing the package makes the WhatsApp Business entry open the right app;
        // if it isn't installed the caller falls back to the error path.
        if (pkg != null) setPackage(pkg)
    }

    private fun mailIntent(rawNumber: String): Intent {
        val uri = Uri.parse(
            "mailto:?subject=" + Uri.encode("Caller lookup") +
                "&body=" + Uri.encode(PhoneUtils.pretty(rawNumber)),
        )
        return Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun truecallerUrl(digits: String): String {
        val country = countryForCallingCode.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { digits.startsWith(it.key) }
            ?.value
            ?: "in"
        return "https://www.truecaller.com/search/$country/$digits"
    }

    private fun appOrStore(context: Context, target: LookupTarget): Intent? {
        val pkg = target.packageName ?: return null
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Not installed — send them to the store listing instead of failing silently.
        return view(
            "https://play.google.com/store/search?q=" + Uri.encode(target.title) + "&c=apps",
        )
    }
}
