import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { FREE_DAILY_TOKENS, PRO_DAILY_TOKENS } from '../src/quota.js'

const NOW = Date.parse('2026-10-02T09:00:00Z')

const get = (path, headers = {}) =>
  new Request(`https://proxy${path}`, { method: 'GET', headers: { 'X-Uid': 'u1', ...headers } })

function build(extra = {}) {
  const store = memoryStore()
  const provider = fakeProvider()
  return { store, provider, worker: createWorker({ store, provider, now: () => NOW, ...extra }) }
}

test('/usage reports the plan and the full allowance for someone who has spent nothing, without calling a model', async () => {
  const { worker, provider } = build()
  const res = await worker.fetch(get('/usage'))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.plan, 'free')
  assert.equal(body.cap, FREE_DAILY_TOKENS)
  assert.equal(body.remaining, FREE_DAILY_TOKENS)
  assert.equal(provider.calls.length, 0, 'asking who you are must not spend tokens')
})

test('/usage subtracts what was already spent today', async () => {
  const { worker, store } = build()
  await store.addUsage('u1', '2026-10-02', 1200, 300)
  const body = await (await worker.fetch(get('/usage'))).json()
  assert.equal(body.remaining, FREE_DAILY_TOKENS - 1500)
})

test('/usage reports an owner as pro with the pro allowance', async () => {
  const { worker } = build({ proUids: ['u1'] })
  const body = await (await worker.fetch(get('/usage'))).json()
  assert.equal(body.plan, 'pro')
  assert.equal(body.cap, PRO_DAILY_TOKENS)
})

test('/usage is refused without the shared secret when one is configured', async () => {
  const { worker } = build({ proxySecret: 's3cret' })
  const res = await worker.fetch(get('/usage'))
  assert.equal(res.status, 403)
})
