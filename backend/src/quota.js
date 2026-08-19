/**
 * How much a user may spend, and whether they have spent it.
 *
 * Pure — every function takes the clock as an argument rather than reading it.
 * That is not ceremony: the one rule here that is genuinely easy to get wrong is
 * the day boundary, and a function that reads `Date.now()` internally can only
 * be tested by waiting until midnight.
 */

/**
 * The free allowance, in tokens per UTC day.
 *
 * TOKENS, not requests. A screen-control turn carries the whole agent prompt
 * plus a description of everything on screen, and costs many times an ordinary
 * chat turn — so a request cap would let one heavy user spend several times
 * another user's on the same nominal allowance.
 */
export const FREE_DAILY_TOKENS = 60_000

/** Paid allowance. High enough not to be felt, low enough to bound abuse. */
export const PRO_DAILY_TOKENS = 2_000_000

/** The `usage_daily` key for a moment in time. Always UTC, never local. */
export function dayKey(nowMs) {
  return new Date(nowMs).toISOString().slice(0, 10)
}

export function capFor(plan) {
  return plan === 'pro' ? PRO_DAILY_TOKENS : FREE_DAILY_TOKENS
}

/**
 * True when the allowance is gone.
 *
 * Checked BEFORE the call, while the true cost is only known AFTER it — so a
 * user can overshoot by at most one turn. That is deliberate and cannot be
 * designed away without either pre-charging an estimate (which would be wrong in
 * both directions) or refusing to answer until the bill arrives. Set the cap
 * slightly under the real budget and accept it; `quota.test.mjs` pins the
 * behaviour so nobody later mistakes it for a bug.
 */
export function isOverCap(usedTokens, cap) {
  return usedTokens >= cap
}

export function remaining(usedTokens, cap) {
  return Math.max(0, cap - usedTokens)
}

/** Seconds until the allowance resets — the next UTC midnight. */
export function secondsUntilReset(nowMs) {
  const next = Date.UTC(
    new Date(nowMs).getUTCFullYear(),
    new Date(nowMs).getUTCMonth(),
    new Date(nowMs).getUTCDate() + 1,
  )
  return Math.max(1, Math.round((next - nowMs) / 1000))
}

/**
 * The over-cap response body.
 *
 * `spoken` exists because this is a voice assistant: the app should be able to
 * say the refusal out loud without composing anything itself, and a server that
 * returns only a status code forces the phone to invent the wording — which is
 * how two places end up disagreeing about what the limit is.
 */
export function overCapBody(nowMs, plan) {
  const hours = Math.round(secondsUntilReset(nowMs) / 3600)
  const when = hours <= 1 ? 'in about an hour' : `in about ${hours} hours`
  return {
    error: 'quota_exhausted',
    plan,
    remaining: 0,
    resetsInSeconds: secondsUntilReset(nowMs),
    spoken: `That's today's AI allowance used up. It resets ${when}.`,
  }
}
