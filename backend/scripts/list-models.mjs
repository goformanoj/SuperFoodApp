/**
 * Shows what every configured key can really reach, sorted by size.
 *
 *   node scripts/list-models.mjs
 *
 * Model ids must come from a provider's own live list, never from memory (see
 * src/models.js). This prints that list, so the ids for CEREBRAS_MODELS,
 * GEMINI_MODELS and friends can be copied from truth. Sends only the key
 * to the platform it belongs to; prints no keys.
 */
import { PLATFORMS, classify, rankModels } from '../src/providers/catalog.js'
import { loadEnv } from './lib.mjs'

const env = loadEnv()
let any = false
for (const [name, p] of Object.entries(PLATFORMS)) {
  const key = (env[p.keyEnv] ?? '').trim()
  if (!key) continue
  any = true
  const url = p.listUrl ?? p.endpoint.replace(/\/chat\/completions$/, '/models')
  console.log(`\n== ${p.label}  (${url})`)
  try {
    const res = await fetch(url, { headers: { Authorization: `Bearer ${key}` } })
    if (!res.ok) { console.log(`   HTTP ${res.status} — key rejected or the list lives at another address`); continue }
    const body = await res.json()
    const rows = Array.isArray(body) ? body : body.data ?? body.models ?? []
    const profiles = rankModels(rows.map((m) => classify(m.id ?? m.name ?? String(m), m)))
    for (const m of profiles) {
      const size = m.totalB ? `${m.totalB}B${m.moe ? ` (${m.activeB}B active)` : ''}` : '?'
      console.log(`   ${m.tier.padEnd(8)} ${size.padEnd(16)} ${m.id}`)
    }
    console.log(`   ${profiles.length} models`)
  } catch (e) {
    console.log(`   failed: ${e.message}`)
  }
}
if (!any) console.log('No keys found. Put them in backend/.dev.vars (git-ignored) or the environment; see the README.')
