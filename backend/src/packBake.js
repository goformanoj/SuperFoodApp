/**
 * The bake loop (Part C2.3): a shared trace → a server pack.
 *
 * When a user shares an app trace (the C2.1 JSON), a run that succeeded carries the
 * one thing no plan could guess — what a generic control is actually *called* in that
 * app. The device logs it the moment a generic intent resolves to a real label:
 *
 *     "Search" is called "Search for atta, dal, coke and more" in com.grofers.customerapp — tapping that
 *
 * This module distils those lines into pack controls, so a label learned on one phone
 * becomes served knowledge every install gets (C2.2) with no reinstall. Near-term this
 * is a Claude-assisted dev step — run `scripts/bake-pack.mjs`, review, paste into
 * `packs.js`, deploy — exactly as prompt tuning already works. Automated ingestion is
 * C2.4. Pure and unit-tested; it only ever *reads* a trace.
 */
import { packFor, PACK_VERSION } from './packs.js'

/** The generic word → intent map, matching the device's ControlVocabulary. */
const INTENT_WORDS = {
  search: 'search',
  'search bar': 'search',
  'search box': 'search',
  'search field': 'search',
  find: 'search',
  cart: 'cart',
  basket: 'cart',
  'my cart': 'cart',
  'view cart': 'cart',
  add: 'add',
  'add to cart': 'add',
  'add item': 'add',
  checkout: 'checkout',
  'check out': 'checkout',
  'place order': 'checkout',
}

/** The intent a generic word expresses, or null when it is already specific. */
export function classifyIntent(word) {
  if (typeof word !== 'string') return null
  const key = word.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').replace(/ +/g, ' ').trim()
  return INTENT_WORDS[key] ?? null
}

// "<generic>" is called "<literal>" in <package> — the device's learned-label line.
const LEARNED = /"([^"]+)" is called "([^"]+)" in (\S+)/

/**
 * Every learned label in a trace: {package, intent, literal}. Reads the step details
 * across all turns; a line whose generic word maps to no known intent is skipped
 * (only the intents a pack speaks are worth serving).
 */
export function learnedLabels(trace) {
  const out = []
  const turns = Array.isArray(trace?.turns) ? trace.turns : []
  for (const turn of turns) {
    const steps = Array.isArray(turn?.steps) ? turn.steps : []
    for (const step of steps) {
      const m = typeof step?.detail === 'string' ? step.detail.match(LEARNED) : null
      if (!m) continue
      const intent = classifyIntent(m[1])
      if (!intent) continue
      out.push({ package: m[3], intent, literal: m[2] })
    }
  }
  return out
}

/** Union of two label lists, existing first, de-duplicated case-sensitively. */
export function mergeLabels(existing, learned) {
  const seen = new Set()
  const out = []
  for (const label of [...(existing ?? []), ...(learned ?? [])]) {
    if (typeof label !== 'string' || label.length === 0 || seen.has(label)) continue
    seen.add(label)
    out.push(label)
  }
  return out
}

/**
 * Distil a trace into a pack for one package, merging what is learned on top of the
 * pack already served for it (so nothing known is lost). Returns the pack object plus
 * `added` — the labels this trace contributes that were not already served — so the
 * baker can see at a glance whether the trace taught anything new. `pkg` is supplied
 * by the baker; when omitted, the package from the trace's own learned lines is used.
 */
export function distill(trace, { package: pkg, name } = {}) {
  const learned = learnedLabels(trace)
  const target = pkg ?? learned[0]?.package ?? null
  if (!target) return null

  const forApp = learned.filter((l) => l.package === target)
  const existing = packFor(target)

  const controls = {}
  const added = {}
  // Seed from what is already served, so a distil never drops known labels.
  if (existing) {
    for (const [intent, labels] of Object.entries(existing.controls)) {
      controls[intent] = [...labels]
    }
  }
  for (const { intent, literal } of forApp) {
    const before = controls[intent] ?? []
    const merged = mergeLabels(before, [literal])
    controls[intent] = merged
    if (merged.length > before.length) {
      added[intent] = mergeLabels(added[intent], [literal])
    }
  }

  return {
    pack: {
      version: PACK_VERSION,
      match: existing?.match ?? shortestFragment(target),
      name: name ?? existing?.name ?? target,
      package: target,
      controls,
    },
    added,
  }
}

/** A reasonable package-fragment key when there is no existing pack to inherit from. */
function shortestFragment(pkg) {
  const parts = pkg.toLowerCase().split('.').filter((p) => p && p !== 'com' && p !== 'in' && p !== 'app' && p !== 'android')
  return parts.sort((a, b) => a.length - b.length)[0] ?? pkg.toLowerCase()
}
