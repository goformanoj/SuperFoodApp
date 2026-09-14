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
  const worker = createWorker({
    store,
    provider,
    proUids: opts.proUids,
    conversationTier: opts.conversationTier,
    now: () => opts.now ?? NOW,
  })
  return { worker, store, provider }
}

/**
 * A provider that records the system prompt of every call and can reply
 * differently to the slim vs. full prompt — the CONVERSATION_PROMPT is the one
 * that says "fast conversation mode", so that substring tells them apart. Usage is
 * small for the slim call and large for the full one, so summed accounting is
 * checkable.
 */
function tieringProvider(replyFor) {
  const calls = []
  return {
    calls,
    async complete({ models, messages, system }) {
      const slim = system.includes('fast conversation mode')
      calls.push({ slim, system })
      const text = replyFor ? replyFor({ slim }) : 'This is a fake reply.'
      const usage = slim
        ? { prompt_tokens: 200, completion_tokens: 10 }
        : { prompt_tokens: 1000, completion_tokens: 200 }
      return { text, usage, model: models[0] }
    },
  }
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

test('a failing subscription read never takes down the brain (dormant billing)', async () => {
  // Regression: the billing code shipped a `SELECT ... FROM subscriptions` into
  // /chat before that table existed in prod, so every turn threw and the Worker
  // returned HTTP 500 — the brain was down. The subscription read must degrade to
  // the base plan, not crash the request.
  const base = memoryStore()
  const store = {
    ...base,
    subscription: async () => {
      throw new Error('no such table: subscriptions')
    },
  }
  const { worker } = build({ store })

  const res = await worker.fetch(chat())
  const body = await res.json()

  assert.equal(res.status, 200)
  assert.equal(body.plan, 'free')
  assert.equal(body.reply, 'This is a fake reply.')
})

test('an owner uid is always pro, so the free cap never stops it', async () => {
  const store = memoryStore()
  // Already past the FREE cap for today — a normal user would be refused.
  await store.addUsage('owner', dayKey(NOW), FREE_DAILY_TOKENS, 0)
  const { worker } = build({ store, proUids: ['owner'] })

  const res = await worker.fetch(chat('owner'))
  const body = await res.json()

  assert.equal(res.status, 200) // pro cap (2M) is far above today's use
  assert.equal(body.plan, 'pro')
})

test('the owner list does not lift anyone else', async () => {
  const store = memoryStore()
  await store.addUsage('someone-else', dayKey(NOW), FREE_DAILY_TOKENS, 0)
  const { worker } = build({ store, proUids: ['owner'] })

  const res = await worker.fetch(chat('someone-else'))

  assert.equal(res.status, 429) // still on free, still capped
})

test('an owner email from the verified token is always pro (survives uid changes)', async () => {
  // The owner's uid changes between anonymous and Google-linked sessions; the email
  // in the verified token does not. Matching the gmail is the robust "owner is pro".
  const store = memoryStore()
  await store.addUsage('any-google-uid', dayKey(NOW), FREE_DAILY_TOKENS, 0) // past the free cap
  const verifyToken = async () => ({ uid: 'any-google-uid', claims: { email: 'GoForPranjal@Gmail.com' } })
  const worker = createWorker({
    store,
    provider: fakeProvider(),
    verifyToken,
    proEmails: ['goforpranjal@gmail.com'], // stored lower-cased, matched case-insensitively
    now: () => NOW,
  })
  const req = new Request('https://proxy/chat', {
    method: 'POST',
    headers: { Authorization: 'Bearer tok', 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }),
  })

  const res = await worker.fetch(req)
  const body = await res.json()

  assert.equal(res.status, 200)
  assert.equal(body.plan, 'pro')
})

// --- two-tier prompt (CONVO_TIER) -------------------------------------------
// The one rule under test: an errand must never be answered without the tools.

test('tiering OFF: every turn still uses the full prompt (unchanged behaviour)', async () => {
  // Default build has conversationTier undefined → off. A chat message must NOT be
  // downgraded to the slim prompt when the switch is off.
  const provider = tieringProvider()
  const { worker } = build({ provider })

  await worker.fetch(chat('u1', { messages: [{ role: 'user', content: 'how are you' }] }))

  assert.equal(provider.calls.length, 1)
  assert.equal(provider.calls[0].slim, false, 'used the slim prompt while tiering was off')
})

test('tiering ON: a plain chat turn uses ONLY the slim prompt', async () => {
  const provider = tieringProvider() // default reply does not escalate
  const { worker } = build({ provider, conversationTier: true })

  const body = await (await worker.fetch(
    chat('u1', { messages: [{ role: 'user', content: 'what is the capital of France' }] }),
  )).json()

  assert.equal(provider.calls.length, 1, 'a chat turn should be a single slim call')
  assert.equal(provider.calls[0].slim, true)
  // Metered at the slim call's cost only.
  assert.equal(body.usage.input, 200)
  assert.equal(body.usage.output, 10)
})

test('tiering ON: a clearly actiony message skips the slim prompt entirely', async () => {
  // This is the errand-safety guarantee: an action goes STRAIGHT to the full
  // prompt, identical to the untiered path — no reliance on the model raising a flag.
  const provider = tieringProvider()
  const { worker } = build({ provider, conversationTier: true })

  await worker.fetch(chat('u1', { messages: [{ role: 'user', content: 'add milk to my blinkit cart' }] }))

  assert.equal(provider.calls.length, 1, 'an errand must not make a wasted slim call')
  assert.equal(provider.calls[0].slim, false, 'an errand must use the FULL prompt')
})

test('tiering ON: a chat turn that needs to act ESCALATES to the full prompt', async () => {
  // The safety net for an action the keyword gate missed (e.g. a vague ask or a
  // "yes" follow-up): the slim prompt flags <<NEEDS_ACTION>>, and the turn is
  // re-run on the full prompt so the errand still runs.
  const provider = tieringProvider(({ slim }) => (slim ? '<<NEEDS_ACTION>>' : '<<OPEN|Spotify>> playing that now'))
  const { worker, store } = build({ provider, conversationTier: true })

  // A keyword-less request that still needs a device action — exactly what the
  // escalation net exists for (the pre-gate can't catch every phrasing).
  const body = await (await worker.fetch(
    chat('u1', { messages: [{ role: 'user', content: 'can you sort out my morning for me' }] }),
  )).json()

  assert.equal(provider.calls.length, 2, 'should try slim, then escalate to full')
  assert.equal(provider.calls[0].slim, true)
  assert.equal(provider.calls[1].slim, false)
  // The user gets the FULL prompt's answer (with the marker), never the flag.
  assert.match(body.reply, /<<OPEN\|Spotify>>/)
  assert.doesNotMatch(body.reply, /NEEDS_ACTION/)
  // Both calls are charged — input 200+1000, output 10+200.
  assert.equal(body.usage.input, 1200)
  assert.equal(body.usage.output, 210)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 1410)
})

test('tiering ON: an explicit system override is never tiered', async () => {
  // The app's PICK "chooser" sends its own system; the Worker must use it as-is,
  // not run it through the conversation/escalation machinery.
  const provider = tieringProvider()
  const { worker } = build({ provider, conversationTier: true })

  await worker.fetch(chat('u1', {
    messages: [{ role: 'user', content: 'which one' }],
    system: 'You are a chooser. Reply with a number.',
  }))

  assert.equal(provider.calls.length, 1)
  assert.equal(provider.calls[0].slim, false)
  assert.match(provider.calls[0].system, /You are a chooser/)
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
