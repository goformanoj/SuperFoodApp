/**
 * Firebase ID-token verification tests.
 *
 * These mint REAL RS256 tokens from a locally-generated keypair and feed the
 * matching public JWK to the verifier — so the whole signature path is exercised
 * offline, with no Firebase project, no network and no deploy. A token signed by
 * the wrong key, expired, or aimed at another project must be rejected; a good
 * one must yield its uid. This is the Phase 3 payoff: the trust decision is
 * testable where it is written.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateKeyPairSync, createSign } from 'node:crypto'
import {
  verifyIdToken,
  firebaseVerifier,
  firebaseKeyStore,
  parseMaxAge,
  AuthError,
} from '../src/auth.js'

const PROJECT = 'jarvis-os-test'
const ISS = `https://securetoken.google.com/${PROJECT}`
const NOW_MS = 1_757_000_000_000 // a fixed instant so exp/iat are deterministic
const NOW_SEC = Math.floor(NOW_MS / 1000)
const now = () => NOW_MS

// One keypair Google would hold; another that nobody trusts, for the forgery test.
const good = makeKey('kid-good')
const attacker = makeKey('kid-good') // same kid, different key — a real forgery attempt

function makeKey(kid) {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const jwk = { ...publicKey.export({ format: 'jwk' }), kid, alg: 'RS256', use: 'sig' }
  return { privateKey, jwk, kid }
}

const b64url = (buf) => Buffer.from(buf).toString('base64url')

/** Mint a signed JWT. Overrides let each test bend exactly one thing. */
function mint({ key = good, header = {}, claims = {} } = {}) {
  const h = b64url(JSON.stringify({ alg: 'RS256', kid: key.kid, typ: 'JWT', ...header }))
  const body = b64url(
    JSON.stringify({
      iss: ISS,
      aud: PROJECT,
      sub: 'user-abc',
      auth_time: NOW_SEC - 100,
      iat: NOW_SEC - 100,
      exp: NOW_SEC + 3600,
      ...claims,
    }),
  )
  const signer = createSign('RSA-SHA256')
  signer.update(`${h}.${body}`)
  signer.end()
  const sig = b64url(signer.sign(key.privateKey))
  return `${h}.${body}.${sig}`
}

const verify = (token, over = {}) =>
  verifyIdToken(token, { keys: [good.jwk], projectId: PROJECT, now, ...over })

async function rejects(token, code, over) {
  await assert.rejects(
    () => verify(token, over),
    (e) => e instanceof AuthError && e.code === code,
    `expected AuthError code ${code}`,
  )
}

test('a valid token yields its uid', async () => {
  const { uid, claims } = await verify(mint())
  assert.equal(uid, 'user-abc')
  assert.equal(claims.sub, 'user-abc')
})

test('a forged signature (right kid, wrong key) is rejected', async () => {
  // The whole point: knowing the kid is public; only Google holds the key.
  await rejects(mint({ key: attacker }), 'invalid_signature')
})

test('a tampered payload is rejected — claims cannot be edited after signing', async () => {
  const [h, , s] = mint().split('.')
  const forgedBody = b64url(JSON.stringify({ iss: ISS, aud: PROJECT, sub: 'admin', exp: NOW_SEC + 3600 }))
  await rejects(`${h}.${forgedBody}.${s}`, 'invalid_signature')
})

test('an expired token is rejected', async () => {
  await rejects(mint({ claims: { exp: NOW_SEC - 3600 } }), 'token_expired')
})

test('expiry honours a small clock skew', async () => {
  // 30s past exp, but within the default 60s tolerance — a slightly-off phone.
  const { uid } = await verify(mint({ claims: { exp: NOW_SEC - 30 } }))
  assert.equal(uid, 'user-abc')
})

test('the wrong project (aud) is rejected', async () => {
  await rejects(mint({ claims: { aud: 'someone-elses-project' } }), 'wrong_audience')
})

test('the wrong issuer is rejected', async () => {
  await rejects(mint({ claims: { iss: 'https://evil.example/jarvis-os-test' } }), 'wrong_issuer')
})

test('an empty subject is rejected', async () => {
  await rejects(mint({ claims: { sub: '' } }), 'invalid_subject')
})

test('an over-long subject is rejected', async () => {
  await rejects(mint({ claims: { sub: 'x'.repeat(129) } }), 'invalid_subject')
})

test('a future iat is rejected', async () => {
  await rejects(mint({ claims: { iat: NOW_SEC + 3600 } }), 'token_not_yet_valid')
})

test('a non-RS256 alg is refused before any key work', async () => {
  await rejects(mint({ header: { alg: 'none' } }), 'unsupported_alg')
})

test('an unknown kid is rejected', async () => {
  await rejects(mint({ key: makeKey('some-other-kid') }), 'unknown_kid')
})

test('a non-JWT string is rejected as malformed', async () => {
  await rejects('not.a.jwt', 'malformed_token')
  await rejects('only-one-part', 'malformed_token')
  await rejects('two.parts', 'malformed_token')
  await rejects('', 'malformed_token')
})

test('a missing project id is a server error, not a pass', async () => {
  await rejects(mint(), 'no_project_id', { projectId: '' })
})

// --- parseMaxAge (pure) ---

test('parseMaxAge reads Google-shaped Cache-Control', () => {
  assert.equal(parseMaxAge('public, max-age=22214, must-revalidate, no-transform'), 22214)
  assert.equal(parseMaxAge('max-age=3600'), 3600)
  assert.equal(parseMaxAge('no-cache'), null)
  assert.equal(parseMaxAge(null), null)
  assert.equal(parseMaxAge(''), null)
})

// --- firebaseVerifier: fetch, cache, and the rotation retry ---

function fakeFetch(jwks, cacheControl = 'public, max-age=3600') {
  const calls = []
  const impl = async (url) => {
    calls.push(url)
    return new Response(JSON.stringify({ keys: jwks() }), {
      status: 200,
      headers: { 'cache-control': cacheControl, 'content-type': 'application/json' },
    })
  }
  return { impl, calls }
}

test('firebaseVerifier fetches keys, then serves from cache', async () => {
  const f = fakeFetch(() => [good.jwk])
  const verifyToken = firebaseVerifier({ projectId: PROJECT, fetchImpl: f.impl, now })
  assert.equal((await verifyToken(mint())).uid, 'user-abc')
  assert.equal((await verifyToken(mint())).uid, 'user-abc')
  assert.equal(f.calls.length, 1, 'second verify should hit the cache, not the network')
})

test('firebaseVerifier refetches once when a kid is unknown (key rotation)', async () => {
  // Start with only an old key cached; sign with a freshly rotated-in key. The
  // first verify misses, forces a refetch that now includes the new key, retries.
  const rotated = makeKey('kid-rotated')
  let published = [good.jwk]
  const f = fakeFetch(() => published)
  const verifyToken = firebaseVerifier({ projectId: PROJECT, fetchImpl: f.impl, now })

  await verifyToken(mint()) // primes the cache with [good]
  assert.equal(f.calls.length, 1)

  published = [good.jwk, rotated.jwk] // Google rotates
  const { uid } = await verifyToken(mint({ key: rotated }))
  assert.equal(uid, 'user-abc')
  assert.equal(f.calls.length, 2, 'unknown kid should trigger exactly one refetch')
})

test('firebaseVerifier surfaces a real bad token without an endless refetch', async () => {
  const f = fakeFetch(() => [good.jwk])
  const verifyToken = firebaseVerifier({ projectId: PROJECT, fetchImpl: f.impl, now })
  await assert.rejects(() => verifyToken(mint({ key: attacker })), (e) => e.code === 'invalid_signature')
})

test('firebaseKeyStore reports an unavailable endpoint rather than caching nothing', async () => {
  const store = firebaseKeyStore({ fetchImpl: async () => new Response('nope', { status: 503 }), now })
  await assert.rejects(() => store.get(), (e) => e instanceof AuthError && e.code === 'keys_unavailable')
})
