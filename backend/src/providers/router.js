/**
 * The router: many AI platforms behind the one `complete()` the Worker already uses.
 *
 * ## Why this exists
 *
 * Every free platform has a ceiling, and the ceilings are small. Groq's free plan
 * allows 8,000 tokens a MINUTE per model; one JARVIS call carries ~4,000 tokens of
 * instructions, so two calls a minute is the limit, however many requests are
 * "allowed". A single platform therefore cannot carry the app, but several can:
 * when one is out of allowance or having a bad day, the request goes to the next.
 *
 * ## What it does for each request
 *
 *  1. Drops platforms that cannot do the job (only Groq has built-in web search; not
 *     every platform reads images) and platforms that recently failed (a short
 *     cooldown, so a dead platform costs one slow request, not one per request).
 *  2. Tries the rest in priority order, each with ITS OWN model list.
 *  3. Checks every reply (normalize.js): a reply that invents a tool, prints a tool
 *     call as text, or comes back empty is REJECTED and the next platform is tried.
 *     The user gets a good answer from a lower-priority model rather than a bad one
 *     from the top one.
 *  4. Never gives up merely out of caution: if every platform is cooling down, it
 *     tries them anyway.
 *
 * This is the outer router. `groq.js` is the inner one (it already tries several
 * models on one platform); this one chooses between platforms.
 */
import { ProviderError } from './groq.js'
import { normalizeResult, nineCharId, remapToolIds } from './normalize.js'

const COOLDOWN_MS = { auth: 10 * 60_000, limited: 30_000, other: 15_000, badOutput: 5_000 }

/** What a request needs, so platforms that cannot serve it are skipped. */
export function requirementsOf({ messages = [], tools, extra } = {}) {
  return {
    builtin: !!tools?.some((t) => t?.type !== 'function'),
    functions: !!tools?.some((t) => t?.type === 'function'),
    vision: messages.some((m) => Array.isArray(m?.content) && m.content.some((p) => p?.type === 'image_url')),
    reasoning: !!extra?.reasoningEffort,
  }
}

function canServe(backend, needs) {
  const caps = backend.caps ?? {}
  if (needs.builtin && !caps.builtinSearch) return false
  if (needs.vision && !caps.vision) return false
  if (needs.functions && caps.tools === false) return false
  return true
}

function cooldownFor(error) {
  const msg = String(error?.message ?? error)
  if (/http_40[123]|unauthori|invalid.*key|forbidden/i.test(msg)) return COOLDOWN_MS.auth
  if (/rate_limit|http_429|http_402|all_models/i.test(msg) || error?.status === 503) return COOLDOWN_MS.limited
  return COOLDOWN_MS.other
}

/**
 * @param backends  [{ name, provider, models, caps, quirks, visionModels }] in priority order.
 *   `models` is `'inherit'` (use the list the caller passed), an array, or an async
 *   function `(needs) => string[]` for live discovery.
 * @param options   { now, onEvent } — injectable clock and a hook that sees failovers.
 */
export function routerProvider(backends, options = {}) {
  const now = options.now ?? (() => Date.now())
  const onEvent = options.onEvent ?? (() => {})
  const blockedUntil = new Map()
  const stats = new Map(backends.map((b) => [b.name, { ok: 0, failed: 0, rejected: 0, last: null }]))

  const block = (name, ms) => blockedUntil.set(name, now() + ms)

  async function modelsFor(backend, callerModels, needs) {
    if (backend.models === 'inherit') return callerModels ?? []
    if (typeof backend.models === 'function') return (await backend.models(needs)) ?? []
    if (needs.vision) return backend.visionModels ?? []
    return backend.models ?? []
  }

  function prepare(backend, messages) {
    return backend.quirks?.toolIdLen9 ? remapToolIds(messages, nineCharId) : messages
  }

  return {
    backends: backends.map((b) => b.name),
    stats: () => Object.fromEntries(stats),

    async complete(request) {
      const { models, messages, system, tools, extra } = request
      const needs = requirementsOf(request)
      const eligible = backends.filter((b) => canServe(b, needs))
      if (eligible.length === 0) throw new ProviderError('no_platform_can_serve_this', 503)

      const ready = eligible.filter((b) => (blockedUntil.get(b.name) ?? 0) <= now())
      const order = ready.length ? ready : eligible
      const inputChars = (system?.length ?? 0) + JSON.stringify(messages ?? []).length + (tools ? JSON.stringify(tools).length : 0)
      const failures = []

      for (const backend of order) {
        const st = stats.get(backend.name)
        try {
          const candidates = await modelsFor(backend, models, needs)
          if (candidates.length === 0) {
            failures.push(`${backend.name}:no_models`)
            continue
          }
          const useExtra = backend.caps?.reasoningEffort || !extra?.reasoningEffort
            ? extra
            : { ...extra, reasoningEffort: undefined }
          const raw = await backend.provider.complete({
            models: candidates, messages: prepare(backend, messages), system, tools, extra: useExtra,
          })
          const checked = normalizeResult(raw, { tools, inputChars })
          if (!checked.ok) {
            st.rejected++
            st.last = checked.reason
            block(backend.name, COOLDOWN_MS.badOutput)
            failures.push(`${backend.name}:rejected(${checked.reason})`)
            onEvent({ type: 'rejected', backend: backend.name, model: raw?.model, reason: checked.reason })
            continue
          }
          st.ok++
          st.last = null
          if (failures.length) onEvent({ type: 'failover', served: backend.name, skipped: failures })
          return { ...checked.result, backend: backend.name }
        } catch (e) {
          st.failed++
          st.last = String(e?.message ?? e)
          block(backend.name, cooldownFor(e))
          failures.push(`${backend.name}:${st.last}`)
          onEvent({ type: 'failed', backend: backend.name, error: st.last })
        }
      }

      const everyLimited = failures.every((f) => /rate_limit|all_models|no_models/.test(f))
      throw new ProviderError(`all_platforms_failed: ${failures.join(' | ')}`.slice(0, 400), everyLimited ? 503 : 502)
    },
  }
}
