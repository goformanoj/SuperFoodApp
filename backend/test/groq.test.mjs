import { test } from 'node:test'
import assert from 'node:assert/strict'
import { groqProvider, looksRetired, retryAfterSeconds } from '../src/providers/groq.js'

const MODELS = ['model-a', 'model-b']
const ask = (p) => p.complete({ models: MODELS, messages: [{ role: 'user', content: 'hi' }] })

/** Replaces global fetch with a scripted sequence, and records what was asked. */
function scripted(responses) {
  const seen = []
  const original = globalThis.fetch
  globalThis.fetch = async (_url, init) => {
    seen.push(JSON.parse(init.body).model)
    const next = responses.shift()
    if (!next) throw new Error('the provider asked more times than the script allows')
    return new Response(next.body ?? '', { status: next.status ?? 200, headers: next.headers ?? {} })
  }
  return { seen, restore: () => { globalThis.fetch = original } }
}

const ok = (content) =>
  ({ body: JSON.stringify({ choices: [{ message: { content } }], usage: { prompt_tokens: 5, completion_tokens: 2 } }) })

test('a normal reply comes back with its model and usage', async () => {
  const s = scripted([ok('hello')])
  try {
    const out = await ask(groqProvider('k'))
    assert.equal(out.text, 'hello')
    assert.equal(out.model, 'model-a')
    assert.equal(out.usage.prompt_tokens, 5)
  } finally { s.restore() }
})

test('an empty reply retries the SAME model before moving on', async () => {
  // It is not a broken model, it is one that spent its budget without emitting
  // text — transient. The Android client fell straight through to the next
  // model, and when both llamas were retired there was no next model, so the
  // turn died with "Empty reply from model".
  const s = scripted([ok('   '), ok('second time lucky')])
  try {
    const out = await ask(groqProvider('k'))
    assert.equal(out.text, 'second time lucky')
    assert.deepEqual(s.seen, ['model-a', 'model-a'], 'it should not have switched models')
  } finally { s.restore() }
})

test('a retired model is dropped and never asked again in this isolate', async () => {
  const s = scripted([
    { status: 404, body: 'model not found' },
    ok('from the second model'),
    ok('again from the second model'),
  ])
  try {
    const provider = groqProvider('k')
    assert.equal((await ask(provider)).text, 'from the second model')
    assert.equal((await ask(provider)).text, 'again from the second model')
    // Three calls, not four: the dead model is asked once, ever.
    assert.deepEqual(s.seen, ['model-a', 'model-b', 'model-b'])
  } finally { s.restore() }
})

test('Groq reporting a dead model as 400 is also caught', async () => {
  // It has used both statuses. Matching only on 404 missed it once already.
  const s = scripted([{ status: 400, body: '{"error":{"message":"model has been decommissioned"}}' }, ok('b')])
  try {
    assert.equal((await ask(groqProvider('k'))).text, 'b')
  } finally { s.restore() }
})

test('a 429 cools that model down and the other one answers', async () => {
  // Quotas are per model, not per account.
  const s = scripted([{ status: 429, body: 'try again in 30s', headers: { 'Retry-After': '30' } }, ok('b')])
  try {
    const provider = groqProvider('k')
    assert.equal((await ask(provider)).text, 'b')
  } finally { s.restore() }
})

test('every model retired is a 503, not a generic failure', async () => {
  const s = scripted([{ status: 404, body: 'gone' }, { status: 404, body: 'gone' }])
  try {
    await assert.rejects(ask(groqProvider('k')), (e) => {
      assert.equal(e.status, 503)
      assert.match(e.message, /retired/)
      return true
    })
  } finally { s.restore() }
})

test('a non-retryable HTTP error stops the chain immediately', async () => {
  // A bad key is not going to be fixed by asking a different model, and trying
  // anyway just doubles the noise in the provider's logs.
  const s = scripted([{ status: 401, body: 'invalid api key' }])
  try {
    await assert.rejects(ask(groqProvider('k')))
    assert.deepEqual(s.seen, ['model-a'], 'it should not have tried the second model')
  } finally { s.restore() }
})

test('the key never appears in the error surfaced upward', async () => {
  const s = scripted([{ status: 401, body: 'invalid api key gsk_supersecret' }])
  try {
    await ask(groqProvider('gsk_supersecret'))
    assert.fail('should have thrown')
  } catch (e) {
    assert.ok(!String(e.message).includes('gsk_supersecret'), 'the key leaked into the error')
  } finally { s.restore() }
})

// --- the pure helpers --------------------------------------------------------

test('the retry wait is read from the header, then the body, then defaulted', () => {
  assert.equal(retryAfterSeconds('30', ''), 30)
  assert.equal(retryAfterSeconds(null, 'please try again in 12s'), 12)
  assert.equal(retryAfterSeconds(null, 'try again in 2m'), 120)
  assert.equal(retryAfterSeconds(null, 'no idea'), 20)
})

test('the retry wait is clamped, however large the provider claims', () => {
  assert.equal(retryAfterSeconds('99999', ''), 900)
})

test('the words Groq uses for a dead model are all recognised', () => {
  for (const body of ['decommissioned', 'no longer supported', 'does not exist', 'has been deprecated']) {
    assert.ok(looksRetired(body), body)
  }
  assert.ok(!looksRetired('rate limit reached'))
})
