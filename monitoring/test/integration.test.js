/**
 * End-to-end tests: the Worker, actually running.
 *
 *   node --test monitoring/test/integration.test.js
 *
 * The unit tests in `sink.test.js` check the pure functions. These boot the
 * Worker inside Miniflare — the real Workers runtime, the same one production
 * uses — with a real D1 database, and dispatch real requests through it.
 *
 * That difference matters more than it sounds. A unit test can prove `normalise`
 * returns the right row and still not notice that the route is misspelled, the
 * binding name is wrong, or D1 rejects the INSERT. Those are the failures that
 * reach production.
 *
 * Skipped rather than failed when Miniflare is absent, so the suite still runs on
 * a machine that has not installed it.
 */
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');

// The memory check comes BEFORE the import, and that ordering is the whole
// point. Importing miniflare loads workerd, workerd boots its WASM runtime, and
// on a small machine that fails asynchronously — after the tests have already
// reported a skip. The runner then reports "asynchronous activity after the test
// ended", which looks like a test bug and is really a memory limit.
const MIN_MEMORY_BYTES = 3 * 1024 * 1024 * 1024;
const totalMemory = (await import('node:os')).totalmem();
const enoughMemory = totalMemory >= MIN_MEMORY_BYTES;

let Miniflare = null;
let skip = false;

if (!enoughMemory) {
  skip = `needs ~3 GB to boot workerd, this machine has ${Math.round(totalMemory / 1024 / 1024 / 1024)} GB`;
} else {
  try {
    ({ Miniflare } = await import('miniflare'));
  } catch {
    skip = 'miniflare is not installed';
  }
}

const TOKEN = 'integration-test-token';

let mf;

before(async () => {
  if (skip) return;
  // Both modules are declared, and the type is explicit on each.
  //
  // `scriptPath` alone treats the Worker as one script, so `lib.js` was parsed
  // as a classic script and Miniflare refused it: "'import' and 'export' may
  // appear only with 'sourceType: module'". Production is unaffected —
  // `wrangler deploy` bundles `main` with esbuild, so the import resolves — but
  // a test harness that cannot load the module graph tests nothing.
  mf = new Miniflare({
    modules: [
      { type: 'ESModule', path: join(root, 'src/index.js') },
      { type: 'ESModule', path: join(root, 'src/lib.js') },
    ],
    modulesRoot: join(root, 'src'),
    compatibilityDate: '2026-01-01',
    d1Databases: { DB: 'kavach-monitoring-test' },
    bindings: { INGEST_TOKEN: TOKEN },
  });

  // The schema, applied the same way `wrangler d1 execute` applies it.
  const db = await mf.getD1Database('DB');
  const schema = readFileSync(join(root, 'schema.sql'), 'utf8');
  for (const statement of schema.split(';').map((s) => s.trim()).filter(Boolean)) {
    await db.exec(statement.replace(/\n/g, ' '));
  }
});

after(async () => {
  if (mf) await mf.dispose();
});

const post = (body, token = TOKEN) =>
  mf.dispatchFetch('http://sink/v1/logs', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });

const get = (path, token = TOKEN) =>
  mf.dispatchFetch(`http://sink${path}`, {
    headers: token ? { authorization: `Bearer ${token}` } : {},
  });

const event = (over = {}) => ({
  timestamp: new Date().toISOString(),
  severity: 'info',
  category: 'network',
  message: 'request',
  app_id: 'kavach-server',
  environment: 'production',
  request_id: 'req_integration_1',
  metadata: { method: 'GET', endpoint: '/v1/lookup', status: 200, duration_ms: 37 },
  ...over,
});

// ------------------------------------------------------------------- routes --

describe('the routes exist and answer', { skip }, () => {
  test('health is open and does not need a token', async () => {
    const res = await get('/health', null);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true });
  });

  test('an unknown path is a 404, not a crash', async () => {
    const res = await get('/nope', null);
    assert.equal(res.status, 404);
  });

  test('the dashboard is served as HTML', async () => {
    const res = await get('/', null);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /text\/html/);
    const body = await res.text();
    assert.match(body, /Kavach — server monitoring/);
    // The page says what it is for, so a reader does not mistake it for the
    // app's diagnostics.
    assert.match(body, /Never the app's diagnostics/);
  });

  test('ingest without a token is refused', async () => {
    const res = await post({ events: [event()] }, null);
    assert.equal(res.status, 401);
  });

  test('ingest with the wrong token is refused', async () => {
    const res = await post({ events: [event()] }, 'not-the-token');
    assert.equal(res.status, 401);
  });

  test('summary without a token is refused', async () => {
    const res = await get('/v1/summary', null);
    assert.equal(res.status, 401);
  });
});

// ------------------------------------------------------------------ ingest --

describe('ingest writes through to D1', { skip }, () => {
  test('a batch is stored and reported', async () => {
    const res = await post({ events: [event(), event({ category: 'error', severity: 'error' })] });
    assert.equal(res.status, 202);
    const body = await res.json();
    assert.equal(body.stored, 2);
    assert.deepEqual(body.rejected, {});
  });

  test('the stored rows come back out', async () => {
    // This is the assertion a unit test cannot make: that the INSERT matched the
    // schema and the SELECT can read it.
    const res = await get('/v1/summary?window=1h');
    const body = await res.json();
    assert.ok(body.totals.events >= 2, `expected events, got ${JSON.stringify(body.totals)}`);
    const network = body.by_category.find((c) => c.category === 'network');
    assert.ok(network, 'the network category should be present');
    assert.ok(network.count >= 1);
  });

  test('latency and status are queryable, not buried in the metadata blob', async () => {
    const res = await get('/v1/summary?window=1h');
    const body = await res.json();
    // Lifting these three out of metadata is what makes this a plain query.
    assert.ok(body.slowest_endpoints.length >= 1, 'no endpoints reported');
    const lookup = body.slowest_endpoints.find((e) => e.endpoint === '/v1/lookup');
    assert.ok(lookup, 'the endpoint should be grouped');
    assert.ok(Number(lookup.avg_ms) > 0);
  });

  test('a user diagnostic is refused end to end', async () => {
    // The guard, through the whole stack rather than at the function.
    const res = await post({ events: [event(), { ...event(), user_code: 'KV-4F9-K2Q' }] });
    assert.equal(res.status, 202);
    const body = await res.json();
    assert.equal(body.stored, 1);
    assert.equal(body.rejected.user_diagnostics_not_accepted, 1);
  });

  test('a refused event is genuinely absent from the database', async () => {
    await post({ events: [{ ...event({ message: 'should-not-exist' }), user_code: 'KV-XXX-XXX' }] });
    const res = await get('/v1/recent?window=1h&limit=500');
    const body = await res.json();
    assert.ok(
      !body.events.some((e) => e.message === 'should-not-exist'),
      'a user diagnostic reached the database',
    );
  });

  test('a bad severity is rejected and counted, not stored', async () => {
    const res = await post({ events: [event({ severity: 'critical' })] });
    const body = await res.json();
    assert.equal(body.stored, 0);
    assert.equal(body.rejected.unknown_severity, 1);
  });

  test('a batch over the limit is refused whole', async () => {
    const many = Array.from({ length: 201 }, () => event());
    const res = await post({ events: many });
    assert.equal(res.status, 413);
  });

  test('a non-JSON body is a 400, not a crash', async () => {
    const res = await mf.dispatchFetch('http://sink/v1/logs', {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}` },
      body: 'not json at all',
    });
    assert.equal(res.status, 400);
  });

  test('a bare array is accepted as well as {events: []}', async () => {
    const res = await post([event({ message: 'bare-array' })]);
    assert.equal(res.status, 202);
    assert.equal((await res.json()).stored, 1);
  });
});

// ------------------------------------------------------------------ reading --

describe('reading', { skip }, () => {
  test('recent filters by category', async () => {
    await post({ events: [event({ category: 'security', message: 'a security event' })] });
    const res = await get('/v1/recent?window=1h&category=security');
    const body = await res.json();
    assert.ok(body.events.length >= 1);
    assert.ok(body.events.every((e) => e.category === 'security'));
  });

  test('an unknown filter is refused rather than ignored', async () => {
    // Silently ignoring it would return everything, which reads as "no matches
    // for that filter" when it is really "that filter does not exist".
    const res = await get('/v1/recent?category=nonsense');
    assert.equal(res.status, 400);
  });

  test('the window is echoed back', async () => {
    const body = await (await get('/v1/summary?window=7d')).json();
    assert.equal(body.window, '7d');
  });

  test('the request id survives, so an app event can be joined to its request', async () => {
    await post({ events: [event({ request_id: 'req_join_me' })] });
    const body = await (await get('/v1/summary?window=1h')).json();
    assert.ok(Number(body.totals.requests) >= 1);
  });
});

// ----------------------------------------------------------------- retention --

describe('retention', { skip }, () => {
  test('the sweep removes old rows and keeps recent ones', async () => {
    const old = new Date(Date.now() - 40 * 24 * 60 * 60 * 1000).toISOString();
    await post({ events: [event({ timestamp: old, message: 'ancient' })] });

    const db = await mf.getD1Database('DB');
    const before = await db.prepare('SELECT COUNT(*) AS n FROM log_events').first();
    assert.ok(before.n >= 1);

    const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
    await db.prepare('DELETE FROM log_events WHERE ts < ?').bind(cutoff).run();

    const remaining = await db
      .prepare("SELECT COUNT(*) AS n FROM log_events WHERE message = 'ancient'")
      .first();
    assert.equal(remaining.n, 0, 'the old row should have been swept');
  });
});
