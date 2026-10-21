package com.kavach.caller.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kavach.caller.domain.model.LookupStrategy
import com.kavach.caller.domain.model.LookupTarget

/**
 * Opens a number in another app or a website, so the user can try to find out
 * who is calling (F9.9).
 *
 * Honesty notes, all verified rather than assumed:
 *  - WhatsApp, Telegram and mail use their documented public links.
 *  - Google search takes the number as a query, so it works directly.
 *  - **Caller Name (truecallers.in) has no URL that carries a number.** Its
 *    search is entirely client-side — the address never changes and a `?number=`
 *    parameter is ignored (confirmed by driving the live site). So instead of
 *    faking a deep link we open the site and copy the number to the clipboard,
 *    and the UI says so.
 *  - Arattai and Hyped have no documented public deep link, so they are handled
 *    as "open if installed, else show the store listing". Their package names are
 *    best-effort and **need checking on a real device**.
 *  - Every failure is reported back so the UI can say what happened.
 */
object LookupLauncher {

    sealed interface Result {
        data object Opened : Result

        /** Opened, but the user needs to do one more thing — explained in [message]. */
        data class OpenedWithNote(val message: String) : Result

        data class Failed(val reason: String) : Result
    }

    fun open(context: Context, target: LookupTarget, rawNumber: String): Result {
        val digits = PhoneUtils.toE164(rawNumber).trimStart('+')
        if (digits.isEmpty()) return Result.Failed("No number to look up.")

        return when (target.strategy) {
            LookupStrategy.WHATSAPP -> launch(
                context, view("https://wa.me/$digits").withPackage(target.packageName), target,
            )
            LookupStrategy.TELEGRAM -> launch(
                context, view("https://t.me/+$digits").withPackage(target.packageName), target,
            )
            LookupStrategy.EMAIL -> launch(context, mailIntent(rawNumber), target)
            LookupStrategy.WEB_SEARCH -> launch(
                context,
                view("https://www.google.com/search?q=" + Uri.encode(PhoneUtils.pretty(rawNumber))),
                target,
            )
            LookupStrategy.SITE_WITH_CLIPBOARD -> openSiteAndCopy(context, target, rawNumber)
            LookupStrategy.APP_OR_STORE -> launch(context, appOrStore(context, target), target)
        }
    }

    private fun launch(context: Context, intent: Intent?, target: LookupTarget): Result {
        if (intent == null) return Result.Failed("Could not build a link for ${target.title}.")
        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (t: Throwable) {
            Result.Failed("${target.title} isn't installed on this phone.")
        }
    }

    /**
     * The site can't receive the number by URL, so we open it and put the number
     * on the clipboard, then tell the user to paste it.
     */
    private fun openSiteAndCopy(context: Context, target: LookupTarget, rawNumber: String): Result {
        val url = target.siteUrl ?: return Result.Failed("No site configured for ${target.title}.")
        val pretty = PhoneUtils.pretty(rawNumber)

        val copied = runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            if (clipboard == null) {
                false
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("phone number", pretty))
                true
            }
        }.getOrDefault(false)

        return try {
            context.startActivity(view(url))
            if (copied) {
                Result.OpenedWithNote("$pretty copied — paste it into the search box on the site.")
            } else {
                Result.OpenedWithNote("Opened the site. Type $pretty into the search box.")
            }
        } catch (t: Throwable) {
            Result.Failed("Couldn't open a browser for ${target.title}.")
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
