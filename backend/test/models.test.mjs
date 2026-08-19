import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PLANS, RETIRED_UPSTREAM, modelsFor } from '../src/models.js'

test('no plan offers a model the provider has retired', () => {
  for (const plan of PLANS) {
    for (const dead of RETIRED_UPSTREAM) {
      assert.ok(!modelsFor(plan).includes(dead), `${plan} still offers ${dead}`)
    }
  }
})

test('each plan can fall back to the other plan’s model', () => {
  // The property that was MISSING in the Android client when Groq retired both
  // llama models hours apart: each tier was left with one live entry, so a
  // single empty reply killed the turn outright. Pinning the cross-cover rather
  // than the count, because "two entries" is also satisfied by two corpses.
  const free = modelsFor('free')
  const pro = modelsFor('pro')
  assert.ok(free.includes(pro[0]), 'free cannot reach the pro model')
  assert.ok(pro.includes(free[0]), 'pro cannot reach the free model')
})

test('no plan lists the same model twice', () => {
  for (const plan of PLANS) {
    const m = modelsFor(plan)
    assert.equal(m.length, new Set(m).size, `${plan} repeats a model`)
  }
})

test('an unknown plan resolves to the free list rather than nothing', () => {
  assert.deepEqual(modelsFor('nonsense'), modelsFor('free'))
})
