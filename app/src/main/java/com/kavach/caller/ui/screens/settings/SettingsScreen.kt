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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kavach.caller.ui.components.KavachCard
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.kavach.caller.domain.DefaultAppStatus
import com.kavach.caller.domain.DefaultRoleNudge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width

/** Theme pack keys must match [com.kavach.caller.ui.theme.ThemePack] names. */
private val themePacks = listOf("AURORA", "CARBON", "DAYLIGHT", "NEON_NOIR", "DYNAMIC")

/** Must match [com.kavach.caller.ui.theme.MotionMode] and the data layer's list. */
/** Must match ScorePolicy.Mode and the data layer's AUTO_BLOCK_ACTIONS. */
private val autoBlockActions = listOf(
    "silence" to "Silence",
    "block" to "Reject",
    "warn" to "Warn only",
)

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
    onOpenCommunity: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))

        // **First, because it is the only setting here that changes what the app
        // is rather than how it behaves.** Hidden entirely once the roles are
        // held — a row saying "you already did this" wastes a tap.
        DefaultAppSection(vm)

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
        SectionHeader("Acting on the spam score")
        KavachCard {
            // The review was explicit that a score must not kill a genuine call
            // on its own. The shipped default is off, and the card says so before
            // it offers the control — a user who never changes anything should
            // know the score is not silently deciding.
            Text(
                if (s.autoBlockScore == 0) {
                    "Off. Kavach only acts on rules you have written. The spam score " +
                        "is shown to you, never enforced."
                } else {
                    "Kavach will act on the score at ${s.autoBlockScore} or above."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "A score is other people's reports plus Kavach's own rules. It is not " +
                    "evidence, so a call is never rejected on the score alone unless you " +
                    "ask for it. A number in your contacts is never acted on this way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "Threshold: " + if (s.autoBlockScore == 0) "off" else "${s.autoBlockScore} / 100",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = s.autoBlockScore.toFloat(),
                onValueChange = { vm.setAutoBlockScore(it.roundToInt()) },
                valueRange = 0f..100f,
                steps = 19,
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "When the score crosses it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                autoBlockActions.forEach { (value, label) ->
                    FilterChip(
                        selected = s.autoBlockAction == value,
                        onClick = { vm.setAutoBlockAction(value) },
                        label = { Text(label) },
                        // Pointless while the threshold is off, and offering a
                        // control that does nothing is the fake-toggle problem.
                        enabled = s.autoBlockScore > 0,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Self-hosted lookup")
        KavachCard {
            // Said first, because the whole point of this section is narrow and
            // a user who expected "connect everything to a server" should find
            // out immediately that this is not that.
            Text(
                "Optional. With a server, Kavach can put a name to a number that " +
                    "is not in your contacts, and show how many people have reported it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Everything else — screening, blocking, the dialer, contacts, " +
                    "recording, messages — works without one. Your diagnostics log " +
                    "is never sent to this or any server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            var url by remember { mutableStateOf(s.backendBaseUrl) }
            var token by remember { mutableStateOf("") }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; vm.setServerUrl(it) },
                label = { Text("Server URL") },
                placeholder = { Text("https://kavach.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; vm.setServerToken(it) },
                label = { Text("API token") },
                // Never echoed back: the field starts empty even when a token is
                // stored, and the card says whether one is set.
                placeholder = { Text(if (vm.hasServerToken()) "A token is saved" else "Paste the token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.testServerConnection(url, token.ifBlank { "" }) },
                    enabled = !vm.serverTesting && url.isNotBlank(),
                ) { Text(if (vm.serverTesting) "Testing…" else "Test connection") }

                if (vm.hasServerToken()) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { token = ""; vm.clearServerToken() }) {
                        Text("Forget token")
                    }
                }
            }

            vm.serverTestResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Running your own server takes one command — see SELF-HOST.md in the repository.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        SectionHeader("Backup")
        KavachCard {
            Text(
                "Backs up your block rules, Kavach's own contact fields and your " +
                    "settings. Not call history, not recordings, not the server token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The file is encrypted with a passphrase you choose. It is not " +
                    "stored anywhere — if you lose it, the backup cannot be opened.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            var passphrase by remember { mutableStateOf("") }
            var mode by remember { mutableStateOf<String?>(null) }
            val context = LocalContext.current

            val saveLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri -> if (uri != null) vm.backup(uri, passphrase) }

            val openLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> if (uri != null) vm.restore(uri, passphrase) }

            Row {
                OutlinedButton(
                    onClick = { mode = "backup" },
                    modifier = Modifier.weight(1f),
                ) { Text("Back up") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { mode = "restore" },
                    modifier = Modifier.weight(1f),
                ) { Text("Restore") }
            }

            if (mode != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (passphrase.isNotBlank()) {
                            if (mode == "backup") {
                                saveLauncher.launch("kavach-backup.kvbk")
                            } else {
                                openLauncher.launch(arrayOf("*/*"))
                            }
                            mode = null
                        }
                    },
                    enabled = passphrase.isNotBlank(),
                ) { Text(if (mode == "backup") "Choose where to save" else "Choose the file") }
            }

            vm.backupMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = onOpenCommunity, modifier = Modifier.fillMaxWidth()) {
            Text("Community reports on my callers")
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


/**
 * "Set as default", shown only while it is not.
 *
 * The rules live in [com.kavach.caller.domain.DefaultRoleNudge]; this is the
 * screen half. Two buttons rather than one, because "later" is a real answer and
 * a single button turns it into "not now, and be asked again on every open".
 */
@Composable
private fun DefaultAppSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    // Recomputed when the screen is revisited, which is when it can have changed.
    var isDefault by remember { mutableStateOf(DefaultAppStatus.isDefault(context)) }
    var launcherFor by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The result tells us nothing useful - the user may have granted the role
        // and cancelled the sheet - so the roles are re-read rather than trusted.
        isDefault = DefaultAppStatus.isDefault(context)
    }

    LaunchedEffect(launcherFor) {
        val role = launcherFor ?: return@LaunchedEffect
        DefaultAppStatus.requestIntent(context, role)?.let { launcher.launch(it) }
        launcherFor = null
    }

    // Becoming the default cancels any scheduled reminder.
    LaunchedEffect(isDefault) {
        if (isDefault) vm.clearDefaultNudge()
    }

    if (!DefaultRoleNudge.shouldShowRow(isDefault)) return

    SectionHeader("Default app")
    KavachCard {
        Text(
            "Kavach is not your default dialer yet.",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Until it is, the in-call screen, caller identification and call " +
                "recording cannot work — Android only gives those to the default app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        val missing = DefaultAppStatus.missing(context)
        missing.forEach { role ->
            Text(
                "Still needed: ${role.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row {
            Button(
                onClick = {
                    // Ask for the first missing role; the next open asks for the
                    // next one. Asking for two at once is two system sheets in a
                    // row, which people abandon.
                    launcherFor = missing.firstOrNull()?.id
                },
                modifier = Modifier.weight(1f),
            ) { Text("Set as default") }

            Spacer(Modifier.width(10.dp))

            OutlinedButton(
                onClick = { vm.setDefaultLater() },
                modifier = Modifier.weight(1f),
            ) { Text("Later") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Choosing Later asks again in two days.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
