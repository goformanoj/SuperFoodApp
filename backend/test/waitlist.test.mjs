import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { cleanSource, hashIp, normaliseEmail, overLimit, MAX_PER_IP_PER_HOUR } from '../src/waitlist.js'
import { renderPrivacy, renderSite, PAGE_HEADERS } from '../src/site.js'

const NOW = Date.parse('2026-10-02T12:00:00Z')

function build(extra = {}) {
  const store = memoryStore()
  const worker = createWorker({ store, provider: fakeProvider(), now: () => NOW, proxySecret: 'sekret', waitlistSalt: 'salt', ...extra })
  return { worker, store }
}
const post = (body, headers = {}) =>
  new Request('https://site/waitlist', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'CF-Connecting-IP': '203.0.113.7', ...headers },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })

// --- the email rule ----------------------------------------------------------

test('good addresses are lower-cased and trimmed', () => {
  assert.equal(normaliseEmail('  Pranjal@Example.COM '), 'pranjal@example.com')
  assert.equal(normaliseEmail('first.last+tag@sub.example.co.in'), 'first.last+tag@sub.example.co.in')
})

test('things that are not addresses are refused', () => {
  const bad = ['', 'a@b', 'no-at.com', 'two@@example.com', 'a b@example.com', '.dot@example.com', 'dot.@example.com', 'a..b@example.com', 'x@-bad.com', 'x@bad-.com', 'x@example.c', 42, null, undefined, {}, 'a'.repeat(250) + '@example.com']
  for (const b of bad) assert.equal(normaliseEmail(b), null, JSON.stringify(b))
})

test('a campaign tag is reduced to harmless characters', () => {
  assert.equal(cleanSource('Twitter_Launch-1'), 'twitter_launch-1')
  assert.equal(cleanSource('<script>alert(1)</script>'), 'scriptalert1script')
  assert.equal(cleanSource('x'.repeat(100)).length, 40)
  assert.equal(cleanSource(7), '')
})

test('the IP is only ever stored as a salted hash, and no salt means no hash', async () => {
  const h = await hashIp('203.0.113.7', 'salt')
  assert.match(h, /^[0-9a-f]{64}$/)
  assert.ok(!h.includes('203'))
  assert.notEqual(h, await hashIp('203.0.113.7', 'other-salt'))
  assert.equal(await hashIp('203.0.113.7', ''), '')
  assert.equal(await hashIp('', 'salt'), '')
})

test('the rate limit trips at the cap and never applies without a hash', () => {
  assert.equal(overLimit(MAX_PER_IP_PER_HOUR - 1, 'abc'), false)
  assert.equal(overLimit(MAX_PER_IP_PER_HOUR, 'abc'), true)
  assert.equal(overLimit(999, ''), false)
})

// --- POST /waitlist -----------------------------------------------------------

test('a valid signup is stored lower-cased, with a hashed IP, and answered ok', async () => {
  const { worker, store } = build()
  const res = await worker.fetch(post({ email: 'Hello@Example.com', source: 'Tw' }))
  assert.equal(res.status, 200)
  assert.deepEqual(await res.json(), { ok: true })
  const rows = await store.waitlistAll()
  assert.deepEqual(rows, [{ email: 'hello@example.com', created_at: NOW, source: 'tw' }])
  assert.ok(!JSON.stringify(rows).includes('203.0.113.7'), 'the IP must never be stored')
})

test('signing up twice is harmless and answers the same, so the form cannot reveal who is on the list', async () => {
  const { worker, store } = build()
  const a = await (await worker.fetch(post({ email: 'a@example.com' }))).json()
  const b = await (await worker.fetch(post({ email: 'a@example.com' }))).json()
  assert.deepEqual(a, b)
  assert.equal((await store.waitlistAll()).length, 1)
})

test('a bad address is a 400, bad JSON is a 400, an oversized body is a 413', async () => {
  const { worker } = build()
  assert.equal((await worker.fetch(post({ email: 'nope' }))).status, 400)
  assert.equal((await worker.fetch(post('{not json'))).status, 400)
  assert.equal((await worker.fetch(post({ email: 'a@example.com', pad: 'x'.repeat(3000) }))).status, 413)
})

test('a bot that fills the hidden field is told ok and is not stored', async () => {
  const { worker, store } = build()
  const res = await worker.fetch(post({ email: 'bot@example.com', company: 'Acme' }))
  assert.equal(res.status, 200)
  assert.equal((await store.waitlistAll()).length, 0)
})

test('one network is slowed down after its hourly cap; another network is not', async () => {
  const { worker } = build()
  for (let i = 0; i < MAX_PER_IP_PER_HOUR; i++) assert.equal((await worker.fetch(post({ email: `u${i}@example.com` }))).status, 200)
  const over = await worker.fetch(post({ email: 'late@example.com' }))
  assert.equal(over.status, 429)
  assert.equal(over.headers.get('retry-after'), '3600')
  const elsewhere = await worker.fetch(post({ email: 'late@example.com' }, { 'CF-Connecting-IP': '198.51.100.9' }))
  assert.equal(elsewhere.status, 200)
})

test('without a salt there is no rate limit rather than a weak one', async () => {
  const { worker } = build({ waitlistSalt: '' })
  for (let i = 0; i < MAX_PER_IP_PER_HOUR + 3; i++) assert.equal((await worker.fetch(post({ email: `v${i}@example.com` }))).status, 200)
})

// --- the owner's export -------------------------------------------------------

test('the export needs the secret and never contains an IP hash', async () => {
  const { worker } = build()
  await worker.fetch(post({ email: 'a@example.com' }))
  const denied = await worker.fetch(new Request('https://site/admin/waitlist'))
  assert.equal(denied.status, 403)
  const ok = await worker.fetch(new Request('https://site/admin/waitlist', { headers: { 'X-Proxy-Secret': 'sekret' } }))
  const body = await ok.json()
  assert.equal(body.count, 1)
  assert.deepEqual(Object.keys(body.rows[0]).sort(), ['created_at', 'email', 'source'])
})

// --- the pages ----------------------------------------------------------------

test('the home page and the privacy page are public and carry the safety headers', async () => {
  const { worker } = build()
  for (const path of ['/', '/privacy']) {
    const res = await worker.fetch(new Request(`https://site${path}`))
    assert.equal(res.status, 200)
    assert.match(res.headers.get('content-type'), /text\/html/)
    assert.equal(res.headers.get('x-frame-options'), 'DENY')
    assert.match(res.headers.get('content-security-policy'), /default-src 'none'/)
  }
})

test('the pages make no external requests and set no cookies', () => {
  const html = renderSite() + renderPrivacy({ contact: 'x@example.com' })
  assert.ok(!/(src|href)="https?:\/\//.test(html), 'no external src/href')
  assert.ok(!/<script[^>]+src=/.test(html), 'no external scripts')
  assert.ok(!/@import|fonts\.googleapis/.test(html))
  assert.equal(PAGE_HEADERS['set-cookie'], undefined)
})

test('the privacy page names the contact only when one is configured, and escapes it', () => {
  assert.match(renderPrivacy({ contact: 'me@example.com' }), /mailto:me@example\.com/)
  assert.match(renderPrivacy({}), /Reply to any email/)
  assert.ok(!renderPrivacy({ contact: '"><script>x</script>' }).includes('<script>x</script>'))
})

test('the page makes only claims the product makes: no invented numbers or testimonials', () => {
  const html = renderSite()
  assert.ok(!/\d+\s?(\+|k|m)?\s?(users|customers|downloads|reviews|stars)/i.test(html))
  assert.ok(!/testimonial|trusted by|as seen/i.test(html))
})

test('the old routes still behave: unknown paths are 404 JSON and /health still answers', async () => {
  const { worker } = build()
  assert.equal((await worker.fetch(new Request('https://site/nope'))).status, 404)
  assert.deepEqual(await (await worker.fetch(new Request('https://site/health'))).json(), { ok: true })
})
