/**
 * Where the counters live.
 *
 * Two implementations of one small interface: [d1Store] for production and
 * [memoryStore] for tests. The interface is deliberately three methods wide
 * rather than "here is a database" — the tests then exercise the real decision
 * logic above it, instead of a hand-written imitation of SQLite.
 *
 * ## D1, not KV
 *
 * KV is eventually consistent. Two turns arriving together would both read the
 * same stale total, both write their own, and the quota would become a
 * suggestion. D1 does an atomic `SET x = x + n` in one statement, which is the
 * whole reason it was chosen.
 */

import { MIGRATIONS } from './schema.js'

/** @typedef {{ userPlan(uid): Promise<string>, usedToday(uid, day): Promise<number>, addUsage(uid, day, inTok, outTok): Promise<void> }} Store */

/** Backed by a real D1 binding. */
export function d1Store(db, nowMs = () => Date.now()) {
  return {
    /** Creates the tables if they are not there. Idempotent by construction. */
    async migrate() {
      for (const sql of MIGRATIONS) await db.prepare(sql).run()
    },

    async userPlan(uid) {
      const row = await db.prepare('SELECT plan FROM users WHERE uid = ?1').bind(uid).first()
      if (row) return row.plan
      // First sight of this user. Created here rather than at sign-up, because
      // anonymous auth means there IS no sign-up — the first request is it.
      await db
        .prepare('INSERT OR IGNORE INTO users (uid, plan, created_at) VALUES (?1, ?2, ?3)')
        .bind(uid, 'free', nowMs())
        .run()
      return 'free'
    },

    async usedToday(uid, day) {
      const row = await db
        .prepare(
          'SELECT input_tokens + output_tokens AS total FROM usage_daily WHERE uid = ?1 AND day = ?2',
        )
        .bind(uid, day)
        .first()
      return row?.total ?? 0
    },

    async addUsage(uid, day, inTok, outTok) {
      // One statement, so two concurrent turns cannot both read the old total.
      await db
        .prepare(
          `INSERT INTO usage_daily (uid, day, input_tokens, output_tokens, requests)
           VALUES (?1, ?2, ?3, ?4, 1)
           ON CONFLICT(uid, day) DO UPDATE SET
             input_tokens  = input_tokens  + excluded.input_tokens,
             output_tokens = output_tokens + excluded.output_tokens,
             requests      = requests + 1`,
        )
        .bind(uid, day, inTok, outTok)
        .run()
    },
  }
}

/**
 * In-memory equivalent for tests.
 *
 * `addUsage` reads and writes with no `await` in between, which on a single
 * threaded runtime is the same guarantee D1's single statement gives — so a
 * concurrency test here is testing something real rather than a fiction.
 */
export function memoryStore(seed = {}) {
  const migrations = []
  const users = new Map(Object.entries(seed.users ?? {}))
  const usage = new Map(Object.entries(seed.usage ?? {}))
  const key = (uid, day) => `${uid}|${day}`

  return {
    async migrate() {
      migrations.push(...MIGRATIONS)
    },
    async userPlan(uid) {
      if (!users.has(uid)) users.set(uid, 'free')
      return users.get(uid)
    },
    async usedToday(uid, day) {
      const row = usage.get(key(uid, day))
      return row ? row.input + row.output : 0
    },
    async addUsage(uid, day, inTok, outTok) {
      const k = key(uid, day)
      const row = usage.get(k) ?? { input: 0, output: 0, requests: 0 }
      row.input += inTok
      row.output += outTok
      row.requests += 1
      usage.set(k, row)
    },
    // Test-only windows onto the state.
    _migrations: migrations,
    _users: users,
    _usage: usage,
  }
}
