import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { FREE_DAILY_TOKENS, dayKey } from '../src/quota.js'

const AT = (iso) => Date.parse(iso)
const NOW = AT('2026-08-18T12:00:00Z')

function chat(uid = 'u1', body = { messages: [{ role: 'user', content: 'hello' }] }) {
  return new Request('https://proxy/chat', {
    method: 'POST',
    headers: { 'X-Uid': uid, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

function build(opts = {}) {
  const store = opts.store ?? memoryStore()
  const provider = opts.provider ?? fakeProvider(opts.providerOptions)
  const worker = createWorker({ store, provider, now: () => opts.now ?? NOW })
  return { worker, store, provider }
}

// --- the happy path ---------------------------------------------------------

test('a turn under the cap is answered and counted', async () => {
  const { worker, store, provider } = build()

  const res = await worker.fetch(chat())
  const body = await res.json()

  assert.equal(res.status, 200)
  assert.equal(body.reply, 'This is a fake reply.')
  assert.equal(provider.calls.length, 1)
  assert.equal(body.usage.input, 1000)
  assert.equal(body.usage.output, 200)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 1200)
})

test('remaining counts down by what the turn actually cost', async () => {
  const { worker } = build()

  const body = await (await worker.fetch(chat())).json()

  assert.equal(body.remaining, FREE_DAILY_TOKENS - 1200)
})

test('a user seen for the first time is created on the free plan', async () => {
  // Anonymous auth means there is no sign-up — the first request IS it.
  const { worker, store } = build()

  const body = await (await worker.fetch(chat('brand-new'))).json()

  assert.equal(body.plan, 'free')
  assert.equal(store._users.get('brand-new'), 'free')
})

// --- the cap ----------------------------------------------------------------

test('over the cap the provider is NEVER called', async () => {
  // The entire point of a cap is not spending the money. A version that called
  // first and counted afterwards would be decoration.
  const store = memoryStore()
  await store.addUsage('u1', dayKey(NOW), FREE_DAILY_TOKENS, 0)
  const { worker, provider } = build({ store })

  const res = await worker.fetch(chat())

  assert.equal(res.status, 429)
  assert.equal(provider.calls.length, 0, 'the provider was called anyway')
})

test('the over-cap reply is something the app can say out loud', async () => {
  const store = memoryStore()
  await store.addUsage('u1', dayKey(NOW), FREE_DAILY_TOKENS, 0)
  const { worker } = build({ store })

  const body = await (await worker.fetch(chat())).json()

  assert.equal(body.error, 'quota_exhausted')
  assert.ok(body.spoken.length > 0)
  assert.ok(body.resetsInSeconds > 0)
})

test('yesterday’s spending does not gate today', async () => {
  const store = memoryStore()
  await store.addUsage('u1', dayKey(AT('2026-08-17T23:00:00Z')), FREE_DAILY_TOKENS, 0)
  const { worker, provider } = build({ store })

  const res = await worker.fetch(chat())

  assert.equal(res.status, 200)
  assert.equal(provider.calls.length, 1)
})

// --- accounting under load ---------------------------------------------------

test('two turns arriving together are both counted', async () => {
  // The reason this is D1 and not KV: with eventual consistency both turns read
  // the same stale total, both write their own, and the quota silently becomes
  // a suggestion.
  const { worker, store } = build()

  await Promise.all([worker.fetch(chat()), worker.fetch(chat())])

  assert.equal(await store.usedToday('u1', dayKey(NOW)), 2400)
})

test('two users do not share an allowance', async () => {
  const { worker, store } = build()

  await worker.fetch(chat('alice'))
  await worker.fetch(chat('bob'))

  assert.equal(await store.usedToday('alice', dayKey(NOW)), 1200)
  assert.equal(await store.usedToday('bob', dayKey(NOW)), 1200)
})

// --- failure ----------------------------------------------------------------

test('a provider failure charges the user nothing', async () => {
  // They got no answer. Billing them for the provider's bad day is the wrong
  // way round.
  const { worker, store } = build({ providerOptions: { fail: 'upstream exploded' } })

  const res = await worker.fetch(chat())

  assert.equal(res.status, 502)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 0)
})

// --- the shape of the door ---------------------------------------------------

test('health needs nothing and answers', async () => {
  const { worker } = build()
  const res = await worker.fetch(new Request('https://proxy/health'))
  assert.equal(res.status, 200)
  assert.deepEqual(await res.json(), { ok: true })
})

test('a request with no uid is refused', async () => {
  const { worker, provider } = build()
  const res = await worker.fetch(
    new Request('https://proxy/chat', { method: 'POST', body: '{"messages":[{"role":"user","content":"x"}]}' }),
  )
  assert.equal(res.status, 401)
  assert.equal(provider.calls.length, 0)
})

test('malformed input is refused without touching the provider', async () => {
  const { worker, provider } = build()

  const bad = await worker.fetch(
    new Request('https://proxy/chat', { method: 'POST', headers: { 'X-Uid': 'u1' }, body: 'not json' }),
  )
  const empty = await worker.fetch(chat('u1', { messages: [] }))

  assert.equal(bad.status, 400)
  assert.equal(empty.status, 400)
  assert.equal(provider.calls.length, 0)
})

test('an unknown route is a 404, not a silent 200', async () => {
  const { worker } = build()
  const res = await worker.fetch(new Request('https://proxy/admin', { method: 'POST' }))
  assert.equal(res.status, 404)
})
