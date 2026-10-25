# Messaging — what a messaging app has, what Kavach has, and the order to close it

Written after a report that **a conversation could not be opened at all** and that
long-pressing went straight to delete. Both were real, both had the same cause, and
both are fixed in v0.49.0 — see [The bug that started this](#the-bug-that-started-this).

The rest of this file is the honest gap list and the order to work through it.

---

## The bug that started this

Two screens, one mistake.

| Where | What was written | What it did |
|---|---|---|
| Conversation list | `.clickable { open }` **plus** `pointerInput { detectTapGestures(onLongPress) }` | The tap detector consumes down and up **whether or not it has an `onTap`**, so `clickable` never fired. **Tapping a conversation did nothing.** |
| Message bubble | `pointerInput { detectTapGestures(onLongPress) }` wrapping a `SelectionContainer` | The action sheet stole the long-press the selection container needed, so the sheet's "Select part of the text" was a sentence the app made impossible. |

The first was mine — I added a swipe handler and wrote a comment claiming the two
gesture handlers were independent. They were not. `combinedClickable` owns both
tap and long-press properly; `detectHorizontalDragGestures` does not compete
because it only claims the pointer after the touch slop.

The second was older. The fix removes the consuming handler and puts the actions
on a ⋮ beside the bubble — a target you can press — and lets `SelectionContainer`
own long-press, which is what it is for.

Long-pressing a conversation now opens a **menu** (open, archive, bubble, bin,
delete from device), not a delete confirmation. Offering exactly one action and
making it the destructive one was not a decision anyone made; it was the only
thing wired.

---

## What a messaging app actually has

Grouped by where the user meets it, with a note on whether Kavach can do it.

### The conversation list

| Feature | Kavach | Notes |
|---|---|---|
| Search | ✅ | address and snippet |
| Category filters | ✅ | personal / OTP / transactional / promotional / spam |
| Archive | ✅ | a place, not a deletion |
| Bin | ✅ | hides from Kavach; the carrier keeps the message |
| Spam and blocked | ✅ | via the blocklist |
| Mark all as read | ✅ | stored as a timestamp, because reading is the system's business |
| Group by day | ✅ | Today / Yesterday / Older |
| Swipe actions | ✅ | archive / bin / nothing |
| **Unread badge** | ⚠️ | count exists, no badge |
| **Pin a conversation** | ❌ | |
| **Mute a conversation** | ❌ | |
| **Multi-select** | ❌ | |

### Inside a conversation

| Feature | Kavach | Notes |
|---|---|---|
| Send and receive | ✅ | |
| Delivery state | ⚠️ | tracked, not shown on the bubble |
| Suggested replies | ✅ | |
| Copy whole message | ✅ | |
| **Select part of the text** | 🔧 | now actually works; it was blocked by the bug above |
| Forward | ✅ | |
| Delete | ⚠️ | from device needs the default-SMS role; otherwise hides |
| **Reply / quote** | ❌ | |
| **Reactions** | ❌ | |
| **Star** | ❌ | |
| **Search within the conversation** | ❌ | |
| **Date separators in the thread** | ❌ | |
| **Sender labels in groups** | ❌ | needs groups |
| **Attachments (images, files)** | ❌ | MMS is not read at all |
| **Voice notes** | ❌ | |
| **Multi-select with a contextual bar** | ❌ | this is the pattern the user expected |

### Composing

| Feature | Kavach | Notes |
|---|---|---|
| Draft saved per conversation | ✅ | |
| SIM choice | ⚠️ | honoured in the sender, not offered in the UI |
| **Scheduled send** | ❌ | |
| **Character / part counter** | ❌ | |
| **Group send** | ❌ | |
| **Attachments** | ❌ | |

### Notifications

| Feature | Kavach | Notes |
|---|---|---|
| Notify on new message | ⚠️ | only when Kavach is the default SMS app, on purpose |
| Bubbles | ✅ | all / selected / none |
| **Quick reply from the notification** | ❌ | |
| **Mark as read from the notification** | ❌ | |
| **Per-conversation sound** | ❌ | |

### Data

| Feature | Kavach | Notes |
|---|---|---|
| **Backup and restore** | ❌ | |
| **Export a conversation** | ❌ | |
| **Search across all messages** | ⚠️ | list search only, not message bodies |

---

## What Kavach will never do, and why

Saying this once here so it does not get re-litigated on every screen.

- **RCS.** Needs carrier support and a Google-operated backend. Google Messages has
  it because Google runs both ends.
- **Read receipts.** Nothing in SMS carries them. A delivery report means the
  handset received it, never that a person read it.
- **Typing indicators.** Same reason.
- **Delete a message from the device** unless Kavach is the default SMS app. The
  provider does not let other apps write to it, and an app that cannot do a thing
  should not offer it.

---

## The order to work through it

Ordered by what a person hits first, not by what is easiest.

### Phase 1 — make the thread usable (next)

The complaint was "I cannot select a message". Selection now works, but there is
still no *selection mode* — the thing every messaging app has where long-press
picks a message and a bar appears with copy, forward, delete, share.

- Multi-select with a contextual bar (long-press starts it, tap adds to it)
- Reply / quote
- Date separators in the thread
- Delivery state shown on the bubble, using what the tracker already records

**Why first:** these are what "a messaging app" means to someone opening one.

### Phase 2 — the list

- Unread badge
- Pin and mute per conversation
- Multi-select in the list (archive or bin several at once)

### Phase 3 — attachments

The largest piece, and the one that needs a real decision: **MMS**. Kavach does
not read MMS at all today. Reading it means parsing `content://mms` parts,
handling the download when the carrier sends a link rather than the content, and
storing the parts. It is a subsystem, not a setting.

Until it exists, no attachment feature can honestly be offered — which is why the
auto-download switches were removed rather than left dead.

### Phase 4 — the smaller things

- Search inside a conversation
- Star
- Character and part counter
- Export a conversation
- Quick reply from the notification

### Not planned

Reactions and scheduled send are worth doing and are not in a phase yet, because
both need the compose path reworked first.

---

## How this gets verified

Every phase ships with tests for the logic and a device check for the behaviour,
because **none of this can be verified from a build machine**. The two bugs above
are the argument: both compiled, both passed lint, and both made the screen
unusable. A test that asserts a callback fires does not tell you a conversation
opens.
