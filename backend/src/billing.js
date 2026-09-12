/**
 * Subscriptions (Part E, Phase 6) — the paid tier is a MONTHLY subscription.
 *
 * The phone buys through Play Billing and sends the purchase token here; the Worker
 * verifies it against the Google Play Developer API and records the result. This
 * file holds the PURE parts — parsing Play's response, and deciding from a stored
 * subscription whether a user is Pro *right now* — so the whole entitlement decision
 * is unit-tested offline. The impure Google call is a thin injected shell (like
 * `auth.js`'s verifier), dormant until a service account is configured.
 *
 * Expiry is stored, so a lapsed subscription downgrades on its own even if the
 * real-time renewal notification (RTDN) never arrives — RTDN only makes it prompt.
 */

/**
 * Play subscription states that grant access. A cancelled-but-not-yet-expired
 * subscription is still ACTIVE until its expiry, so cancellation is handled by the
 * expiry check, not by dropping access immediately (the user paid for the period).
 */
export const ACTIVE_STATES = new Set([
  'SUBSCRIPTION_STATE_ACTIVE',
  'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
])

/**
 * Parse a Play Developer API `purchases.subscriptionsv2.get` response into the
 * small shape we store: the state, the latest line-item expiry (epoch ms), and the
 * product id. Tolerant of missing fields — a malformed response yields an inactive
 * subscription rather than throwing.
 */
export function parsePlaySubscription(json) {
  const o = typeof json === 'string' ? safeParse(json) : (json ?? {})
  const state = typeof o.subscriptionState === 'string'
    ? o.subscriptionState
    : 'SUBSCRIPTION_STATE_UNSPECIFIED'
  const items = Array.isArray(o.lineItems) ? o.lineItems : []
  let expiryMs = 0
  let productId = null
  for (const it of items) {
    const t = it?.expiryTime ? Date.parse(it.expiryTime) : NaN
    if (Number.isFinite(t) && t > expiryMs) expiryMs = t
    if (!productId && typeof it?.productId === 'string') productId = it.productId
  }
  return { state, expiryMs, productId }
}

/** True when a stored subscription grants Pro at [now]. */
export function isActive(sub, now) {
  if (!sub || !ACTIVE_STATES.has(sub.state)) return false
  return sub.expiryMs === 0 || now < sub.expiryMs
}

/**
 * The plan a request should be metered at: `pro` when an active subscription grants
 * it, otherwise the user's stored plan (which stays `free` unless manually set —
 * a manual `pro` override, e.g. for the developer, is still honoured).
 */
export function effectivePlan(userPlan, sub, now) {
  if (isActive(sub, now)) return 'pro'
  return userPlan === 'pro' ? 'pro' : 'free'
}

function safeParse(s) {
  try {
    return JSON.parse(s)
  } catch {
    return {}
  }
}
