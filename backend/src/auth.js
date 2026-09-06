/**
 * Firebase ID-token verification, by hand.
 *
 * ## Why by hand
 *
 * The obvious tool for this is the Firebase Admin SDK, and it is a trap: the
 * Admin SDK is Node-only and does NOT run on Cloudflare Workers (no Node APIs,
 * a different runtime). `BACKEND_PLAN.md` calls this the single biggest trap in
 * the whole plan. So we verify the token ourselves — it is a standard RS256 JWT,
 * and Workers ships WebCrypto, which is all the verification actually needs.
 *
 * ## What a Firebase ID token is
 *
 * Three base64url parts joined by dots: `header.payload.signature`. Google signs
 * `header.payload` with a private key whose PUBLIC half is published, so anyone
 * can check the signature and nobody can forge one. That signature is what turns
 * the caller's self-declared `uid` (the Phase 0/1/2 stub, trivially spoofable)
 * into a uid we can actually trust.
 *
 * ## The split in this file
 *
 * `verifyIdToken` is the pure logic — it takes the public keys as an argument, so
 * it can be tested offline against a locally-minted keypair with zero network and
 * zero deploy. `firebaseKeyStore` / `firebaseVerifier` are the thin impure shells
 * that fetch Google's keys and cache them. Same split as the rest of the backend:
 * the logic worth defending is pure and tested; the wiring is small.
 */

/** The published public keys for Firebase ID tokens, in JWKS (JSON) form. */
export const FIREBASE_JWK_URL =
  'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'

/**
 * A verification failure with a machine-readable `code`, so the Worker can turn
 * any of them into a 401 without a string match. The message is for logs, the
 * code is the contract.
 */
export class AuthError extends Error {
  constructor(code, message) {
    super(message ?? code)
    this.name = 'AuthError'
    this.code = code
  }
}

/**
 * Verifies a Firebase ID token and returns its `{ uid, claims }`.
 *
 * @param token     the raw `header.payload.signature` string
 * @param keys      the JWKS `keys` array from Google (each a JWK with `kid`)
 * @param projectId the Firebase project id — `aud` must equal it, `iss` must be
 *                  `https://securetoken.google.com/<projectId>`
 * @param now       injectable clock (ms), for deterministic tests
 * @param clockSkewSec  tolerance for exp/iat/auth_time, so a phone a minute off
 *                      its clock is not locked out
 *
 * Throws {@link AuthError} on any failure. Signature is checked BEFORE the
 * claims: an unsigned token's claims are attacker-controlled and must not be
 * read as if they meant anything.
 */
export async function verifyIdToken(
  token,
  { keys, projectId, now = () => Date.now(), clockSkewSec = 60 },
) {
  if (!projectId) throw new AuthError('no_project_id', 'server has no Firebase project id configured')

  const parts = String(token ?? '').split('.')
  if (parts.length !== 3 || parts.some((p) => p.length === 0)) {
    throw new AuthError('malformed_token', 'not a three-part JWT')
  }
  const [headerB64, payloadB64, sigB64] = parts

  const header = decodeJsonPart(headerB64, 'header')
  if (header.alg !== 'RS256') throw new AuthError('unsupported_alg', `alg ${header.alg}`)
  if (!header.kid) throw new AuthError('no_kid', 'header has no kid')

  const jwk = (keys ?? []).find((k) => k.kid === header.kid)
  // A rotated-out or invented kid lands here. This is also the honest failure
  // when our cached key set is stale — the caller should refetch and retry once.
  if (!jwk) throw new AuthError('unknown_kid', `no public key for kid ${header.kid}`)

  const key = await importJwk(jwk)
  const data = new TextEncoder().encode(`${headerB64}.${payloadB64}`)
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, b64urlToBytes(sigB64), data)
  if (!ok) throw new AuthError('invalid_signature', 'signature does not match')

  // Only now that the signature holds are the claims trustworthy.
  const claims = decodeJsonPart(payloadB64, 'payload')
  const nowSec = Math.floor(now() / 1000)

  if (claims.aud !== projectId) throw new AuthError('wrong_audience', `aud ${claims.aud}`)
  const expectedIss = `https://securetoken.google.com/${projectId}`
  if (claims.iss !== expectedIss) throw new AuthError('wrong_issuer', `iss ${claims.iss}`)
  if (typeof claims.sub !== 'string' || claims.sub.length === 0 || claims.sub.length > 128) {
    throw new AuthError('invalid_subject', 'sub must be a non-empty string of at most 128 chars')
  }
  if (typeof claims.exp !== 'number' || claims.exp <= nowSec - clockSkewSec) {
    throw new AuthError('token_expired', 'exp is in the past')
  }
  if (typeof claims.iat === 'number' && claims.iat > nowSec + clockSkewSec) {
    throw new AuthError('token_not_yet_valid', 'iat is in the future')
  }
  // Firebase ID tokens carry auth_time (when the user actually authenticated).
  // A future auth_time is nonsensical and a sign of a forged or malformed token.
  if (typeof claims.auth_time === 'number' && claims.auth_time > nowSec + clockSkewSec) {
    throw new AuthError('token_not_yet_valid', 'auth_time is in the future')
  }

  return { uid: claims.sub, claims }
}

/** Import a Google JWK as an RS256 verification key. Minimal fields on purpose. */
async function importJwk(jwk) {
  return crypto.subtle.importKey(
    'jwk',
    { kty: 'RSA', n: jwk.n, e: jwk.e },
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify'],
  )
}

function decodeJsonPart(part, which) {
  try {
    return JSON.parse(new TextDecoder().decode(b64urlToBytes(part)))
  } catch {
    throw new AuthError('malformed_token', `${which} is not valid base64url JSON`)
  }
}

/** base64url -> bytes, without Buffer (which Workers does not have). */
function b64urlToBytes(s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4)
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes
}

/**
 * Parse `max-age` (seconds) out of a Cache-Control header. Google tells us how
 * long its keys are valid; honouring it means we refetch about as often as the
 * keys actually rotate, and no more. Returns null when absent/unparseable, so
 * the caller can fall back to a conservative default rather than caching forever.
 */
export function parseMaxAge(cacheControl) {
  if (!cacheControl) return null
  const m = /(?:^|[,\s])max-age\s*=\s*(\d+)/i.exec(cacheControl)
  if (!m) return null
  return Number(m[1])
}

/**
 * A key store that fetches Google's JWKS and caches it until its max-age lapses.
 *
 * A Worker isolate is reused across requests, so this module-level cache spares
 * all but roughly one fetch per key-rotation window. `get({ force })` refetches
 * even if unexpired — used for the one retry when a token's kid is unknown, which
 * is the legitimate case of a key having rotated since we last looked.
 */
export function firebaseKeyStore({ fetchImpl = fetch, now = () => Date.now(), defaultTtlSec = 3600 } = {}) {
  let cache = null // { keys, expiresAt }

  async function refresh() {
    const res = await fetchImpl(FIREBASE_JWK_URL)
    if (!res.ok) throw new AuthError('keys_unavailable', `key fetch returned ${res.status}`)
    const body = await res.json()
    const keys = body?.keys
    if (!Array.isArray(keys) || keys.length === 0) {
      throw new AuthError('keys_unavailable', 'key endpoint returned no keys')
    }
    const ttl = parseMaxAge(res.headers.get('cache-control')) ?? defaultTtlSec
    cache = { keys, expiresAt: now() + ttl * 1000 }
    return keys
  }

  return {
    async get({ force = false } = {}) {
      if (!force && cache && now() < cache.expiresAt) return cache.keys
      return refresh()
    },
  }
}

/**
 * The verifier the Worker uses: fetch-and-cache keys, verify, and — if the
 * token's kid is unknown — refetch keys once and retry, because that is exactly
 * what a legitimate key rotation looks like from here.
 */
export function firebaseVerifier({ projectId, fetchImpl = fetch, now = () => Date.now() }) {
  const store = firebaseKeyStore({ fetchImpl, now })
  return async function verifyToken(token) {
    let keys = await store.get()
    try {
      return await verifyIdToken(token, { keys, projectId, now })
    } catch (e) {
      if (e instanceof AuthError && e.code === 'unknown_kid') {
        keys = await store.get({ force: true })
        return verifyIdToken(token, { keys, projectId, now })
      }
      throw e
    }
  }
}
