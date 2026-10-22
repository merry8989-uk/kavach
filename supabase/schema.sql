-- Kavach diagnostics → Supabase
--
-- Run this once in the Supabase SQL editor (Dashboard → SQL Editor → New query).
-- It creates the table the app writes to, the policies that keep it safe, the
-- views that make it useful, and a retention job.
--
-- The shape matches the app's logging standard (docs/LOGGING_STANDARD.md) and
-- the server's telemetry_schema.py, so rows from every install join cleanly.

-- ---------------------------------------------------------------- the table --

create table if not exists public.telemetry_events (
  id              bigint generated always as identity primary key,
  received_at     timestamptz not null default now(),
  user_code       text        not null,
  event_name      text        not null,
  schema_version  text        not null,
  event_timestamp timestamptz not null,
  session_id      text        not null,
  attempt_id      text,
  level           text        not null,
  app_version     text        not null,
  os              text        not null,
  device_model    text        not null,
  platform        text        not null default 'android',
  network_type    text,
  screen_or_route text        not null,
  release_channel text,
  properties      jsonb       not null default '{}'::jsonb,

  -- Cheap guards. The Edge Function (see functions/telemetry) does the full
  -- schema check; these stop obvious junk if anyone inserts directly.
  constraint level_known check (level in ('debug', 'info', 'warn', 'error')),
  constraint platform_known check (platform in ('android')),
  constraint user_code_shape check (user_code ~ '^KV-[A-Z2-9]{3}-[A-Z2-9]{3}$'),
  constraint properties_is_object check (jsonb_typeof(properties) = 'object')
);

create index if not exists telemetry_received_idx on public.telemetry_events (received_at desc);
create index if not exists telemetry_user_code_idx on public.telemetry_events (user_code);
create index if not exists telemetry_event_name_idx on public.telemetry_events (event_name);
create index if not exists telemetry_session_idx on public.telemetry_events (session_id);
create index if not exists telemetry_level_idx on public.telemetry_events (level) where level in ('warn', 'error');
create index if not exists telemetry_attempt_idx on public.telemetry_events (attempt_id) where attempt_id is not null;

-- ------------------------------------------------------------------- safety --
--
-- The app ships with the **anon** key, which is public by design. Everything
-- that keeps the data safe is therefore here, not in the app:
--
--   * anon may INSERT, and nothing else.
--   * there is deliberately NO select policy, so the public key cannot read a
--     single row back. Not even the app that wrote them.
--   * you read the data from the dashboard or with the service_role key, which
--     never leaves the server.

alter table public.telemetry_events enable row level security;

drop policy if exists "anon may append events" on public.telemetry_events;
create policy "anon may append events"
  on public.telemetry_events
  for insert
  to anon
  with check (true);

-- Belt and braces: revoke everything except insert from the public role.
revoke all on public.telemetry_events from anon;
grant insert on public.telemetry_events to anon;

-- --------------------------------------------------------------- the views --

-- Anything at error or warn level. Stop-the-line items.
create or replace view public.kavach_errors as
select received_at, user_code, event_name, level, screen_or_route, attempt_id, properties
from public.telemetry_events
where level in ('error', 'warn')
order by received_at desc;

-- Rage taps, dead clicks, back-button spam: the cheapest UX wins.
create or replace view public.kavach_friction as
select received_at, user_code, event_name, screen_or_route, properties
from public.telemetry_events
where event_name in ('rage_tap', 'dead_click', 'back_button_spam', 'permission_loop', 'drop_off')
order by received_at desc;

-- The headline view. A request without its result is a rumour — this pairs
-- them, and shows any request that never got an answer.
create or replace view public.kavach_intent_outcome as
with requests as (
  select attempt_id, user_code, session_id, received_at,
         properties ->> 'permission' as permission,
         properties ->> 'state_before' as state_before
  from public.telemetry_events
  where event_name = 'access_fix_requested' and attempt_id is not null
),
results as (
  select attempt_id,
         properties ->> 'outcome' as outcome,
         properties ->> 'state_after' as state_after,
         properties ->> 'action_taken' as action_taken,
         properties ->> 'error_code' as error_code
  from public.telemetry_events
  where event_name = 'access_fix_result' and attempt_id is not null
)
select r.attempt_id, r.user_code, r.session_id, r.received_at,
       r.permission, r.state_before,
       res.outcome, res.state_after, res.action_taken, res.error_code,
       case when res.attempt_id is null then 'NO RESULT LOGGED' else 'paired' end as pairing
from requests r
left join results res using (attempt_id)
order by r.received_at desc;

-- One line per install: how many events, when last seen, what version.
create or replace view public.kavach_devices as
select user_code,
       count(*)                              as events,
       min(received_at)                      as first_seen,
       max(received_at)                      as last_seen,
       (array_agg(app_version order by received_at desc))[1] as latest_app_version,
       (array_agg(device_model order by received_at desc))[1] as device,
       (array_agg(os order by received_at desc))[1]          as os
from public.telemetry_events
group by user_code
order by last_seen desc;

-- Event mix, for spotting a version that suddenly starts misbehaving.
create or replace view public.kavach_event_mix as
select event_name, level, count(*) as events, count(distinct user_code) as devices
from public.telemetry_events
group by event_name, level
order by events desc;

-- A bridge to the diag_events convention some projects already use: one row per
-- session (counts plus a JSON report) rather than one row per event.
create or replace view public.kavach_session_summary as
select
  session_id,
  (array_agg(user_code order by received_at desc))[1]       as user_id,
  (array_agg(platform order by received_at desc))[1]        as platform,
  (array_agg(app_version order by received_at desc))[1]     as app_version,
  (array_agg(schema_version order by received_at desc))[1]  as schema_version,
  (array_agg(release_channel order by received_at desc))[1] as release_channel,
  count(*)::int                                             as event_count,
  count(*) filter (where level = 'error')::int              as error_count,
  count(*) filter (where level = 'warn')::int               as warning_count,
  (array_agg(nullif(properties ->> 'duration_ms','')::int order by received_at)
     filter (where event_name = 'time_to_first_value'))[1]  as ttfv_ms,
  (array_agg(nullif(properties ->> 'percent','')::int order by received_at desc)
     filter (where event_name = 'scroll_depth'))[1]         as scroll_depth,
  jsonb_build_object(
    'user_code',  (array_agg(user_code order by received_at desc))[1],
    'device',     (array_agg(device_model order by received_at desc))[1],
    'os',         (array_agg(os order by received_at desc))[1],
    'started_at', min(received_at),
    'ended_at',   max(received_at),
    'events',     jsonb_agg(jsonb_build_object(
                    'at', event_name, 'level', level,
                    'route', screen_or_route, 'props', properties
                  ) order by received_at)
  )                                                         as report,
  min(received_at)                                          as created_at
from public.telemetry_events
group by session_id
order by min(received_at) desc;

-- ------------------------------------------------------- view security ----
--
-- Postgres views run with the **view owner's** privileges by default, which is
-- SECURITY DEFINER behaviour. Left alone, that would let the public anon key
-- read the whole dataset through these views and defeat the insert-only policy
-- on the table underneath.
--
-- This was not theoretical: the Supabase security advisor flagged all six views
-- as an ERROR, and the anon key could indeed read `kavach_devices` until
-- security_invoker was switched on. Always run the advisor after DDL.

alter view public.kavach_errors           set (security_invoker = on);
alter view public.kavach_friction         set (security_invoker = on);
alter view public.kavach_intent_outcome   set (security_invoker = on);
alter view public.kavach_devices          set (security_invoker = on);
alter view public.kavach_event_mix        set (security_invoker = on);
alter view public.kavach_session_summary  set (security_invoker = on);

-- ------------------------------------------------------------- retention ----
--
-- The standard promises error 30 days, warn 14, info 7, debug 1. Supabase gives
-- you pg_cron, so this actually runs rather than being a promise in a document.

create or replace function public.kavach_purge() returns bigint
language plpgsql
security definer
as $$
declare
  removed bigint := 0;
  n bigint;
begin
  delete from public.telemetry_events
   where level = 'debug' and received_at < now() - interval '1 day';
  get diagnostics n = row_count; removed := removed + n;

  delete from public.telemetry_events
   where level = 'info' and received_at < now() - interval '7 days';
  get diagnostics n = row_count; removed := removed + n;

  delete from public.telemetry_events
   where level = 'warn' and received_at < now() - interval '14 days';
  get diagnostics n = row_count; removed := removed + n;

  delete from public.telemetry_events
   where level = 'error' and received_at < now() - interval '30 days';
  get diagnostics n = row_count; removed := removed + n;

  return removed;
end;
$$;

-- Neither function may be called by anyone holding the public key. Without
-- this, `kavach_purge` was reachable through /rest/v1/rpc/ and a stranger could
-- have deleted every row. The advisor flagged it; this closes it.
revoke execute on function public.kavach_purge() from anon, authenticated, public;

-- Pin the search path so the function cannot be redirected to another schema.
alter function public.kavach_purge() set search_path = '';

-- Schedule it. If pg_cron is not enabled, turn it on under
-- Database → Extensions and re-run just the three lines below.
create extension if not exists pg_cron;

select cron.unschedule('kavach-purge')
 where exists (select 1 from cron.job where jobname = 'kavach-purge');

select cron.schedule('kavach-purge', '15 3 * * *', $$select public.kavach_purge()$$);
