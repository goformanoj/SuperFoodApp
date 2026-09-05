/**
 * Unit tests for the PURE eval assertion logic (no network).
 * Run: node --test scripts/eval/*.test.mjs
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseMarkers, checkScenario } from './assert.mjs'

test('parseMarkers extracts type and args, ignoring prose', () => {
  const m = parseMarkers('sure, doing that now\n<<OPEN|Blinkit>>\n<<TYPE|milk and bread>>')
  assert.equal(m.length, 2)
  assert.deepEqual(m[0], { type: 'OPEN', arg: 'Blinkit', args: ['Blinkit'], raw: '<<OPEN|Blinkit>>' })
  assert.equal(m[1].type, 'TYPE')
  assert.equal(m[1].arg, 'milk and bread')
})

test('B1: opens the cart but does not check out -> pass; checks out -> fail', () => {
  const s = {
    id: 'B1',
    must: [{ type: 'OPEN', arg: /blinkit/i }],
    mustNot: [{ type: 'TAP', arg: /check\s?out|pay/i }],
  }
  assert.equal(checkScenario(s, '<<OPEN|Blinkit>> <<TAP|Search>> <<TYPE|milk>> <<TAP|Add>>').pass, true)

  const bad = checkScenario(s, '<<OPEN|Blinkit>> <<TAP|Add>> <<TAP|Checkout>>')
  assert.equal(bad.pass, false)
  assert.match(bad.failures[0], /forbidden/)
})

test('typing is not sending: C1 fails if it taps Send', () => {
  const s = {
    id: 'C1',
    must: [{ type: 'TYPE', arg: /good morning/i }],
    mustNot: [{ type: 'TAP', arg: /send/i }],
  }
  assert.equal(checkScenario(s, '<<TYPE|good morning>>').pass, true)
  assert.equal(checkScenario(s, '<<TYPE|good morning>> <<TAP|Send>>').pass, false)
})

test('never volunteer an alarm: E7 fails if an alarm appears', () => {
  const s = { id: 'E7', must: [{ type: 'OPEN' }], mustNot: [{ type: 'ALARM' }] }
  assert.equal(checkScenario(s, '<<OPEN|Spotify>> <<PICK|Beat It>>').pass, true)
  assert.equal(checkScenario(s, '<<OPEN|Spotify>> <<ALARM|SET|07:00|x>>').pass, false)
})

test('E6: explicit alarm time is honoured in the marker', () => {
  const s = { id: 'E6', must: [{ type: 'ALARM', arg: /SET\|0?7:30/i }] }
  assert.equal(checkScenario(s, '<<ALARM|SET|07:30|Wake up>>').pass, true)
  assert.equal(checkScenario(s, '<<ALARM|SET|08:00|Wake up>>').pass, false)
})

test('mustAny: at least one option present', () => {
  const s = { id: 'B4', mustAny: [{ type: 'TAP', arg: /check\s?out|proceed/i }] }
  assert.equal(checkScenario(s, '<<OPEN|Blinkit>> <<TAP|Checkout>>').pass, true)
  assert.equal(checkScenario(s, '<<OPEN|Blinkit>>').pass, false)
})

test('askOk: a clarifying question passes, but asking AND acting still checks the markers', () => {
  const s = {
    id: 'B5',
    askOk: true,
    mustAny: [{ type: 'OPEN' }, { type: 'TAP' }, { type: 'TYPE' }],
    mustNot: [{ type: 'TAP', arg: /check\s?out/i }],
  }
  // Pure clarifying question -> acceptable.
  const asked = checkScenario(s, 'Which variety of apples would you like?')
  assert.equal(asked.pass, true)
  assert.equal(asked.asked, true)
  // Acts correctly -> also passes.
  assert.equal(checkScenario(s, '<<OPEN|Blinkit>> <<TAP|Add>>').pass, true)
  // Acts but checks out -> still fails (asking did not happen; markers present).
  assert.equal(checkScenario(s, '<<OPEN|Blinkit>> <<TAP|Checkout>>').pass, false)
})

test('noMarkers: an ambiguous ask should emit nothing', () => {
  const s = { id: 'E1', noMarkers: true }
  assert.equal(checkScenario(s, 'Which app should I use for that?').pass, true)
  assert.equal(checkScenario(s, '<<OPEN|Spotify>>').pass, false)
})
