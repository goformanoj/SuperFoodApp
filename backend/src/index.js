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
import { SYSTEM_PROMPT, CONVERSATION_PROMPT, DESKTOP_AGENT_PROMPT } from './systemPrompt.js'
import { lastUserText, looksActiony, shouldEscalate } from './promptTier.js'
import { dropSecretMemories } from './guards.js'
import { packFor } from './packs.js'
import { effectivePlan, isActive } from './billing.js'
import { groqProvider } from './providers/groq.js'
import { AuthError, firebaseVerifier } from './auth.js'
import { checkAudio, cleanTranscript, groqTranscriber, tokensForAudio } from './transcribe.js'
import {
  WEB_SEARCH_MODELS, VISION_MODELS, WEB_SEARCH_PROMPT, VISION_PROMPT,
  checkSearch, checkVision, cleanAnswer, sourcesOf, stripThinking,
} from './knowledge.js'

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
  transcriber = null,
  proxySecret = null,
  verifyToken = null,
  verifySubscription = null,
  proUids = [],
  proEmails = [],
  conversationTier = false,
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
            const { uid, claims } = await verifyToken(authz.slice(7).trim())
            // The email rides in the verified token for a Google sign-in — surfaced
            // so an owner allowlist can match the person's gmail (stable across the
            // anonymous↔Google uid changes that linking causes).
            return { uid, email: claims?.email ?? null }
          } catch (e) {
            const code = e instanceof AuthError ? e.code : 'bad_token'
            return { error: Response.json({ error: 'unauthorized', code }, { status: 401 }) }
          }
        }
        const uid = request.headers.get('X-Uid')
        if (!uid) return { error: Response.json({ error: 'no_uid' }, { status: 401 }) }
        return { uid, email: null }
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

      // The user's effective plan: shared by /chat and /transcribe so they can never
      // disagree about who is pro. See the comments at its /chat call site.
      const planFor = async (auth, nowMs) => {
        const userPlan = await store.userPlan(auth.uid)
        let sub = null
        try {
          sub = await store.subscription(auth.uid)
        } catch (e) {
          sub = null
        }
        const ownerEmail = auth.email && proEmails.includes(auth.email.toLowerCase())
        return ownerEmail || proUids.includes(auth.uid) ? 'pro' : effectivePlan(userPlan, sub, nowMs)
      }

      // Speech-to-text (Phase 2): a short WAV in, text out, metered in tokens against
      // the same daily allowance as /chat, checked BEFORE the provider is paid.
      if (request.method === 'POST' && url.pathname === '/transcribe') {
        if (!transcriber) return Response.json({ error: 'not_found' }, { status: 404 })
        const auth = await authenticate()
        if (auth.error) return auth.error
        const audio = new Uint8Array(await request.arrayBuffer())
        const check = checkAudio(audio)
        if (check.error) return Response.json({ error: check.error }, { status: check.status })

        const nowMs = now()
        const day = dayKey(nowMs)
        const plan = await planFor(auth, nowMs)
        const cap = capFor(plan)
        const used = await store.usedToday(auth.uid, day)
        if (isOverCap(used, cap)) return Response.json(overCapBody(nowMs, plan), { status: 429 })

        let result
        try {
          result = await transcriber.transcribe(audio, { language: url.searchParams.get('lang') || undefined })
        } catch (e) {
          // Nothing charged: no transcript, no bill.
          return Response.json({ error: 'transcription_failed' }, { status: e?.status ?? 502 })
        }
        const cost = tokensForAudio(check.seconds)
        await store.addUsage(auth.uid, day, cost, 0)
        return Response.json({
          text: cleanTranscript(result.text),
          seconds: Math.round(check.seconds * 10) / 10,
          plan,
          usage: { input: cost, output: 0 },
          remaining: remaining(used + cost, cap),
        })
      }

      // Knowledge for the desktop agent (AGENT_PLAN §5): the live web and "what's on my
      // screen". Same auth, same allowance, checked BEFORE the provider is paid; the
      // provider's own token counts are billed. The laptop calls these as tools.
      if (request.method === 'POST' && (url.pathname === '/search' || url.pathname === '/vision')) {
        const auth = await authenticate()
        if (auth.error) return auth.error
        let body
        try {
          body = await request.json()
        } catch {
          return Response.json({ error: 'bad_json' }, { status: 400 })
        }
        const isSearch = url.pathname === '/search'
        const input = isSearch ? checkSearch(body) : checkVision(body)
        if (input.error) return Response.json({ error: input.error }, { status: input.status })

        const nowMs = now()
        const day = dayKey(nowMs)
        const plan = await planFor(auth, nowMs)
        const cap = capFor(plan)
        const used = await store.usedToday(auth.uid, day)
        if (isOverCap(used, cap)) return Response.json(overCapBody(nowMs, plan), { status: 429 })

        const ctx = typeof body.context === 'string' && body.context.trim() ? `\n\n${body.context.trim().slice(0, 2000)}` : ''
        let result
        try {
          result = isSearch
            ? await provider.complete({
              models: WEB_SEARCH_MODELS,
              messages: [{ role: 'user', content: input.query }],
              system: WEB_SEARCH_PROMPT + ctx,
              tools: [{ type: 'browser_search' }],
              extra: { reasoningEffort: 'low', maxTokens: 1500, temperature: 0.3 },
            })
            : await provider.complete({
              models: VISION_MODELS,
              messages: [{ role: 'user', content: [
                { type: 'text', text: input.question },
                { type: 'image_url', image_url: { url: input.image } },
              ] }],
              system: VISION_PROMPT + ctx,
              extra: { maxTokens: 1500, temperature: 0.2 },
            })
        } catch (e) {
          return Response.json({ error: 'provider_failed', detail: String(e?.message ?? e) }, { status: e?.status ?? 502 })
        }
        const inTok = result.usage?.prompt_tokens ?? 0
        const outTok = result.usage?.completion_tokens ?? 0
        await store.addUsage(auth.uid, day, inTok, outTok)
        const answer = isSearch ? cleanAnswer(result.text) : stripThinking(result.text)
        return Response.json({
          answer,
          ...(isSearch ? { sources: sourcesOf(result.executedTools, result.text) } : {}),
          model: result.model,
          plan,
          usage: { input: inTok, output: outTok },
          remaining: remaining(used + inTok + outTok, cap),
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
      // [planFor]: the stored plan, `pro` while a subscription is active — and billing
      // is DORMANT and optional, so a subscription read that fails (a missing
      // `subscriptions` table was a real outage) degrades to the base plan instead of
      // a 500. Owner override: the owner's own account is always pro, so the free cap
      // never stops development/testing. Matched by email (from the verified Google
      // token — stable across the uid changes that anonymous↔Google linking causes)
      // or by uid. Everyone else follows the normal effective plan.
      const plan = await planFor(auth, nowMs)
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
      const ctx = body.context
      const withContext = (base) => (ctx ? `${base}\n\n${ctx}` : base)

      // Two-tier prompt (dormant unless CONVO_TIER=on). Serves a plain chat turn
      // from the slim CONVERSATION_PROMPT (~250 tokens) instead of the full ~1,900,
      // but NEVER at the cost of an errand: a clearly-actiony message goes straight
      // to the full prompt (byte-identical to the untiered path below), and a slim
      // answer that shows any sign of needing to act is re-run on the full prompt.
      // The user pays for both calls in that rare case — honest, and still far less
      // over a day than paying the full prompt on every "hi".
      const completeTiered = async () => {
        if (looksActiony(lastUserText(messages))) {
          return provider.complete({ models, messages, system: withContext(SYSTEM_PROMPT) })
        }
        const slim = await provider.complete({ models, messages, system: withContext(CONVERSATION_PROMPT) })
        if (!shouldEscalate(slim.text)) return slim
        const full = await provider.complete({ models, messages, system: withContext(SYSTEM_PROMPT) })
        return { text: full.text, model: full.model, usage: sumUsage(slim.usage, full.usage) }
      }

      // The desktop agent (AGENT_PLAN §4): a client that runs tools sends their
      // schemas; the model may answer with tool calls, which go back to the client to
      // execute. Absent (the phone, the eval) ⇒ every path below is unchanged.
      const tools = validTools(body.tools)
      if (tools === INVALID) return Response.json({ error: 'bad_tools' }, { status: 400 })

      let result
      try {
        if (tools) {
          result = await provider.complete({ models, messages, system: withContext(body.system ?? DESKTOP_AGENT_PROMPT), tools })
        } else if (body.system) {
          // An explicit system override (the app's PICK "chooser") is never tiered —
          // the caller has already decided exactly what the model should see.
          result = await provider.complete({ models, messages, system: withContext(body.system) })
        } else if (conversationTier) {
          result = await completeTiered()
        } else {
          result = await provider.complete({ models, messages, system: withContext(SYSTEM_PROMPT) })
        }
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
        reply: dropSecretMemories(result.text ?? ''),
        // Only present when the model asked to act; the client runs them and calls again.
        ...(result.toolCalls?.length ? { tool_calls: result.toolCalls } : {}),
        model: result.model,
        plan,
        usage: { input: inTok, output: outTok },
        remaining: remaining(used + inTok + outTok, cap),
      })
    },
  }
}

const INVALID = Symbol('invalid')
/** Most tools a client may declare: a guard on prompt size, not a feature limit (the laptop sends
 *  its Google tools only once an account is connected, so the common case stays near 24). */
export const MAX_TOOLS = 36

/**
 * The client's tool list, checked: absent → null (no agent), else each entry must be an
 * OpenAI-shaped function with a sane name and an object schema. Anything malformed is
 * refused whole rather than half-forwarded — a broken schema would only surface later
 * as a confusing provider error.
 */
export function validTools(tools) {
  if (tools === undefined || tools === null) return null
  if (!Array.isArray(tools) || tools.length === 0 || tools.length > MAX_TOOLS) return INVALID
  const ok = tools.every((t) =>
    t?.type === 'function' &&
    typeof t.function?.name === 'string' && /^[a-zA-Z0-9_-]{1,64}$/.test(t.function.name) &&
    (t.function.parameters === undefined || (typeof t.function.parameters === 'object' && !Array.isArray(t.function.parameters))),
  )
  return ok ? tools : INVALID
}

/**
 * Add two provider usages (an escalated turn made two calls). Input and output
 * stay apart, priced differently, exactly as a single call reports them.
 */
function sumUsage(a = {}, b = {}) {
  return {
    prompt_tokens: (a?.prompt_tokens ?? 0) + (b?.prompt_tokens ?? 0),
    completion_tokens: (a?.completion_tokens ?? 0) + (b?.completion_tokens ?? 0),
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
    // Owner allowlist — always pro, so the free cap can't stop the owner's own
    // testing. By email (primary; matches the person's gmail across uid changes) or
    // uid. Committed [vars] values, not secrets — see wrangler.toml.
    const proUids = (env.PRO_UIDS ?? '').split(',').map((s) => s.trim()).filter(Boolean)
    const proEmails = (env.PRO_EMAILS ?? '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean)
    // Two-tier prompt. OFF (unset, or anything but "on") keeps the full agent prompt
    // on every turn — unchanged behaviour, so this deploys dormant. Flip to "on" in
    // wrangler.toml [vars] only once the eval confirms markers still fire; "off"
    // reverts instantly.
    const conversationTier = (env.CONVO_TIER ?? '').trim().toLowerCase() === 'on'
    // Speech-to-text uses the same server-held Groq key (Whisper), metered as tokens.
    const transcriber = groqTranscriber(env.GROQ_API_KEY)
    return createWorker({
      store, provider, transcriber, proxySecret: env.PROXY_SECRET, verifyToken, proUids, proEmails, conversationTier,
    }).fetch(request)
  },
}
