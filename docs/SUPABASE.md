# Collecting diagnostics in Supabase

Supabase is managed Postgres, so this path needs **no server to run**. The app
appends to a real SQL table, and you query it in the dashboard, in SQL, or from
anything that can read Postgres.

Supabase is the diagnostics destination. One other exists, and they share a
single queue on the device — turning one off and the other on loses nothing:

| Destination | Use it when | What it needs |
|---|---|---|
| **Supabase** | **the default — SQL and charts, no server** | a project + one SQL file |
| Kavach server | you also want to own the caller-lookup and consent traffic | a VPS |

Notion was removed as a destination: it required every user to create their own
integration and paste a token, and their rows would have landed in their
workspace rather than yours.

---

## 1. Create the table

In your Supabase project: **SQL Editor → New query**, paste the whole of
[`supabase/schema.sql`](../supabase/schema.sql), and run it.

That creates the table, its indexes, the row-level security policies, four views
and a nightly retention job.

---

## 2. Get the two values

**Project Settings → API**:

- **Project URL** — `https://xxxxxxxx.supabase.co`
- **anon public** key

---

## 3. Point the app at it

In Kavach: **Settings → Diagnostics & logs → Supabase**

1. Paste the project URL
2. Paste the anon key
3. Press **Save**
4. Press **Test connection** — it sends one row and reports Supabase's own answer
5. Turn **Automatic upload** on

From then on events are queued on the device as they happen and flushed by a
background worker, Wi-Fi-only if you have that set.

---

## Why a public key is safe here

The anon key is **designed to be public** — it is shipped in every Supabase web
app. What protects the data is the policy, not the key:

```sql
alter table public.telemetry_events enable row level security;

create policy "anon may append events"
  on public.telemetry_events for insert to anon with check (true);

revoke all on public.telemetry_events from anon;
grant insert on public.telemetry_events to anon;
```

There is **no select policy**, so the anon key cannot read a single row back —
not even the rows it just wrote. You read the data from the dashboard, or with
the `service_role` key, which never leaves your machine.

That is also why the app sends `Prefer: return=minimal`: asking PostgREST to
echo the inserted rows back would fail on a policy that is working correctly.

---

## The views

| View | What it answers |
|---|---|
| `kavach_errors` | everything at warn or error — stop-the-line items |
| `kavach_friction` | rage taps, dead clicks, back-button spam |
| `kavach_intent_outcome` | pairs `access_fix_requested` with `access_fix_result`, and flags any request with **no result logged** |
| `kavach_devices` | one line per install: event count, first and last seen, version |
| `kavach_event_mix` | which events dominate, and how many devices produce them |

The intent→outcome view is the important one. A request without its result is a
rumour: it was that shape — 71 requests and not one result — that the whole
logging standard was written to prevent.

---

## Retention

The standard promises error 30 days, warn 14, info 7, debug 1. Supabase gives
you `pg_cron`, so `kavach_purge()` actually runs nightly instead of being a
promise in a document.

```sql
select public.kavach_purge();   -- run it by hand any time
select * from cron.job;         -- confirm the schedule
```

---

## Optional: validate before insert

The direct path lets the app insert, which means a modified or buggy client
could write rows that do not match the schema. If that matters, deploy the Edge
Function instead:

```bash
supabase functions deploy telemetry --no-verify-jwt
```

Then point the app at
`https://<project>.supabase.co/functions/v1/telemetry` instead of the REST table
URL. It validates every event against the same catalogue the app and the FastAPI
server use, and inserts with the service role — so you can then revoke insert
from `anon` entirely.

---

## The security advisor found three real holes

The first version of this schema looked right and was not. Running
`supabase_get_advisors` after the DDL caught all three, and they are now fixed in
`schema.sql`:

| Finding | What it meant | Fix |
|---|---|---|
| `security_definer_view` (ERROR, 6 views) | Postgres views run with the **owner's** privileges by default, so the public anon key could read the whole dataset through `kavach_devices` — defeating the insert-only policy on the table underneath | `alter view … set (security_invoker = on)` on every view |
| `anon_security_definer_function_executable` | `kavach_purge()` was reachable through `/rest/v1/rpc/`, so anyone with the public key could have **deleted every row** | `revoke execute … from anon, authenticated, public` |
| `function_search_path_mutable` | The function could be redirected to another schema | `alter function … set search_path = ''` |

The lesson is in the schema file itself: **run the advisor after DDL.** A policy
that reads correctly is not the same as a policy that holds.

Verified afterwards, live, against the project:

```
anon INSERT                     allowed
anon SELECT from the table      denied
anon SELECT from the views      denied
anon calling kavach_purge()     denied
```

## Honest limits

- **The app is the only thing writing here.** If you also run the Kavach server,
  point its `DATABASE_URL` at this same Postgres and both land in one table.
- **No alerting.** Supabase can email you on a query, but nothing is wired up;
  the weekly review is still something you do.
- **Storing other people's diagnostics makes you the data fiduciary** under the
  DPDP Act — retention, deletion requests and a grievance contact are yours.
  The purge job handles retention; the rest is paperwork.
- **Nothing here is device-verified.** The Android side of this path has been
  built and tested at the unit level, not exercised against a live Supabase
  project.
