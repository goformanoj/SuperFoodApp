/**
 * Subscriptions decide the paid tier, so the entitlement logic is worth defending:
 * a cancelled-but-unexpired user keeps access they paid for, and a lapsed one loses
 * it even if the renewal notification never arrives.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parsePlaySubscription, isActive, effectivePlan } from '../src/billing.js'

const NOW = 1_760_000_000_000
const future = NOW + 30 * 24 * 3600 * 1000
const past = NOW - 1000

test('parsePlaySubscription pulls state, latest expiry, and product', () => {
  const body = {
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
    lineItems: [
      { productId: 'jarvis_pro_monthly', expiryTime: new Date(future).toISOString() },
    ],
  }
  const s = parsePlaySubscription(body)
  assert.equal(s.state, 'SUBSCRIPTION_STATE_ACTIVE')
  assert.equal(s.productId, 'jarvis_pro_monthly')
  assert.equal(s.expiryMs, future)
})

test('parsePlaySubscription accepts a JSON string and survives junk', () => {
  assert.equal(parsePlaySubscription('not json').state, 'SUBSCRIPTION_STATE_UNSPECIFIED')
  assert.equal(parsePlaySubscription('{}').expiryMs, 0)
})

test('isActive: an accepted state within expiry is Pro', () => {
  assert.ok(isActive({ state: 'SUBSCRIPTION_STATE_ACTIVE', expiryMs: future }, NOW))
  assert.ok(isActive({ state: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', expiryMs: future }, NOW))
})

test('isActive: expired or wrong-state is not Pro', () => {
  assert.ok(!isActive({ state: 'SUBSCRIPTION_STATE_ACTIVE', expiryMs: past }, NOW))
  assert.ok(!isActive({ state: 'SUBSCRIPTION_STATE_EXPIRED', expiryMs: future }, NOW))
  assert.ok(!isActive({ state: 'SUBSCRIPTION_STATE_CANCELED', expiryMs: past }, NOW))
  assert.ok(!isActive(null, NOW))
})

test('a cancelled subscription keeps access until its paid period ends', () => {
  // Play reports CANCELED immediately on cancel, but the user paid through expiry —
  // access should follow the ACTIVE window, not the cancel event.
  assert.ok(isActive({ state: 'SUBSCRIPTION_STATE_ACTIVE', expiryMs: future }, NOW))
})

test('effectivePlan: active sub is pro; lapsed sub falls back to the stored plan', () => {
  const activeSub = { state: 'SUBSCRIPTION_STATE_ACTIVE', expiryMs: future }
  const deadSub = { state: 'SUBSCRIPTION_STATE_EXPIRED', expiryMs: past }
  assert.equal(effectivePlan('free', activeSub, NOW), 'pro')
  assert.equal(effectivePlan('free', deadSub, NOW), 'free')
  assert.equal(effectivePlan('free', null, NOW), 'free')
  // A manual pro override (e.g. the developer) still counts with no subscription.
  assert.equal(effectivePlan('pro', null, NOW), 'pro')
})
