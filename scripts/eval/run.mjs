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
 * Env:
 *   WORKER_URL          e.g. https://superfoodapp.goformanoj.workers.dev  (required)
 *   PROXY_SECRET        the X-Proxy-Secret the Worker expects              (required)
 *   EVAL_UID            the X-Uid to send (default: "eval-harness")
 *   SYSTEM_PROMPT_FILE  optional path to a system prompt to send as body.system.
 *                       If unset, the Worker's own default system prompt is used
 *                       (see BACKEND_PLAN.md Phase 4 — moving SystemPrompt.kt
 *                       server-side; until that lands, pass a file here or the
 *                       model gets no protocol and emits no markers).
 *   GATE                "1" to exit non-zero on any failure (default: off,
 *                       because token cost + non-determinism make it advisory).
 */
import { readFile } from 'node:fs/promises'
import { SCENARIOS } from './scenarios.mjs'
import { checkScenario } from './assert.mjs'

const {
  WORKER_URL,
  PROXY_SECRET,
  EVAL_UID = 'eval-harness',
  SYSTEM_PROMPT_FILE,
  GATE,
} = process.env

if (!WORKER_URL || !PROXY_SECRET) {
  console.error('Set WORKER_URL and PROXY_SECRET (see the header of this file).')
  process.exit(2)
}

const system = SYSTEM_PROMPT_FILE ? await readFile(SYSTEM_PROMPT_FILE, 'utf8') : undefined
if (!system) {
  console.warn('⚠  No SYSTEM_PROMPT_FILE set — relying on the Worker default system prompt.\n')
}

// Groq's free tier rate-limits, and firing every scenario back-to-back trips it
// (the first baseline lost 4 rows to HTTP 502 rate_limited). Space the calls out
// and, when the Worker reports a rate limit, wait the suggested window and retry
// so a throttle does not read as a plan-quality failure.
const SPACING_MS = 2000
const MAX_RETRIES = 3
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function ask(prompt, attempt = 0) {
  const res = await fetch(new URL('/chat', WORKER_URL), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Proxy-Secret': PROXY_SECRET,
      'X-Uid': EVAL_UID,
    },
    body: JSON.stringify({
      messages: [{ role: 'user', content: prompt }],
      ...(system ? { system } : {}),
    }),
  })
  const text = await res.text()
  if (!res.ok) {
    const m = /rate_limited:(\d+)s/.exec(text)
    if (m && attempt < MAX_RETRIES) {
      await sleep((Number(m[1]) + 1) * 1000)
      return ask(prompt, attempt + 1)
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
  const { reply, error } = await ask(s.prompt)
  if (error) {
    failedRows.push({ id: s.id, failures: [error] })
    console.log(`✘ ${s.id}  ${s.prompt}\n    ${error}`)
    continue
  }
  const r = checkScenario(s, reply)
  if (r.pass) {
    passed++
    console.log(`✓ ${s.id}  ${s.prompt}  (${r.markerCount} markers)`)
  } else {
    failedRows.push({ id: s.id, failures: r.failures })
    console.log(`✘ ${s.id}  ${s.prompt}`)
    for (const f of r.failures) console.log(`    - ${f}`)
  }
}

const total = SCENARIOS.length
const pct = total ? Math.round((passed / total) * 100) : 0
console.log(`\nScore: ${passed}/${total} (${pct}%)`)

if (GATE === '1' && failedRows.length > 0) process.exit(1)
