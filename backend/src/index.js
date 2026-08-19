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
import { fakeProvider } from './providers/fake.js'

/**
 * Builds a handler from its dependencies.
 *
 * A factory rather than a bare `fetch` so the tests can inject a store, a
 * provider and a clock. The alternative — reaching for globals inside the
 * handler — is what makes a worker testable only by deploying it.
 */
export function createWorker({ store, provider, now = () => Date.now() }) {
  return {
    async fetch(request) {
      const url = new URL(request.url)

      if (request.method === 'GET' && url.pathname === '/health') {
        return Response.json({ ok: true })
      }

      if (request.method !== 'POST' || url.pathname !== '/chat') {
        return Response.json({ error: 'not_found' }, { status: 404 })
      }

      // PHASE 3 replaces this with a verified Firebase ID token. Until then any
      // caller can claim any uid, which is exactly why this must not be deployed
      // to a public URL before Phase 3.
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

      const model = modelsFor(plan)[0]
      let result
      try {
        result = await provider.complete({ model, messages, system: body.system })
      } catch (e) {
        // Nothing is charged. The user got no answer; billing them for the
        // provider's bad day would be the wrong way round.
        return Response.json(
          { error: 'provider_failed', detail: String(e?.message ?? e) },
          { status: 502 },
        )
      }

      // The provider's own numbers, never an estimate of ours. Input and output
      // are kept apart because they are priced differently.
      const inTok = result.usage?.prompt_tokens ?? 0
      const outTok = result.usage?.completion_tokens ?? 0
      await store.addUsage(uid, day, inTok, outTok)

      return Response.json({
        reply: result.text,
        model,
        plan,
        usage: { input: inTok, output: outTok },
        remaining: remaining(used + inTok + outTok, cap),
      })
    },
  }
}

export default {
  async fetch(request, env) {
    // PHASE 1 swaps this for the real Groq provider behind the same interface.
    const provider = fakeProvider()
    const store = d1Store(env.DB)
    return createWorker({ store, provider }).fetch(request)
  },
}
