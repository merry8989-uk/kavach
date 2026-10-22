// Optional hardened intake for Kavach diagnostics.
//
// The simple path is the app writing to the table directly over PostgREST,
// which needs no server at all and is what supabase/schema.sql is set up for.
//
// This function exists for when you want the events **validated before they
// land**, so a modified or buggy client cannot write rows that do not match the
// schema. It is the same rule set the app and the FastAPI server use.
//
// Deploy:
//   supabase functions deploy telemetry --no-verify-jwt
//
// Then point the app at:
//   https://<project>.supabase.co/functions/v1/telemetry
// instead of the REST table URL.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const ENVELOPE_REQUIRED = [
  "event_name", "schema_version", "timestamp", "session_id",
  "platform", "app_version", "os", "device_model", "screen_or_route", "log_level",
];

const LEVELS = new Set(["debug", "info", "warn", "error"]);

// event_name -> required properties
const CATALOGUE: Record<string, string[]> = {
  session_start: ["start_type"],
  screen_view: ["screen_name"],
  permission_state_resolved: ["permission", "state"],
  access_fix_requested: ["permission", "state_before"],
  access_fix_result: ["action_taken", "state_after", "outcome"],
  permission_loop: ["permission", "request_count"],
  data_query_result: ["source", "item_count"],
  api_request: ["endpoint", "method", "status_code", "latency_ms"],
  crash: ["fatal", "exception_type", "stack_trace_hash"],
  anr: ["duration_ms"],
  error_state_shown: ["error_code", "screen"],
  performance_marker: ["flow", "duration_ms"],
  feature_toggle: ["feature", "enabled"],
  feature_snapshot: ["total", "enabled_count"],
  call_screened: ["action", "outcome"],
  call_state: ["direction", "phase"],
  sms_sent: ["outcome"],
  auto_reply: ["outcome"],
  update_check: ["outcome", "current_version"],
  identity_lookup: ["outcome"],
  rage_tap: ["element", "tap_count"],
  dead_click: ["element"],
  back_button_spam: ["press_count"],
  drop_off: ["step"],
  time_to_first_value: ["duration_ms"],
  scroll_depth: ["percent"],
};

const RESERVED = new Set([...ENVELOPE_REQUIRED, "attempt_id", "user_id", "user_code",
  "network_type", "release_channel"]);

function problems(event: Record<string, unknown>): string[] {
  const out: string[] = [];
  for (const key of ENVELOPE_REQUIRED) {
    if (!event[key]) out.push(`missing '${key}'`);
  }
  const name = event.event_name as string | undefined;
  if (!name) return out;

  const required = CATALOGUE[name];
  if (!required) {
    out.push(`unknown event_name '${name}'`);
    return out;
  }
  for (const key of required) {
    if (!event[key]) out.push(`'${name}' missing '${key}'`);
  }
  const level = event.log_level as string;
  if (level && !LEVELS.has(level)) out.push(`bad log_level '${level}'`);
  return out;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "POST only" }), { status: 405 });
  }

  let payload: { user_code?: string; events?: Record<string, unknown>[] };
  try {
    payload = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "invalid JSON" }), { status: 400 });
  }

  const userCode = (payload.user_code ?? "").trim();
  const events = payload.events ?? [];

  if (!/^KV-[A-Z2-9]{3}-[A-Z2-9]{3}$/.test(userCode)) {
    return new Response(JSON.stringify({ error: "invalid user_code" }), { status: 400 });
  }
  if (!Array.isArray(events) || events.length === 0 || events.length > 200) {
    return new Response(JSON.stringify({ error: "events must be 1..200" }), { status: 400 });
  }

  const rows: Record<string, unknown>[] = [];
  const reasons: string[] = [];
  let rejected = 0;

  for (const event of events) {
    const issues = problems(event);
    if (issues.length > 0) {
      rejected++;
      if (reasons.length < 5) reasons.push(issues.join("; "));
      continue;
    }

    const properties: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(event)) {
      if (!RESERVED.has(key)) properties[key] = value;
    }

    rows.push({
      user_code: userCode,
      event_name: event.event_name,
      schema_version: event.schema_version,
      event_timestamp: event.timestamp,
      session_id: event.session_id,
      attempt_id: event.attempt_id ?? null,
      level: event.log_level,
      app_version: event.app_version,
      os: event.os,
      device_model: event.device_model,
      platform: event.platform,
      network_type: event.network_type ?? null,
      screen_or_route: event.screen_or_route,
      release_channel: event.release_channel ?? null,
      properties,
    });
  }

  if (rows.length > 0) {
    // Service role: the function is the trusted writer, so the anon key never
    // needs insert rights at all when this path is used.
    const db = createClient(SUPABASE_URL, SERVICE_KEY, {
      auth: { persistSession: false },
    });
    const { error } = await db.from("telemetry_events").insert(rows);
    if (error) {
      return new Response(
        JSON.stringify({ error: error.message, accepted: 0, rejected }),
        { status: 500, headers: { "Content-Type": "application/json" } },
      );
    }
  }

  return new Response(
    JSON.stringify({ accepted: rows.length, rejected, reasons }),
    { status: 202, headers: { "Content-Type": "application/json" } },
  );
});
