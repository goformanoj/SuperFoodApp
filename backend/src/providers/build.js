/**
 * Turns the keys the Worker was given into a router.
 *
 * Adding a platform is: put its key in the Worker's secrets. Nothing else. A platform
 * with no key simply isn't there, so the app keeps working with one key (as before) or
 * six. Missing keys are not errors; only "no keys at all" is (the Worker refuses to run).
 *
 * The router is built ONCE per Worker instance and reused. That matters: it remembers
 * which platforms are cooling down, and a router rebuilt on every request would forget
 * within milliseconds and hammer a rate-limited platform on every call.
 */
import { openAiCompatProvider } from './groq.js'
import { routerProvider } from './router.js'
import { PLATFORMS, classify, rankModels, suitability } from './catalog.js'
import { SCORECARD } from '../scorecard.js'

const OPENROUTER_MODELS_URL = 'https://openrouter.ai/api/v1/models'
const DISCOVERY_TTL_MS = 6 * 60 * 60_000
/** OpenRouter's own automatic picker among free models: the last resort if discovery finds nothing. */
export const OPENROUTER_AUTO = 'openrouter/free'

const NOT_CHAT = /safety|guard|moderat|embed|rerank|lyria|tts|whisper|image-/i
const isYes =(v) => /^(1|yes|true|on)$/i.test(String(v ?? '').trim())
const listOf = (v) => String(v ?? '').split(',').map((s) => s.trim()).filter(Boolean)

/**
 * Live list of OpenRouter's FREE models that can call tools, best first.
 *
 * Free models come and go, so this reads OpenRouter's public catalog (no key needed)
 * instead of freezing a list that would rot. Cached for hours; if the catalog cannot be
 * fetched it falls back to the last good list, then to OpenRouter's own auto-picker.
 */
export function openRouterDiscovery({ fetchImpl = (...a) => fetch(...a), now = () => Date.now(), scorecard = SCORECARD.models, top = 4 } = {}) {
  let cache = null
  let at = 0
  let failedAt = -Infinity

  async function catalog() {
    if (cache && now() - at < DISCOVERY_TTL_MS) return cache
    if (now() - failedAt < 60_000) return cache ?? []
    try {
      const res = await fetchImpl(OPENROUTER_MODELS_URL)
      if (!res.ok) throw new Error(`http_${res.status}`)
      const data = (await res.json()).data
      if (!Array.isArray(data)) throw new Error('bad_catalog')
      cache = data
      at = now()
    } catch {
      failedAt = now() // don't hammer a catalog that is down; keep serving the last good list
    }
    return cache ?? []
  }

  return async (needs = {}) => {
    const free = (await catalog()).filter((m) =>
      /:free$/.test(m.id ?? '') &&
      Number(m.pricing?.prompt) === 0 && Number(m.pricing?.completion) === 0 &&
      !/^stealth\//.test(m.id) &&
      // Not everything free is a chat model: safety classifiers, embedders, music and image makers are not.
      !NOT_CHAT.test(m.id) &&
      (m.architecture?.output_modalities ?? ['text']).includes('text'))
    const profiles = free
      .map((m) => classify(m.id, m))
      .filter((p) => (needs.functions ? p.tools : true))
      .filter((p) => (needs.vision ? p.vision : true))
      .filter((p) => (needs.functions ? suitability(p, scorecard[p.id]).includes('agent') : true))
    const ids = rankModels(profiles, scorecard).slice(0, top).map((p) => p.id)
    if (!needs.vision && !ids.includes(OPENROUTER_AUTO)) ids.push(OPENROUTER_AUTO)
    return ids
  }
}

/** The platforms this environment has keys for, in the order they will be tried. */
export function buildBackends(env, { scorecard = SCORECARD.models, fetchImpl, endpoints = {} } = {}) {
  const warnings = []
  const disabled = new Set(listOf(env.DISABLED_PROVIDERS))
  const wanted = listOf(env.PROVIDER_ORDER)
  const rank = (name) => {
    const i = wanted.indexOf(name)
    return i >= 0 ? i - 1000 : PLATFORMS[name].priority
  }
  const backends = []

  for (const name of Object.keys(PLATFORMS).sort((a, b) => rank(a) - rank(b))) {
    const p = PLATFORMS[name]
    const key = (env[p.keyEnv] ?? '').trim()
    if (!key || disabled.has(name)) continue
    // Enforced here, not just documented: a platform that may train on free-tier prompts
    // must never see JARVIS's emails and documents unless the owner said so.
    if (p.trainsOnFreeTier && !isYes(env.ALLOW_TRAINING_TIERS)) {
      warnings.push(`${name}: key present but skipped — its free tier may train on prompts. Set ALLOW_TRAINING_TIERS=yes to allow.`)
      continue
    }
    // Not a block — a card-requiring platform is still fine to use — but silence here is
    // how "Cerebras is free" turned into wrong advice once already; say it every time.
    if (p.requiresCard) warnings.push(`${name}: this is a PAID platform (card required) — ${p.freeNote}.`)
    let models
    if (p.inheritModels) models = 'inherit'
    else if (p.discover === 'openrouter') models = openRouterDiscovery({ fetchImpl, scorecard })
    else {
      models = listOf(env[p.modelsEnv])
      if (models.length === 0) models = p.defaultModels ?? []
      if (models.length === 0) {
        warnings.push(`${name}: key present but no models — set ${p.modelsEnv} (run scripts/list-models.mjs to see what the key can use).`)
        continue
      }
    }
    backends.push({
      name,
      label: p.label,
      provider: openAiCompatProvider(key, { endpoint: endpoints[name] ?? p.endpoint, headers: p.headers }),
      models,
      visionModels: p.visionModels,
      caps: p.caps,
      quirks: p.quirks,
    })
  }
  return { backends, warnings }
}

let memo = null

/**
 * The router for this environment, built once and reused while the keys are unchanged.
 * Returns null when there is no usable key at all (the caller refuses to serve).
 */
export function providerFor(env, options = {}) {
  const sig = JSON.stringify([
    ...Object.values(PLATFORMS).flatMap((p) => [env[p.keyEnv], p.modelsEnv && env[p.modelsEnv]]),
    env.ALLOW_TRAINING_TIERS, env.DISABLED_PROVIDERS, env.PROVIDER_ORDER,
  ])
  if (!options.fresh && memo?.sig === sig) return memo.value
  const { backends, warnings } = buildBackends(env, options)
  const value = backends.length === 0 ? null : {
    provider: routerProvider(backends, {
      onEvent: options.onEvent ?? ((e) => console.log(JSON.stringify({ router: e }))),
    }),
    names: backends.map((b) => b.name),
    warnings,
  }
  if (value && warnings.length && !options.quiet) console.log(JSON.stringify({ router: { type: 'warnings', warnings } }))
  if (!options.fresh) memo = { sig, value }
  return value
}
