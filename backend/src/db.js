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
import { incomingWins } from './sync.js'

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

    async subscription(uid) {
      const row = await db
        .prepare('SELECT state, expiry_ms, product_id, purchase_token FROM subscriptions WHERE uid = ?1')
        .bind(uid)
        .first()
      if (!row) return null
      return {
        state: row.state,
        expiryMs: row.expiry_ms,
        productId: row.product_id,
        purchaseToken: row.purchase_token,
      }
    },

    async setSubscription(uid, sub, updatedAtMs = nowMs()) {
      await db
        .prepare(
          `INSERT INTO subscriptions (uid, product_id, purchase_token, state, expiry_ms, updated_at)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6)
           ON CONFLICT(uid) DO UPDATE SET
             product_id     = excluded.product_id,
             purchase_token = excluded.purchase_token,
             state          = excluded.state,
             expiry_ms      = excluded.expiry_ms,
             updated_at     = excluded.updated_at`,
        )
        .bind(uid, sub.productId ?? null, sub.purchaseToken ?? null, sub.state, sub.expiryMs ?? 0, updatedAtMs)
        .run()
    },

    // Phase 7 sync (AGENT_PLAN §7). Rows are checked (sync.js) before they ever
    // reach here; this layer only decides who wins and writes the result.
    async pushSyncRows(uid, rows) {
      // One push should never contain the same row twice — the client's own
      // outbox can't produce that — but if it somehow did, keep the one with
      // the HIGHEST updatedAt (the same rule a second, separate push would
      // apply), not whichever happened to come last in the array.
      const byId = new Map()
      for (const row of rows) {
        const kept = byId.get(row.id)
        if (!kept || row.updatedAt > kept.updatedAt) byId.set(row.id, row)
      }
      rows = [...byId.values()]
      const ids = rows.map((r) => r.id)
      // One SELECT for the whole batch, not one per row — a burst of local
      // edits (the common case) would otherwise be N round trips to D1.
      const placeholders = ids.map((_, i) => `?${i + 2}`).join(',')
      const existing = await db
        .prepare(`SELECT ref_id, updated_at FROM sync_rows WHERE uid = ?1 AND ref_id IN (${placeholders})`)
        .bind(uid, ...ids)
        .all()
      const existingAt = new Map((existing.results ?? []).map((r) => [r.ref_id, r.updated_at]))

      const accepted = []
      const serverWins = []
      const statements = []
      for (const row of rows) {
        if (incomingWins(existingAt.get(row.id), row.updatedAt)) {
          accepted.push(row.id)
          statements.push(
            db
              .prepare(
                `INSERT INTO sync_rows (uid, kind, ref_id, updated_at, deleted, data)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT(uid, kind, ref_id) DO UPDATE SET
                   updated_at = excluded.updated_at, deleted = excluded.deleted, data = excluded.data`,
              )
              .bind(uid, row.kind, row.id, row.updatedAt, row.deleted ? 1 : 0, row.deleted ? '{}' : JSON.stringify(row.data)),
          )
        } else {
          serverWins.push(row.id)
        }
      }
      if (statements.length) await db.batch(statements)

      // The server's own version of every row it just overruled, so the
      // client can absorb the correction from this one response — it does
      // not need a second round trip to find out what it should have sent.
      let overruled = []
      if (serverWins.length) {
        const ph = serverWins.map((_, i) => `?${i + 2}`).join(',')
        const rows2 = await db
          .prepare(`SELECT kind, ref_id, updated_at, deleted, data FROM sync_rows WHERE uid = ?1 AND ref_id IN (${ph})`)
          .bind(uid, ...serverWins)
          .all()
        overruled = (rows2.results ?? []).map(fromRow)
      }
      return { accepted, serverWins: overruled }
    },

    async pullSyncRows(uid, since, kinds, limit) {
      const ph = kinds.map((_, i) => `?${i + 3}`).join(',')
      const res = await db
        .prepare(
          `SELECT kind, ref_id, updated_at, deleted, data FROM sync_rows
           WHERE uid = ?1 AND updated_at > ?2 AND kind IN (${ph})
           ORDER BY updated_at ASC LIMIT ?${kinds.length + 3}`,
        )
        .bind(uid, since, ...kinds, limit)
        .all()
      return (res.results ?? []).map(fromRow)
    },
  }
}

/** A D1 row → the shape the Worker's JSON response uses. */
function fromRow(r) {
  return { kind: r.kind, id: r.ref_id, updatedAt: r.updated_at, deleted: !!r.deleted, data: r.deleted ? {} : JSON.parse(r.data) }
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
  const subs = new Map(Object.entries(seed.subscriptions ?? {}))
  const syncRows = new Map()
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
    async subscription(uid) {
      return subs.get(uid) ?? null
    },
    async setSubscription(uid, sub, updatedAtMs = Date.now()) {
      subs.set(uid, {
        state: sub.state,
        expiryMs: sub.expiryMs ?? 0,
        productId: sub.productId ?? null,
        purchaseToken: sub.purchaseToken ?? null,
        updatedAt: updatedAtMs,
      })
    },

    // Phase 7 sync — the same contract as d1Store, kept in a Map so the sync
    // tests exercise the real accept/overrule decision, not a fiction of it.
    async pushSyncRows(uid, rows) {
      const accepted = []
      const overruled = []
      for (const row of rows) {
        const k = syncKeyOf(uid, row.kind, row.id)
        const existing = syncRows.get(k)
        if (incomingWins(existing?.updatedAt, row.updatedAt)) {
          accepted.push(row.id)
          syncRows.set(k, { kind: row.kind, id: row.id, updatedAt: row.updatedAt, deleted: !!row.deleted, data: row.deleted ? {} : row.data })
        } else {
          overruled.push({ ...existing })
        }
      }
      return { accepted, serverWins: overruled }
    },
    async pullSyncRows(uid, since, kinds, limit) {
      return [...syncRows.entries()]
        .filter(([k, r]) => k.startsWith(`${uid}|`) && r.updatedAt > since && kinds.includes(r.kind))
        .map(([, r]) => r)
        .sort((a, b) => a.updatedAt - b.updatedAt)
        .slice(0, limit)
    },
    // Test-only windows onto the state.
    _migrations: migrations,
    _users: users,
    _usage: usage,
    _subs: subs,
    _syncRows: syncRows,
  }
}

/** `uid|kind|id`: a `|` can't appear in a uid or kind, so this never collides. */
function syncKeyOf(uid, kind, id) {
  return `${uid}|${kind}|${id}`
}
