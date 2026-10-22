# Languages

Kavach ships in **English** and **Hindi**. Both are complete: every user-facing
string in the Compose UI resolves through a resource, and CI fails the build if
the two files drift apart.

## How it works

| Piece | File |
|---|---|
| The choice | `i18n/AppLanguage.kt` |
| Reading and writing it | `i18n/LanguageStore.kt` |
| Applying it | `MainActivity.attachBaseContext` |
| The picker | Settings → Appearance |
| The strings | `res/values/strings.xml`, `res/values-hi/strings.xml` |

`attachBaseContext` wraps the context with the chosen locale, so every
`stringResource` call resolves against the right resources with no per-composable
work and no chance of a screen drawing in the wrong language first. Changing the
language recreates the activity rather than restarting the process, so the back
stack survives and the user lands back on Settings.

**Why a preference and not DataStore.** The language has to be known in
`attachBaseContext`, which runs before anything asynchronous can be awaited. A
`runBlocking` read of DataStore there is a deadlock waiting to happen; a
synchronous preference read is the right tool for a value needed this early.

## Adding a language

1. Copy `res/values-hi/` to `res/values-<tag>/` and translate the values.
2. Add an entry to `AppLanguage`.
3. Nothing else changes — no other file knows the list.

CI's *translation key parity* step will fail if a key is missing or orphaned, so
a half-finished translation cannot ship quietly.

## Honest limits

- **Nothing is verified on a device.** The locale plumbing is code-read and
  unit-checked, not seen on a phone.
- **Hindi only covers strings that go through a resource.** Anything rendered by
  the system (the notification shade, the permission dialogs, the share sheet)
  follows the *phone's* language, not Kavach's — that is an Android boundary, not
  something an app can override.
- **The Hindi is a first pass.** It is accurate and natural for UI labels, but it
  has not been read by a native reviewer, and long explanatory sentences are the
  ones most worth a second opinion.
