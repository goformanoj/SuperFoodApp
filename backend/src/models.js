/**
 * Which model serves which plan.
 *
 * **This file is the reason the backend exists at all.** On 2026-08-18 Groq
 * retired `llama-3.3-70b-versatile` and `llama-3.1-8b-instant` hours apart. Each
 * removal cost a Kotlin change, a CI build, and a reinstall by the user — and in
 * between, the fallback chain silently ran down to one live model per tier,
 * which is why a single empty reply killed a turn outright. Here, the same
 * change is a deploy.
 */

/**
 * Both plans list the SAME two models in opposite order, on purpose: each can
 * reach the other's, so neither is ever left with a single point of failure.
 * That is the property that was missing when both llamas died.
 */
const BY_PLAN = {
  free: ['openai/gpt-oss-20b', 'openai/gpt-oss-120b'],
  pro: ['openai/gpt-oss-120b', 'openai/gpt-oss-20b'],
}

/**
 * Models the provider has retired, kept so a future edit cannot quietly put one
 * back. Asserted in `models.test.mjs`.
 *
 * NEVER add a model id from memory. Guessing is precisely what left two dead
 * models in the Android client; check the provider's live list, or use ids this
 * account's own traffic proves are alive.
 */
export const RETIRED_UPSTREAM = [
  'llama-3.3-70b-versatile',
  'llama-3.1-8b-instant',
  'gemma2-9b-it',
]

export function modelsFor(plan) {
  return BY_PLAN[plan] ?? BY_PLAN.free
}

export const PLANS = Object.keys(BY_PLAN)
