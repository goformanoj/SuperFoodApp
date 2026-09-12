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
import { dropSecretMemories } from './guards.js'
import { packFor } from './packs.js'
import { effectivePlan, isActive } from './billing.js'
import { groqProvider } from './providers/groq.js'
import { AuthError, firebaseVerifier } from './auth.js'

/**
 * Builds a handler from its dependencies.
 *
 * A factory rather than a bare `fetch` so the tests can inject a store, a
 * provider and a clock. The alternative — reaching for globals inside the
 * handler — is what makes a worker testable only by deploying it.
 */
export function createWorker({
  store,
  provider,
  proxySecret = null,
  verifyToken = null,
  verifySubscription = null,
  now = () => Date.now(),
}) {
  return {
    async fetch(request) {
      const url = new URL(request.url)

      // Shared auth for the per-user routes (/chat, /billing/verify): the app secret
      // gates the build, and the uid comes from a verified Firebase token (Phase 3)
      // or the stubbed X-Uid before that. Returns { uid } or { error: Response }.
      const authenticate = async () => {
        if (!checkSecret(request, proxySecret)) {
          return { error: Response.json({ error: 'forbidden' }, { status: 403 }) }
        }
        if (verifyToken) {
          const authz = request.headers.get('Authorization') ?? ''
          if (!authz.startsWith('Bearer ')) {
            return { error: Response.json({ error: 'no_token' }, { status: 401 }) }
          }
          try {
            const { uid } = await verifyToken(authz.slice(7).trim())
            return { uid }
          } catch (e) {
            const code = e instanceof AuthError ? e.code : 'bad_token'
            return { error: Response.json({ error: 'unauthorized', code }, { status: 401 }) }
          }
        }
        const uid = request.headers.get('X-Uid')
        if (!uid) return { error: Response.json({ error: 'no_uid' }, { status: 401 }) }
        return { uid }
      }

      if (request.method === 'GET' && url.pathname === '/health') {
        return Response.json({ ok: true })
      }

      // PART C2.2 — per-app packs. `GET /apps/<package>` returns the generic
      // control-label knowledge for that app (or 404 when nothing is known), which
      // the device fetches and caches to make a plan land in the real UI. Behind
      // the same shared secret as /chat — the app already presents it, and there is
      // no reason to serve config to anyone who did not. Packs carry no user data.
      if (request.method === 'GET' && url.pathname.startsWith('/apps/')) {
        if (!checkSecret(request, proxySecret)) {
          return Response.json({ error: 'forbidden' }, { status: 403 })
        }
        let pkg
        try {
          pkg = decodeURIComponent(url.pathname.slice('/apps/'.length))
        } catch {
          return Response.json({ error: 'bad_package' }, { status: 400 })
        }
        const pack = packFor(pkg)
        if (!pack) return Response.json({ error: 'no_pack' }, { status: 404 })
        return Response.json(pack)
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

      // PART E — subscription verification. The phone posts a Play purchase token
      // after a successful subscribe; we verify it, record the subscription, and the
      // user's plan is `pro` while it is active. Dormant (503) until a Play service
      // account wires the verifier, exactly like Firebase identity's project-id switch.
      if (request.method === 'POST' && url.pathname === '/billing/verify') {
        const a = await authenticate()
        if (a.error) return a.error
        if (!verifySubscription) {
          return Response.json({ error: 'billing_unconfigured' }, { status: 503 })
        }
        let body
        try {
          body = await request.json()
        } catch {
          return Response.json({ error: 'bad_json' }, { status: 400 })
        }
        const productId = body?.productId
        const purchaseToken = body?.purchaseToken
        if (!productId || !purchaseToken) {
          return Response.json({ error: 'missing_purchase' }, { status: 400 })
        }
        let sub
        try {
          sub = await verifySubscription({ productId, purchaseToken })
        } catch (e) {
          return Response.json({ error: 'verify_failed', detail: String(e?.message ?? e) }, { status: 502 })
        }
        const nowMs = now()
        await store.setSubscription(a.uid, { ...sub, productId, purchaseToken }, nowMs)
        const active = isActive(sub, nowMs)
        return Response.json({
          plan: active ? 'pro' : 'free',
          state: sub.state,
          expiresAt: sub.expiryMs || null,
          active,
        })
      }

      if (request.method !== 'POST' || url.pathname !== '/chat') {
        return Response.json({ error: 'not_found' }, { status: 404 })
      }

      // Auth (shared app secret + a verified Firebase uid, or the stubbed X-Uid
      // before Phase 3 activation) is shared with /billing/verify above.
      const auth = await authenticate()
      if (auth.error) return auth.error
      const uid = auth.uid

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
      // Effective plan: `pro` while a subscription is active, else the stored plan
      // (which stays `free` unless manually overridden). No subscription ⇒ identical
      // to the old behaviour, so nothing changes for a user who never subscribed.
      const userPlan = await store.userPlan(uid)
      const sub = await store.subscription(uid)
      const plan = effectivePlan(userPlan, sub, nowMs)
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
      // Grounding context (current screen + remembered facts) rides appended to
      // the system prompt, exactly as the app's GroqClient.buildPayload does
      // (`base\n\ncontext`). Without it the model rightly asks "which app?"; with
      // it, the same call tests plan quality instead of penalising a fair question.
      const baseSystem = body.system ?? SYSTEM_PROMPT
      const system = body.context ? `${baseSystem}\n\n${body.context}` : baseSystem
      let result
      try {
        result = await provider.complete({ models, messages, system })
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

      // Rule 6 guard: a code/PIN/password must never reach the device's memory,
      // whatever the model emitted. Strip secret <<REMEMBER>> markers server-side.
      return Response.json({
        reply: dropSecretMemories(result.text),
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
    // Phase 3 turns on the moment a Firebase project id is present. Until then
    // the Worker keeps the stubbed-uid behaviour, so this code can ship and
    // deploy with nothing changed for the live app or the eval harness. `iss`
    // and `aud` are derived from this one non-secret value.
    const verifyToken = env.FIREBASE_PROJECT_ID
      ? firebaseVerifier({ projectId: env.FIREBASE_PROJECT_ID })
      : null
    return createWorker({ store, provider, proxySecret: env.PROXY_SECRET, verifyToken }).fetch(request)
  },
}
