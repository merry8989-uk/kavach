# App authentication

## The problem this replaced

The app shipped **one bearer token, identical on every device**, and used it for
everything: lookup, reports, corrections, telemetry.

An APK is trivially decompilable. So that token was not a secret — it was a
published key with a small speed bump in front of it. Anyone who took the app
apart had the whole API.

The second half was worse. Because the token was shared, revoking it would have
broken every user at once, which means in practice **it was never revoked**. A
credential you cannot rotate is not really a credential.

And it had already caused a real bug: `/v1/account` DELETE used
`actor_hash(token)` as the actor, and with one token that hash was **the same for
everybody** — so one person's deletion request removed *every app user's reports
at once*.

## What it is now

| Token | Proves | Where it lives | Lifetime |
|---|---|---|---|
| **Bootstrap** | nothing much | shipped in the APK | rotated by re-pasting |
| **Install** | *what* is calling | Keystore-encrypted on the device | revocable per install |
| **User** (JWT) | *who* is calling | from the OTP flow | short |

The bootstrap token does exactly one thing: `POST /v1/app/register`. That call is
rate-limited per source, and what comes back is a token belonging to one install.

```
APK  ──(bootstrap)──▶  POST /v1/app/register  ──▶  install token
                                                      │
                              everything else ────────┘
```

The install token is stored **only as a hash** on the server, so a database leak
does not hand out working tokens.

## Scopes

- **Install token**: lookup, reputation, reports, corrections, telemetry.
- **User token**: the profile, consent, and **account deletion**.

Deletion is deliberately on the user side. An app token proves *what* is calling;
only a user token proves *who* — and erasing somebody's data must not be possible
with a credential that ships in every copy of the app.

## Revoking one install

```sql
update app_installs set revoked = true where install_id = '...';
```

That install stops working immediately. Every other install is untouched, which is
the entire point.

## What this does not do

It does **not** make the app un-clonable. Nothing shipped in a client is secret,
and a determined person can still register their own install. What it changes is
the shape of the damage:

- one leaked install is cut off **on its own**;
- minting installs in bulk costs something, because registration is rate-limited;
- the token in the APK is no longer a full API key.

Making the app itself provably genuine needs **Play Integrity**, which requires a
Play Console listing and a Google Cloud project. That is the natural next step and
it is not done here.

## Development

`ALLOW_SHARED_TOKEN=true` accepts the bootstrap token on every endpoint, which is
what the old contract did. It exists so existing clients keep working while they
are updated. **The production guard refuses to start with it on**, because turning
it on puts the public token back in charge.
