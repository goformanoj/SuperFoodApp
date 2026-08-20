import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'

const SECRET = 'a-long-random-string'

function chat(headers = {}) {
  return new Request('https://proxy/chat', {
    method: 'POST',
    headers: { 'X-Uid': 'u1', 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }),
  })
}

function build() {
  const provider = fakeProvider()
  const worker = createWorker({ store: memoryStore(), provider, proxySecret: SECRET })
  return { worker, provider }
}

test('the right secret gets through', async () => {
  const { worker } = build()
  const res = await worker.fetch(chat({ 'X-Proxy-Secret': SECRET }))
  assert.equal(res.status, 200)
})

test('a wrong secret is refused WITHOUT calling the provider', async () => {
  // The whole point of the gate is that a stranger cannot spend the Groq key.
  // Refusing after the call would leave the bill exactly where it was.
  const { worker, provider } = build()

  const res = await worker.fetch(chat({ 'X-Proxy-Secret': 'guess' }))

  assert.equal(res.status, 403)
  assert.equal(provider.calls.length, 0)
})

test('a missing secret is refused', async () => {
  const { worker, provider } = build()
  const res = await worker.fetch(chat())
  assert.equal(res.status, 403)
  assert.equal(provider.calls.length, 0)
})

test('a secret of the wrong length is refused, not compared loosely', async () => {
  const { worker } = build()
  assert.equal((await worker.fetch(chat({ 'X-Proxy-Secret': SECRET + 'x' }))).status, 403)
  assert.equal((await worker.fetch(chat({ 'X-Proxy-Secret': SECRET.slice(0, -1) }))).status, 403)
})

test('health stays open — it must be checkable from a browser', async () => {
  const { worker } = build()
  const res = await worker.fetch(new Request('https://proxy/health'))
  assert.equal(res.status, 200)
})

test('with no secret configured the gate is off — but the deployed worker never gets there', async () => {
  // createWorker is also used by the eval harness and by tests, which have no
  // secret. The DEPLOYED entry point fails closed instead: see the 503 in
  // index.js, which refuses to serve at all when PROXY_SECRET is unset.
  const worker = createWorker({ store: memoryStore(), provider: fakeProvider(), proxySecret: null })
  assert.equal((await worker.fetch(chat())).status, 200)
})
