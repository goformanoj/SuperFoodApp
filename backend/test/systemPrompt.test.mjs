import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { SYSTEM_PROMPT } from '../src/systemPrompt.js'

function chat(body) {
  return new Request('https://proxy/chat', {
    method: 'POST',
    headers: { 'X-Uid': 'u1', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

function build() {
  const store = memoryStore()
  const provider = fakeProvider()
  const worker = createWorker({ store, provider })
  return { worker, provider }
}

test('when the request sends no system prompt, the Worker supplies its default', async () => {
  const { worker, provider } = build()
  await worker.fetch(chat({ messages: [{ role: 'user', content: 'hi' }] }))
  assert.equal(provider.calls[0].system, SYSTEM_PROMPT)
})

test('a request may still override the system prompt', async () => {
  const { worker, provider } = build()
  await worker.fetch(chat({ messages: [{ role: 'user', content: 'hi' }], system: 'custom prompt' }))
  assert.equal(provider.calls[0].system, 'custom prompt')
})

test('the default prompt carries the marker protocol the eval depends on', () => {
  // A cheap guard against the prompt being gutted: the eval asserts on these
  // marker kinds, so they must be taught here.
  for (const token of ['<<OPEN|', '<<TYPE|', '<<TAP|', '<<PICK|', '<<ALARM|', 'TYPING IS NOT SENDING']) {
    assert.ok(SYSTEM_PROMPT.includes(token), `system prompt is missing ${token}`)
  }
})
