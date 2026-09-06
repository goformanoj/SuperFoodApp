/**
 * The real Groq call.
 *
 * ## Why the fallback chain lives HERE
 *
 * The Worker asks for a *list* of candidate models, not one, and this file
 * decides which of them actually answers. That is deliberate: which models are
 * dead, which are cooling down after a 429, and what an empty reply means are
 * all provider-specific facts, and the layer above should not have to know any
 * of them.
 *
 * Every rule below was paid for by a device trace on 2026-08-18:
 *
 *  - **A retired model is dropped for the life of the isolate.** Groq answered
 *    404 for `llama-3.3-70b-versatile` and "decommissioned" for
 *    `llama-3.1-8b-instant`. Retirement never clears, so re-asking is a wasted
 *    round trip on every single request.
 *  - **A 429 cools that model down, not the account.** Quotas are per model.
 *  - **An empty reply retries the SAME model once.** It is not a broken model,
 *    it is one that spent its budget without emitting text — transient. The
 *    Android client fell straight through to the next model instead, and when
 *    both llamas died there was no next model, so the turn died with it.
 */

const ENDPOINT = 'https://api.groq.com/openai/v1/chat/completions'
const EMPTY_REPLY = 'empty_reply'

export class ProviderError extends Error {
  constructor(message, status = 502) {
    super(message)
    this.status = status
  }
}

export function groqProvider(apiKey, options = {}) {
  // Per-isolate memory. Cloudflare keeps an isolate alive across many requests,
  // so this is worth having; it is not a cache that needs invalidating, because
  // a retirement is permanent and a cooldown carries its own expiry.
  const retired = new Set()
  const blockedUntil = new Map()
  const now = options.now ?? (() => Date.now())
  const maxTokens = options.maxTokens ?? 900
  // Same-model attempts before giving up on a transient failure. Injectable sleep
  // so tests do not actually wait.
  const maxAttempts = options.maxAttempts ?? 3
  const sleep = options.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)))

  async function once(model, messages, system) {
    const body = {
      model,
      temperature: 0.7,
      max_tokens: maxTokens,
      messages: system ? [{ role: 'system', content: system }, ...messages] : messages,
    }
    const res = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
      body: JSON.stringify(body),
    })
    const text = await res.text()

    if (res.ok) {
      let parsed
      try {
        parsed = JSON.parse(text)
      } catch {
        return { error: 'bad_json_from_provider', retryable: false }
      }
      const content = parsed?.choices?.[0]?.message?.content?.trim() ?? ''
      // Empty is transient (a model that spent its budget without emitting text):
      // worth another go on the SAME model.
      if (!content) return { error: EMPTY_REPLY, retryable: true, transient: true }
      return { text: content, usage: parsed.usage ?? {}, model }
    }

    if (res.status === 429) {
      // A cooldown, not a same-model retry: the quota needs time to clear, so move
      // to the next model rather than hammering this one.
      const wait = retryAfterSeconds(res.headers.get('Retry-After'), text)
      blockedUntil.set(model, now() + wait * 1000)
      return { error: `rate_limited:${wait}s`, retryable: true, transient: false }
    }

    // 404, or a 400 whose body says the model is gone. Groq has used both.
    if (res.status === 404 || (res.status === 400 && looksRetired(text))) {
      retired.add(model)
      return { error: 'model_retired', retryable: true, transient: false }
    }

    // Everything else. Groq's free tier intermittently answers 400/408/409/5xx
    // under rapid fire — the eval harness saw exactly this and worked around it by
    // retrying, but the live single-turn path had no retry, so one transient blip
    // killed a whole errand step (`provider_failed` in a device trace). Treat those
    // as transient and worth another go on the same model; a hard 401/403 (bad key)
    // is fatal and must not be retried.
    const transient = res.status >= 500 || res.status === 400 || res.status === 408 || res.status === 409
    return { error: `http_${res.status}`, retryable: transient, transient, detail: text.slice(0, 200) }
  }

  return {
    async complete({ models, messages, system }) {
      let lastError = 'no_model_tried'
      let anyTried = false

      for (const model of models) {
        if (retired.has(model)) continue
        if ((blockedUntil.get(model) ?? 0) > now()) continue
        anyTried = true

        // Try the same model a few times for a TRANSIENT failure (empty reply, or a
        // 400/5xx blip under rapid fire), with a short backoff between http retries.
        // A cooldown (429) or a retirement is not transient — those break out to the
        // next model. A fatal error (bad key) stops everything.
        for (let attempt = 1; attempt <= maxAttempts; attempt++) {
          const outcome = await once(model, messages, system)
          if (outcome.text) return outcome
          lastError = outcome.error
          if (!outcome.retryable) throw new ProviderError(outcome.error, 502)
          if (!outcome.transient) break
          if (attempt < maxAttempts && outcome.error !== EMPTY_REPLY) {
            await sleep(250 * attempt)
          }
        }
      }

      // Checked AFTER the loop as well as before it. A test caught the
      // inconsistency: models dying on THIS request left `anyTried` true and
      // reported a generic 502, while the identical end state on the NEXT
      // request reported 503 — the same situation answered differently
      // depending only on when it happened.
      if (models.every((m) => retired.has(m))) {
        throw new ProviderError('all_models_retired', 503)
      }
      if (!anyTried) {
        throw new ProviderError('all_models_rate_limited', 503)
      }
      throw new ProviderError(lastError, 502)
    },
  }
}

/** Groq states the wait in the header, or in the message body. Clamped. */
export function retryAfterSeconds(header, body) {
  const fromHeader = Number(header)
  if (Number.isFinite(fromHeader) && fromHeader > 0) return Math.min(fromHeader, 900)
  const match = /try again in\s+([\d.]+)\s*(m|s)/i.exec(body ?? '')
  if (match) {
    const value = Number(match[1])
    const seconds = match[2].toLowerCase() === 'm' ? value * 60 : value
    if (Number.isFinite(seconds) && seconds > 0) return Math.min(Math.ceil(seconds), 900)
  }
  return 20
}

/** Groq has reported a dead model as both 404 and 400 — match on the words too. */
export function looksRetired(body) {
  return /decommission|no longer supported|does not exist|deprecated/i.test(body ?? '')
}
