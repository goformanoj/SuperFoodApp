import { test } from 'node:test'
import assert from 'node:assert/strict'
import { nineCharId, normalizeResult, remapToolIds, rescueToolCalls } from '../src/providers/normalize.js'

const TOOLS = [
  { type: 'function', function: { name: 'play_youtube', parameters: { type: 'object', properties: { query: { type: 'string' } }, required: ['query'] } } },
  { type: 'function', function: { name: 'get_time', parameters: { type: 'object', properties: {} } } },
]
const norm = (raw, tools = TOOLS) => normalizeResult(raw, { tools, inputChars: 400 })
const usage = { prompt_tokens: 10, completion_tokens: 5 }

test('a <think> block is removed so every model answers in plain words', () => {
  const r = norm({ text: '<think>let me see</think>It is five.', usage }, null)
  assert.equal(r.ok, true)
  assert.equal(r.result.text, 'It is five.')
})

test('a tool call printed as JSON text becomes a real tool call', () => {
  for (const text of [
    '{"name":"play_youtube","parameters":{"query":"Channa Mereya"}}',
    '{"name":"play_youtube","arguments":{"query":"Channa Mereya"}}',
    '```json\n{"name":"play_youtube","parameters":{"query":"Channa Mereya"}}\n```',
    '[{"name":"play_youtube","parameters":{"query":"Channa Mereya"}}]',
    '<tool_call>{"name":"play_youtube","arguments":{"query":"Channa Mereya"}}</tool_call>',
  ]) {
    const r = norm({ text, usage })
    assert.equal(r.ok, true, text)
    assert.equal(r.result.text, '')
    assert.equal(r.result.toolCalls[0].name, 'play_youtube')
    assert.deepEqual(JSON.parse(r.result.toolCalls[0].arguments), { query: 'Channa Mereya' })
  }
})

test('the invented-tool reply that fooled a small model is rejected, not shown or run', () => {
  // Seen live: asked "what is 12 times 13?", llama3.2:3b answered with a call to a "multiply" tool that does not exist.
  const r = norm({ text: '{"name":"multiply","parameters":{"x":12,"y":13}}', usage })
  assert.equal(r.ok, false)
  assert.match(r.reason, /unknown_tool:multiply/)
})

test('an unknown tool called properly is rejected too', () => {
  const r = norm({ text: '', toolCalls: [{ id: 'c1', name: 'launch_missiles', arguments: '{}' }], usage })
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'unknown_tool:launch_missiles')
})

test('ordinary JSON that merely has a "name" field is an answer, not a tool call', () => {
  const r = norm({ text: '{"name":"Bob","age":3}', usage })
  assert.equal(r.ok, true)
  assert.equal(r.result.text, '{"name":"Bob","age":3}')
  assert.equal(r.result.toolCalls, undefined)
})

test('a tool with no arguments can be called as just its name', () => {
  const r = norm({ text: '{"name":"get_time"}', usage })
  assert.equal(r.ok, true)
  assert.equal(r.result.toolCalls[0].name, 'get_time')
  assert.equal(r.result.toolCalls[0].arguments, '{}')
})

test('a call missing a required argument, or with broken arguments, is rejected', () => {
  assert.match(norm({ text: '', toolCalls: [{ id: 'c', name: 'play_youtube', arguments: '{}' }], usage }).reason, /missing:play_youtube\.query/)
  assert.match(norm({ text: '', toolCalls: [{ id: 'c', name: 'play_youtube', arguments: '{oops' }], usage }).reason, /bad_arguments/)
})

test('a good call passes through, and a blank or repeated id gets a fresh one', () => {
  const r = norm({ text: '', toolCalls: [
    { id: '', name: 'play_youtube', arguments: '{"query":"a"}' },
    { id: 'same', name: 'play_youtube', arguments: '{"query":"b"}' },
    { id: 'same', name: 'play_youtube', arguments: '{"query":"c"}' },
  ], usage })
  assert.equal(r.ok, true)
  const ids = r.result.toolCalls.map((c) => c.id)
  assert.equal(new Set(ids).size, 3)
  assert.ok(ids.every(Boolean))
})

test('an empty reply is rejected so the next platform gets a turn', () => {
  assert.equal(norm({ text: '  ', usage }).reason, 'empty')
  assert.equal(norm({ text: '<think>only thoughts</think>', usage }, null).reason, 'empty')
})

test('missing token counts are estimated so a turn is never free', () => {
  const r = norm({ text: 'hello there friend', usage: {} }, null)
  assert.equal(r.ok, true)
  assert.ok(r.result.usage.prompt_tokens > 0)
  assert.ok(r.result.usage.completion_tokens > 0)
  const real = norm({ text: 'hi', usage }, null)
  assert.deepEqual([real.result.usage.prompt_tokens, real.result.usage.completion_tokens], [10, 5])
})

test('built-in tool results (web search) pass through untouched', () => {
  const r = normalizeResult({ text: 'Answer 【1†L1】', executedTools: [{ x: 1 }], usage, model: 'm' }, { tools: [{ type: 'browser_search' }] })
  assert.equal(r.ok, true)
  assert.deepEqual(r.result.executedTools, [{ x: 1 }])
  assert.equal(r.result.model, 'm')
})

test('rescueToolCalls leaves ordinary sentences alone', () => {
  assert.deepEqual(rescueToolCalls('Sure — playing it now.'), { calls: [], rest: 'Sure — playing it now.' })
})

test('tool-call ids can be rewritten consistently for a platform that demands 9 characters', () => {
  const id = nineCharId('call_abc_123')
  assert.match(id, /^[A-Za-z0-9]{9}$/)
  assert.equal(nineCharId('call_abc_123'), id)
  assert.notEqual(nineCharId('call_abc_124'), id)
  const msgs = [
    { role: 'user', content: 'hi' },
    { role: 'assistant', content: '', tool_calls: [{ id: 'call_abc_123', type: 'function', function: { name: 'get_time', arguments: '{}' } }] },
    { role: 'tool', tool_call_id: 'call_abc_123', content: '{}' },
  ]
  const out = remapToolIds(msgs, nineCharId)
  assert.equal(out[1].tool_calls[0].id, id)
  assert.equal(out[2].tool_call_id, id)
  assert.equal(msgs[2].tool_call_id, 'call_abc_123') // input untouched
})
