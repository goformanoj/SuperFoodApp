import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { checkRow, checkPush, incomingWins, parseKinds, parseSince, KINDS, MAX_ROWS_PER_PUSH } from '../src/sync.js'

const NOW = Date.parse('2026-09-29T09:00:00Z')

function req(method, path, body, headers = {}) {
  return new Request(`https://proxy${path}`, {
    method,
    headers: { 'X-Uid': 'u1', 'Content-Type': 'application/json', ...headers },
    body: body !== undefined ? (typeof body === 'string' ? body : JSON.stringify(body)) : undefined,
  })
}

function build() {
  const store = memoryStore()
  const provider = fakeProvider()
  const worker = createWorker({ store, provider, now: () => NOW })
  return { worker, store, provider }
}

const task = (id, updatedAt, title = 'x') => ({ kind: 'task', id, updatedAt, data: { title } })

// --- /sync/push ------------------------------------------------------------

test('a new row is accepted and stored', async () => {
  const { worker, store } = build()
  const res = await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 100, 'Call the bank')] }))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.deepEqual(body, { accepted: ['t1'], serverWins: [] })
  assert.equal(store._syncRows.get('u1|task|t1').data.title, 'Call the bank')
})

test('a newer edit overwrites an older one; the provider is never called (no model, no charge)', async () => {
  const { worker, store, provider } = build()
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 100, 'v1')] }))
  const res = await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 200, 'v2')] }))
  assert.deepEqual((await res.json()).accepted, ['t1'])
  assert.equal(store._syncRows.get('u1|task|t1').data.title, 'v2')
  assert.equal(provider.calls.length, 0)
})

test('an older or equal edit is refused, and the server hands back its own version', async () => {
  const { worker, store } = build()
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 200, 'server')] }))

  const older = await (await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 100, 'older')] }))).json()
  assert.deepEqual(older, { accepted: [], serverWins: [{ kind: 'task', id: 't1', updatedAt: 200, deleted: false, data: { title: 'server' } }] })

  const tie = await (await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 200, 'tie')] }))).json()
  assert.deepEqual(tie.accepted, [])
  assert.equal(tie.serverWins[0].data.title, 'server')
  assert.equal(store._syncRows.get('u1|task|t1').data.title, 'server')
})

test('a tombstone deletes without needing data, and a later real edit revives it', async () => {
  const { worker, store } = build()
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 100)] }))
  const del = await worker.fetch(req('POST', '/sync/push', { rows: [{ kind: 'task', id: 't1', updatedAt: 200, deleted: true }] }))
  assert.deepEqual((await del.json()).accepted, ['t1'])
  assert.equal(store._syncRows.get('u1|task|t1').deleted, true)
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 300, 'back')] }))
  assert.equal(store._syncRows.get('u1|task|t1').deleted, false)
})

test('a duplicate id inside one push resolves by updatedAt, not array order', async () => {
  const { worker, store } = build()
  // A client's own outbox can never send two rows for the same id in one push;
  // this only guards that IF it somehow happened, the outcome follows the same
  // last-write-wins rule as everywhere else, not "whichever came last".
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 50, 'earlier-but-listed-first'), task('t1', 100, 'later-but-listed-second')] }))
  assert.equal(store._syncRows.get('u1|task|t1').data.title, 'later-but-listed-second')
})

test('different accounts never see each other\'s rows', async () => {
  const { worker, store } = build()
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 100)] }))
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 999, 'other account')] }, { 'X-Uid': 'u2' }))
  assert.equal(store._syncRows.get('u1|task|t1').updatedAt, 100)
  assert.equal(store._syncRows.get('u2|task|t1').data.title, 'other account')
})

test('bad rows are refused before anything is stored, one bad row fails the whole push', async () => {
  const { worker, store } = build()
  const cases = [
    { rows: [] },
    { rows: 'nope' },
    { rows: [{ kind: 'nope', id: 'x', updatedAt: 1, data: {} }] },
    { rows: [{ kind: 'task', id: '', updatedAt: 1, data: {} }] },
    { rows: [{ kind: 'task', id: 'x', updatedAt: -1, data: {} }] },
    { rows: [{ kind: 'task', id: 'x', updatedAt: 1, data: 'nope' }] },
    { rows: [{ kind: 'task', id: 'x', updatedAt: 1, data: { big: 'x'.repeat(9000) } }] },
    { rows: [task('ok', 1), { kind: 'task', id: 'bad', updatedAt: -1, data: {} }] },
    { rows: Array.from({ length: MAX_ROWS_PER_PUSH + 1 }, (_, i) => task(`t${i}`, i + 1)) },
  ]
  for (const body of cases) {
    const res = await worker.fetch(req('POST', '/sync/push', body))
    assert.equal(res.status, 400, JSON.stringify(body).slice(0, 60))
  }
  assert.equal(store._syncRows.size, 0)
})

test('the routes sit behind the same auth as everything else: no uid, no sync', async () => {
  const { worker } = build()
  const noUidPush = await worker.fetch(new Request('https://proxy/sync/push', { method: 'POST', body: '{}' }))
  assert.equal(noUidPush.status, 401)
  const noUidPull = await worker.fetch(new Request('https://proxy/sync/pull', { method: 'GET' }))
  assert.equal(noUidPull.status, 401)
})

// --- /sync/pull --------------------------------------------------------------

test('a pull returns only what changed since the cursor, oldest first', async () => {
  const { worker } = build()
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 100), task('t2', 300)] }))
  const first = await (await worker.fetch(req('GET', '/sync/pull?since=0'))).json()
  assert.deepEqual(first.rows.map((r) => r.id), ['t1', 't2'])
  assert.equal(first.serverTime, NOW)
  assert.equal(first.hasMore, false)

  await worker.fetch(req('POST', '/sync/push', { rows: [task('t3', 200)] }))
  const second = await (await worker.fetch(req('GET', `/sync/pull?since=${first.rows[0].updatedAt}`))).json()
  // t1 (updatedAt 100) is not newer than since=100; t3 (200) and t2 (300) are.
  assert.deepEqual(second.rows.map((r) => r.id), ['t3', 't2'])
})

test('kinds filters the pull; an unknown kind is ignored, not an error', async () => {
  const { worker } = build()
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 100), { kind: 'reminder', id: 'r1', updatedAt: 200, data: { text: 'x', at: 1 } }] }))
  const onlyTasks = await (await worker.fetch(req('GET', '/sync/pull?since=0&kinds=task'))).json()
  assert.deepEqual(onlyTasks.rows.map((r) => r.kind), ['task'])
  const junk = await (await worker.fetch(req('GET', '/sync/pull?since=0&kinds=task,nonsense'))).json()
  assert.deepEqual(junk.rows.map((r) => r.kind), ['task'])
  const blank = await (await worker.fetch(req('GET', '/sync/pull?since=0&kinds='))).json()
  assert.equal(blank.rows.length, 2) // blank/absent = every kind
})

test('a deletion pulls as a tombstone, not silently dropped', async () => {
  const { worker } = build()
  await worker.fetch(req('POST', '/sync/push', { rows: [task('t1', 100)] }))
  await worker.fetch(req('POST', '/sync/push', { rows: [{ kind: 'task', id: 't1', updatedAt: 200, deleted: true }] }))
  const pulled = await (await worker.fetch(req('GET', '/sync/pull?since=0'))).json()
  assert.deepEqual(pulled.rows[0], { kind: 'task', id: 't1', updatedAt: 200, deleted: true, data: {} })
})

test('bad JSON, missing uid: the usual failures, unchanged', async () => {
  const { worker } = build()
  assert.equal((await worker.fetch(req('POST', '/sync/push', '{nope'))).status, 400)
  assert.equal((await worker.fetch(new Request('https://proxy/sync/pull', { method: 'GET' }))).status, 401)
})

// --- pure helpers --------------------------------------------------------------

test('checkRow', () => {
  assert.equal(checkRow(task('t1', 1)), null)
  assert.equal(checkRow({ kind: 'task', id: 't1', updatedAt: 1, deleted: true }), null) // no data needed
  assert.equal(checkRow(null), 'not_an_object')
  assert.equal(checkRow({ kind: 'task', id: 't1', updatedAt: 1, deleted: 'yes', data: {} }), 'bad_deleted')
})

test('incomingWins: strictly newer wins, a tie or older loses, a first sighting always wins', () => {
  assert.equal(incomingWins(undefined, 1), true)
  assert.equal(incomingWins(100, 101), true)
  assert.equal(incomingWins(100, 100), false)
  assert.equal(incomingWins(100, 99), false)
})

test('parseKinds / parseSince', () => {
  assert.deepEqual(parseKinds(null), KINDS)
  assert.deepEqual(parseKinds(''), KINDS)
  assert.deepEqual(parseKinds('task,task,reminder'), ['task', 'reminder'])
  assert.deepEqual(parseKinds('junk'), KINDS)
  assert.equal(parseSince(null), 0)
  assert.equal(parseSince('abc'), 0)
  assert.equal(parseSince('-5'), 0)
  assert.equal(parseSince('123.9'), 123)
})
