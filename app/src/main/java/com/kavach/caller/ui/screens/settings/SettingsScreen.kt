package com.kavach.caller.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kavach.caller.ui.screens.updates.UpdateSection
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R
import com.kavach.caller.i18n.LanguageStore
import com.kavach.caller.i18n.AppLanguage
import androidx.compose.ui.platform.LocalContext

/** Theme pack keys must match [com.kavach.caller.ui.theme.ThemePack] names. */
private val themePacks = listOf("AURORA", "CARBON", "DAYLIGHT", "NEON_NOIR", "DYNAMIC")

/** Must match [com.kavach.caller.ui.theme.MotionMode] and the data layer's list. */
private val motionModes = listOf(
    "system" to "Follow system",
    "full" to "Full motion",
    "reduced" to "Reduced motion",
)
private val announcerModes = listOf("ALWAYS", "KNOWN_ONLY", "UNKNOWN_ONLY", "HEADSET_ONLY", "OFF")

@Composable
fun SettingsScreen(
    onOpenBlocklist: () -> Unit = {},
    onOpenRecordings: () -> Unit = {},
    onOpenFeatures: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenSelfTest: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenReplies: () -> Unit = {},
    onOpenPolicy: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))

        SectionHeader("Modules")
        OutlinedButton(onClick = onOpenFeatures, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.features_modules))
        }
        Text(stringResource(R.string.turn_individual_modules_on_or_off_and_see_exactly_wh),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Protection")
        OutlinedButton(onClick = onOpenBlocklist, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.blocking_rules_blocklist))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenRecordings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.call_recordings))
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Caller announcer (F7)")
        Text(stringResource(R.string.announcement_mode), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            announcerModes.forEach { mode ->
                FilterChip(
                    selected = s.announcerMode == mode,
                    onClick = { vm.setAnnouncerMode(mode) },
                    label = { Text(mode.replace('_', ' ')) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        SwitchRow("Spoken spam verdict", s.announceSpamVerdict) { vm.setAnnounceSpamVerdict(it) }

        Spacer(Modifier.height(16.dp))
        SectionHeader("AI assistant (F10)")
        SwitchRow("Use cloud transcription & summaries", s.cloudAiEnabled) { vm.setCloudAiEnabled(it) }
        Text(stringResource(R.string.cloud_ai_sends_audio_to_kavach_s_servers_for_transcr),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Recording (F8)")
        Text(stringResource(R.string.recording_is_announced_to_callers_and_disabled_autom),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Appearance (F11)")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            themePacks.forEach { pack ->
                FilterChip(
                    selected = s.themePack == pack,
                    onClick = { vm.setThemePack(pack) },
                    label = { Text(pack.replace('_', ' ')) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.appearance),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        LanguagePicker()

        Spacer(Modifier.height(14.dp))
        Text(
            "Motion",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        // "System" is the default and it is the honest one: Android already has
        // this setting, in Accessibility, and the people who use it are not being
        // fussy — reduced motion is what makes a phone usable with a vestibular
        // disorder. Kavach follows it unless told otherwise.
        Text(
            "Kavach follows Android's animation setting. Choose here to override it for this app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            motionModes.forEach { (value, label) ->
                FilterChip(
                    selected = s.motionMode == value,
                    onClick = { vm.setMotionMode(value) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Updates")
        Spacer(Modifier.height(8.dp))
        UpdateSection()

        Spacer(Modifier.height(16.dp))
        SectionHeader("Data & battery")
        SwitchRow("Offline-only mode", s.offlineOnly) { vm.setOfflineOnly(it) }
        Text(
            "Stops every network call Kavach would make. Online lookup, reports and update " +
                "checks are turned off; everything on-device keeps working.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Data saver", s.dataSaver) { vm.setDataSaver(it) }
        Text(stringResource(R.string.stretches_the_background_call_log_sync_from_every_6),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Messaging")
        OutlinedButton(onClick = onOpenReplies, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.suggested_replies_auto_reply))
        }
        Text(
            "Manage the one-tap replies shown while typing, and set an automatic reply " +
                "to incoming messages.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.warnings),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        SwitchRow("Warn about suspicious links", s.smsWarnOnLinks) { vm.setSmsWarnOnLinks(it) }
        Text(stringResource(R.string.flags_shortened_links_look_alike_domains_and_unencry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SwitchRow("Warn about suspected scams", s.smsWarnOnSpam) { vm.setSmsWarnOnSpam(it) }
        Text(stringResource(R.string.flags_prize_kyc_blocking_and_work_from_home_messages),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SwitchRow("One-time password banner", s.smsShowOtpBanner) { vm.setSmsShowOtpBanner(it) }
        Text(stringResource(R.string.offers_to_copy_the_code_without_ever_displaying_it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "These control Kavach's own warnings. Changing the system notification for a " +
                "message needs the default SMS role, which Kavach does not ask for.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Your identity")
        OutlinedButton(onClick = onOpenAccount, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.my_number_consent))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenPolicy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.how_kavach_identifies_callers))
        }
        Text(
            "Verify your own number, choose whether your name is published, and read exactly " +
                "where caller names come from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Troubleshooting")
        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.diagnostics_logs))
        }
        Text(stringResource(R.string.local_technical_log_you_can_share_to_help_fix_a_prob),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSelfTest, modifier = Modifier.fillMaxWidth()) {
            Text("Self-test this device")
        }
        Text(
            "Checks what actually works here — permissions, roles, storage, encryption and " +
                "the classification rules — and lists separately what only a real call or " +
                "message can prove.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("About")
        Text(stringResource(R.string.kavach_armour_for_every_call), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.feature_requests_feedback_osintgram_io),
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Language, applied immediately.
 *
 * The choice is stored in a plain preference rather than DataStore, because it
 * has to be readable in `attachBaseContext` before anything asynchronous can be
 * awaited. Changing it recreates the activity so every screen re-resolves its
 * strings — including the ones already on the back stack.
 */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val activity = context.findActivity()
    var current by remember { mutableStateOf(LanguageStore.current(context)) }

    Text(
        stringResource(R.string.language),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        AppLanguage.entries.forEach { option ->
            FilterChip(
                selected = current == option,
                onClick = {
                    LanguageStore.set(context, option)
                    current = option
                    // Recreate rather than restart the process: the back stack
                    // survives, so the user lands back on Settings.
                    activity?.recreate()
                },
                label = { Text(option.nativeLabel) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.language_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
