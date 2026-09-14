import { test } from 'node:test'
import assert from 'node:assert/strict'
import { lastUserText, looksActiony, shouldEscalate } from '../src/promptTier.js'

// --- lastUserText -----------------------------------------------------------

test('lastUserText returns the most recent user message', () => {
  const messages = [
    { role: 'user', content: 'first' },
    { role: 'assistant', content: 'reply' },
    { role: 'user', content: 'second' },
  ]
  assert.equal(lastUserText(messages), 'second')
})

test('lastUserText skips a trailing assistant message', () => {
  const messages = [
    { role: 'user', content: 'do the thing' },
    { role: 'assistant', content: 'on it' },
  ]
  assert.equal(lastUserText(messages), 'do the thing')
})

test('lastUserText is safe on junk input', () => {
  assert.equal(lastUserText(null), '')
  assert.equal(lastUserText([]), '')
  assert.equal(lastUserText([{ role: 'assistant', content: 'hi' }]), '')
  assert.equal(lastUserText([{ role: 'user', content: 42 }]), '')
})

// --- looksActiony: real errands MUST route to the full prompt ----------------

test('clear device requests look actiony (route straight to the full prompt)', () => {
  const errands = [
    'add milk to my blinkit cart',
    'open spotify',
    'play despacito',
    'set an alarm for 7am',
    'text mom that I am running late',
    'send it to John on whatsapp',
    'remind me to call the dentist',
    'order a pizza from dominos',
    'take a screenshot',
    'turn on the flashlight',
    'search youtube for standup comedy',
    'navigate home',
    'put on some music',
    'wake me at 6am',
    'go to maps',
    'spotify?', // a bare app name is a request to use it
  ]
  for (const e of errands) {
    assert.equal(looksActiony(e), true, `should look actiony: "${e}"`)
  }
})

test('plain conversation does not look actiony (slim-eligible)', () => {
  const chats = [
    'hi jarvis',
    'thanks!',
    'how are you today',
    "what's the capital of France",
    'explain how backends work',
    "what's 2 plus 2",
    'tell me a joke',
    'who won the world cup in 2022',
    'good morning',
    'that was helpful, cheers',
  ]
  for (const c of chats) {
    assert.equal(looksActiony(c), false, `should be slim-eligible: "${c}"`)
  }
})

test('looksActiony is safe on junk input', () => {
  assert.equal(looksActiony(null), false)
  assert.equal(looksActiony(''), false)
  assert.equal(looksActiony('   '), false)
})

// --- shouldEscalate: any sign of needing to act re-runs on the full prompt ---

test('the explicit NEEDS_ACTION flag escalates', () => {
  assert.equal(shouldEscalate('<<NEEDS_ACTION>>'), true)
  assert.equal(shouldEscalate('  <<needs_action>>  '), true)
  assert.equal(shouldEscalate('<< NEEDS_ACTION >>'), true)
})

test('a stray app marker in a slim reply escalates', () => {
  // The slim prompt should not emit markers, but if the model tries to act anyway,
  // escalate so it gets a proper, complete plan from the full prompt.
  assert.equal(shouldEscalate('<<OPEN|Spotify>> sure'), true)
})

test('a spoken action claim in a slim reply escalates', () => {
  assert.equal(shouldEscalate('Opening Spotify now'), true)
  assert.equal(shouldEscalate('Adding milk to your cart'), true)
  assert.equal(shouldEscalate('Setting an alarm for 7'), true)
  assert.equal(shouldEscalate('Sending that to Mom'), true)
})

test('an ordinary conversational reply does NOT escalate', () => {
  const replies = [
    'The capital of France is Paris.',
    'Sure, happy to help!',
    "I'm doing well, thanks for asking.",
    '2 plus 2 is 4.',
    'A backend is the part of an app that runs on servers.',
    '',
    '   ',
  ]
  for (const r of replies) {
    assert.equal(shouldEscalate(r), false, `should NOT escalate: "${r}"`)
  }
})

test('shouldEscalate is safe on junk input', () => {
  assert.equal(shouldEscalate(null), false)
  assert.equal(shouldEscalate(undefined), false)
})
