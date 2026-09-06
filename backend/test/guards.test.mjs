import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dropSecretMemories } from '../src/guards.js'

test('drops a REMEMBER carrying a bare code, taking its spoken echo too', () => {
  const out = dropSecretMemories("Got it, I've saved the code 458213. <<REMEMBER|458213>>")
  assert.ok(!/REMEMBER/.test(out))
  assert.ok(!out.includes('458213'))
})

test('drops REMEMBER naming a secret even without digits on that token', () => {
  assert.equal(dropSecretMemories('<<REMEMBER|my wifi password is hunter2>>'), '')
  assert.equal(dropSecretMemories('<<REMEMBER|card ending 1234>>'), '')
})

test('keeps an ordinary memory untouched', () => {
  assert.equal(dropSecretMemories('<<REMEMBER|call me Sam>>'), '<<REMEMBER|call me Sam>>')
  assert.equal(
    dropSecretMemories('Sure. <<REMEMBER|my music app is Spotify>>'),
    'Sure. <<REMEMBER|my music app is Spotify>>',
  )
})

test('leaves a short house-number style memory (fewer than 4 digits) alone', () => {
  assert.equal(dropSecretMemories('<<REMEMBER|I live in flat 22B>>'), '<<REMEMBER|I live in flat 22B>>')
})

test('only touches REMEMBER — other markers with digits pass', () => {
  assert.equal(dropSecretMemories('<<ALARM|SET|07:30|Gym>>'), '<<ALARM|SET|07:30|Gym>>')
})
