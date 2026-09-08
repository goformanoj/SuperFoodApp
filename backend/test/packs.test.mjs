/**
 * Packs are generic per-app UI knowledge served to every install (Part C2.2). The
 * two things that matter: the right pack is found by package fragment, and nothing
 * user-specific ever lives in one.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { packFor, PACK_VERSION, INTENTS } from '../src/packs.js'

test('a known app resolves by package fragment', () => {
  const pack = packFor('com.grofers.customerapp')
  assert.equal(pack.name, 'Blinkit')
  assert.equal(pack.match, 'grofers')
  assert.equal(pack.package, 'com.grofers.customerapp')
  assert.equal(pack.version, PACK_VERSION)
  assert.ok(pack.controls.search.includes('Search for atta, dal, coke and more'))
})

test('matching is case-insensitive', () => {
  assert.equal(packFor('COM.GROFERS.CustomerApp')?.name, 'Blinkit')
})

test('an unknown app yields null, not an empty pack', () => {
  assert.equal(packFor('com.whatsapp'), null)
  assert.equal(packFor('com.unknown.shopping'), null)
})

test('a missing or non-string package is null, not a throw', () => {
  assert.equal(packFor(''), null)
  assert.equal(packFor(undefined), null)
  assert.equal(packFor(null), null)
  assert.equal(packFor(42), null)
})

test('every pack speaks only known intents and carries no user data', () => {
  for (const pkg of ['com.grofers.customerapp', 'com.zeptonow.app', 'in.swiggy.android', 'com.application.zomato', 'com.google.android.youtube', 'in.amazon.mShop.android.shopping']) {
    const pack = packFor(pkg)
    assert.ok(pack, `expected a pack for ${pkg}`)
    for (const intent of Object.keys(pack.controls)) {
      assert.ok(INTENTS.includes(intent), `unknown intent "${intent}" in ${pack.name}`)
      assert.ok(Array.isArray(pack.controls[intent]) && pack.controls[intent].length > 0)
    }
    // A pack is generic UI knowledge only — no address/contact/order fields.
    const keys = Object.keys(pack)
    assert.deepEqual(keys.sort(), ['controls', 'match', 'name', 'package', 'version'])
  }
})
