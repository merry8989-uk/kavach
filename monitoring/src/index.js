/**
 * The monitoring sink — entry point.
 *
 * **This module exports exactly one thing: the default handler.**
 *
 * A Worker's named exports are entry points, so anything else exported from here
 * is treated as a handler and rejected unless it is a function or an
 * ExportedHandler:
 *
 *   Uncaught TypeError: Incorrect type for map entry 'MAX_BATCH':
 *   the provided value is not of type 'function or ExportedHandler'.
 *
 * That is not a warning — the Worker does not start. The unit tests could not
 * catch it, because Node does not care about extra exports. The integration test
 * caught it on its first run, which is the whole reason it exists.
 *
 * Everything testable lives in `lib.js`. See `README.md` for what this sink is
 * for, and for what it deliberately refuses.
 */
import {
  CATEGORIES,
  MAX_BATCH,
  RETENTION_DAYS,
  SEVERITIES,
  normalise,
  store,
  summary,
  windowMs,
} from './lib.js';

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
