/**
 * The edge server, actually running.
 *
 * Boots the Worker in Miniflare — the real Workers runtime — with a real D1, and
 * dispatches real requests through it. The unit tests prove the rules; this
 * proves the routes exist, the binding name matches, and D1 accepts the SQL.
 *
 * Skipped with a reason on a machine too small to boot workerd. The memory check
 * runs **before** the miniflare import: importing it loads workerd, which then
 * fails asynchronously after the tests have already reported a skip.
 */
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');

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
const PEPPER = 'integration-test-pepper';

let mf;

/** Split a schema file into single-line statements D1 will accept. */
function splitSql(sql) {
  return sql
    .split('\n')
    .map((line) => line.replace(/--.*$/, ''))
    .join('\n')
    .split(';')
    .map((s) => s.replace(/\s+/g, ' ').trim())
    .filter(Boolean);
}

before(async () => {
  if (skip) return;
  mf = new Miniflare({
    modules: [
      { type: 'ESModule', path: join(root, 'src/index.js') },
      { type: 'ESModule', path: join(root, 'src/lib.js') },
    ],
    modulesRoot: join(root, 'src'),
    compatibilityDate: '2026-01-01',
    d1Databases: { DB: 'kavach-edge-test' },
    bindings: { APP_TOKEN: TOKEN, PHONE_PEPPER: PEPPER },
  });

  const db = await mf.getD1Database('DB');
  const statements = splitSql(readFileSync(join(root, 'schema.sql'), 'utf8'));
  console.log(`applying ${statements.length} statements:`);
  for (const statement of statements) {
    console.log(`  ${statement.length} chars  ${statement.slice(0, 60)}`);
    await db.prepare(statement).run();
  }
});

after(async () => {
  if (mf) await mf.dispose();
});

const get = (path, token = TOKEN) =>
  mf.dispatchFetch(`http://edge${path}`, {
    headers: token ? { authorization: `Bearer ${token}` } : {},
  });

const post = (path, body, token = TOKEN) =>
  mf.dispatchFetch(`http://edge${path}`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });

// ------------------------------------------------------------------- routes --

describe('routes', { skip }, () => {
  test('health is open, because Test connection asks before it has a token', async () => {
    const res = await get('/health', null);
    assert.equal(res.status, 200);
    assert.equal((await res.json()).status, 'ok');
  });

  test('the status page is HTML', async () => {
    const res = await get('/', null);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /text\/html/);
  });

  test('lookup without a token is refused', async () => {
    assert.equal((await get('/v1/lookup?number=%2B919876543210', null)).status, 401);
  });

  test('an unknown path is a 404', async () => {
    assert.equal((await get('/nope')).status, 404);
  });
});

// ------------------------------------------------------------------- lookup --

describe('lookup', { skip }, () => {
  test('an unknown number says so rather than inventing a name', async () => {
    const res = await get('/v1/lookup?number=%2B919876549999');
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.source, 'NONE');
    assert.equal(body.displayName, null);
    assert.ok(body.unavailableReason);
  });

  test('a bad number is a 422, not a 500', async () => {
    assert.equal((await get('/v1/lookup?number=abc')).status, 422);
  });

  test('a business listing is returned after it is inserted', async () => {
    // Insert directly, the way the moderation path would.
    const { hashNumber } = await import('../src/lib.js');
    const digest = await hashNumber('+919876540001', PEPPER);
    const db = await mf.getD1Database('DB');
    await db
      .prepare(
        'INSERT INTO names (number_hash, name, source, confidence, is_active, created_at) ' +
          'VALUES (?, ?, ?, ?, 1, ?)',
      )
      .bind(digest, 'Sunrise Diagnostics', 'business', 95, Date.now())
      .run();

    const body = await (await get('/v1/lookup?number=%2B919876540001')).json();
    assert.equal(body.displayName, 'Sunrise Diagnostics');
    assert.equal(body.source, 'BUSINESS');
  });

  test('a community suggestion is still not shown', async () => {
    const { hashNumber } = await import('../src/lib.js');
    const digest = await hashNumber('+919876540002', PEPPER);
    const db = await mf.getD1Database('DB');
    await db
      .prepare(
        'INSERT INTO names (number_hash, name, source, confidence, is_active, created_at) ' +
          'VALUES (?, ?, ?, ?, 1, ?)',
      )
      .bind(digest, 'Somebody Guessed This', 'community', 40, Date.now())
      .run();

    const body = await (await get('/v1/lookup?number=%2B919876540002')).json();
    assert.equal(body.displayName, null, 'a suggestion must never become a name');
  });
});

// ------------------------------------------------------------------ reports --

describe('reports', { skip }, () => {
  test('a report is accepted and moves the score', async () => {
    const res = await post('/v1/reports', { number: '+919876543210', category: 'telemarketing' });
    assert.equal(res.status, 202);
    const body = await res.json();
    assert.equal(body.accepted, true);
    assert.equal(body.reports, 1);

    const lookup = await (await get('/v1/lookup?number=%2B919876543210')).json();
    assert.ok(lookup.spamRisk > 0);
    assert.equal(lookup.reports, 1);
    assert.equal(lookup.displayName, null, 'a report is not a name');
  });

  test('reporting twice from the same caller counts once', async () => {
    // Otherwise one annoyed person is indistinguishable from a crowd.
    await post('/v1/reports', { number: '+919876543211', category: 'fraud' });
    const second = await (await post('/v1/reports', { number: '+919876543211', category: 'fraud' })).json();
    assert.equal(second.reports, 1);
  });

  test('an unknown category is refused', async () => {
    const res = await post('/v1/reports', { number: '+919876543212', category: 'nonsense' });
    assert.equal(res.status, 422);
  });

  test('a bad number is refused', async () => {
    assert.equal((await post('/v1/reports', { number: 'x', category: 'fraud' })).status, 422);
  });

  test('a non-JSON body is a 400', async () => {
    const res = await mf.dispatchFetch('http://edge/v1/reports', {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}` },
      body: 'not json',
    });
    assert.equal(res.status, 400);
  });

  test('the report detail is honest about what a report is', async () => {
    const body = await (await post('/v1/reports', { number: '+919876543213', category: 'survey' })).json();
    assert.match(body.detail, /not a finding|community report/i);
  });
});
