import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ProviderError } from '../src/providers/groq.js'
import { requirementsOf, routerProvider } from '../src/providers/router.js'

const USAGE = { prompt_tokens: 100, completion_tokens: 20 }
const TOOLS = [{ type: 'function', function: { name: 'get_time', parameters: { type: 'object', properties: {} } } }]
const ask = (r, extra = {}) => r.complete({ models: ['groq-model'], messages: [{ role: 'user', content: 'hi' }], system: 's', ...extra })

/** A platform stand-in: `script` decides what each call does. Records what it was asked. */
function backend(name, script, extras = {}) {
  const calls = []
  return {
    name,
    calls,
    models: extras.models ?? [`${name}-model`],
    caps: { tools: true, ...extras.caps },
    quirks: extras.quirks,
    visionModels: extras.visionModels,
    provider: {
      async complete(req) {
        calls.push(req)
        const step = typeof script === 'function' ? script(req, calls.length) : script
        if (step instanceof Error) throw step
        return { text: 'ok', usage: USAGE, model: `${name}-model`, ...step }
      },
    },
  }
}

test('the first platform answers when it is healthy, and the reply says who answered', async () => {
  const a = backend('a', { text: 'from a' })
  const b = backend('b', { text: 'from b' })
  const out = await ask(routerProvider([a, b]))
  assert.equal(out.text, 'from a')
  assert.equal(out.backend, 'a')
  assert.equal(b.calls.length, 0)
})

test('when a platform fails, the next one answers — and the failover is reported', async () => {
  const events = []
  const a = backend('a', new ProviderError('all_models_rate_limited', 503))
  const b = backend('b', { text: 'from b' })
  const out = await ask(routerProvider([a, b], { onEvent: (e) => events.push(e) }))
  assert.equal(out.backend, 'b')
  assert.ok(events.some((e) => e.type === 'failover' && e.served === 'b'))
})

test('a reply that invents a tool is rejected and the next platform is tried', async () => {
  const a = backend('a', { text: '{"name":"multiply","parameters":{"x":1}}' })
  const b = backend('b', { text: 'The answer is 156.' })
  const r = routerProvider([a, b])
  const out = await ask(r, { tools: TOOLS })
  assert.equal(out.text, 'The answer is 156.')
  assert.equal(out.backend, 'b')
  assert.equal(r.stats().a.rejected, 1)
})

test('a tool call printed as text is repaired into a real call, with no failover', async () => {
  const a = backend('a', { text: '{"name":"get_time","parameters":{}}' })
  const out = await ask(routerProvider([a]), { tools: TOOLS })
  assert.equal(out.backend, 'a')
  assert.equal(out.toolCalls[0].name, 'get_time')
  assert.equal(out.text, '')
})

test('a failing platform cools down, so later requests skip it until the time has passed', async () => {
  let t = 1_000_000
  const a = backend('a', new ProviderError('all_models_rate_limited', 503))
  const b = backend('b', { text: 'from b' })
  const r = routerProvider([a, b], { now: () => t })
  await ask(r)
  await ask(r)
  assert.equal(a.calls.length, 1, 'second request should not have bothered a')
  t += 31_000
  await ask(r)
  assert.equal(a.calls.length, 2, 'after the cooldown a gets another chance')
})

test('a bad key is remembered for much longer than a busy platform', async () => {
  let t = 0
  const a = backend('a', new ProviderError('http_401', 502))
  const b = backend('b', {})
  const r = routerProvider([a, b], { now: () => t })
  await ask(r)
  t += 5 * 60_000
  await ask(r)
  assert.equal(a.calls.length, 1)
  t += 6 * 60_000
  await ask(r)
  assert.equal(a.calls.length, 2)
})

test('if every platform is cooling down it tries them anyway rather than refusing', async () => {
  let t = 0
  let ok = false
  const a = backend('a', () => (ok ? { text: 'back' } : new ProviderError('http_500', 502)))
  const r = routerProvider([a], { now: () => t })
  await assert.rejects(ask(r))
  ok = true
  assert.equal((await ask(r)).text, 'back')
})

test('web search (a built-in tool) is only ever sent to a platform that has it', async () => {
  const groq = backend('groq', { text: 'searched', executedTools: [{ n: 1 }] }, { caps: { builtinSearch: true } })
  const other = backend('other', {})
  const out = await ask(routerProvider([other, groq]), { tools: [{ type: 'browser_search' }] })
  assert.equal(out.backend, 'groq')
  assert.equal(other.calls.length, 0)
  assert.deepEqual(out.executedTools, [{ n: 1 }])
  await assert.rejects(ask(routerProvider([other]), { tools: [{ type: 'browser_search' }] }), /no_platform_can_serve_this/)
})

test('images go only to platforms that read them, with their own vision models', async () => {
  const text = backend('text', {})
  const eyes = backend('eyes', {}, { caps: { vision: true }, visionModels: ['eyes-vision'] })
  const messages = [{ role: 'user', content: [{ type: 'text', text: 'what?' }, { type: 'image_url', image_url: { url: 'data:image/png;base64,AA==' } }] }]
  const out = await routerProvider([text, eyes]).complete({ models: ['x'], messages, system: 's' })
  assert.equal(out.backend, 'eyes')
  assert.deepEqual(eyes.calls[0].models, ['eyes-vision'])
  assert.equal(text.calls.length, 0)
})

test('each platform gets its own model list; the first inherits the caller\'s', async () => {
  const groq = backend('groq', new ProviderError('all_models_rate_limited', 503), { models: 'inherit' })
  const cerebras = backend('cerebras', {}, { models: ['gpt-oss-120b', 'qwen-3.8-27b'] })
  await ask(routerProvider([groq, cerebras]))
  assert.deepEqual(groq.calls[0].models, ['groq-model'])
  assert.deepEqual(cerebras.calls[0].models, ['gpt-oss-120b', 'qwen-3.8-27b'])
})

test('live model discovery is asked what the request needs', async () => {
  const seen = []
  const dynamic = backend('or', {}, { models: async (needs) => { seen.push(needs); return ['discovered:free'] } })
  await ask(routerProvider([dynamic]), { tools: TOOLS })
  assert.equal(seen[0].functions, true)
  assert.deepEqual(dynamic.calls[0].models, ['discovered:free'])
})

test('a platform with no models is skipped, not a crash', async () => {
  const empty = backend('empty', {}, { models: [] })
  const b = backend('b', { text: 'b' })
  assert.equal((await ask(routerProvider([empty, b]))).backend, 'b')
})

test('reasoning effort is only sent to platforms that understand it', async () => {
  const a = backend('a', {}, { caps: { reasoningEffort: true } })
  const b = backend('b', new ProviderError('all_models_rate_limited', 503))
  const c = backend('c', {})
  await ask(routerProvider([b, c]), { extra: { reasoningEffort: 'low', maxTokens: 50 } })
  assert.equal(c.calls[0].extra.reasoningEffort, undefined)
  assert.equal(c.calls[0].extra.maxTokens, 50)
  await ask(routerProvider([a]), { extra: { reasoningEffort: 'low' } })
  assert.equal(a.calls[0].extra.reasoningEffort, 'low')
})

test('a platform that insists on 9-character tool ids gets them, consistently', async () => {
  const picky = backend('picky', {}, { quirks: { toolIdLen9: true } })
  const messages = [
    { role: 'user', content: 'time?' },
    { role: 'assistant', content: '', tool_calls: [{ id: 'call_from_groq_1', type: 'function', function: { name: 'get_time', arguments: '{}' } }] },
    { role: 'tool', tool_call_id: 'call_from_groq_1', content: '{}' },
  ]
  await routerProvider([picky]).complete({ models: [], messages, system: 's', tools: TOOLS })
  const sent = picky.calls[0].messages
  assert.match(sent[1].tool_calls[0].id, /^[A-Za-z0-9]{9}$/)
  assert.equal(sent[1].tool_calls[0].id, sent[2].tool_call_id)
})

test('when everything fails the error names each platform, and rate limits read as 503', async () => {
  const a = backend('a', new ProviderError('all_models_rate_limited', 503))
  const b = backend('b', new ProviderError('all_models_rate_limited', 503))
  await assert.rejects(ask(routerProvider([a, b])), (e) => e.status === 503 && /a:all_models_rate_limited/.test(e.message) && /b:/.test(e.message))
  const c = backend('c', new ProviderError('http_500', 502))
  await assert.rejects(ask(routerProvider([c])), (e) => e.status === 502)
})

test('requirementsOf reads what a request needs', () => {
  assert.deepEqual(requirementsOf({ messages: [], tools: [{ type: 'browser_search' }] }), { builtin: true, functions: false, vision: false, reasoning: false })
  assert.equal(requirementsOf({ messages: [], tools: TOOLS, extra: { reasoningEffort: 'low' } }).reasoning, true)
})

// --- through the real Worker -------------------------------------------------------------------

import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { dayKey } from '../src/quota.js'

test('through /chat: a failover still answers, says who answered, and bills the platform that did', async () => {
  const NOW = Date.parse('2026-09-29T12:00:00Z')
  const a = backend('a', new ProviderError('all_models_rate_limited', 503))
  const b = backend('b', { text: 'Hello from b', usage: { prompt_tokens: 300, completion_tokens: 40 } }, { models: ['b-model'] })
  const store = memoryStore()
  const w = createWorker({ store, provider: routerProvider([a, b]), now: () => NOW })
  const res = await w.fetch(new Request('https://proxy/chat', {
    method: 'POST', headers: { 'X-Uid': 'u1', 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }),
  }))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.reply, 'Hello from b')
  assert.equal(body.backend, 'b')
  assert.deepEqual(body.usage, { input: 300, output: 40 })
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 340)
})

test('through /chat with every platform down: an error, and nothing is billed', async () => {
  const NOW = Date.parse('2026-09-29T12:00:00Z')
  const store = memoryStore()
  const w = createWorker({ store, provider: routerProvider([backend('a', new ProviderError('http_500', 502))]), now: () => NOW })
  const res = await w.fetch(new Request('https://proxy/chat', {
    method: 'POST', headers: { 'X-Uid': 'u1', 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }),
  }))
  assert.equal(res.status, 502)
  assert.match((await res.json()).detail, /all_platforms_failed/)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 0)
})
