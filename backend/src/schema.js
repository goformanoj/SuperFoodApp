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
]

/** Table names, for the drift check against `schema.sql`. */
export const TABLES = ['users', 'usage_daily']
