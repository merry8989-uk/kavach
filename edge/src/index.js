/**
 * Kavach edge server — entry point.
 *
 * **This module exports exactly one thing: the default handler.** A Worker's
 * named exports are entry points, and exporting a constant from here makes
 * workerd refuse to start. Everything testable lives in `lib.js`.
 *
 * ## What this is
 *
 * The three features the app shows as *Unavailable* need a shared database, and
 * no amount of local work gives them one:
 *
 * - **online caller lookup** — `GET /v1/lookup`
 * - **community reports** — `POST /v1/reports`
 * - **verified profile** — not here; it needs an SMS gateway to deliver a code,
 *   and pretending otherwise would be the fake this is meant to avoid
 *
 * ## What it deliberately is not
 *
 * It does not store phone numbers. Every row keys on an HMAC of the number under
 * a pepper held in a Worker secret, so the database cannot be turned back into a
 * list of people's numbers without that pepper.
 *
 * It does not invent a caller name. A community suggestion is counted, never
 * shown — `buildLookup` is where that rule lives.
 */
import {
  buildLookup,
  hashNumber,
  normaliseNumber,
  scoreFromReports,
  validateReport,
  REPORT_WINDOW_DAYS,
} from './lib.js';

function json(body, status = 200) {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/**
 * Bearer-token check, constant time.
 *
 * A shared server that anyone can write to is a server whose data means nothing,
 * and the aggregates are only worth reading if the input is trustworthy.
 */
function authorised(request, env) {
  const expected = env.APP_TOKEN;
  if (!expected) return false;
  const header = request.headers.get('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (token.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < token.length; i += 1) diff |= token.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
}

/** The reporter's identity for dedupe. Never the number being reported. */
async function reporterHash(request, env) {
  // The token identifies the install; hashing it keeps the reports table free of
  // anything that could be reversed into a credential.
  const header = request.headers.get('authorization') ?? '';
  return hashNumber(header, env.PHONE_PEPPER ?? 'no-pepper');
}

// ------------------------------------------------------------------- lookup --

async function lookup(request, env, url) {
  const raw = url.searchParams.get('number');
  const number = normaliseNumber(raw);
  if (!number) return json({ error: 'invalid phone number' }, 422);

  const digest = await hashNumber(number, env.PHONE_PEPPER ?? '');

  // The best publishable name, or nothing. A community suggestion is deliberately
  // not fetched here — see buildLookup, which would refuse it anyway, and fetching
  // it would invite someone to "just" use it.
  const nameRow = await env.DB.prepare(
    `SELECT name, source, confidence, created_at
     FROM names
     WHERE number_hash = ? AND is_active = 1 AND source IN ('self_declared','business')
     ORDER BY confidence DESC, created_at DESC
     LIMIT 1`,
  )
    .bind(digest)
    .first();

  const scoreRow = await env.DB.prepare(
    'SELECT score, report_count, updated_at FROM scores WHERE number_hash = ?',
  )
    .bind(digest)
    .first();

  return json(
    buildLookup(number, {
      nameRow,
      reportCount: scoreRow?.report_count ?? 0,
      lastUpdated: Math.max(nameRow?.created_at ?? 0, scoreRow?.updated_at ?? 0),
    }),
  );
}

// ------------------------------------------------------------------- reports --

async function report(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: 'body must be JSON' }, 400);
  }

  const parsed = validateReport(body);
  if (parsed.error) return json({ error: parsed.error }, 422);

  const digest = await hashNumber(parsed.number, env.PHONE_PEPPER ?? '');
  const reporter = await reporterHash(request, env);
  const now = Date.now();

  await env.DB.prepare(
    'INSERT INTO numbers (number_hash, country, first_seen) VALUES (?, ?, ?) ' +
      'ON CONFLICT(number_hash) DO NOTHING',
  )
    .bind(digest, 'IN', now)
    .run();

  // One row per reporter per number, replaced rather than added. Two taps from
  // the same person is one report, which is what stops a score being inflated by
  // somebody who is annoyed rather than numerous.
  await env.DB.prepare(
    'INSERT INTO reports (number_hash, reporter_hash, category, note, created_at) ' +
      'VALUES (?, ?, ?, ?, ?) ' +
      'ON CONFLICT(number_hash, reporter_hash) DO UPDATE SET ' +
      'category = excluded.category, note = excluded.note, created_at = excluded.created_at',
  )
    .bind(digest, reporter, parsed.category, parsed.note, now)
    .run();

  // Count only reports inside the window: an old complaint should fade rather
  // than accumulate into a permanent sentence.
  const cutoff = now - REPORT_WINDOW_DAYS * 86_400_000;
  const counted = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM reports WHERE number_hash = ? AND created_at >= ?',
  )
    .bind(digest, cutoff)
    .first();

  const count = counted?.n ?? 0;
  const score = scoreFromReports(count);

  await env.DB.prepare(
    'INSERT INTO scores (number_hash, score, report_count, updated_at) VALUES (?, ?, ?, ?) ' +
      'ON CONFLICT(number_hash) DO UPDATE SET ' +
      'score = excluded.score, report_count = excluded.report_count, updated_at = excluded.updated_at',
  )
    .bind(digest, score, count, now)
    .run();

  return json(
    {
      accepted: true,
      reports: count,
      detail:
        'Report counted. This is one person\u2019s report, not a finding \u2014 it ' +
        'moves the score, and the app shows it as a community report rather than ' +
        'as a fact.',
    },
    202,
  );
}

// ------------------------------------------------------------------ handlers --

function status() {
  const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kavach edge server</title>
<style>
  body{margin:0;padding:32px;background:#03090F;color:#e7eaf0;
       font:15px/1.6 ui-sans-serif,system-ui,-apple-system,sans-serif}
  h1{font-size:20px;margin:0 0 6px}
  .sub{color:#8b93a3;font-size:13px;margin-bottom:24px}
  code{background:#0b1219;border:1px solid #1b2530;border-radius:6px;padding:2px 6px}
  li{margin-bottom:6px}
  .note{margin-top:24px;color:#8b93a3;font-size:13px;max-width:640px}
</style></head><body>
<h1>Kavach edge server</h1>
<div class="sub">Caller lookup and community reports, on Cloudflare Workers + D1.</div>
<ul>
  <li><code>GET /health</code> — liveness, used by the app's Test connection</li>
  <li><code>GET /v1/lookup?number=+919876543210</code> — what is known about a number</li>
  <li><code>POST /v1/reports</code> — file a spam report</li>
</ul>
<div class="note">
  Numbers are never stored. Every row keys on an HMAC of the number, so this
  database cannot be turned back into a list of people's phone numbers without
  the pepper. A community suggestion is counted, never shown as a name.
</div>
</body></html>`;
  return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8' } });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Liveness is open: the app's "Test connection" checks it before it has been
    // told whether its token is right, and a health check that needs a credential
    // cannot answer the question it is asked.
    if (url.pathname === '/health') {
      return json({ status: 'ok', service: 'kavach-edge' });
    }
    if (url.pathname === '/' && request.method === 'GET') return status();

    if (!authorised(request, env)) return json({ error: 'unauthorised' }, 401);

    if (url.pathname === '/v1/lookup' && request.method === 'GET') {
      return lookup(request, env, url);
    }
    if (url.pathname === '/v1/reports' && request.method === 'POST') {
      return report(request, env);
    }

    return json({ error: 'not found' }, 404);
  },
};
