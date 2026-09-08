/**
 * Server-side app packs (Part C2.2).
 *
 * A pack is the generic, per-app knowledge the on-device executor needs to make a
 * plan land — what the search box / cart / add control is actually *labelled* in a
 * given app. On device this lives in `ControlVocabulary` (a seeded table baked into
 * the APK); serving it from here is what makes it reliable for EVERYONE without a
 * reinstall — the same move already made for the system prompt. The app fetches
 * `/apps/<package>` and caches it; a new label learned from a shared trace becomes a
 * deploy, not a release.
 *
 * ## What a pack may and may not contain
 *
 * ONLY generic UI knowledge: control labels and (later) recipes. NEVER anything
 * user-specific — an address, an order's contents, a contact. A trace is sensitive
 * data and is redacted before it is shared; a pack, distilled from many traces, must
 * itself be safe to hand to every install. Keep it that way.
 *
 * Read-mostly config, so — like `systemPrompt.js` — it is a plain module here and a
 * deploy is how it changes. Storage is deliberately not the D1 quota path; eventual
 * consistency would be fine, but a served module is simpler still.
 */

/** Bump when the pack SHAPE changes so an old cached client can tell. */
export const PACK_VERSION = 1

/** The intents a pack speaks, matching the device's `ControlVocabulary`. */
export const INTENTS = ['search', 'cart', 'add', 'checkout']

/**
 * Packs keyed by a package *fragment*, not an exact id — shopping apps ship under
 * names unrelated to their brand (Blinkit is `com.grofers.customerapp`) and behind
 * regional / white-label variants a fragment survives and an exact key would miss.
 * Seeded from the same real device traces as `ControlVocabulary`.
 */
const PACKS = [
  {
    match: 'grofers', // Blinkit — the trace that motivated the errand loop.
    name: 'Blinkit',
    controls: {
      search: ['Search for atta, dal, coke and more', 'Search "atta"', 'Search for products'],
      cart: ['My Cart', 'Cart', 'View Cart'],
      add: ['ADD', 'Add'],
    },
  },
  {
    match: 'zepto',
    name: 'Zepto',
    controls: {
      search: ['Search for over 5000 products', 'Search for products'],
      cart: ['Cart'],
      add: ['Add', 'ADD'],
    },
  },
  {
    match: 'swiggy',
    name: 'Swiggy',
    controls: { search: ['Search for restaurants and food'] },
  },
  {
    match: 'zomato',
    name: 'Zomato',
    controls: { search: ['Restaurant name, cuisine, or a dish...'] },
  },
  {
    match: 'youtube',
    name: 'YouTube',
    controls: { search: ['Search YouTube', 'Search'] },
  },
  {
    match: 'amazon',
    name: 'Amazon',
    controls: { search: ['Search Amazon.in', 'Search Amazon', 'Search'] },
  },
]

/**
 * The pack for a package id, or null when nothing is known (the app then behaves as
 * it did before packs existed — its baked-in `ControlVocabulary` only). Matching is
 * case-insensitive containment of the fragment; the first seeded match wins.
 */
export function packFor(pkg) {
  if (typeof pkg !== 'string' || pkg.length === 0) return null
  const id = pkg.toLowerCase()
  const hit = PACKS.find((p) => id.includes(p.match))
  if (!hit) return null
  return {
    version: PACK_VERSION,
    package: pkg,
    match: hit.match,
    name: hit.name,
    controls: hit.controls,
  }
}
