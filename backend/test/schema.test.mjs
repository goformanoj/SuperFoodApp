import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { MIGRATIONS, TABLES } from '../src/schema.js'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'

const SECRET = 'a-long-random-string'
const build = () => ({
  store: memoryStore(),
  worker: null,
  make(store) {
    return createWorker({ store, provider: fakeProvider(), proxySecret: SECRET })
  },
})

const migrate = (headers = {}) =>
  new Request('https://proxy/admin/migrate', { method: 'POST', headers })

test('every migration is idempotent, which is what makes the route safe to expose', () => {
  // Running it twice must be harmless — otherwise a second tap on a phone
  // becomes a destructive operation.
  for (const sql of MIGRATIONS) {
    assert.match(sql, /CREATE TABLE IF NOT EXISTS/i, sql.slice(0, 40))
  }
})

test('no migration can drop or delete anything', () => {
  // This endpoint is reachable over the internet behind one shared string. It
  // should be incapable of destroying data even if that string leaks.
  for (const sql of MIGRATIONS) {
    assert.doesNotMatch(sql, /\b(DROP|DELETE|TRUNCATE|ALTER)\b/i)
  }
})

test('schema.sql has not drifted from the code that actually runs', () => {
  // src/schema.js is authoritative because there is no laptop in this project;
  // schema.sql is the convenience copy for anyone who has a terminal. A test
  // beats a comment asking people to keep two files in step.
  const sql = readFileSync(new URL('../schema.sql', import.meta.url), 'utf8')
  for (const table of TABLES) {
    assert.ok(sql.includes(table), `schema.sql is missing ${table}`)
  }
  assert.equal(MIGRATIONS.length, TABLES.length)
})

test('migrate runs behind the shared secret', async () => {
  const b = build()
  const worker = b.make(b.store)

  const res = await worker.fetch(migrate({ 'X-Proxy-Secret': SECRET }))

  assert.equal(res.status, 200)
  assert.equal(b.store._migrations.length, MIGRATIONS.length)
})

test('migrate without the secret does nothing at all', async () => {
  const b = build()
  const worker = b.make(b.store)

  const res = await worker.fetch(migrate())

  assert.equal(res.status, 403)
  assert.equal(b.store._migrations.length, 0, 'it migrated anyway')
})

test('migrate twice is still fine', async () => {
  const b = build()
  const worker = b.make(b.store)

  await worker.fetch(migrate({ 'X-Proxy-Secret': SECRET }))
  const second = await worker.fetch(migrate({ 'X-Proxy-Secret': SECRET }))

  assert.equal(second.status, 200)
})
