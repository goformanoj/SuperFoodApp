/**
 * The subscription endpoint and its effect on metering: /billing/verify records a
 * verified purchase and makes /chat meter that user at the Pro cap.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'

const SECRET = 'shhh'
const NOW = 1_760_000_000_000
const future = NOW + 30 * 24 * 3600 * 1000

const activeVerifier = async ({ productId }) => ({
  state: 'SUBSCRIPTION_STATE_ACTIVE',
  expiryMs: future,
  productId,
})

function worker({ store = memoryStore(), verifySubscription = activeVerifier } = {}) {
  return createWorker({
    store,
    provider: fakeProvider(),
    proxySecret: SECRET,
    verifySubscription,
    now: () => NOW,
  })
}

function verify(body, { secret = SECRET, uid = 'uid-1' } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (secret !== null) headers['X-Proxy-Secret'] = secret
  if (uid !== null) headers['X-Uid'] = uid
  return new Request('https://w.dev/billing/verify', { method: 'POST', headers, body: JSON.stringify(body) })
}

test('a verified purchase upgrades the user to pro and is recorded', async () => {
  const store = memoryStore()
  const res = await worker({ store }).fetch(verify({ productId: 'jarvis_pro_monthly', purchaseToken: 'tok-1' }))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.plan, 'pro')
  assert.equal(body.active, true)
  // Recorded, so a later /chat sees it without re-verifying.
  assert.equal((await store.subscription('uid-1')).state, 'SUBSCRIPTION_STATE_ACTIVE')
})

test('after verifying, /chat meters that user as pro', async () => {
  const store = memoryStore()
  const w = worker({ store })
  await w.fetch(verify({ productId: 'jarvis_pro_monthly', purchaseToken: 'tok-1' }))

  const chat = new Request('https://w.dev/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Proxy-Secret': SECRET, 'X-Uid': 'uid-1' },
    body: JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }),
  })
  const res = await w.fetch(chat)
  assert.equal(res.status, 200)
  assert.equal((await res.json()).plan, 'pro')
})

test('billing is dormant (503) when no verifier is configured', async () => {
  const res = await worker({ verifySubscription: null }).fetch(
    verify({ productId: 'p', purchaseToken: 't' }),
  )
  assert.equal(res.status, 503)
  assert.equal((await res.json()).error, 'billing_unconfigured')
})

test('a missing purchase field is a 400', async () => {
  const res = await worker().fetch(verify({ productId: 'p' }))
  assert.equal(res.status, 400)
  assert.equal((await res.json()).error, 'missing_purchase')
})

test('the endpoint is behind the shared secret', async () => {
  const res = await worker().fetch(verify({ productId: 'p', purchaseToken: 't' }, { secret: 'wrong' }))
  assert.equal(res.status, 403)
})

test('a user with no subscription is still metered free', async () => {
  const chat = new Request('https://w.dev/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Proxy-Secret': SECRET, 'X-Uid': 'never-paid' },
    body: JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }),
  })
  const res = await worker().fetch(chat)
  assert.equal((await res.json()).plan, 'free')
})
