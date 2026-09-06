/**
 * Phase 2 eval harness — fire the checklist at a LIVE Worker and score it.
 *
 * Reads scenarios.mjs, POSTs each prompt to `POST /chat`, parses the markers out
 * of the reply, checks the per-row assertions (assert.mjs), and prints a
 * scorecard.
 *
 * This calls the real Worker and SPENDS REAL GROQ TOKENS, and the model is not
 * deterministic — so it is NOT wired to run on every push. Run it on demand (or
 * nightly). It cannot run from the Claude session (egress is blocked to
 * workers.dev); it runs from CI or any machine that can reach the Worker.
 *
 * ## Auth
 *
 * Once Phase 3 is live the Worker requires a signed Firebase ID token and ignores
 * `X-Uid`. So when `FIREBASE_WEB_API_KEY` is set, the eval signs in anonymously
 * through Firebase's REST API (exactly what the app does on first launch), gets a
 * real ID token, and sends it as `Authorization: Bearer`. This exercises the real
 * verification path rather than a bypass — and each sign-in mints a fresh
 * anonymous uid, which by itself gives every run a fresh daily allowance (so the
 * old per-run `EVAL_UID` trick is no longer needed in token mode). With no web api
 * key set, it falls back to the pre-Phase-3 `X-Uid` stub, so this keeps working
 * before activation and for a Worker deployed without a project id.
 *
 * Env:
 *   WORKER_URL          e.g. https://superfoodapp.goformanoj.workers.dev  (required)
 *   PROXY_SECRET        the X-Proxy-Secret the Worker expects              (required)
 *   FIREBASE_WEB_API_KEY  Firebase project's public Web API key. When set, the
 *                       eval authenticates with a real anonymous ID token (Phase 3).
 *   EVAL_UID            the X-Uid to send in the pre-Phase-3 stub path
 *                       (default: "eval-harness"). Ignored in token mode.
 *   SYSTEM_PROMPT_FILE  optional path to a system prompt to send as body.system.
 *                       If unset, the Worker's own default system prompt is used
 *                       (see BACKEND_PLAN.md Phase 4 — moving SystemPrompt.kt
 *                       server-side; until that lands, pass a file here or the
 *                       model gets no protocol and emits no markers).
 *   GATE                "1" to exit non-zero on any failure (default: off,
 *                       because token cost + non-determinism make it advisory).
 */
import { readFile } from 'node:fs/promises'
import { SCENARIOS, DEFAULT_CONTEXT } from './scenarios.mjs'
import { checkScenario } from './assert.mjs'

const {
  WORKER_URL,
  PROXY_SECRET,
  FIREBASE_WEB_API_KEY,
  EVAL_UID = 'eval-harness',
  SYSTEM_PROMPT_FILE,
  GATE,
} = process.env

if (!WORKER_URL || !PROXY_SECRET) {
  console.error('Set WORKER_URL and PROXY_SECRET (see the header of this file).')
  process.exit(2)
}

/**
 * Sign in anonymously via Firebase's REST API and return a real ID token — the
 * same call the app makes on first launch. A fresh anonymous user per run, so a
 * fresh daily allowance comes for free.
 */
async function firebaseAnonToken(apiKey) {
  const res = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${apiKey}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ returnSecureToken: true }),
    },
  )
  const body = await res.json().catch(() => ({}))
  if (!res.ok || !body.idToken) {
    throw new Error(`anonymous sign-in failed: HTTP ${res.status} ${JSON.stringify(body).slice(0, 200)}`)
  }
  return body.idToken
}

// Auth headers, decided once. Token mode (Phase 3) when a web api key is present,
// else the pre-Phase-3 X-Uid stub. PROXY_SECRET rides along either way — it gates
// the app, the token identifies the user.
let authHeaders
if (FIREBASE_WEB_API_KEY) {
  const idToken = await firebaseAnonToken(FIREBASE_WEB_API_KEY)
  authHeaders = { Authorization: `Bearer ${idToken}` }
  console.log('🔑 Authenticating with a real anonymous Firebase ID token (Phase 3).\n')
} else {
  authHeaders = { 'X-Uid': EVAL_UID }
  console.warn('⚠  No FIREBASE_WEB_API_KEY — using the pre-Phase-3 X-Uid stub path.\n')
}

const system = SYSTEM_PROMPT_FILE ? await readFile(SYSTEM_PROMPT_FILE, 'utf8') : undefined
if (!system) {
  console.warn('⚠  No SYSTEM_PROMPT_FILE set — relying on the Worker default system prompt.\n')
}

// Groq's free tier rate-limits, and firing every scenario back-to-back trips it
// (the first baseline lost 4 rows to HTTP 502 rate_limited). Space the calls out
// and, when the Worker reports a rate limit, wait the suggested window and retry
// so a throttle does not read as a plan-quality failure.
const SPACING_MS = 3000
const MAX_RETRIES = 3
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function ask(prompt, context, attempt = 0) {
  const res = await fetch(new URL('/chat', WORKER_URL), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Proxy-Secret': PROXY_SECRET,
      ...authHeaders,
    },
    body: JSON.stringify({
      messages: [{ role: 'user', content: prompt }],
      ...(context ? { context } : {}),
      ...(system ? { system } : {}),
    }),
  })
  const text = await res.text()
  if (!res.ok) {
    // Retry ANY upstream error, not just rate limits: Groq's free tier
    // intermittently returns http_400/5xx under rapid fire (run #7 lost 4 rows
    // to transient http_400 that passed on other runs). Wait the suggested
    // rate-limit window if given, else a few seconds, and try again.
    if (attempt < MAX_RETRIES) {
      const m = /rate_limited:(\d+)s/.exec(text)
      await sleep(m ? (Number(m[1]) + 1) * 1000 : 4000)
      return ask(prompt, context, attempt + 1)
    }
    return { error: `HTTP ${res.status}: ${text.slice(0, 160)}` }
  }
  try {
    return { reply: JSON.parse(text).reply ?? '' }
  } catch {
    return { error: `bad JSON from worker: ${text.slice(0, 160)}` }
  }
}

let passed = 0
const failedRows = []

for (const [i, s] of SCENARIOS.entries()) {
  if (i > 0) await sleep(SPACING_MS)
  const { reply, error } = await ask(s.prompt, s.context ?? DEFAULT_CONTEXT)
  if (error) {
    failedRows.push({ id: s.id, failures: [error] })
    console.log(`✘ ${s.id}  ${s.prompt}\n    ${error}`)
    continue
  }
  const r = checkScenario(s, reply)
  if (r.pass) {
    passed++
    console.log(`✓ ${s.id}  ${s.prompt}  (${r.asked ? 'asked a clarifying question' : `${r.markerCount} markers`})`)
  } else {
    failedRows.push({ id: s.id, failures: r.failures })
    console.log(`✘ ${s.id}  ${s.prompt}`)
    for (const f of r.failures) console.log(`    - ${f}`)
    // Show what the model ACTUALLY said, so a "missing marker" can be diagnosed
    // (did it chat, ask, or emit a marker in the wrong shape?) — this is the raw
    // material for tuning the server-side system prompt.
    console.log(`    reply: ${JSON.stringify((reply ?? '').replace(/\s+/g, ' ').trim()).slice(0, 400)}`)
  }
}

const total = SCENARIOS.length
const pct = total ? Math.round((passed / total) * 100) : 0
console.log(`\nScore: ${passed}/${total} (${pct}%)`)

if (GATE === '1' && failedRows.length > 0) process.exit(1)
