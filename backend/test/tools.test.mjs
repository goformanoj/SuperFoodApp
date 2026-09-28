import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker, validTools, MAX_TOOLS } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { groqProvider, toolCallsOf } from '../src/providers/groq.js'
import { DESKTOP_AGENT_PROMPT, SYSTEM_PROMPT } from '../src/systemPrompt.js'
import { dayKey } from '../src/quota.js'

const NOW = Date.parse('2026-09-28T12:00:00Z')

const TOOLS = [
  { type: 'function', function: { name: 'add_task', description: 'Add a to-do', parameters: { type: 'object', properties: { title: { type: 'string' } }, required: ['title'] } } },
  { type: 'function', function: { name: 'get_time', description: 'Now', parameters: { type: 'object', properties: {} } } },
]

function chat(body) {
  return new Request('https://proxy/chat', {
    method: 'POST',
    headers: { 'X-Uid': 'u1', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

function build(providerOptions) {
  const store = memoryStore()
  const provider = fakeProvider(providerOptions)
  const worker = createWorker({ store, provider, now: () => NOW })
  return { worker, store, provider }
}

// --- /chat with tools ---------------------------------------------------------

test('tool calls from the model come back to the client, and the turn is metered', async () => {
  const call = { id: 'c1', name: 'add_task', arguments: '{"title":"Call the bank"}' }
  const { worker, store, provider } = build({ script: [{ text: '', toolCalls: [call] }] })
  const res = await worker.fetch(chat({ messages: [{ role: 'user', content: 'Add call the bank' }], tools: TOOLS, context: 'Current date/time: Monday.' }))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.deepEqual(body.tool_calls, [call])
  assert.equal(body.reply, '')
  // The desktop prompt, with the client's context appended — NOT the phone's marker prompt.
  assert.ok(provider.calls[0].system.startsWith(DESKTOP_AGENT_PROMPT))
  assert.ok(provider.calls[0].system.endsWith('Current date/time: Monday.'))
  assert.deepEqual(provider.calls[0].tools, TOOLS)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 1200)
})

test('a tool result goes back and the model answers in words', async () => {
  const { worker, provider } = build({ script: [{ text: 'Added “Call the bank” for tomorrow at 5.' }] })
  const messages = [
    { role: 'user', content: 'Add call the bank tomorrow at 5' },
    { role: 'assistant', content: '', tool_calls: [{ id: 'c1', type: 'function', function: { name: 'add_task', arguments: '{"title":"Call the bank"}' } }] },
    { role: 'tool', tool_call_id: 'c1', content: '{"ok":true}' },
  ]
  const body = await (await worker.fetch(chat({ messages, tools: TOOLS }))).json()
  assert.equal(body.reply, 'Added “Call the bank” for tomorrow at 5.')
  assert.equal(body.tool_calls, undefined)
  assert.deepEqual(provider.calls[0].messages, messages) // passed through untouched
})

test('without tools the response has no tool_calls and the phone prompt is used (unchanged path)', async () => {
  const { worker, provider } = build()
  const body = await (await worker.fetch(chat({ messages: [{ role: 'user', content: 'hi' }] }))).json()
  assert.equal(body.reply, 'This is a fake reply.')
  assert.ok(!('tool_calls' in body))
  assert.equal(provider.calls[0].tools, undefined)
  assert.equal(provider.calls[0].system, SYSTEM_PROMPT)
})

test('an explicit system override still wins with tools', async () => {
  const { worker, provider } = build({ script: [{ text: 'ok' }] })
  await worker.fetch(chat({ messages: [{ role: 'user', content: 'x' }], tools: TOOLS, system: 'Only say ok.' }))
  assert.equal(provider.calls[0].system, 'Only say ok.')
})

test('malformed tool lists are refused before any spend', async () => {
  const { worker, store, provider } = build()
  for (const tools of [[], 'nope', [{ type: 'function', function: { name: 'bad name!' } }], [{ type: 'other' }]]) {
    const res = await worker.fetch(chat({ messages: [{ role: 'user', content: 'x' }], tools }))
    assert.equal(res.status, 400, JSON.stringify(tools))
  }
  assert.equal(provider.calls.length, 0)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 0)
})

test('validTools: null when absent, the list when good, capped in size', () => {
  assert.equal(validTools(undefined), null)
  assert.deepEqual(validTools(TOOLS), TOOLS)
  const many = Array.from({ length: MAX_TOOLS + 1 }, (_, i) => ({ type: 'function', function: { name: `t${i}` } }))
  assert.notEqual(validTools(many), many)
})

// --- the real Groq provider, against a mocked network --------------------------

function mockFetch(responses) {
  const bodies = []
  const orig = globalThis.fetch
  let i = 0
  globalThis.fetch = async (_url, init) => {
    bodies.push(JSON.parse(init.body))
    const r = responses[Math.min(i++, responses.length - 1)]
    return new Response(JSON.stringify(r), { status: 200 })
  }
  return { bodies, restore: () => { globalThis.fetch = orig } }
}

test('groq: a tool-only reply is returned, not retried as "empty"', async () => {
  const net = mockFetch([{
    choices: [{ message: { content: null, tool_calls: [{ id: 'x', type: 'function', function: { name: 'add_task', arguments: '{"title":"T"}' } }] } }],
    usage: { prompt_tokens: 10, completion_tokens: 5 },
  }])
  try {
    const out = await groqProvider('k', { sleep: async () => {} }).complete({ models: ['m'], messages: [{ role: 'user', content: 'x' }], system: 's', tools: TOOLS })
    assert.equal(out.text, '')
    assert.deepEqual(out.toolCalls, [{ id: 'x', name: 'add_task', arguments: '{"title":"T"}' }])
    assert.equal(net.bodies.length, 1)
    assert.deepEqual(net.bodies[0].tools, TOOLS)
    assert.equal(net.bodies[0].tool_choice, 'auto')
  } finally { net.restore() }
})

test('groq: without tools the request body has no tools at all', async () => {
  const net = mockFetch([{ choices: [{ message: { content: 'hello' } }], usage: {} }])
  try {
    const out = await groqProvider('k').complete({ models: ['m'], messages: [{ role: 'user', content: 'x' }], system: 's' })
    assert.equal(out.text, 'hello')
    assert.equal(out.toolCalls, undefined)
    assert.ok(!('tools' in net.bodies[0]))
    assert.ok(!('tool_choice' in net.bodies[0]))
  } finally { net.restore() }
})

test('toolCallsOf keeps string arguments, stringifies objects, drops nameless calls', () => {
  assert.deepEqual(toolCallsOf({ tool_calls: [
    { id: 'a', function: { name: 'n', arguments: '{"x":1}' } },
    { id: 'b', function: { name: 'm', arguments: { y: 2 } } },
    { id: 'c', function: {} },
  ] }), [
    { id: 'a', name: 'n', arguments: '{"x":1}' },
    { id: 'b', name: 'm', arguments: '{"y":2}' },
  ])
  assert.deepEqual(toolCallsOf({}), [])
})
