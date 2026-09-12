-- JARVIS OS proxy — D1 schema.
-- Applied with: wrangler d1 execute jarvis --file=backend/schema.sql

CREATE TABLE IF NOT EXISTS users (
  uid        TEXT PRIMARY KEY,
  plan       TEXT NOT NULL DEFAULT 'free',
  created_at INTEGER NOT NULL
);

-- One row per user per UTC day. Input and output are separate columns because
-- they are priced differently; `requests` is kept only as a cheap abuse signal,
-- since the CAP is on tokens (a screen-control turn costs many times a chat one).
CREATE TABLE IF NOT EXISTS usage_daily (
  uid           TEXT NOT NULL,
  day           TEXT NOT NULL,               -- 'YYYY-MM-DD', UTC
  input_tokens  INTEGER NOT NULL DEFAULT 0,
  output_tokens INTEGER NOT NULL DEFAULT 0,
  requests      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (uid, day)
);

-- One subscription per user (Part E billing). Effective plan is 'pro' while this
-- row is active (an accepted state and not past expiry). Separate table, not a
-- column on users, so the migration is a plain idempotent CREATE (no ALTER).
CREATE TABLE IF NOT EXISTS subscriptions (
  uid            TEXT PRIMARY KEY,
  product_id     TEXT,
  purchase_token TEXT,
  state          TEXT NOT NULL,              -- Play subscriptionState
  expiry_ms      INTEGER NOT NULL DEFAULT 0, -- epoch ms of latest line item expiry
  updated_at     INTEGER NOT NULL
);
