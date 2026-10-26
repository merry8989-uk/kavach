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
| **Unread badge** | ✅ | count on each conversation |
| **Pin a conversation** | ✅ | pinned sort to the top of the inbox only |
| **Mute a conversation** | ✅ | stops the notification; still arrives, still counts unread |
| **Multi-select** | ✅ | long-press picks, the bar archives, bins or marks read |

### Inside a conversation

| Feature | Kavach | Notes |
|---|---|---|
| Send and receive | ✅ | |
| Delivery state | ✅ | shown under outgoing bubbles, from the tracker |
| Suggested replies | ✅ | |
| Copy whole message | ✅ | |
| **Select part of the text** | ✅ | restored in v0.53.0 as a **mode**, not a gesture — see below |
| Forward | ✅ | |
| Delete | ⚠️ | from device needs the default-SMS role; otherwise hides |
| **Reply / quote** | ✅ | the quote is carried in the sent text, not just shown on screen |
| **Reactions** | ❌ | |
| **Star** | ✅ | marked on the bubble; stored in Kavach, not the provider |
| **Search within the conversation** | ✅ | filters messages, not conversations |
| **Date separators in the thread** | ✅ | by calendar day, so 11pm reads as Yesterday at 1am |
| **Sender labels in groups** | ❌ | needs groups |
| **Attachments (images, files)** | ❌ | MMS is not read at all |
| **Voice notes** | ❌ | |
| **Multi-select with a contextual bar** | ✅ | long-press selects, tap adds, the bar copies, forwards and deletes |

### Composing

| Feature | Kavach | Notes |
|---|---|---|
| Draft saved per conversation | ✅ | |
| SIM choice | ⚠️ | honoured in the sender, not offered in the UI |
| **Scheduled send** | ❌ | |
| **Character / part counter** | ✅ | GSM-7 vs UCS-2, so the limit is right |
| **Group send** | ❌ | |
| **Attachments** | ❌ | |

### Notifications

| Feature | Kavach | Notes |
|---|---|---|
| Notify on new message | ⚠️ | only when Kavach is the default SMS app, on purpose |
| Bubbles | ✅ | all / selected / none |
| **Quick reply from the notification** | ✅ | RemoteInput; sends without opening the app |
| **Mark as read from the notification** | ✅ | records the moment; the badge honours it |
| **Per-conversation sound** | ✅ | a separate channel, because the channel owns the sound |

### Data

| Feature | Kavach | Notes |
|---|---|---|
| **Backup and restore** | ❌ | |
| **Export a conversation** | ✅ | a plain transcript, through the share sheet |
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

### Phase 1 — make the thread usable (**done, v0.50.0**)

The complaint was "I cannot select a message". Selection now works, but there is
still no *selection mode* — the thing every messaging app has where long-press
picks a message and a bar appears with copy, forward, delete, share.

- Multi-select with a contextual bar (long-press starts it, tap adds to it)
- Reply / quote
- Date separators in the thread
- Delivery state shown on the bubble, using what the tracker already records

**Why first:** these are what "a messaging app" means to someone opening one.

#### The decision that had to be revisited

**Partial text selection was removed in v0.50.0 to make multi-select work, and
that was wrong.**

It was dropped because one gesture cannot do two things: `SelectionContainer`
needs long-press to start selecting text, and multi-select needs long-press to
select a message. Removing one was a defensible way to resolve that. It was still
wrong, because **partial copy was asked for from the start** — "copy the whole
message or a part of it from the middle" — and quietly dropping a requirement is
not a design decision, it is a regression with a comment on it.

The resolution in v0.53.0 is a **mode rather than a gesture**. Choosing "Select
text" from the action sheet puts *that one bubble* into a `SelectionContainer`;
every other bubble keeps the long-press that multi-select uses. The two features
stop competing because they no longer happen at the same moment.

The mode is shown as a bar with a way out, because a mode the user cannot see is a
mode they get stuck in and blame the app for.

<details><summary>The original note, kept for the record</summary>

`SelectionContainer` needs long-press to start selecting text. Multi-select needs
long-press to start selecting messages. One gesture, two features, and whichever
is wired second is dead. Every messaging app resolves this the same way — long-press
selects the *message*, and partial text selection lives behind an action — so that
is what happened here.

The old "Select part of the text" menu item was worse than either: it showed a
sentence telling the user to long-press text, in an app that had taken the
long-press away from the text.

</details>

### Phase 2 — the list (**done, v0.51.0**)

- ✅ Unread badge
- ✅ Pin and mute per conversation
- ✅ Multi-select in the list (archive, bin or mark read several at once)

Pinning sorts a conversation to the top **of the inbox only** — a pin is about
what you want at the top of your messages, not about the archive. Muting stops
the notification and nothing else: the conversation still arrives, still sorts,
and still counts as unread, because a mute the user cannot see is a mute they will
forget they set.

### Phase 3 — attachments (**dropped**)

Not being built. MMS is a subsystem — parsing `content://mms` parts, handling the
case where the carrier sends a link rather than the content, storing the parts —
and it is out of scope by decision, not by omission.

This is why the auto-download settings were removed in v0.48.0 rather than left
as dead switches: there is nothing to download.

### Phase 4 — the smaller things (**done, v0.52.0**)

- ✅ Search inside a conversation
- ✅ Star
- ✅ Character and part counter
- ✅ Export a conversation
- ✅ Quick reply from the notification

The counter is the one worth a note. The limit is not one number: a GSM-7 message
fits 160 characters in one part and 153 in each part after that, because the
concatenation header eats seven. **One character outside GSM-7 — an emoji, a
Devanagari letter, a curly quote — switches the whole message to UCS-2**, where
the numbers are 70 and 67. Telling someone "160 left" and then charging them for
two messages is exactly what that setting exists to prevent.

### Not planned

Reactions and scheduled send are worth doing and are not in a phase yet, because
both need the compose path reworked first.

MMS and attachments are **not planned at all** — dropped by decision.

---

## How this gets verified

Every phase ships with tests for the logic and a device check for the behaviour,
because **none of this can be verified from a build machine**. The two bugs above
are the argument: both compiled, both passed lint, and both made the screen
unusable. A test that asserts a callback fires does not tell you a conversation
opens.
