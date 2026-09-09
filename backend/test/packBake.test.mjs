/**
 * The bake loop (Part C2.3): a shared trace becomes a served pack. What matters is
 * that a learned "X is called Y in <pkg>" line is turned into the right control label,
 * that nothing already served is dropped, and that a trace teaching nothing new says so.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { classifyIntent, learnedLabels, mergeLabels, distill } from '../src/packBake.js'
import { PACK_VERSION } from '../src/packs.js'

// A trace shaped like the C2.1 export, carrying the device's learned-label lines.
function trace(...details) {
  return {
    schema: 'jarvis.app-trace/1',
    createdAt: 0,
    turns: [{ goal: 'add milk on blinkit', outcome: 'ok', steps: details.map((d) => ({ stage: 'SCREEN', detail: d })) }],
  }
}

test('classifyIntent maps generic words, and ignores specific ones', () => {
  assert.equal(classifyIntent('Search'), 'search')
  assert.equal(classifyIntent('search bar'), 'search')
  assert.equal(classifyIntent('View Cart'), 'cart')
  assert.equal(classifyIntent('add to cart'), 'add')
  assert.equal(classifyIntent('Place Order'), 'checkout')
  assert.equal(classifyIntent('Beat It'), null)
  assert.equal(classifyIntent(42), null)
})

test('learnedLabels pulls every resolution line out of a trace', () => {
  const t = trace(
    '"Search" is called "Search for atta, dal, coke and more" in com.grofers.customerapp — tapping that',
    'on screen: results',
    '"add to cart" is called "ADD" in com.grofers.customerapp — tapping that',
  )
  assert.deepEqual(learnedLabels(t), [
    { package: 'com.grofers.customerapp', intent: 'search', literal: 'Search for atta, dal, coke and more' },
    { package: 'com.grofers.customerapp', intent: 'add', literal: 'ADD' },
  ])
})

test('a resolution line whose generic word has no intent is skipped', () => {
  const t = trace('"Home" is called "Home tab" in com.x — tapping that')
  assert.deepEqual(learnedLabels(t), [])
})

test('mergeLabels unions, keeps order, drops dupes and blanks', () => {
  assert.deepEqual(mergeLabels(['A', 'B'], ['B', 'C', '']), ['A', 'B', 'C'])
})

test('distil a brand-new app produces a pack keyed by a sensible fragment', () => {
  const t = trace('"Search" is called "Find groceries" in com.newmart.shop — tapping that')
  const { pack, added } = distill(t, {})
  assert.equal(pack.package, 'com.newmart.shop')
  assert.equal(pack.version, PACK_VERSION)
  assert.equal(pack.match, 'shop') // shortest meaningful fragment
  assert.deepEqual(pack.controls.search, ['Find groceries'])
  assert.deepEqual(added.search, ['Find groceries'])
})

test('distil merges onto an existing pack without dropping served labels', () => {
  // Blinkit already has a served pack; a trace teaching a NEW search label must add
  // it while keeping the ones already served.
  const t = trace('"Search" is called "Search Blinkit" in com.grofers.customerapp — tapping that')
  const { pack, added } = distill(t, {})
  assert.equal(pack.name, 'Blinkit')
  assert.equal(pack.match, 'grofers')
  assert.ok(pack.controls.search.includes('Search for atta, dal, coke and more'), 'served label kept')
  assert.ok(pack.controls.search.includes('Search Blinkit'), 'new label added')
  assert.deepEqual(added.search, ['Search Blinkit'])
})

test('a trace that teaches nothing new reports no additions', () => {
  const t = trace('"Search" is called "Search for atta, dal, coke and more" in com.grofers.customerapp — tapping that')
  const { added } = distill(t, {})
  assert.deepEqual(added, {})
})

test('the target package can be forced, ignoring other apps in the trace', () => {
  const t = trace(
    '"Search" is called "A" in com.one.app — tapping that',
    '"Search" is called "B" in com.two.app — tapping that',
  )
  const { pack } = distill(t, { package: 'com.two.app', name: 'Two' })
  assert.equal(pack.package, 'com.two.app')
  assert.deepEqual(pack.controls.search, ['B'])
})

test('a trace with no learned labels distils to null', () => {
  assert.equal(distill(trace('on screen: nothing useful'), {}), null)
  assert.equal(distill({ turns: [] }, {}), null)
})
