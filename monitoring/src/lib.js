/**
 * The monitoring sink's logic: vocabularies, validation, storage and the queries.
 *
 * Kept out of `index.js` on purpose, and the reason is a real bug rather than
 * tidiness. A Worker module's **named exports are entry points** — workerd
 * treats each one as something it might route to. Exporting `SEVERITIES` (an
 * array) or `MAX_BATCH` (a number) from the entry module makes it refuse to
 * start:
 *
 *   Uncaught TypeError: Incorrect type for map entry 'MAX_BATCH':
 *   the provided value is not of type 'function or ExportedHandler'.
 *
 * The unit tests never caught that, because Node does not care about extra
 * exports. The integration test did, on its first run.
 *
 * So `index.js` exports exactly one thing — the default handler — and everything
 * testable lives here.
 */
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

