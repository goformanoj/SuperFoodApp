/**
 * The Worker's Phase 3 wiring: when a token verifier is present, /chat must take
 * its uid from a verified `Authorization: Bearer` token and ignore `X-Uid`; when
 * no verifier is present, the old stubbed `X-Uid` path stays (so this ships
 * without breaking the live app or the eval harness).
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateKeyPairSync, createSign } from 'node:crypto'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { firebaseVerifier } from '../src/auth.js'
import { dayKey } from '../src/quota.js'

const PROJECT = 'jarvis-os-test'
const NOW_MS = 1_757_000_000_000
const now = () => NOW_MS
const NOW_SEC = Math.floor(NOW_MS / 1000)

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'k1', alg: 'RS256', use: 'sig' }
const b64url = (b) => Buffer.from(b).toString('base64url')

function token(claims = {}) {
  const h = b64url(JSON.stringify({ alg: 'RS256', kid: 'k1', typ: 'JWT' }))
  const body = b64url(
    JSON.stringify({
      iss: `https://securetoken.google.com/${PROJECT}`,
      aud: PROJECT,
      sub: 'firebase-uid-1',
      auth_time: NOW_SEC - 100,
      iat: NOW_SEC - 100,
      exp: NOW_SEC + 3600,
      ...claims,
    }),
  )
  const signer = createSign('RSA-SHA256')
  signer.update(`${h}.${body}`)
  signer.end()
  return `${h}.${body}.${b64url(signer.sign(privateKey))}`
}

// A verifier backed by a fake key endpoint, so no network is touched.
function verifier() {
  const fetchImpl = async () =>
    new Response(JSON.stringify({ keys: [jwk] }), {
      status: 200,
      headers: { 'cache-control': 'max-age=3600' },
    })
  return firebaseVerifier({ projectId: PROJECT, fetchImpl, now })
}

function build() {
  const store = memoryStore()
  const provider = fakeProvider()
  const worker = createWorker({ store, provider, verifyToken: verifier(), now })
  return { worker, store, provider }
}

function req(headers) {
  return new Request('https://proxy/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }),
  })
}

test('a valid Bearer token is accepted and its sub becomes the uid', async () => {
  const { worker, store } = build()
  const res = await worker.fetch(req({ Authorization: `Bearer ${token()}` }))
  assert.equal(res.status, 200)
  // The verified uid, not anything the caller declared, is what gets metered.
  assert.equal(await store.usedToday('firebase-uid-1', dayKey(NOW_MS)), 1200)
})

test('with a verifier on, a request with no token is 401 — no X-Uid fallback', async () => {
  const { worker, provider } = build()
  const res = await worker.fetch(req({ 'X-Uid': 'firebase-uid-1' }))
  assert.equal(res.status, 401)
  assert.equal((await res.json()).error, 'no_token')
  assert.equal(provider.calls.length, 0)
})

test('X-Uid cannot impersonate: a spoofed X-Uid is ignored when a token is required', async () => {
  const { worker, store } = build()
  // Present a real token for uid A, and a spoofed X-Uid for uid B. Usage must
  // land on A (the token), never B (the header).
  await worker.fetch(req({ Authorization: `Bearer ${token({ sub: 'real-A' })}`, 'X-Uid': 'victim-B' }))
  assert.equal(await store.usedToday('real-A', dayKey(NOW_MS)), 1200)
  assert.equal(await store.usedToday('victim-B', dayKey(NOW_MS)), 0)
})

test('an expired token is 401 with a code', async () => {
  const { worker, provider } = build()
  const res = await worker.fetch(req({ Authorization: `Bearer ${token({ exp: NOW_SEC - 3600 })}` }))
  assert.equal(res.status, 401)
  const body = await res.json()
  assert.equal(body.error, 'unauthorized')
  assert.equal(body.code, 'token_expired')
  assert.equal(provider.calls.length, 0)
})

test('a garbage token is 401, not a 500', async () => {
  const { worker } = build()
  const res = await worker.fetch(req({ Authorization: 'Bearer not-a-real-token' }))
  assert.equal(res.status, 401)
  assert.equal((await res.json()).code, 'malformed_token')
})

test('a non-Bearer Authorization header is 401', async () => {
  const { worker } = build()
  const res = await worker.fetch(req({ Authorization: 'Basic abc123' }))
  assert.equal(res.status, 401)
  assert.equal((await res.json()).error, 'no_token')
})

test('with NO verifier wired, the stubbed X-Uid path still works', async () => {
  // This is what keeps the pre-Phase-3 deploy and the eval harness alive.
  const worker = createWorker({ store: memoryStore(), provider: fakeProvider(), now })
  const res = await worker.fetch(req({ 'X-Uid': 'stub-uid' }))
  assert.equal(res.status, 200)
})
