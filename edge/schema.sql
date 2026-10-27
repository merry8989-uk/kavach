-- Kavach edge server — D1 schema.
--
-- Apply with:
--   npx wrangler d1 execute kavach-edge --remote --file=schema.sql
--
-- ## Numbers are never stored
--
-- Every table keys on `number_hash`, an HMAC-SHA256 of the E.164 number under a
-- pepper that lives in a Worker secret. The raw number is never written, never
-- logged, and cannot be recovered from the database without the pepper.
--
-- That is not decoration. A phone number identifies a person; a table of them is
-- a liability whether or not the names beside them are right. The pepper can be
-- rotated, and rotating it makes every stored hash useless — which is a blunt
-- instrument, but it is one that exists.

CREATE TABLE IF NOT EXISTS numbers (
  number_hash TEXT PRIMARY KEY,
  country     TEXT NOT NULL DEFAULT 'IN',
  first_seen  INTEGER NOT NULL
);

-- A name for a number, with where it came from.
--
-- `source` is the whole honesty mechanism:
--   self_declared — the owner verified it. The only one that may read "verified".
--   business      — a public listing, claimed and reviewed.
--   community     — somebody suggested it. Not a fact, and never shown as one.
CREATE TABLE IF NOT EXISTS names (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  number_hash TEXT NOT NULL,
  name        TEXT NOT NULL,
  source      TEXT NOT NULL DEFAULT 'community',
  confidence  INTEGER NOT NULL DEFAULT 40,
  is_active   INTEGER NOT NULL DEFAULT 1,
  created_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_names_number ON names (number_hash, is_active);

-- Spam reports. One row per reporter per number, replaced rather than added, so
-- a single person cannot inflate a score by tapping twice.
CREATE TABLE IF NOT EXISTS reports (
  number_hash   TEXT NOT NULL,
  reporter_hash TEXT NOT NULL,
  category      TEXT NOT NULL,
  note          TEXT,
  created_at    INTEGER NOT NULL,
  PRIMARY KEY (number_hash, reporter_hash)
);

-- The rolled-up score. Derived from reports, never from a guess.
CREATE TABLE IF NOT EXISTS scores (
  number_hash  TEXT PRIMARY KEY,
  score        INTEGER NOT NULL DEFAULT 0,
  report_count INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL
);
