/**
 * The tables, as executable statements.
 *
 * The authoritative copy lives here rather than in `schema.sql` because there is
 * no laptop in this project: the user builds and operates JARVIS entirely from a
 * phone, so `wrangler d1 execute --file=schema.sql` is not a step they can take.
 * `POST /admin/migrate` runs these instead. `schema.sql` is kept as a convenience
 * for anyone who does have a terminal, and `schema.test.mjs` checks the two have
 * not drifted apart.
 *
 * Every statement is `IF NOT EXISTS`, so running it twice is harmless — which is
 * what makes it safe to expose as an endpoint at all.
 */
export const MIGRATIONS = [
  `CREATE TABLE IF NOT EXISTS users (
     uid        TEXT PRIMARY KEY,
     plan       TEXT NOT NULL DEFAULT 'free',
     created_at INTEGER NOT NULL
   )`,
  `CREATE TABLE IF NOT EXISTS usage_daily (
     uid           TEXT NOT NULL,
     day           TEXT NOT NULL,
     input_tokens  INTEGER NOT NULL DEFAULT 0,
     output_tokens INTEGER NOT NULL DEFAULT 0,
     requests      INTEGER NOT NULL DEFAULT 0,
     PRIMARY KEY (uid, day)
   )`,
  // One subscription row per user (Part E billing). A separate table rather than
  // a column on `users` so the migration stays a plain CREATE TABLE IF NOT EXISTS
  // — the drift/idempotency tests forbid ALTER, and an ALTER is not idempotent in
  // SQLite anyway. The effective plan is `pro` while this row is active.
  `CREATE TABLE IF NOT EXISTS subscriptions (
     uid            TEXT PRIMARY KEY,
     product_id     TEXT,
     purchase_token TEXT,
     state          TEXT NOT NULL,
     expiry_ms      INTEGER NOT NULL DEFAULT 0,
     updated_at     INTEGER NOT NULL
   )`,
  // Phase 7 (AGENT_PLAN §7): one small table holds every device's tasks,
  // reminders, notes and memory for an account — not one table per kind, so a
  // new synced kind is a code change here, never another migration. `kind`+`id`
  // together are the row's identity (the device that made it chose `id`, a
  // UUID, so two devices can never collide by picking the same one), and
  // `uid` leads the primary key, so SQLite's own index on it already answers
  // "this account's rows" fast — no separate index needed at this scale.
  // Last-write-wins by `updated_at`: whichever device's clock says later wins,
  // ties go to whatever is already stored. `deleted` is a tombstone, not a
  // DELETE — the row must survive so every OTHER device can be told to remove
  // its own copy; nothing here is ever actually deleted.
  `CREATE TABLE IF NOT EXISTS sync_rows (
     uid        TEXT NOT NULL,
     kind       TEXT NOT NULL,
     ref_id     TEXT NOT NULL,
     updated_at INTEGER NOT NULL,
     deleted    INTEGER NOT NULL DEFAULT 0,
     data       TEXT NOT NULL DEFAULT '{}',
     PRIMARY KEY (uid, kind, ref_id)
   )`,
  // The public waitlist: who asked for access, when, via which link, and a salted hash of their IP
  // (rate-limiting only — see waitlist.js). Email is the key, so signing up twice is a no-op.
  `CREATE TABLE IF NOT EXISTS waitlist (
     email      TEXT PRIMARY KEY,
     created_at INTEGER NOT NULL,
     source     TEXT NOT NULL DEFAULT '',
     ip_hash    TEXT NOT NULL DEFAULT ''
   )`,
]

/** Table names, for the drift check against `schema.sql`. */
export const TABLES = ['users', 'usage_daily', 'subscriptions', 'sync_rows', 'waitlist']
