// Kavach caller-ID edge function — Supabase / Deno.
//
// Same contract and the same rules as `edge/` (the Cloudflare Worker), because
// the Android app is written against one API and should not care which host is
// behind it. What changed is the storage: Postgres instead of D1.
//
// ## What this is
//
// The two features the app shows as *Unavailable* that need a shared database:
//   GET  /v1/lookup?number=+91...   what is known about a number
//   POST /v1/reports                file a spam report
//
// The third, **verified profile**, is not here and will keep saying Unavailable:
// it needs an SMS gateway to deliver a one-time code, and a feature that looks
// available and cannot work is exactly the fake this avoids.
//
// ## What it deliberately will not do
//
// It does not store phone numbers. Every row keys on an HMAC-SHA256 of the number
// under PHONE_PEPPER, so this database cannot be turned back into a list of
// people's numbers without that secret.
//
// It does not invent a caller name. A community suggestion is counted, never
// shown — see `buildLookup`.

import { createClient } from 'jsr:@supabase/supabase-js@2';

// --------------------------------------------------------------- constants --

const PUBLISHABLE = new Set(['self_declared', 'business']);

const CONFIDENCE: Record<string, number> = {
  self_declared: 100,
  business: 95,
  community: 40,
};

const CATEGORIES = [
  'telemarketing', 'fraud', 'loan', 'insurance', 'survey',
  'robocall', 'silence', 'other',
];

/** Old reports fade rather than accumulate into a permanent sentence. */
const REPORT_WINDOW_DAYS = 180;

// ------------------------------------------------------------------ helpers --

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/** HMAC-SHA256 under the pepper. The raw number never leaves this function. */
async function hashValue(value: string, pepper: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(pepper),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * E.164-ish normalisation.
 *
 * Not a full parser — the app already sends E.164. This strips what people paste
 * and refuses anything that cannot be a number, so a garbage string never becomes
 * a hash somebody else later collides with.
 */
function normaliseNumber(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  const plus = trimmed.startsWith('+');
  const digits = trimmed.replace(/[^\d]/g, '');
  if (digits.length < 6 || digits.length > 15) return null;
  return plus ? `+${digits}` : digits;
}

/**
 * A score from a report count.
 *
 * Capped short of 100 on purpose: the app treats a high score as a reason to
 * warn, and a score that could reach 100 on reports alone would let a campaign of
 * bad-faith reports look like certainty.
 */
function scoreFromReports(count: number): number {
  if (count <= 0) return 0;
  return Math.min(Math.round(100 * (1 - Math.exp(-count / 12))), 95);
}

function freshnessOf(lastUpdated: number, now = Date.now()): string {
  if (!lastUpdated) return 'UNKNOWN';
  const days = (now - lastUpdated) / 86_400_000;
  if (days < 0) return 'UNKNOWN';
  if (days <= 7) return 'FRESH';
  if (days <= 90) return 'RECENT';
  return 'STALE';
}

/**
 * The lookup response — where the honesty rules live, so they are in one place.
 *
 * - a name is returned only when its provenance is publishable;
 * - a community suggestion is never returned as a name, only counted;
 * - nothing known means `source: NONE` and an explicit reason, not a blank card.
 */
function buildLookup(
  number: string,
  opts: { nameRow: any | null; reportCount: number; lastUpdated: number; now?: number },
) {
  const now = opts.now ?? Date.now();
  const count = opts.reportCount ?? 0;
  const score = scoreFromReports(count);
  const nameRow = opts.nameRow;
  const publishable = !!nameRow && PUBLISHABLE.has(nameRow.source);

  return {
    number,
    displayName: publishable ? nameRow.name : null,
    numberType: null,
    businessCategory: publishable && nameRow.source === 'business' ? (nameRow.category ?? null) : null,
    source: publishable ? (nameRow.source === 'business' ? 'BUSINESS' : 'CONTACT') : 'NONE',
    confidence: publishable ? (nameRow.confidence ?? CONFIDENCE[nameRow.source] ?? 0) / 100 : 0,
    // Only a self-declared name may be called verified, and there is no OTP path
    // here — so in practice this is never VERIFIED from this function.
    verificationStatus: nameRow?.source === 'self_declared' ? 'VERIFIED' : 'UNVERIFIED',
    spamRisk: score,
    reports: count,
    lastUpdated: opts.lastUpdated ?? 0,
    freshness: freshnessOf(opts.lastUpdated, now),
    unavailableReason:
      publishable || count > 0 ? null : 'Nothing is known about this number yet.',
  };
}

// -------------------------------------------------------------------- server --

Deno.serve(async (req: Request) => {
  const url = new URL(req.url);

  const SUPABASE_URL = Deno.env.get('SUPABASE_URL') ?? '';
  const SERVICE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';

  const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

  // **Secrets: environment first, then a locked-down table.**
  //
  // The proper home for these is Supabase's own Edge Function secrets
  // (Dashboard → Edge Functions → Secrets), and that is what the environment
  // branch reads. It could not be set from the tooling that deployed this, so the
  // values also live in `kavach_config`, which has RLS on and no policies — the
  // service role reads it, nothing else can.
  //
  // The order matters: move them to real secrets later and this keeps working
  // with no code change, because the environment is checked first.
  async function config(key: string): Promise<string> {
    const fromEnv = Deno.env.get(key);
    if (fromEnv) return fromEnv;
    const { data } = await db.from('kavach_config').select('value').eq('key', key).maybeSingle();
    return data?.value ?? '';
  }

  const APP_TOKEN = await config('APP_TOKEN');
  const PHONE_PEPPER = await config('PHONE_PEPPER');

  if (!APP_TOKEN || !PHONE_PEPPER) {
    // Said plainly rather than 500-ing: an unconfigured secret is a setup
    // mistake, and the message should name it.
    return json({ error: 'server is not configured: APP_TOKEN and PHONE_PEPPER must be set' }, 503);
  }

  // Liveness is open. The app's "Test connection" asks before it has been told
  // whether its token is right, and a health check that needs the credential
  // cannot answer the question it is asked.
  //
  // **Both paths, because the app asks for `/health/ready`.**
  //
  // This function used to answer only `/health`, and `endsWith('/health')` does
  // not match `/health/ready` — so the app's Test connection got a 404 against a
  // server that was working perfectly, and reported "something answered at that
  // address, but it is not a Kavach server". A health check that lies about a
  // healthy server is worse than no health check.
  if (url.pathname.endsWith('/health')) {
    return json({ status: 'ok', service: 'kavach-supabase' });
  }
  if (url.pathname.endsWith('/health/ready')) {
    // Readiness means the database is actually reachable, which is what the
    // FastAPI backend means by it too. A container that answers liveness but
    // cannot reach Postgres should not be reported ready.
    const { error } = await db.from('kavach_config').select('key').limit(1);
    return error
      ? json({ status: 'degraded', database: 'unreachable', detail: error.message }, 503)
      : json({ status: 'ready', database: 'ok', service: 'kavach-supabase' });
  }

  // Constant-time bearer check.
  const header = req.headers.get('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  let diff = token.length === APP_TOKEN.length ? 0 : 1;
  for (let i = 0; i < Math.min(token.length, APP_TOKEN.length); i += 1) {
    diff |= token.charCodeAt(i) ^ APP_TOKEN.charCodeAt(i);
  }
  if (diff !== 0) return json({ error: 'unauthorised' }, 401);

  // ---------------------------------------------------------------- lookup --
  if (url.pathname.endsWith('/lookup') && req.method === 'GET') {
    const rawParam = url.searchParams.get('number');
    const number = normaliseNumber(rawParam);
    if (!number) {
      // The received URL is echoed because "invalid phone number" with no context
      // is a 422 nobody can act on. It says nothing about the caller.
      return json(
        {
          error: 'invalid phone number',
          received: rawParam,
          search: url.search,
          path: url.pathname,
        },
        422,
      );
    }

    const digest = await hashValue(number, PHONE_PEPPER);

    // Only publishable provenance is fetched. A community suggestion is not
    // fetched at all, so nobody can later "just" use it.
    const { data: nameRow } = await db
      .from('kavach_names')
      .select('name, source, confidence, category, created_at')
      .eq('number_hash', digest)
      .eq('is_active', true)
      .in('source', ['self_declared', 'business'])
      .order('confidence', { ascending: false })
      .limit(1)
      .maybeSingle();

    const { data: scoreRow } = await db
      .from('kavach_scores')
      .select('score, report_count, updated_at')
      .eq('number_hash', digest)
      .maybeSingle();

    const lastUpdated = Math.max(
      nameRow ? Date.parse(nameRow.created_at) : 0,
      scoreRow ? Date.parse(scoreRow.updated_at) : 0,
    );

    return json(
      buildLookup(number, {
        nameRow,
        reportCount: scoreRow?.report_count ?? 0,
        lastUpdated,
      }),
    );
  }

  // --------------------------------------------------------------- reports --
  if (url.pathname.endsWith('/reports') && req.method === 'POST') {
    let body: any;
    try {
      body = await req.json();
    } catch {
      return json({ error: 'body must be JSON' }, 400);
    }

    const number = normaliseNumber(body?.number);
    if (!number) return json({ error: 'invalid phone number' }, 422);

    const category = String(body?.category ?? '').toLowerCase();
    if (!CATEGORIES.includes(category)) {
      // Refused, not filed as "other" — that would hide a typo forever.
      return json({ error: `category must be one of: ${CATEGORIES.join(', ')}` }, 422);
    }
    const note = typeof body?.note === 'string' ? body.note.slice(0, 500) : null;

    const digest = await hashValue(number, PHONE_PEPPER);

    // **The reporter must be per install, not the shared token.**
    //
    // This used to hash the Authorization header — which is the *same* APP_TOKEN
    // for every client. So every reporter produced an identical hash, and because
    // reports upsert on (number_hash, reporter_hash), the second person's report
    // silently *replaced* the first instead of adding to it. A crowd of fifty
    // looked like one person, and the score never moved past 8.
    //
    // The app sends its own per-install pseudonym, `KV-XXX-XXX`, generated on the
    // device and derived from nothing about the person. That is what identifies a
    // reporter.
    const install = (req.headers.get('x-kavach-install') ?? '').trim();
    if (!install) {
      // Refused rather than collapsed into one bucket. Accepting the request and
      // counting it as a duplicate is the bug this replaced, and it is worse than
      // a clear error because nobody ever finds out.
      return json(
        {
          error: 'missing X-Kavach-Install header',
          detail:
            'Reports are counted per install. Without it every report from every ' +
            'device would collapse into one, and a crowd would look like one person.',
        },
        422,
      );
    }
    const reporter = await hashValue(install, PHONE_PEPPER);

    await db.from('kavach_numbers').upsert(
      { number_hash: digest, country: 'IN' },
      { onConflict: 'number_hash', ignoreDuplicates: true },
    );

    // One row per reporter per number, replaced rather than added. Two taps from
    // one person is one report, which is what stops a score being inflated by
    // somebody who is annoyed rather than numerous.
    await db.from('kavach_reports').upsert(
      { number_hash: digest, reporter_hash: reporter, category, note, created_at: new Date().toISOString() },
      { onConflict: 'number_hash,reporter_hash' },
    );

    const cutoff = new Date(Date.now() - REPORT_WINDOW_DAYS * 86_400_000).toISOString();
    const { count } = await db
      .from('kavach_reports')
      .select('*', { count: 'exact', head: true })
      .eq('number_hash', digest)
      .gte('created_at', cutoff);

    const reportCount = count ?? 0;
    const score = scoreFromReports(reportCount);

    await db.from('kavach_scores').upsert(
      {
        number_hash: digest,
        score,
        report_count: reportCount,
        updated_at: new Date().toISOString(),
      },
      { onConflict: 'number_hash' },
    );

    return json(
      {
        accepted: true,
        reports: reportCount,
        detail:
          'Report counted. This is one person\u2019s report, not a finding \u2014 it ' +
          'moves the score, and the app shows it as a community report rather than ' +
          'as a fact.',
      },
      202,
    );
  }

  return json({ error: 'not found' }, 404);
});
