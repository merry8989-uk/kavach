-- Kavach monitoring sink — D1 schema.
--
-- Apply with:
--   npx wrangler d1 execute kavach-monitoring --remote --file=schema.sql
--
-- Three of these columns are lifted out of the event's `metadata` blob rather
-- than left inside it: `duration_ms`, `status` and `endpoint`. They are the three
-- the dashboard actually queries, and indexing them turns latency and error-rate
-- reporting into plain SQL instead of JSON parsing inside SQLite — which is the
-- difference between a query that runs and one that scans.

CREATE TABLE IF NOT EXISTS log_events (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  -- When this sink received it. Kept alongside `ts` because the two differ when
  -- a batch was buffered, and a gap between them is itself a signal.
  received_at    INTEGER NOT NULL,
  -- Event time, epoch millis.
  ts             INTEGER NOT NULL,

  severity       TEXT NOT NULL,
  category       TEXT NOT NULL,
  message        TEXT,
  app_id         TEXT,
  environment    TEXT,
  -- The correlation id. The app mints it per user action and the server reuses
  -- it, which is what makes the two logs one story.
  request_id     TEXT,
  logger         TEXT,

  duration_ms    INTEGER,
  status         INTEGER,
  endpoint       TEXT,

  exception_type TEXT,
  metadata       TEXT
);

-- The dashboard's main access pattern: a time window, optionally filtered.
CREATE INDEX IF NOT EXISTS idx_log_events_ts        ON log_events (ts);
CREATE INDEX IF NOT EXISTS idx_log_events_cat_ts    ON log_events (category, ts);
CREATE INDEX IF NOT EXISTS idx_log_events_sev_ts    ON log_events (severity, ts);
-- Slow-endpoint and error-rate queries group by endpoint inside a window.
CREATE INDEX IF NOT EXISTS idx_log_events_endpoint  ON log_events (endpoint, ts);
-- Joining an app event to the request it caused is a lookup by this.
CREATE INDEX IF NOT EXISTS idx_log_events_request   ON log_events (request_id);
