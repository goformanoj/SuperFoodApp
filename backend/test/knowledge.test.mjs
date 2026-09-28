import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { groqProvider } from '../src/providers/groq.js'
import { dayKey } from '../src/quota.js'
import {
  WEB_SEARCH_MODELS, VISION_MODELS, WEB_SEARCH_PROMPT, VISION_PROMPT, MAX_IMAGE_B64,
  checkSearch, checkVision, cleanAnswer, sourcesOf, stripThinking,
} from '../src/knowledge.js'

const NOW = Date.parse('2026-09-28T12:00:00Z')
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

function post(path, body, headers = {}) {
  return new Request(`https://proxy${path}`, {
    method: 'POST',
    headers: { 'X-Uid': 'u1', 'Content-Type': 'application/json', ...headers },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

function build(providerOptions, extra = {}) {
  const store = memoryStore()
  const provider = fakeProvider(providerOptions)
  const worker = createWorker({ store, provider, now: () => NOW, ...extra })
  return { worker, store, provider }
}

// --- /search -------------------------------------------------------------------

test('web search runs browser_search on the gpt-oss models and returns a clean answer with sources', async () => {
  const executedTools = [{ type: 'browser_search', search_results: { results: [
    { title: 'RBI holds repo rate at 5.5%', url: 'https://example.com/rbi', content: '…' },
    { title: 'Monetary policy statement', url: 'https://rbi.org.in/statement', content: '…' },
  ] } }]
  const { worker, store, provider } = build({ script: [{ text: 'The RBI kept the repo rate at 5.5% on 1 October 【0†L3-L5】.', executedTools }] })
  const res = await worker.fetch(post('/search', { query: 'latest RBI rate decision', context: 'Current date/time: Monday.' }))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.answer, 'The RBI kept the repo rate at 5.5% on 1 October.')
  assert.deepEqual(body.sources, [
    { title: 'RBI holds repo rate at 5.5%', url: 'https://example.com/rbi' },
    { title: 'Monetary policy statement', url: 'https://rbi.org.in/statement' },
  ])
  const call = provider.calls[0]
  assert.deepEqual(call.models, WEB_SEARCH_MODELS)
  assert.deepEqual(call.tools, [{ type: 'browser_search' }])
  assert.deepEqual(call.messages, [{ role: 'user', content: 'latest RBI rate decision' }])
  assert.ok(call.system.startsWith(WEB_SEARCH_PROMPT))
  assert.ok(call.system.endsWith('Current date/time: Monday.'))
  // Metered like /chat: the provider's own numbers.
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 1200)
  assert.equal(body.usage.input, 1000)
})

test('an empty or oversized query is refused before any spend', async () => {
  const { worker, store, provider } = build()
  for (const [q, status] of [['', 400], ['   ', 400], ['x'.repeat(401), 413]]) {
    const res = await worker.fetch(post('/search', { query: q }))
    assert.equal(res.status, status)
  }
  assert.equal(provider.calls.length, 0)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 0)
})

test('search is refused over the cap, and the provider is never paid', async () => {
  const { worker, store, provider } = build()
  await store.addUsage('u1', dayKey(NOW), 10_000_000, 0)
  const res = await worker.fetch(post('/search', { query: 'weather' }))
  assert.equal(res.status, 429)
  assert.equal(provider.calls.length, 0)
})

test('a provider failure is not billed', async () => {
  const { worker, store } = build({ fail: 'boom' })
  const res = await worker.fetch(post('/search', { query: 'weather' }))
  assert.equal(res.status, 502)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 0)
})

test('the knowledge routes sit behind the same app secret', async () => {
  const { worker, provider } = build({}, { proxySecret: 's3cret' })
  for (const path of ['/search', '/vision']) {
    const res = await worker.fetch(post(path, { query: 'x', image: PNG }))
    assert.equal(res.status, 403)
  }
  assert.equal(provider.calls.length, 0)
})

// --- /vision -------------------------------------------------------------------

test('a screenshot question goes to the vision model as text + image, thinking stripped', async () => {
  const { worker, store, provider } = build({ script: [{ text: '<think>looking…</think>It is a “disk full” error: free some space on C:.' }] })
  const res = await worker.fetch(post('/vision', { image: PNG, question: "What's this error?" }))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.answer, 'It is a “disk full” error: free some space on C:.')
  assert.equal(body.sources, undefined)
  const call = provider.calls[0]
  assert.deepEqual(call.models, VISION_MODELS)
  assert.equal(call.tools, undefined)
  assert.equal(call.system, VISION_PROMPT)
  assert.deepEqual(call.messages[0].content, [
    { type: 'text', text: "What's this error?" },
    { type: 'image_url', image_url: { url: PNG } },
  ])
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 1200)
})

test('bad images are refused before any spend', async () => {
  const { worker, provider } = build()
  const cases = [
    [{ question: 'x' }, 400],
    [{ image: 'https://example.com/a.png' }, 415],
    [{ image: 'data:image/gif;base64,R0lGOD' }, 415],
    [{ image: 'data:image/png;base64,' + 'A'.repeat(MAX_IMAGE_B64 + 4) }, 413],
    [{ image: PNG, question: 'q'.repeat(1001) }, 413],
  ]
  for (const [body, status] of cases) {
    const res = await worker.fetch(post('/vision', body))
    assert.equal(res.status, status, JSON.stringify(body).slice(0, 60))
  }
  assert.equal(provider.calls.length, 0)
})

test('bad JSON is a 400, not a crash', async () => {
  const { worker } = build()
  assert.equal((await worker.fetch(post('/search', '{nope'))).status, 400)
})

// --- pure helpers --------------------------------------------------------------

test('checkVision defaults the question', () => {
  assert.equal(checkVision({ image: PNG }).question, 'What is on this screen?')
  assert.equal(checkSearch({ query: '  news  ' }).query, 'news')
})

test('cleanAnswer drops citation marks and the spaces they leave', () => {
  assert.equal(cleanAnswer('Rates rose 【1†L2-L9】 today 【3】.'), 'Rates rose today.')
  assert.equal(cleanAnswer(null), '')
})

test('sourcesOf: executed tools first, then URLs in the text; de-duplicated and capped', () => {
  const tools = [{ search_results: { results: [{ title: ' A ', url: 'https://a.com/x' }, { url: 'javascript:alert(1)' }, { title: 'dup', url: 'https://a.com/x' }] } }]
  const s = sourcesOf(tools, 'See https://www.b.org/page). And https://a.com/x')
  assert.deepEqual(s, [{ title: 'A', url: 'https://a.com/x' }, { title: 'b.org', url: 'https://www.b.org/page' }])
  const many = Array.from({ length: 9 }, (_, i) => ({ url: `https://s${i}.com` }))
  assert.equal(sourcesOf([{ search_results: many }], '').length, 5)
  assert.deepEqual(sourcesOf(undefined, undefined), [])
})

test('stripThinking removes inline reasoning, even unterminated', () => {
  assert.equal(stripThinking('<think>a\nb</think>\nAnswer'), 'Answer')
  assert.equal(stripThinking('<think>never closed'), '')
})

// --- the real provider's request shape ------------------------------------------

test('groq: a built-in tool gets no tool_choice; reasoning effort and limits pass through; executed_tools come back', async () => {
  const sent = []
  const realFetch = globalThis.fetch
  globalThis.fetch = async (_url, init) => {
    sent.push(JSON.parse(init.body))
    return new Response(JSON.stringify({
      choices: [{ message: { content: 'ok', executed_tools: [{ search_results: { results: [{ url: 'https://x.com' }] } }] } }],
      usage: { prompt_tokens: 5, completion_tokens: 1 },
    }), { status: 200 })
  }
  try {
    const p = groqProvider('k')
    const out = await p.complete({
      models: ['openai/gpt-oss-120b'], messages: [{ role: 'user', content: 'q' }], system: 's',
      tools: [{ type: 'browser_search' }], extra: { reasoningEffort: 'low', maxTokens: 1500, temperature: 0.3 },
    })
    assert.equal(sent[0].tool_choice, undefined)
    assert.deepEqual(sent[0].tools, [{ type: 'browser_search' }])
    assert.equal(sent[0].reasoning_effort, 'low')
    assert.equal(sent[0].max_tokens, 1500)
    assert.equal(sent[0].temperature, 0.3)
    assert.equal(out.executedTools.length, 1)
    // A plain call is unchanged: default temperature, no reasoning_effort.
    await p.complete({ models: ['openai/gpt-oss-120b'], messages: [{ role: 'user', content: 'q' }] })
    assert.equal(sent[1].temperature, 0.7)
    assert.equal('reasoning_effort' in sent[1], false)
  } finally {
    globalThis.fetch = realFetch
  }
})
