/**
 * The JARVIS OS proxy.
 *
 * The phone POSTs a conversation here; this attaches the server-held key, meters
 * what it costs, and returns the reply. The key never reaches the device, the
 * model list is a deploy rather than an APK, and the daily allowance cannot be
 * lifted by patching the client.
 *
 * ## Phase 0
 *
 * Auth is STUBBED (`X-Uid`) and the provider is the fake one. Both are replaced
 * in later phases — see `BACKEND_PLAN.md`. Everything else, the quota decision
 * and the token accounting, is real and is what the tests exercise.
 */
import { capFor, dayKey, isOverCap, overCapBody, remaining } from './quota.js'
import { modelsFor } from './models.js'
import { d1Store } from './db.js'
import { MIGRATIONS } from './schema.js'
import { SYSTEM_PROMPT } from './systemPrompt.js'
import { groqProvider } from './providers/groq.js'

/**
 * Builds a handler from its dependencies.
 *
 * A factory rather than a bare `fetch` so the tests can inject a store, a
 * provider and a clock. The alternative — reaching for globals inside the
 * handler — is what makes a worker testable only by deploying it.
 */
export function createWorker({ store, provider, proxySecret = null, now = () => Date.now() }) {
  return {
    async fetch(request) {
      const url = new URL(request.url)

      if (request.method === 'GET' && url.pathname === '/health') {
        return Response.json({ ok: true })
      }

      // Creating the tables, without a terminal.
      //
      // This project is built and operated entirely from a phone, so
      // `wrangler d1 execute --file=schema.sql` is not a step the user can take.
      // Every statement is IF NOT EXISTS, so this is idempotent — which is what
      // makes it safe to expose at all — and it is behind the same shared secret
      // as everything else.
      if (request.method === 'POST' && url.pathname === '/admin/migrate') {
        if (!checkSecret(request, proxySecret)) {
          return Response.json({ error: 'forbidden' }, { status: 403 })
        }
        if (!store.migrate) {
          return Response.json({ error: 'not_supported' }, { status: 501 })
        }
        await store.migrate()
        return Response.json({ ok: true, tables: MIGRATIONS.length })
      }

      if (request.method !== 'POST' || url.pathname !== '/chat') {
        return Response.json({ error: 'not_found' }, { status: 404 })
      }

      // A shared secret, until Firebase lands in Phase 3.
      //
      // Crude, and not a substitute for real auth — everyone shares one string,
      // so it identifies the APP, not a user. But without it a deployed Worker is
      // an open relay to somebody's Groq account: the uid below is self-declared,
      // so anyone who finds the URL could spend the whole allowance. This makes it
      // a closed relay in the meantime.
      if (!checkSecret(request, proxySecret)) {
        return Response.json({ error: 'forbidden' }, { status: 403 })
      }

      // PHASE 3 replaces this with a verified Firebase ID token. Until then any
      // caller past the shared secret can claim any uid.
      const uid = request.headers.get('X-Uid')
      if (!uid) return Response.json({ error: 'no_uid' }, { status: 401 })

      let body
      try {
        body = await request.json()
      } catch {
        return Response.json({ error: 'bad_json' }, { status: 400 })
      }
      const messages = body?.messages
      if (!Array.isArray(messages) || messages.length === 0) {
        return Response.json({ error: 'no_messages' }, { status: 400 })
      }

      const nowMs = now()
      const day = dayKey(nowMs)
      const plan = await store.userPlan(uid)
      const cap = capFor(plan)
      const used = await store.usedToday(uid, day)

      // Refused BEFORE the provider is called. The entire point of a cap is not
      // spending the money, so a version that called first and counted after
      // would be decoration.
      if (isOverCap(used, cap)) {
        return Response.json(overCapBody(nowMs, plan), { status: 429 })
      }

      // The whole candidate list, not one: which of them is dead or cooling down
      // is the provider's business, and today's two retirements are exactly why
      // that decision must not be frozen into a single choice up here.
      const models = modelsFor(plan)
      let result
      try {
        result = await provider.complete({ models, messages, system: body.system ?? SYSTEM_PROMPT })
      } catch (e) {
        // Nothing is charged. The user got no answer; billing them for the
        // provider's bad day would be the wrong way round.
        return Response.json(
          { error: 'provider_failed', detail: String(e?.message ?? e) },
          { status: e?.status ?? 502 },
        )
      }

      // The provider's own numbers, never an estimate of ours. Input and output
      // are kept apart because they are priced differently.
      const inTok = result.usage?.prompt_tokens ?? 0
      const outTok = result.usage?.completion_tokens ?? 0
      await store.addUsage(uid, day, inTok, outTok)

      return Response.json({
        reply: result.text,
        model: result.model,
        plan,
        usage: { input: inTok, output: outTok },
        remaining: remaining(used + inTok + outTok, cap),
      })
    },
  }
}

/** True when the caller presented the shared secret, or none is configured. */
function checkSecret(request, proxySecret) {
  if (!proxySecret) return true
  return constantTimeEquals(request.headers.get('X-Proxy-Secret') ?? '', proxySecret)
}

/**
 * Compares without leaking length or position through timing. Overkill for a
 * shared secret behind TLS, and cheap enough that there is no reason not to.
 */
function constantTimeEquals(a, b) {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return diff === 0
}

export default {
  async fetch(request, env) {
    // FAIL CLOSED. A Worker deployed without its secret would otherwise be an
    // open relay to the Groq key sitting next to it — and the failure mode of
    // "allow when unconfigured" is that nobody notices until the bill does.
    if (!env.PROXY_SECRET) {
      return Response.json({ error: 'server_unconfigured' }, { status: 503 })
    }
    if (!env.GROQ_API_KEY) {
      return Response.json({ error: 'server_unconfigured' }, { status: 503 })
    }
    const provider = groqProvider(env.GROQ_API_KEY)
    const store = d1Store(env.DB)
    return createWorker({ store, provider, proxySecret: env.PROXY_SECRET }).fetch(request)
  },
}
