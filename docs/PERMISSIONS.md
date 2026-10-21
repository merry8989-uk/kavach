# Permissions and special access

Every permission Kavach declares, why it exists, and what happens if it is
refused. Nothing here is requested "just in case": each entry names the feature
that stops working without it.

**Request policy.** Everything is *declared* in the manifest so a feature can be
offered, but requests happen through the normal Android flow and can be refused
individually. Onboarding walks the whole set in order; every feature also
re-checks its own status before it runs, so revoking a permission later degrades
that feature instead of crashing the app.

---

## Declared permissions

### Telephony and calling

| Permission | Needed for | If refused |
|---|---|---|
| `READ_PHONE_STATE` | Knowing a call is ringing, and which SIM it is on | Caller ID and in-call state stop working |
| `READ_PHONE_NUMBERS` | Reading the number of the active SIM | SIM picker shows generic labels |
| `CALL_PHONE` | Placing a call from the dialer | You can still dial; the OS asks each time |
| `ANSWER_PHONE_CALLS` | Answering/hanging up from the in-call screen | Answer via the system UI |
| `MANAGE_OWN_CALLS` | Running our own call UI as a self-managed connection | Falls back to the system call UI |
| `PROCESS_OUTGOING_CALLS` *(maxSdk 28)* | Legacy outgoing-call interception | Nothing on Android 10+; it is not requested there |

### Contacts and call log

| Permission | Needed for | If refused |
|---|---|---|
| `READ_CONTACTS` | Naming callers from your own address book | Callers show as numbers only |
| `WRITE_CONTACTS` | Saving a contact from the dialer | The add/edit buttons explain the refusal |
| `READ_CALL_LOG` | Importing call history | History tab stays empty (manual entry still works) |
| `WRITE_CALL_LOG` | Writing a call back to the system log | History lives only inside Kavach |

`READ_CALL_LOG` is legitimate for a default dialer, and the app is eligible for
that role. Play still requires the call-log declaration form for distribution.

### Messaging

| Permission | Needed for | If refused |
|---|---|---|
| `READ_SMS` | Threads and SMS spam filtering | Messages tab is hidden |
| `SEND_SMS` | Replying from a thread | Reply is unavailable |
| `RECEIVE_SMS` | Intercepting new messages | New messages only appear in the system app |

Kavach does **not** request `ROLE_SMS`. It declares no SMS intent filters, so
Android would never grant it; offering a button that cannot work would be
dishonest UI.

### Recording and call audio

| Permission | Needed for | If refused |
|---|---|---|
| `RECORD_AUDIO` | Call recording and the AI assistant | Both features report themselves unavailable |
| `MODIFY_AUDIO_SETTINGS` | Routing audio to earpiece/speaker/Bluetooth while recording | Recording may capture silence on some devices |

**Honest scope:** Android does not let an ordinary app inject audio into a
cellular uplink. Two-way recording therefore depends on the device and OEM, and
Kavach says so rather than claiming it always works. Nothing is marked verified
until it has been tested on the actual phone.

### Foreground services

| Permission | Needed for |
|---|---|
| `FOREGROUND_SERVICE` | Keeping the recorder/assistant alive during a call |
| `FOREGROUND_SERVICE_MICROPHONE` | The microphone type for those services |
| `FOREGROUND_SERVICE_PHONE_CALL` | The phone-call type, so the OS treats them as call work |

### In-call experience

| Permission | Needed for | If refused |
|---|---|---|
| `POST_NOTIFICATIONS` | Call briefs, missed-call reminders, update alerts | Features still work; nothing is shown |
| `USE_FULL_SCREEN_INTENT` | Full-screen in-call UI on Android 14+ | Falls back to a heads-up notification |
| `WAKE_LOCK` | Keeping the screen awake during a call | Screen may sleep mid-call |
| `DISABLE_KEYGUARD` | Showing the call UI over the lock screen | You unlock first |
| `VIBRATE` | Ring vibration | No vibration |
| `SYSTEM_ALERT_WINDOW` *(optional)* | The caller-name overlay while ringing | The name still appears in the notification |

### Network and updates

| Permission | Needed for |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Online lookup, reports, update checks |
| `REQUEST_INSTALL_PACKAGES` | Handing a downloaded update APK to the system installer |

---

## Deliberately NOT requested

Declaring these would be a lie, because a normal app can never hold them:

| Permission | Why not |
|---|---|
| `CAPTURE_AUDIO_OUTPUT` | `signature\|privileged` — system apps only |
| `MODIFY_PHONE_STATE` | `signature\|privileged` — system apps only |
| `READ_VOICEMAIL`, `WRITE_VOICEMAIL` | `signature\|privileged` |
| `QUERY_ALL_PACKAGES` | Play-restricted; we use a scoped `<queries>` block instead |
| `BIND_INCALL_SERVICE`, `BIND_SCREENING_SERVICE` | Held by the system and already declared *on* our services |

---

## Package visibility (`<queries>`)

Android 11+ hides other installed apps unless they are declared. Kavach names
the messaging apps it can hand a number to, plus the browser, mail and installer
intents it needs:

`com.whatsapp`, `com.whatsapp.w4b`, `org.telegram.messenger`,
`com.zoho.arattai`, `com.hyped.app`, and `VIEW https`, `SENDTO mailto`,
`VIEW content`.

Without this block the lookup sheet would report "not installed" for apps that
*are* installed, and the WhatsApp Business entry could not launch its package.

---

## Roles

| Role | Purpose | If not held |
|---|---|---|
| `ROLE_DIALER` | Replaces the stock dialer: in-call screen, dual-SIM, recording | Kavach still identifies callers |
| `ROLE_CALL_SCREENING` | Blocks and screens calls before they ring | Blocking falls back to the dialer |

---

## Local data protection

The Room database is opened through **SQLCipher**. The passphrase is a random
32-byte secret, encrypted with a non-exportable AES-256-GCM key in the Android
Keystore; the ciphertext lives in private preferences. It is never on disk in
the clear.

Not yet covered: recording **audio files** are written to app-private storage but
are not individually encrypted (`EncryptedFile`), and the backend URL/token and
account JWT live in DataStore. Both are tracked as remaining work.

---

*Engineering guidance, not legal advice. Play policy declarations and DPDP
obligations should be reviewed before public distribution.*
