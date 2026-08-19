import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  FREE_DAILY_TOKENS, capFor, dayKey, isOverCap, overCapBody, remaining, secondsUntilReset,
} from '../src/quota.js'

const AT = (iso) => Date.parse(iso)

test('the day key is UTC, not local', () => {
  // The one rule here that is genuinely easy to get wrong. A local-time key
  // would give users in some zones two allowances a day and others none.
  assert.equal(dayKey(AT('2026-08-18T00:00:00Z')), '2026-08-18')
  assert.equal(dayKey(AT('2026-08-18T23:59:59Z')), '2026-08-18')
  assert.equal(dayKey(AT('2026-08-19T00:00:00Z')), '2026-08-19')
})

test('a day boundary rolls the allowance over', () => {
  assert.notEqual(dayKey(AT('2026-08-18T23:59:59Z')), dayKey(AT('2026-08-19T00:00:01Z')))
})

test('the cap depends on the plan and an unknown plan is treated as free', () => {
  assert.equal(capFor('free'), FREE_DAILY_TOKENS)
  assert.ok(capFor('pro') > capFor('free'))
  // Never fail open: a plan string nobody recognises must not mean "unlimited".
  assert.equal(capFor('enterprise-platinum'), FREE_DAILY_TOKENS)
  assert.equal(capFor(undefined), FREE_DAILY_TOKENS)
})

test('the cap is reached, not merely exceeded', () => {
  assert.equal(isOverCap(FREE_DAILY_TOKENS - 1, FREE_DAILY_TOKENS), false)
  assert.equal(isOverCap(FREE_DAILY_TOKENS, FREE_DAILY_TOKENS), true)
})

test('a user can overshoot by exactly one turn, and that is intended', () => {
  // The cap is checked BEFORE the call and the true cost is known AFTER it.
  // Pinned so a future reader does not mistake it for a bug and "fix" it by
  // pre-charging an estimate, which would be wrong in both directions.
  const cap = FREE_DAILY_TOKENS
  const used = cap - 1
  assert.equal(isOverCap(used, cap), false, 'one token left means one more turn')

  const afterAnExpensiveTurn = used + 4000
  assert.ok(afterAnExpensiveTurn > cap, 'and that turn may cost far more than one token')
  assert.equal(isOverCap(afterAnExpensiveTurn, cap), true, 'but the next one is refused')
})

test('remaining never goes negative', () => {
  assert.equal(remaining(0, 100), 100)
  assert.equal(remaining(140, 100), 0)
})

test('the reset countdown points at the next UTC midnight', () => {
  assert.equal(secondsUntilReset(AT('2026-08-18T23:00:00Z')), 3600)
  assert.equal(secondsUntilReset(AT('2026-08-18T00:00:00Z')), 86400)
})

test('the over-cap body carries a sentence the app can speak', () => {
  // This is a voice assistant. A server that returns only a status code forces
  // the phone to invent the wording, which is how two places end up disagreeing
  // about what the limit was.
  const body = overCapBody(AT('2026-08-18T20:00:00Z'), 'free')
  assert.equal(body.error, 'quota_exhausted')
  assert.equal(body.remaining, 0)
  assert.ok(body.spoken.length > 0)
  assert.ok(!body.spoken.includes('undefined'))
  assert.ok(!/[*_`#]/.test(body.spoken), 'never markdown — this gets read aloud')
})
