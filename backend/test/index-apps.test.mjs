/**
 * The Worker's pack route (Part C2.2): GET /apps/<package>, behind the shared
 * secret, returns the pack or 404.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'

const SECRET = 'shhh'

function worker() {
  return createWorker({ store: memoryStore(), provider: fakeProvider(), proxySecret: SECRET })
}

function get(path, { secret = SECRET } = {}) {
  const headers = {}
  if (secret !== null) headers['X-Proxy-Secret'] = secret
  return worker().fetch(new Request(`https://w.dev${path}`, { method: 'GET', headers }))
}

test('a known package returns its pack', async () => {
  const res = await get('/apps/com.grofers.customerapp')
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.name, 'Blinkit')
  assert.ok(body.controls.cart.includes('My Cart'))
})

test('a url-encoded package id is decoded', async () => {
  const res = await get(`/apps/${encodeURIComponent('com.grofers.customerapp')}`)
  assert.equal(res.status, 200)
  assert.equal((await res.json()).name, 'Blinkit')
})

test('an unknown package is 404 no_pack', async () => {
  const res = await get('/apps/com.whatsapp')
  assert.equal(res.status, 404)
  assert.equal((await res.json()).error, 'no_pack')
})

test('the pack route is behind the shared secret', async () => {
  const res = await get('/apps/com.grofers.customerapp', { secret: 'wrong' })
  assert.equal(res.status, 403)
})

test('a malformed percent-encoding is 400, not a crash', async () => {
  const res = await get('/apps/%')
  assert.equal(res.status, 400)
  assert.equal((await res.json()).error, 'bad_package')
})
