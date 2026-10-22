package com.kavach.caller.ui.screens.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.account.AccountRepository
import com.kavach.caller.data.account.OtpChallenge
import com.kavach.caller.data.remote.ConsentEventDto
import com.kavach.caller.data.settings.AppSettings
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val account: AccountRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val appSettings = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    var phone by mutableStateOf("")
    var otp by mutableStateOf("")
    var name by mutableStateOf("")
    var publish by mutableStateOf(false)
    var challenge by mutableStateOf<OtpChallenge?>(null)
    var history by mutableStateOf<List<ConsentEventDto>>(emptyList())
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    val isConfigured: Boolean get() = account.isConfigured

    fun onSignIn() {
        val s = appSettings.value
        name = s.accountDisplayName
        publish = s.accountPublishName
        if (account.isSignedIn) load()
    }

    fun dismissMessage() {
        message = null
    }

    fun sendCode() = viewModelScope.launch {
        busy = true
        message = null
        account.requestOtp(phone.trim())
            .onSuccess { challenge = it; message = it.debugCode?.let { code -> "Dev code: $code" } }
            .onFailure { message = it.message }
        busy = false
    }

    fun verify() = viewModelScope.launch {
        val current = challenge ?: return@launch
        busy = true
        message = null
        account.verifyOtp(current.requestId, otp.trim())
            .onSuccess { isNew ->
                challenge = null
                otp = ""
                message = if (isNew) "Number verified. Welcome to Kavach." else "Signed in."
                onSignIn()
            }
            .onFailure { message = it.message }
        busy = false
    }

    fun saveProfile() = viewModelScope.launch {
        busy = true
        message = null
        account.saveProfile(name.trim(), publish)
            .onSuccess {
                message = if (publish) {
                    "Published. People who call you will now see \"${it.display_name}\"."
                } else {
                    "Saved. Your name is not published."
                }
            }
            .onFailure { message = it.message }
        busy = false
    }

    fun load() = viewModelScope.launch {
        account.refreshProfile()
        history = account.consentHistory()
    }

    fun signOut() = viewModelScope.launch {
        account.signOut()
        history = emptyList()
        name = ""
        publish = false
        message = "Signed out."
    }

    fun deleteAccount() = viewModelScope.launch {
        busy = true
        account.deleteAccount()
            .onSuccess {
                history = emptyList()
                name = ""
                publish = false
                message = "Account erased and your name withdrawn."
            }
            .onFailure { message = it.message }
        busy = false
    }
}

/**
 * The consent-based account (docs/CALLER-ID-PLAN.md).
 *
 * Sign in with a phone number, then decide whether to publish your own name.
 * Publishing is off until you turn it on, and turning it off withdraws the name
 * immediately.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    vm: AccountViewModel = hiltViewModel(),
) {
    val s by vm.appSettings.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.onSignIn() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(stringResource(R.string.my_number),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (!vm.isConfigured) {
                SectionHeader("Server required")
                KavachCard {
                    Text(
                        "Verifying your number and publishing your name need a Kavach server. " +
                            "Add one in Settings -> Server (advanced), or self-host the backend " +
                            "in this repository.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(32.dp))
                return@Column
            }

            vm.message?.let { text ->
                Spacer(Modifier.height(8.dp))
                KavachCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { vm.dismissMessage() }) { Text(stringResource(R.string.ok)) }
                    }
                }
            }

            if (!s.accountToken.isNotBlank()) {
                // ---------------- sign in ----------------
                SectionHeader("Verify your number")
                KavachCard {
                    Text(
                        "We send a one-time code. Your number is stored only as a hash on the " +
                            "server — never in the clear.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = vm.phone,
                        onValueChange = { vm.phone = it },
                        label = { Text(stringResource(R.string.phone_number)) },
                        placeholder = { Text(stringResource(R.string.n91_98765_43210)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.sendCode() },
                        enabled = !vm.busy && vm.phone.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.send_code)) }

                    if (vm.challenge != null) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = vm.otp,
                            onValueChange = { vm.otp = it },
                            label = { Text(stringResource(R.string.enter_the_code)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { vm.verify() },
                            enabled = !vm.busy && vm.otp.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.verify)) }
                    }
                }
            } else {
                // ---------------- profile & consent ----------------
                SectionHeader("Your name")
                KavachCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.number_verified), fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.kavach_cannot_show_your_number_here_the_server_keeps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = vm.name,
                        onValueChange = { vm.name = it },
                        label = { Text(stringResource(R.string.display_name)) },
                        placeholder = { Text(stringResource(R.string.how_people_should_see_you)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.publish_my_name), color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "People who call you will see this name. Off by default, and you " +
                                    "can withdraw it at any time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = vm.publish,
                            onCheckedChange = { vm.publish = it },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.saveProfile() },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.save)) }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A name is only ever shown if its owner consented, or if it is a verified " +
                            "business listing. Kavach never guesses a name from other people's " +
                            "contacts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionHeader("Your consent history")
                KavachCard {
                    if (vm.history.isEmpty()) {
                        Text(stringResource(R.string.no_decisions_recorded_yet),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        vm.history.forEach { event ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    event.action.replace('_', ' '),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    SimpleDateFormat("dd MMM HH:mm", Locale("en", "IN"))
                                        .format(Date(event.created_at * 1000)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                SectionHeader("Account")
                KavachCard {
                    OutlinedButton(
                        onClick = { vm.signOut() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.sign_out)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text(stringResource(R.string.delete_my_account)) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Deleting withdraws your name immediately, removes your profile, and keeps " +
                            "only anonymous report counts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_your_account)) },
            text = {
                Text(
                    "Your name will stop being shown to anyone who calls you, and your profile " +
                        "will be removed. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteAccount() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
