/**
 * Which AI platforms JARVIS can use, and how good each model is likely to be at
 * JARVIS's job — sorted into categories by size (parameters) and, once measured,
 * by ability.
 *
 * ## Two different kinds of knowledge, kept apart on purpose
 *
 *  - **Guessed from the name** (`classify`): a model called `...-120b` has ~120
 *    billion parameters; `...-27b` has ~27 billion. Bigger is *usually* better at
 *    following a long tool-calling prompt, so it is a sensible first ordering. It
 *    is a guess, and is labelled as one.
 *  - **Measured** (`SCORECARD`, written by `scripts/probe.mjs`): the model was
 *    actually given JARVIS's real requests and either called the right tool or it
 *    did not. Measured results always beat the guess.
 *
 * ## Model ids
 *
 * NEVER add a model id from memory (see models.js: two dead models once cost a
 * Kotlin change, a CI build and a reinstall). Free catalogs churn without notice —
 * Cerebras once dropped from about a dozen models to two overnight. So a platform
 * either lists ids that a provider's own docs or live list confirmed (with the date),
 * or has none and takes them from an env var / live discovery.
 */

/** Parameter counts read from a model id: total, and active (for mixture-of-experts `-a12b`). */
export function parseParams(id) {
  let totalB = null
  let activeB = null
  // A number followed by `b`, not glued to other letters/digits/dots: 120b, 2.6b, a12b (active).
  for (const m of String(id).toLowerCase().matchAll(/(?<![a-z0-9.])(a)?(\d+(?:\.\d+)?)b(?![a-z0-9])/g)) {
    const n = Number(m[2])
    if (m[1]) activeB = n
    else totalB = totalB === null ? n : Math.max(totalB, n)
  }
  return { totalB, activeB }
}

const WORD_TIERS = [
  [/\b(ultra|large|pro|super|opus)\b/, 'large'],
  [/\b(medium|plus|sonnet)\b/, 'medium'],
  [/\b(small|mini|nano|lite|flash|lightning|tiny|instant|haiku)\b/, 'small'],
]

/** large ≥ 100B, medium ≥ 20B, small below; `unknown` when neither the size nor a size-word is in the name. */
export function tierOf(id, totalB = parseParams(id).totalB) {
  if (totalB !== null) return totalB >= 100 ? 'large' : totalB >= 20 ? 'medium' : 'small'
  const lower = String(id).toLowerCase()
  for (const [re, tier] of WORD_TIERS) if (re.test(lower)) return tier
  return 'unknown'
}

const TIER_WEIGHT = { large: 3, medium: 2, unknown: 1, small: 0 }

/**
 * What a model is, from its id and (when the platform publishes it) its metadata.
 * `meta` follows OpenRouter's shape: `supported_parameters`, `architecture.input_modalities`.
 */
export function classify(id, meta = {}) {
  const { totalB, activeB } = parseParams(id)
  const params = meta.supported_parameters ?? []
  const lower = String(id).toLowerCase()
  return {
    id,
    totalB,
    activeB,
    moe: activeB !== null,
    tier: tierOf(id, totalB),
    tools: meta.tools ?? params.includes('tools'),
    vision: meta.vision ?? (meta.architecture?.input_modalities ?? []).includes('image'),
    reasoning: meta.reasoning ?? (params.includes('reasoning') || /reasoning|thinking|gpt-oss|r1|qwq/.test(lower)),
  }
}

/**
 * The jobs a model is suited to. Measured beats guessed: with a scorecard entry, a
 * model is an `agent` (can drive JARVIS's tools) only if it actually got at least
 * 75% of the probe cases right. Without one, the guess is that a medium-or-larger
 * model with tool support can, and a small one is chat-only.
 */
export function suitability(profile, measured = null) {
  const jobs = ['chat']
  if (profile.vision) jobs.push('vision')
  const agent = measured
    ? measured.total > 0 && measured.passed / measured.total >= 0.75
    : profile.tools && (profile.tier === 'large' || profile.tier === 'medium')
  if (agent) jobs.push('agent')
  return jobs
}

/** Best first: measured pass-rate, then size tier, then raw parameter count. */
export function rankModels(profiles, scorecard = {}) {
  const rate = (p) => {
    const m = scorecard[p.id]
    return m && m.total > 0 ? m.passed / m.total : null
  }
  return [...profiles].sort((a, b) => {
    const ra = rate(a)
    const rb = rate(b)
    if (ra !== null && rb !== null && ra !== rb) return rb - ra
    if (ra !== null && rb === null) return ra >= 0.5 ? -1 : 1
    if (ra === null && rb !== null) return rb >= 0.5 ? 1 : -1
    const tier = TIER_WEIGHT[b.tier] - TIER_WEIGHT[a.tier]
    return tier !== 0 ? tier : (b.totalB ?? 0) - (a.totalB ?? 0)
  })
}

/**
 * The platforms. Every one speaks the same OpenAI-shaped chat format, which is what
 * lets one adapter serve them all.
 *
 *  - `priority`: lower is tried first. Groq leads because it is the fastest and the
 *    one the live app has proven; the rest are the safety net behind it.
 *  - `trainsOnFreeTier`: the platform may use free-tier prompts to train its models.
 *    JARVIS sends emails and documents, so these stay OFF unless the owner sets
 *    ALLOW_TRAINING_TIERS=yes. Enforced in code (see build.js), not just documented.
 *  - `caps`: what the platform can do. `builtinSearch` (Groq's browser_search) exists
 *    only on Groq, so /search can only ever be served there.
 */
export const PLATFORMS = {
  groq: {
    label: 'Groq',
    priority: 10,
    endpoint: 'https://api.groq.com/openai/v1/chat/completions',
    keyEnv: 'GROQ_API_KEY',
    inheritModels: true, // the plan's own list from models.js
    caps: { tools: true, vision: true, builtinSearch: true, reasoningEffort: true },
    trainsOnFreeTier: false,
  },
  cerebras: {
    label: 'Cerebras',
    priority: 90, // tried LAST: not free (see requiresCard below), so exhaust the free options first
    endpoint: 'https://api.cerebras.ai/v1/chat/completions',
    keyEnv: 'CEREBRAS_API_KEY',
    modelsEnv: 'CEREBRAS_MODELS',
    // Cerebras' own rate-limit docs, read 2026-09-29. Its catalog shrinks without notice:
    // run scripts/list-models.mjs to see what the key can really reach.
    defaultModels: ['gpt-oss-120b', 'qwen-3.8-27b'],
    visionModels: ['qwen-3.8-27b'],
    caps: { tools: true, vision: true },
    trainsOnFreeTier: false,
    // NOT a free tier, checked against Cerebras' own pricing page 2026-09-30: a card is
    // required before the API works at all, and what it unlocks is a one-time $5 credit
    // that expires 30 days after it's granted, not a renewing free allowance. It still
    // works here as a paid fallback; build.js warns about this so nobody mistakes it for
    // "another free platform" the way an earlier answer in this project wrongly did.
    requiresCard: true,
    freeNote: 'a one-time $5 credit, expires 30 days after signup — not a renewing free tier',
  },
  cloudflare: {
    label: 'Cloudflare Workers AI',
    priority: 20, // free, no card — tried early, alongside the other genuinely free platforms
    // {account_id} is filled in by build.js from CLOUDFLARE_ACCOUNT_ID — this platform's
    // address is per-account, unlike every other one here.
    endpoint: 'https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1/chat/completions',
    keyEnv: 'CLOUDFLARE_API_TOKEN',
    accountIdEnv: 'CLOUDFLARE_ACCOUNT_ID',
    modelsEnv: 'CLOUDFLARE_MODELS',
    // Cloudflare's own model docs, read 2026-09-30. All on the free 10,000-Neurons/day
    // allowance (every account, no card) as of that date; its newer models (Kimi, GLM-5.x)
    // were moved behind the Workers PAID plan on 2026-07-28 and are deliberately left out.
    defaultModels: ['@cf/openai/gpt-oss-120b', '@cf/openai/gpt-oss-20b', '@cf/zai-org/glm-4.7-flash'],
    caps: { tools: true, vision: false },
    trainsOnFreeTier: false,
  },
  openrouter: {
    label: 'OpenRouter (free models)',
    priority: 30,
    endpoint: 'https://openrouter.ai/api/v1/chat/completions',
    keyEnv: 'OPENROUTER_API_KEY',
    discover: 'openrouter', // models come from OpenRouter's public list, never a frozen guess
    headers: { 'X-Title': 'JARVIS OS' },
    caps: { tools: true, vision: true },
    trainsOnFreeTier: false,
  },
  gemini: {
    label: 'Google Gemini (AI Studio)',
    priority: 50,
    endpoint: 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions',
    keyEnv: 'GEMINI_API_KEY',
    modelsEnv: 'GEMINI_MODELS',
    caps: { tools: true, vision: false },
    trainsOnFreeTier: true, // outside the EU/UK/EEA, per the platform's terms
  },
  mistral: {
    label: 'Mistral (La Plateforme)',
    priority: 60,
    endpoint: 'https://api.mistral.ai/v1/chat/completions',
    keyEnv: 'MISTRAL_API_KEY',
    modelsEnv: 'MISTRAL_MODELS',
    caps: { tools: true, vision: false },
    trainsOnFreeTier: true, // the free "Experiment" tier requires opting in to training
    quirks: { toolIdLen9: true }, // its API insists tool-call ids are exactly 9 letters/digits
  },
}
