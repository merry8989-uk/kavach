# Kavach — Permissions & Roles

## 1. Permission matrix

| Permission | API | Feature it enables | If denied |
|---|---|---|---|
| READ_CONTACTS | 23 | Caller ID, contacts list, T9 | Contacts screen empty; caller shows as "Unknown caller" |
| WRITE_CONTACTS | 23 | Add / edit / delete contacts | Kavach keeps its own copy; device store not written |
| READ_CALL_LOG | 23 | Call history | Call log empty; sync does nothing |
| WRITE_CALL_LOG | 23 | (reserved) | no effect |
| READ_PHONE_STATE | 23 | Screening, SIM list, dual-SIM | Screening role unusable; SIM picker hidden |
| READ_PHONE_NUMBERS | 26 | Own-number display | hidden |
| CALL_PHONE | 23 | Direct call placement | falls back to `ACTION_DIAL` |
| ANSWER_PHONE_CALLS | 26 | Assistant answering | assistant cannot auto-answer |
| MANAGE_OWN_CALLS | 26 | Self-managed calls (declared) | not currently used |
| READ_SMS / RECEIVE_SMS | 23 | Message threads | Messages screen empty |
| SEND_SMS | 23 | Send a message | send silently no-ops |
| RECORD_AUDIO | 23 | Recording + assistant capture | recording/assistant unavailable |
| POST_NOTIFICATIONS | 33 | Call briefs, update banner | notifications suppressed |
| REQUEST_INSTALL_PACKAGES | 23 | In-app update install | download works; install must be manual |
| INTERNET / ACCESS_NETWORK_STATE | 23 | Update check, future cloud | offline features still work |

## 2. Roles

| Role | API | Why | Fallback when not held |
|---|---|---|---|
| `ROLE_DIALER` | 29 | Default dialer: in-call UI, reliable FGS start for capture | `TelecomManager.acceptRingingCall()` (less reliable); mic-FGS start may be blocked on Android 12+ |
| `ROLE_CALL_SCREENING` | 29 | Identify/block before the phone rings | No screening at all — the core protection is off |
| `ROLE_SMS` | 29 | Read/reply/filter messages | SMS permissions can still be granted directly, but default-SMS features don't apply |

Below API 29 the role APIs do not exist; the setup screen says so and points the user
at system Settings.

## 3. Request policy — and a deliberate deviation

The master specification says: *"Request permissions contextually, not all at first
launch."*

**Kavach currently asks for everything on first launch** (F14), because that was the
explicit product instruction given for this build. This is a real deviation and is
flagged here rather than hidden.

Reconciling options, in order of preference:
1. **Recommended:** keep the first-run screen but make the big "Grant all" button
   optional, and additionally request each permission contextually the first time its
   feature is opened. Belt and braces.
2. Request only READ_CONTACTS + READ_PHONE_STATE at first run (enough for caller ID),
   and defer call log, SMS, microphone and notifications to first use.
3. Keep as-is (all at first launch).

Whichever is chosen, the following already hold:

- Every permission is explained in plain language before it is requested.
- Denial is handled without crashing — dependent features degrade to honest empty
  states rather than fake data.
- `Open app settings` is offered for permanently-denied permissions.
- Role status is re-checked on every visit to the setup screen (the screen polls),
  and the nav gate re-evaluates on each launch.

## 4. Rules Kavach follows

- Never bypass Android permission checks.
- Never assume accessibility access grants call or recording access.
- Never assume the default-dialer role grants unrestricted SMS, call-log or
  recording access.
- Re-check role/permission state on resume and before dependent operations
  (the setup screen polls; the nav gate re-checks each launch).
- Every permission-dependent feature reflects its true authorisation state.

## 5. Where permissions are requested

| Location | What |
|---|---|
| `ui/screens/onboarding/PermissionsScreen.kt` | First-run: all groups + 3 roles, live status, next-step button |
| `ui/nav/KavachNav.kt` | Gate: re-shows setup if `onboardingDone` is false or core permissions are missing |
| Runtime guards | `DialerScreen` (CALL_PHONE), `MessagesScreen` (SEND_SMS), `activeSims` (READ_PHONE_STATE), `UpdateInstaller` (install intent) |
