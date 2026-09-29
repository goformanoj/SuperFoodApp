/**
 * Measures how good each model is at JARVIS's actual job, and sorts them.
 *
 *   node scripts/probe.mjs                 # print the scorecard
 *   node scripts/probe.mjs --write         # also save it to src/scorecard.js (the router reads it)
 *   node scripts/probe.mjs --quick         # 2 requests per model instead of 4
 *   node scripts/probe.mjs --only=cerebras,openrouter --delay=15
 *
 * Every model gets the same real requests — JARVIS's own desktop system prompt and tool
 * definitions — and passes a case only if it answers the way the app needs: the right
 * tool with valid arguments, or a plain answer when no tool fits. A model that prints a
 * tool call as text, invents a tool, or comes back empty FAILS (normalize.js is the
 * judge, the same one the live router uses).
 *
 * COST: about 2-3k tokens per request, on the free allowance of whichever platform is
 * being tested. Rate-limit errors are not counted against a model — they say nothing
 * about its ability — so a busy platform is reported as "skipped", not "failed".
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { PLATFORMS, classify } from '../src/providers/catalog.js'
import { buildBackends, openRouterDiscovery } from '../src/providers/build.js'
import { normalizeResult } from '../src/providers/normalize.js'
import { modelsFor } from '../src/models.js'
import { DESKTOP_AGENT_PROMPT } from '../src/systemPrompt.js'
import { BACKEND_DIR, flag, loadEnv, sleep } from './lib.mjs'

const TOOLS = [
  { name: 'play_youtube', description: 'Find and play a song, video or playlist on YouTube. Use for ANY request to play music or a video.', props: { query: { type: 'string' }, type: { type: 'string', enum: ['video', 'playlist'] } }, req: ['query'] },
  { name: 'add_task', description: 'Add a to-do item, optionally with a due date/time.', props: { title: { type: 'string' }, due: { type: 'string', description: 'ISO date-time' } }, req: ['title'] },
  { name: 'open_url', description: 'Open a web address in the default browser.', props: { url: { type: 'string' } }, req: ['url'] },
  { name: 'get_time', description: 'The current date and time.', props: {}, req: [] },
].map((t) => ({ type: 'function', function: { name: t.name, description: t.description, parameters: { type: 'object', properties: t.props, required: t.req } } }))

const SYSTEM = `${DESKTOP_AGENT_PROMPT}\n\nCurrent date/time: Tuesday 29 September 2026, 22:40 (Asia/Kolkata, UTC+05:30).`
const CASES = [
  { q: 'play Channa Mereya by Arijit Singh', want: 'play_youtube' },
  { q: 'add a task to call the bank tomorrow at 5pm', want: 'add_task' },
  { q: 'what is 12 times 13?', want: null, mustSay: /156/ },
  { q: 'play the best Arijit Singh playlist', want: 'play_youtube' },
]

const env = loadEnv()
const only = String(flag('only', '')).split(',').filter(Boolean)
const delay = Number(flag('delay', 12)) * 1000
const cases = flag('quick') ? CASES.slice(0, 2) : CASES
const { backends, warnings } = buildBackends(env, { scorecard: {} })
warnings.forEach((w) => console.log(`! ${w}`))
if (!backends.length) { console.log('No usable keys. Put them in backend/.dev.vars (see the README).'); process.exit(1) }

const results = {}
const rows = []
for (const b of backends) {
  if (only.length && !only.includes(b.name)) continue
  let models = b.models
  if (models === 'inherit') models = [...new Set([...modelsFor('pro'), ...modelsFor('free')])]
  else if (typeof models === 'function') models = await openRouterDiscovery({ top: 10, scorecard: {} })({ functions: true })
  for (const model of models) {
    let passed = 0, total = 0, skipped = 0
    const times = [], notes = []
    for (const c of cases) {
      const t0 = Date.now()
      try {
        const raw = await b.provider.complete({
          models: [model], system: SYSTEM, tools: TOOLS, extra: { maxTokens: 300, temperature: 0.2 },
          messages: [{ role: 'user', content: c.q }],
        })
        times.push(Date.now() - t0)
        const n = normalizeResult(raw, { tools: TOOLS })
        const got = n.ok ? n.result.toolCalls?.[0]?.name ?? null : undefined
        const right = n.ok && got === c.want && (!c.mustSay || c.mustSay.test(n.result.text))
        total++
        if (right) passed++
        else notes.push(n.ok ? `"${c.q.slice(0, 18)}…"→${got ?? 'text'}` : n.reason)
      } catch (e) {
        const msg = String(e?.message ?? e)
        if (/rate_limit|429|all_models|http_402|http_40[13]/.test(msg)) { skipped++; if (/http_40[13]/.test(msg)) notes.push('key rejected') }
        else { total++; notes.push(msg.slice(0, 40)) }
      }
      await sleep(delay)
    }
    const key = model
    if (total > 0) results[key] = { passed, total, medianMs: times.sort((a, z) => a - z)[Math.floor(times.length / 2)] ?? null }
    const p = classify(model)
    rows.push({ platform: b.name, model, tier: p.tier, size: p.totalB ? `${p.totalB}B` : '?', score: total ? `${passed}/${total}` : 'skipped', ms: results[key]?.medianMs ?? '-', notes: notes.join('; ') + (skipped ? ` (${skipped} rate-limited)` : '') })
    console.log(`  ${b.name.padEnd(11)} ${model.padEnd(50)} ${rows.at(-1).score}`)
  }
}

console.log('\nPLATFORM    TIER    SIZE   SCORE   MEDIAN   MODEL')
for (const r of rows.sort((a, z) => (z.score.split('/')[0] - a.score.split('/')[0]) || 0)) {
  console.log(`${r.platform.padEnd(11)} ${r.tier.padEnd(7)} ${r.size.padEnd(6)} ${r.score.padEnd(7)} ${String(r.ms).padEnd(8)} ${r.model}${r.notes ? `   [${r.notes}]` : ''}`)
}

if (flag('write')) {
  const path = join(BACKEND_DIR, 'src', 'scorecard.js')
  const body = `/**\n * MEASURED results: how each model did on JARVIS's real probe requests.\n *\n * Written by \`node scripts/probe.mjs --write\`, never by hand. While it is empty the\n * router orders models by size (see catalog.js \`rankModels\`).\n *\n *   models: { "<model id>": { passed, total, medianMs } }\n */\nexport const SCORECARD = ${JSON.stringify({ generatedAt: new Date().toISOString().slice(0, 10), models: results }, null, 2)}\n`
  writeFileSync(path, body)
  console.log(`\nSaved ${Object.keys(results).length} models to src/scorecard.js`)
}
