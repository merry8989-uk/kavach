/**
 * Kavach monitoring sink.
 *
 * The backend has emitted structured JSON logs since v0.35.0 — twelve
 * categories, six severities, one envelope with a correlation id. What it has
 * never had is anywhere to put them. This is that place: a Cloudflare Worker in
 * front of D1, which is a good fit because the volume is small, the queries are
 * aggregates, and nothing here needs to be fast enough to sit in a request path.
 *
 * What this is for
 * ----------------
 * The **server's** own health: request latency, error rates, which category is
 * noisy, whether the database is reachable, which provider is failing. That is
 * categories 6, 7, 8 and 11 of the taxonomy, and none of it can be seen from a
 * phone.
 *
 * What this deliberately refuses
 * ------------------------------
 * **Anything from the app.** The user's diagnostics log stays on their device
 * and leaves only when they choose to send it — that is a standing instruction
 * and a design decision, not a setting. This sink enforces it rather than
 * documenting it: every event the app emits carries a `user_code` (its readable
 * per-install pseudonym), and the server never emits one. An event that carries
 * one is rejected outright.
 *
 * So the rule is not "please do not send user diagnostics here". The rule is
 * that user diagnostics cannot be stored here even if something tried.
 */

// The same vocabularies the app and the server use. Duplicated rather than
// imported because this runs in a different runtime; a test checks the three
// stay in step.
export const SEVERITIES = ['trace', 'debug', 'info', 'warn', 'error', 'fatal'];
export const CATEGORIES = [
  'application', 'error', 'security', 'performance', 'activity',
  'infrastructure', 'network', 'database', 'audit', 'analytics',
  'background', 'trace',
];

/** Rejected rather than stored: see the note above about `user_code`. */
export const REJECTION = {
  USER_DIAGNOSTICS: 'user_diagnostics_not_accepted',
  UNKNOWN_SEVERITY: 'unknown_severity',
  UNKNOWN_CATEGORY: 'unknown_category',
  MALFORMED: 'malformed_event',
};

export const MAX_BATCH = 200;
export const MAX_MESSAGE = 500;
/** Days kept. The cron trigger prunes anything older. */
export const RETENTION_DAYS = 30;

// ---------------------------------------------------------------- validating --

/**
 * Coerce one event into a storable row, or say why not.
 *
 * Returns `{row}` or `{rejected}`. Nothing is silently dropped: a batch response
 * reports what was stored and what was refused, and why, because a sink that
 * quietly discards is a sink that lies about coverage.
 */
export function normalise(input) {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) {
    return { rejected: REJECTION.MALFORMED };
  }

  // The guard. An app event carries the install's pseudonym; a server event does
  // not — the key is not present at all.
  //
  // Presence, not truthiness, and the difference is the whole guard: a test
  // caught the first version using `if (input.user_code)`, which would have let
  // `user_code: ""` through. For a privacy check the stricter reading is the
  // right one, so an empty or null value is refused too.
  if ('user_code' in input) {
    return { rejected: REJECTION.USER_DIAGNOSTICS };
  }

  const severity = String(input.severity ?? '').toLowerCase();
  if (!SEVERITIES.includes(severity)) {
    return { rejected: REJECTION.UNKNOWN_SEVERITY };
  }

  // A typo must not create a category no filter will ever match, and it must not
  // be silently filed under something else either — it is reported.
  const category = String(input.category ?? '').toLowerCase();
  if (!CATEGORIES.includes(category)) {
    return { rejected: REJECTION.UNKNOWN_CATEGORY };
  }

  const metadata = input.metadata && typeof input.metadata === 'object' ? input.metadata : {};

  // Three fields are lifted out of the metadata blob so latency, error rate and
  // endpoint queries are plain indexed SQL rather than JSON parsing inside
  // SQLite. They are the three this sink exists to answer.
  const durationMs = toInt(metadata.duration_ms);
  const status = toInt(metadata.status);
  const endpoint = typeof metadata.endpoint === 'string' ? metadata.endpoint.slice(0, 200) : null;

  return {
    row: {
      ts: parseTimestamp(input.timestamp),
      severity,
      category,
      message: truncate(input.message, MAX_MESSAGE),
      app_id: truncate(input.app_id, 60),
      environment: truncate(input.environment, 30),
      request_id: truncate(input.request_id, 64),
      logger: truncate(input.logger, 60),
      duration_ms: durationMs,
      status,
      endpoint,
      exception_type: truncate(input.exception?.type, 80),
      metadata: JSON.stringify(metadata).slice(0, 4000),
    },
  };
}

function truncate(value, max) {
  if (value === null || value === undefined) return null;
  const s = String(value);
  return s.length > max ? s.slice(0, max) : s;
}

function toInt(value) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : null;
}

/** Epoch millis, or now. An unparseable timestamp is not worth refusing over. */
export function parseTimestamp(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  const parsed = Date.parse(String(value ?? ''));
  return Number.isFinite(parsed) ? parsed : Date.now();
}

// ------------------------------------------------------------------- storage --

export async function store(db, rows, receivedAt) {
  if (rows.length === 0) return;
  const stmt = db.prepare(
    `INSERT INTO log_events
       (received_at, ts, severity, category, message, app_id, environment,
        request_id, logger, duration_ms, status, endpoint, exception_type, metadata)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  );
  const batch = rows.map((r) =>
    stmt.bind(
      receivedAt, r.ts, r.severity, r.category, r.message, r.app_id, r.environment,
      r.request_id, r.logger, r.duration_ms, r.status, r.endpoint, r.exception_type, r.metadata,
    ),
  );
  await db.batch(batch);
}

const WINDOWS = {
  '1h': 60 * 60 * 1000,
  '6h': 6 * 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
  '30d': 30 * 24 * 60 * 60 * 1000,
};

export function windowMs(window, now = Date.now()) {
  return now - (WINDOWS[window] ?? WINDOWS['24h']);
}

/**
 * The numbers worth looking at, in one round trip each.
 *
 * Deliberately a handful of small queries rather than one clever one: these are
 * read by a person looking at a dashboard, and an unreadable query that is
 * marginally faster is the wrong trade.
 */
export async function summary(db, window = '24h') {
  const since = windowMs(window);

  const totals = await db
    .prepare(
      `SELECT
         COUNT(*) AS events,
         SUM(CASE WHEN severity IN ('error','fatal') THEN 1 ELSE 0 END) AS errors,
         SUM(CASE WHEN severity = 'warn' THEN 1 ELSE 0 END) AS warnings,
         COUNT(DISTINCT request_id) AS requests,
         COUNT(DISTINCT app_id) AS apps
       FROM log_events WHERE ts >= ?`,
    )
    .bind(since)
    .first();

  const byCategory = await db
    .prepare(
      `SELECT category, COUNT(*) AS count,
              SUM(CASE WHEN severity IN ('error','fatal') THEN 1 ELSE 0 END) AS errors
       FROM log_events WHERE ts >= ? GROUP BY category ORDER BY count DESC`,
    )
    .bind(since)
    .all();

  const bySeverity = await db
    .prepare(
      `SELECT severity, COUNT(*) AS count FROM log_events WHERE ts >= ?
       GROUP BY severity ORDER BY count DESC`,
    )
    .bind(since)
    .all();

  // Latency and error rate, which is what the review asked for by name.
  const traffic = await db
    .prepare(
      `SELECT
         COUNT(*) AS requests,
         SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) AS server_errors,
         SUM(CASE WHEN status IN (401,403) THEN 1 ELSE 0 END) AS refused,
         AVG(duration_ms) AS avg_ms,
         MAX(duration_ms) AS max_ms
       FROM log_events
       WHERE ts >= ? AND category = 'network' AND duration_ms IS NOT NULL`,
    )
    .bind(since)
    .first();

  const slowest = await db
    .prepare(
      `SELECT endpoint, COUNT(*) AS calls, AVG(duration_ms) AS avg_ms, MAX(duration_ms) AS max_ms
       FROM log_events
       WHERE ts >= ? AND category = 'network' AND endpoint IS NOT NULL AND duration_ms IS NOT NULL
       GROUP BY endpoint ORDER BY avg_ms DESC LIMIT 10`,
    )
    .bind(since)
    .all();

  // Provider failures: the AI and SMS endpoints are the two that reach out, and
  // an unconfigured provider answers 503 rather than pretending.
  const unavailable = await db
    .prepare(
      `SELECT endpoint, COUNT(*) AS count FROM log_events
       WHERE ts >= ? AND status = 503 AND endpoint IS NOT NULL
       GROUP BY endpoint ORDER BY count DESC LIMIT 10`,
    )
    .bind(since)
    .all();

  const totalsRow = totals ?? {};
  const requests = Number(totalsRow.requests ?? 0);
  const errors = Number(totalsRow.errors ?? 0);

  return {
    window,
    since: new Date(since).toISOString(),
    totals: {
      events: Number(totalsRow.events ?? 0),
      errors,
      warnings: Number(totalsRow.warnings ?? 0),
      requests,
      apps: Number(totalsRow.apps ?? 0),
      error_rate: requests > 0 ? Number((errors / requests).toFixed(4)) : 0,
    },
    by_category: byCategory.results ?? [],
    by_severity: bySeverity.results ?? [],
    traffic: traffic ?? {},
    slowest_endpoints: slowest.results ?? [],
    provider_unavailable: unavailable.results ?? [],
  };
}

// ------------------------------------------------------------------ handlers --

function json(body, status = 200) {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/**
 * Bearer-token check.
 *
 * A monitoring sink that anyone can write to is a sink anyone can poison, and
 * the aggregates are only worth reading if the input is trustworthy.
 */
function authorised(request, env) {
  const expected = env.INGEST_TOKEN;
  if (!expected) return false; // no token configured means no writes at all
  const header = request.headers.get('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  // Length first: a constant-time compare over different lengths leaks the length.
  if (token.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < token.length; i += 1) diff |= token.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
}

async function ingest(request, env) {
  if (!authorised(request, env)) {
    return json({ error: 'unauthorised' }, 401);
  }

  let payload;
  try {
    payload = await request.json();
  } catch {
    return json({ error: 'body must be JSON' }, 400);
  }

  const incoming = Array.isArray(payload) ? payload : payload?.events;
  if (!Array.isArray(incoming)) {
    return json({ error: 'expected an array, or {events: [...]}' }, 400);
  }
  if (incoming.length > MAX_BATCH) {
    return json({ error: `at most ${MAX_BATCH} events per batch`, received: incoming.length }, 413);
  }

  const rows = [];
  const rejected = {};
  for (const event of incoming) {
    const result = normalise(event);
    if (result.row) {
      rows.push(result.row);
    } else {
      // Counted, never silently dropped.
      rejected[result.rejected] = (rejected[result.rejected] ?? 0) + 1;
    }
  }

  await store(env.DB, rows, Date.now());
  return json({ stored: rows.length, rejected }, 202);
}

async function recent(request, env, url) {
  if (!authorised(request, env)) return json({ error: 'unauthorised' }, 401);

  const limit = Math.min(Number(url.searchParams.get('limit') ?? 100) || 100, 500);
  const filters = ['ts >= ?'];
  const binds = [windowMs(url.searchParams.get('window') ?? '24h')];

  const category = url.searchParams.get('category');
  if (category) {
    if (!CATEGORIES.includes(category)) return json({ error: 'unknown category' }, 400);
    filters.push('category = ?');
    binds.push(category);
  }
  const severity = url.searchParams.get('severity');
  if (severity) {
    if (!SEVERITIES.includes(severity)) return json({ error: 'unknown severity' }, 400);
    filters.push('severity = ?');
    binds.push(severity);
  }

  const rows = await env.DB.prepare(
    `SELECT ts, severity, category, message, app_id, environment, request_id, logger,
            duration_ms, status, endpoint, exception_type
     FROM log_events WHERE ${filters.join(' AND ')}
     ORDER BY ts DESC LIMIT ?`,
  )
    .bind(...binds, limit)
    .all();

  return json({ events: rows.results ?? [] });
}

/**
 * The dashboard.
 *
 * One small page rather than a separate front-end, because the audience is one
 * operator looking at a handful of numbers, and a build step for that would be
 * more machinery than the thing it serves.
 */
function dashboard() {
  const html = `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kavach — server monitoring</title>
<style>
  :root { color-scheme: dark }
  body { margin:0; padding:28px; background:#03090F; color:#e7eaf0;
         font:15px/1.5 ui-sans-serif,system-ui,-apple-system,sans-serif }
  h1 { font-size:20px; margin:0 0 4px }
  .sub { color:#8b93a3; font-size:13px; margin-bottom:22px }
  .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; margin-bottom:22px }
  .card { background:#0b1219; border:1px solid #1b2530; border-radius:12px; padding:14px 16px }
  .card .n { font-size:26px; font-weight:600 }
  .card .l { color:#8b93a3; font-size:12px; margin-top:2px }
  h2 { font-size:14px; color:#8b93a3; font-weight:600; margin:22px 0 8px; text-transform:uppercase; letter-spacing:.04em }
  table { width:100%; border-collapse:collapse; font-size:14px }
  th,td { text-align:left; padding:7px 10px; border-bottom:1px solid #151d26 }
  th { color:#8b93a3; font-weight:500; font-size:12px }
  .bad { color:#ff8b8b }
  .warn { color:#ffd08b }
  .ok { color:#7ee0a8 }
  .err { color:#ff8b8b; padding:20px }
  a { color:#6cc6ff }
</style></head><body>
<h1>Kavach — server monitoring</h1>
<div class="sub">The server's own health. Never the app's diagnostics — those stay on the device.</div>
<div id="root" class="err">Loading…</div>
<script>
const root = document.getElementById('root');
const pct = (v) => (v * 100).toFixed(2) + '%';
const ms = (v) => v == null ? '—' : Math.round(v) + ' ms';
function table(head, rows) {
  if (!rows || !rows.length) return '<div class="sub">Nothing in this window.</div>';
  return '<table><tr>' + head.map(h => '<th>' + h + '</th>').join('') + '</tr>' +
    rows.map(r => '<tr>' + r.map(c => '<td>' + c + '</td>').join('') + '</tr>').join('') + '</table>';
}
async function load(w) {
  try {
    const r = await fetch('/v1/summary?window=' + w);
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const d = await r.json();
    const t = d.totals;
    root.className = '';
    root.innerHTML =
      '<div class="grid">' +
        card(t.events, 'events') + card(t.errors, 'errors') +
        card(pct(t.error_rate), 'error rate') + card(t.requests, 'requests') +
        card(t.warnings, 'warnings') + card(t.apps, 'app ids') +
      '</div>' +
      '<h2>By category</h2>' +
      table(['Category','Events','Errors'],
        d.by_category.map(c => [c.category, c.count, c.errors ? '<span class="bad">' + c.errors + '</span>' : '0'])) +
      '<h2>By severity</h2>' +
      table(['Severity','Events'],
        d.by_severity.map(s => ['<span class="' + sevClass(s.severity) + '">' + s.severity + '</span>', s.count])) +
      '<h2>Slowest endpoints</h2>' +
      table(['Endpoint','Calls','Average','Max'],
        d.slowest_endpoints.map(e => [e.endpoint, e.calls, ms(e.avg_ms), ms(e.max_ms)])) +
      '<h2>Provider unavailable (503)</h2>' +
      table(['Endpoint','Count'],
        d.provider_unavailable.map(e => [e.endpoint, '<span class="warn">' + e.count + '</span>'])) +
      '<div class="sub" style="margin-top:18px">Window: ' + d.window +
        ' · since ' + d.since + '</div>';
  } catch (e) {
    root.className = 'err';
    root.textContent = 'Could not load: ' + e.message;
  }
}
function card(n, l) { return '<div class="card"><div class="n">' + n + '</div><div class="l">' + l + '</div></div>'; }
function sevClass(s) { return s === 'fatal' || s === 'error' ? 'bad' : s === 'warn' ? 'warn' : 'ok'; }
load('24h');
</script></body></html>`;
  return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8' } });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/health') return json({ ok: true });
    if (url.pathname === '/') return dashboard();

    if (url.pathname === '/v1/logs' && request.method === 'POST') return ingest(request, env);
    if (url.pathname === '/v1/summary') {
      if (!authorised(request, env)) return json({ error: 'unauthorised' }, 401);
      return json(await summary(env.DB, url.searchParams.get('window') ?? '24h'));
    }
    if (url.pathname === '/v1/recent') return recent(request, env, url);

    return json({ error: 'not found' }, 404);
  },

  /**
   * Retention.
   *
   * A sink with no ceiling is a sink that eventually costs more than it is worth
   * and holds data nobody agreed to keep. Thirty days is enough to see a trend
   * and short enough to forget a bad week.
   */
  async scheduled(event, env) {
    const cutoff = Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000;
    const result = await env.DB.prepare('DELETE FROM log_events WHERE ts < ?').bind(cutoff).run();
    console.log(JSON.stringify({
      severity: 'info',
      category: 'infrastructure',
      message: 'retention sweep',
      app_id: 'kavach-monitoring',
      deleted: result.meta?.changes ?? 0,
    }));
  },
};
