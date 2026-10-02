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

-- Phase 7 (AGENT_PLAN §7): one row per synced task/reminder/note/memory, from
-- whichever device last touched it. Last-write-wins by updated_at; deleted is
-- a tombstone (never an actual DELETE), so every device learns to remove it too.
CREATE TABLE IF NOT EXISTS sync_rows (
  uid        TEXT NOT NULL,
  kind       TEXT NOT NULL,               -- 'task' | 'reminder' | 'note' | 'memory'
  ref_id     TEXT NOT NULL,               -- the id the creating device chose (a UUID)
  updated_at INTEGER NOT NULL,
  deleted    INTEGER NOT NULL DEFAULT 0,
  data       TEXT NOT NULL DEFAULT '{}',  -- the entity's fields, as JSON
  PRIMARY KEY (uid, kind, ref_id)
);

-- The public waitlist. Email is the key (signing up twice changes nothing); ip_hash is a salted
-- SHA-256 used only to rate-limit one network, never the address itself.
CREATE TABLE IF NOT EXISTS waitlist (
  email      TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  source     TEXT NOT NULL DEFAULT '',
  ip_hash    TEXT NOT NULL DEFAULT ''
);
