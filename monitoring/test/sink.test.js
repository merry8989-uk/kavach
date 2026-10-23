/**
 * Tests for the monitoring sink.
 *
 * Run with:  node --test monitoring/test/
 *
 * The two that matter most are the guard (`user_code` is refused) and the
 * rejection accounting (nothing is silently dropped). A sink that quietly
 * discards events is a sink that lies about its own coverage, which is worse
 * than one that stores nothing.
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import {
  CATEGORIES,
  MAX_BATCH,
  REJECTION,
  RETENTION_DAYS,
  SEVERITIES,
  normalise,
  parseTimestamp,
  summary,
  windowMs,
} from '../src/lib.js';

// ---------------------------------------------------------------- a fake D1 --

/**
 * Just enough of the D1 API for these tests: `prepare().bind().first()/all()`
 * and `batch()`. Records what was written so the assertions can look at rows
 * rather than at SQL strings.
 */
function fakeDb({ first = {}, all = [], batchResult = {} } = {}) {
  const written = [];
  const queries = [];
  const handler = (sql) => {
    queries.push(sql);
    return {
      bind: (...args) => {
        queries.push({ sql, args });
        return {
          first: async () => first,
          all: async () => ({ results: all }),
          run: async () => ({ meta: { changes: 1 } }),
        };
      },
      first: async () => first,
      all: async () => ({ results: all }),
      run: async () => ({ meta: { changes: 1 } }),
    };
  };
  return {
    written,
    queries,
    prepare: (sql) => handler(sql),
    batch: async (stmts) => {
      written.push(...stmts);
      return batchResult;
    },
  };
}

const serverEvent = (over = {}) => ({
  timestamp: '2026-03-12T10:00:00Z',
  severity: 'info',
  category: 'network',
  message: 'request',
  app_id: 'kavach-server',
  environment: 'production',
  request_id: 'req_abc123',
  logger: 'kavach',
  metadata: { method: 'GET', endpoint: '/v1/lookup', status: 200, duration_ms: 41 },
  ...over,
});

// ------------------------------------------------------------------- the guard --

describe('the sink refuses user diagnostics', () => {
  test('an event carrying a user_code is rejected', () => {
    // The app stamps its per-install pseudonym on every event; the server never
    // does. This is the one field that reliably tells them apart, and it is why
    // the local-only rule is enforced here rather than merely documented.
    const result = normalise({ ...serverEvent(), user_code: 'KV-4F9-K2Q' });
    assert.equal(result.rejected, REJECTION.USER_DIAGNOSTICS);
    assert.equal(result.row, undefined);
  });

  test('an empty user_code is still a refusal', () => {
    // Presence, not truthiness — a field that exists at all means it came from
    // an install.
    const result = normalise({ ...serverEvent(), user_code: '' });
    assert.equal(result.rejected, REJECTION.USER_DIAGNOSTICS);
  });

  test('a null user_code is refused too', () => {
    // The key existing at all is the signal, whatever it holds.
    assert.equal(
      normalise({ ...serverEvent(), user_code: null }).rejected,
      REJECTION.USER_DIAGNOSTICS,
    );
  });

  test('a server event without one is accepted', () => {
    assert.ok(normalise(serverEvent()).row);
  });
});

// ---------------------------------------------------------------- validation --

describe('validation', () => {
  test('every severity the taxonomy uses is accepted', () => {
    for (const severity of SEVERITIES) {
      assert.ok(normalise(serverEvent({ severity })).row, severity);
    }
  });

  test('every category the taxonomy uses is accepted', () => {
    for (const category of CATEGORIES) {
      assert.ok(normalise(serverEvent({ category })).row, category);
    }
  });

  test('the vocabularies match the app and the server', () => {
    assert.deepEqual(SEVERITIES, ['trace', 'debug', 'info', 'warn', 'error', 'fatal']);
    assert.equal(CATEGORIES.length, 12);
  });

  test('an unknown severity is refused, not filed as something else', () => {
    const result = normalise(serverEvent({ severity: 'critical' }));
    assert.equal(result.rejected, REJECTION.UNKNOWN_SEVERITY);
  });

  test('an unknown category is refused', () => {
    assert.equal(normalise(serverEvent({ category: 'nonsense' })).rejected, REJECTION.UNKNOWN_CATEGORY);
  });

  test('a typo must not create a category no filter will ever match', () => {
    const result = normalise(serverEvent({ category: 'Network' }));
    // Case is normalised, so this one is fine; a genuine typo is not.
    assert.ok(result.row);
    assert.equal(normalise(serverEvent({ category: 'netwrok' })).rejected, REJECTION.UNKNOWN_CATEGORY);
  });

  test('a non-object is refused', () => {
    for (const bad of [null, 'a string', 42, ['an', 'array']]) {
      assert.equal(normalise(bad).rejected, REJECTION.MALFORMED);
    }
  });
});

// ------------------------------------------------------------------ lifting --

describe('the three fields lifted out of metadata', () => {
  test('duration, status and endpoint become columns', () => {
    // Lifting them is what makes latency and error-rate reporting plain SQL
    // rather than JSON parsing inside SQLite.
    const { row } = normalise(serverEvent());
    assert.equal(row.duration_ms, 41);
    assert.equal(row.status, 200);
    assert.equal(row.endpoint, '/v1/lookup');
  });

  test('a missing or non-numeric duration becomes null rather than NaN', () => {
    const { row } = normalise(serverEvent({ metadata: { duration_ms: 'soon' } }));
    assert.equal(row.duration_ms, null);
  });

  test('the metadata blob is still kept, bounded', () => {
    const { row } = normalise(serverEvent({ metadata: { extra: 'x'.repeat(9000) } }));
    assert.ok(row.metadata.length <= 4000);
  });
});

// -------------------------------------------------------------- timestamps --

describe('timestamps', () => {
  test('an ISO string is parsed', () => {
    assert.equal(parseTimestamp('2026-03-12T10:00:00Z'), Date.parse('2026-03-12T10:00:00Z'));
  });

  test('an epoch number passes through', () => {
    assert.equal(parseTimestamp(1_772_000_000_000), 1_772_000_000_000);
  });

  test('an unparseable value falls back to now rather than dropping the event', () => {
    const before = Date.now();
    const parsed = parseTimestamp('the day before yesterday');
    assert.ok(parsed >= before && parsed <= Date.now());
  });
});

// ------------------------------------------------------------------ windows --

describe('windows', () => {
  test('every advertised window resolves to a real duration', () => {
    const now = 10_000_000_000_000;
    assert.equal(now - windowMs('1h', now), 60 * 60 * 1000);
    assert.equal(now - windowMs('24h', now), 24 * 60 * 60 * 1000);
    assert.equal(now - windowMs('7d', now), 7 * 24 * 60 * 60 * 1000);
  });

  test('an unknown window falls back to a day rather than to everything', () => {
    const now = 10_000_000_000_000;
    assert.equal(now - windowMs('forever', now), 24 * 60 * 60 * 1000);
  });
});

// ------------------------------------------------------------------ summary --

describe('summary', () => {
  test('reports the totals the dashboard shows', async () => {
    const db = fakeDb({
      first: { events: 120, errors: 6, warnings: 14, requests: 100, apps: 1 },
      all: [{ category: 'network', count: 90, errors: 2 }],
    });
    const result = await summary(db, '24h');

    assert.equal(result.totals.events, 120);
    assert.equal(result.totals.errors, 6);
    assert.equal(result.totals.error_rate, 0.06);
    assert.equal(result.by_category[0].category, 'network');
  });

  test('an error rate with no requests is zero, not a division by nothing', async () => {
    const db = fakeDb({ first: { events: 0, errors: 0, warnings: 0, requests: 0, apps: 0 } });
    const result = await summary(db, '1h');
    assert.equal(result.totals.error_rate, 0);
  });

  test('an empty database produces zeroes rather than throwing', async () => {
    const db = fakeDb({ first: null, all: [] });
    const result = await summary(db, '24h');
    assert.equal(result.totals.events, 0);
    assert.deepEqual(result.by_category, []);
  });

  test('the window is echoed back, so a stale dashboard is visible', async () => {
    const db = fakeDb({ first: {}, all: [] });
    assert.equal((await summary(db, '7d')).window, '7d');
  });
});

// ------------------------------------------------------------------- config --

describe('retention', () => {
  test('a ceiling is defined', () => {
    // A sink with no ceiling holds data nobody agreed to keep.
    assert.equal(RETENTION_DAYS, 30);
  });

  test('a batch is bounded', () => {
    assert.equal(MAX_BATCH, 200);
  });
});


// ------------------------------------------------------- the entry point --

describe('the entry module exports only a handler', () => {
  /**
   * A Worker's named exports are entry points. Exporting anything that is not a
   * function or an ExportedHandler makes workerd refuse to start:
   *
   *   Incorrect type for map entry 'MAX_BATCH':
   *   the provided value is not of type 'function or ExportedHandler'.
   *
   * That happened: the first version exported its constants and helpers from
   * `index.js` and the Worker did not boot. The integration test caught it, but
   * the integration test skips on a small machine — so this static check exists
   * to catch it everywhere, including where workerd cannot run at all.
   */
  const here = dirname(fileURLToPath(import.meta.url));
  const entry = readFileSync(join(here, '..', 'src', 'index.js'), 'utf8');

  test('there are no named exports', () => {
    const named = [...entry.matchAll(/^export\s+(?!default\b)/gm)];
    assert.equal(
      named.length,
      0,
      `index.js has ${named.length} named export(s); move them to lib.js. ` +
        'A Worker refuses to start otherwise.',
    );
  });

  test('the default export is the handler object', () => {
    assert.match(entry, /^export default \{/m);
  });

  test('it imports what it needs from lib.js', () => {
    assert.match(entry, /from '\.\/lib\.js'/);
  });
});
