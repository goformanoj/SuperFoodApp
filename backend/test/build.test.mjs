import { test } from 'node:test'
import assert from 'node:assert/strict'
import { OPENROUTER_AUTO, buildBackends, openRouterDiscovery, providerFor } from '../src/providers/build.js'
import worker from '../src/index.js'

const names = (env, opts) => buildBackends(env, opts).backends.map((b) => b.name)
const ask = (p, extra = {}) => p.complete({ models: ['gpt-oss-20b'], messages: [{ role: 'user', content: 'hi' }], system: 's', ...extra })

/** Replaces global fetch with a handler, recording every call. */
function fakeNet(handler) {
  const calls = []
  const original = globalThis.fetch
  globalThis.fetch = async (url, init) => {
    const body = init?.body ? JSON.parse(init.body) : null
    calls.push({ url: String(url), auth: init?.headers?.Authorization, body })
    return handler(String(url), body)
  }
  return { calls, restore: () => { globalThis.fetch = original } }
}
const okReply = (content) => new Response(JSON.stringify({ choices: [{ message: { content } }], usage: { prompt_tokens: 7, completion_tokens: 3 } }), { status: 200 })

test('no keys at all: nothing to route to', () => {
  assert.equal(providerFor({}, { fresh: true }), null)
})

test('one key works exactly as before; more keys just add platforms behind it', () => {
  assert.deepEqual(names({ GROQ_API_KEY: 'g' }), ['groq'])
  // cerebras is last by default: it is the paid one, so the free platforms are tried first.
  assert.deepEqual(names({ GROQ_API_KEY: 'g', CEREBRAS_API_KEY: 'c', OPENROUTER_API_KEY: 'o' }), ['groq', 'openrouter', 'cerebras'])
  assert.deepEqual(names({ CEREBRAS_API_KEY: 'c' }), ['cerebras'])
})

test('platforms that may train on free-tier prompts stay off unless the owner opts in', () => {
  const env = { GROQ_API_KEY: 'g', GEMINI_API_KEY: 'k', GEMINI_MODELS: 'some-model', MISTRAL_API_KEY: 'm', MISTRAL_MODELS: 'some-model' }
  const off = buildBackends(env)
  assert.deepEqual(off.backends.map((b) => b.name), ['groq'])
  assert.equal(off.warnings.length, 2)
  assert.match(off.warnings[0], /may train on prompts/)
  assert.deepEqual(names({ ...env, ALLOW_TRAINING_TIERS: 'yes' }), ['groq', 'gemini', 'mistral'])
})

test('a card-required platform is never silently mistaken for a free one — it still runs, but warns every time', () => {
  const r = buildBackends({ GROQ_API_KEY: 'g', CEREBRAS_API_KEY: 'c' })
  assert.deepEqual(r.backends.map((b) => b.name), ['groq', 'cerebras'])
  assert.equal(r.warnings.length, 1)
  assert.match(r.warnings[0], /cerebras.*PAID platform \(card required\)/)
})

test('a platform with no model list is skipped with a clear warning, never guessed', () => {
  const env = { GEMINI_API_KEY: 'k', ALLOW_TRAINING_TIERS: 'yes' }
  const r = buildBackends(env)
  assert.deepEqual(r.backends, [])
  assert.match(r.warnings[0], /GEMINI_MODELS/)
  assert.deepEqual(names({ ...env, GEMINI_MODELS: 'a, b' }), ['gemini'])
  assert.deepEqual(buildBackends({ ...env, GEMINI_MODELS: 'a, b' }).backends[0].models, ['a', 'b'])
})

test('order and switching off are configurable without a code change', () => {
  const env = { GROQ_API_KEY: 'g', CEREBRAS_API_KEY: 'c', OPENROUTER_API_KEY: 'o' }
  assert.deepEqual(names({ ...env, PROVIDER_ORDER: 'openrouter,cerebras' }), ['openrouter', 'cerebras', 'groq'])
  assert.deepEqual(names({ ...env, DISABLED_PROVIDERS: 'groq' }), ['openrouter', 'cerebras'])
})

test('the router is built once and reused while the keys stay the same', () => {
  const env = { GROQ_API_KEY: 'same-key' }
  const a = providerFor(env, { quiet: true })
  assert.equal(providerFor({ ...env }, { quiet: true }), a)
  assert.notEqual(providerFor({ GROQ_API_KEY: 'other-key' }, { quiet: true }), a)
})

test('each platform is called at its own address with its own key and its own models', async () => {
  const net = fakeNet((url) => (url.includes('groq.com') ? new Response('{"error":"bad key"}', { status: 401 }) : okReply('from cerebras')))
  try {
    const built = providerFor({ GROQ_API_KEY: 'gkey', CEREBRAS_API_KEY: 'ckey' }, { fresh: true, quiet: true, onEvent() {} })
    const out = await ask(built.provider)
    assert.equal(out.text, 'from cerebras')
    assert.equal(out.backend, 'cerebras')
    assert.match(net.calls[0].url, /api\.groq\.com/)
    assert.equal(net.calls[0].auth, 'Bearer gkey')
    assert.match(net.calls[1].url, /api\.cerebras\.ai/)
    assert.equal(net.calls[1].auth, 'Bearer ckey')
    assert.equal(net.calls[1].body.model, 'gpt-oss-120b')
  } finally { net.restore() }
})

test('Cloudflare needs BOTH the token and the account id — the id fills in its per-account address', () => {
  const noId = buildBackends({ CLOUDFLARE_API_TOKEN: 't' })
  assert.deepEqual(noId.backends, [])
  assert.match(noId.warnings[0], /cloudflare.*CLOUDFLARE_ACCOUNT_ID/)

  const withId = buildBackends({ CLOUDFLARE_API_TOKEN: 't', CLOUDFLARE_ACCOUNT_ID: 'acct123' })
  assert.equal(withId.warnings.length, 0)
  assert.deepEqual(withId.backends[0].models, ['@cf/openai/gpt-oss-120b', '@cf/openai/gpt-oss-20b', '@cf/zai-org/glm-4.7-flash'])
})

test('Cloudflare is tried before OpenRouter and Cerebras — free platforms first, paid last', () => {
  assert.deepEqual(names({ GROQ_API_KEY: 'g', CEREBRAS_API_KEY: 'c', OPENROUTER_API_KEY: 'o', CLOUDFLARE_API_TOKEN: 't', CLOUDFLARE_ACCOUNT_ID: 'a' }),
    ['groq', 'cloudflare', 'openrouter', 'cerebras'])
})

test("Cloudflare's account id actually reaches the URL, not just the config", async () => {
  const net = fakeNet(() => okReply('from cloudflare'))
  try {
    const built = providerFor({ CLOUDFLARE_API_TOKEN: 'cftok', CLOUDFLARE_ACCOUNT_ID: 'acct-xyz' }, { fresh: true, quiet: true, onEvent() {} })
    const out = await ask(built.provider)
    assert.equal(out.backend, 'cloudflare')
    assert.match(net.calls[0].url, /accounts\/acct-xyz\/ai\/v1\/chat\/completions/)
    assert.equal(net.calls[0].auth, 'Bearer cftok')
  } finally { net.restore() }
})

test('the Worker refuses to run with no AI key, and does not need a Groq key specifically', async () => {
  const noKeys = await worker.fetch(new Request('https://x/chat', { method: 'POST' }), { PROXY_SECRET: 's' })
  assert.equal(noKeys.status, 503)
  assert.deepEqual(await noKeys.json(), { error: 'server_unconfigured' })
  const noSecret = await worker.fetch(new Request('https://x/chat', { method: 'POST' }), { CEREBRAS_API_KEY: 'c' })
  assert.equal(noSecret.status, 503)
  assert.ok(providerFor({ CEREBRAS_API_KEY: 'c' }, { quiet: true }))
})

// --- live OpenRouter discovery, against a fake catalog -----------------------------------------

const model = (id, extra = {}) => ({
  id, pricing: { prompt: '0', completion: '0' }, supported_parameters: ['tools'], architecture: { input_modalities: ['text'] }, ...extra,
})
const CATALOG = {
  data: [
    model('nvidia/nemotron-3-super-120b-a12b:free'),
    model('google/gemma-4-31b-it:free', { architecture: { input_modalities: ['text', 'image'] } }),
    model('qwen/qwen3.8-27b:free'),
    model('liquid/lfm-2.5-2.6b:free'),
    model('acme/no-tools-70b:free', { supported_parameters: ['temperature'] }),
    model('acme/paid-200b', { pricing: { prompt: '0.001', completion: '0.002' } }),
    model('stealth/space-bunny-alpha'),
    model('nvidia/nemotron-3.5-content-safety:free', { architecture: { input_modalities: ['text', 'image'] } }),
    model('acme/pic-maker-30b:free', { architecture: { input_modalities: ['text', 'image'], output_modalities: ['image'] } }),
  ],
}
const catalogFetch = (calls = []) => async (url) => { calls.push(url); return new Response(JSON.stringify(CATALOG), { status: 200 }) }

test('OpenRouter discovery keeps only free, tool-capable, non-stealth models, biggest first, with the auto-picker last', async () => {
  const find = openRouterDiscovery({ fetchImpl: catalogFetch() })
  const ids = await find({ functions: true })
  assert.deepEqual(ids, [
    'nvidia/nemotron-3-super-120b-a12b:free',
    'google/gemma-4-31b-it:free',
    'qwen/qwen3.8-27b:free',
    OPENROUTER_AUTO,
  ])
})

test('a tiny model is not offered the agent job until it has been measured', async () => {
  const guessed = await openRouterDiscovery({ fetchImpl: catalogFetch() })({ functions: true })
  assert.ok(!guessed.includes('liquid/lfm-2.5-2.6b:free'))
  const measured = await openRouterDiscovery({ fetchImpl: catalogFetch(), scorecard: { 'liquid/lfm-2.5-2.6b:free': { passed: 4, total: 4 } } })({ functions: true })
  assert.equal(measured[0], 'liquid/lfm-2.5-2.6b:free')
})

test('a model that measured badly is pushed to the back', async () => {
  const ids = await openRouterDiscovery({ fetchImpl: catalogFetch(), scorecard: { 'nvidia/nemotron-3-super-120b-a12b:free': { passed: 0, total: 4 } } })({ functions: true })
  assert.notEqual(ids[0], 'nvidia/nemotron-3-super-120b-a12b:free')
})

test('classifiers and image/music makers are never offered as chat models', async () => {
  const find = openRouterDiscovery({ fetchImpl: catalogFetch() })
  for (const needs of [{}, { functions: true }, { vision: true }]) {
    const ids = await find(needs)
    assert.ok(!ids.some((id) => /safety|pic-maker/.test(id)), JSON.stringify(needs))
  }
})

test('discovery for images offers only image-capable free models', async () => {
  assert.deepEqual(await openRouterDiscovery({ fetchImpl: catalogFetch() })({ vision: true }), ['google/gemma-4-31b-it:free'])
})

test('the catalog is fetched once and cached, then refreshed after hours', async () => {
  let t = 0
  const calls = []
  const find = openRouterDiscovery({ fetchImpl: catalogFetch(calls), now: () => t })
  await find({}); await find({}); await find({ functions: true })
  assert.equal(calls.length, 1)
  t += 7 * 60 * 60_000
  await find({})
  assert.equal(calls.length, 2)
})

test('if the catalog is unreachable, OpenRouter\'s own free auto-picker is used, and not re-fetched every request', async () => {
  let n = 0
  const find = openRouterDiscovery({ fetchImpl: async () => { n++; throw new Error('offline') } })
  assert.deepEqual(await find({ functions: true }), [OPENROUTER_AUTO])
  assert.deepEqual(await find({ functions: true }), [OPENROUTER_AUTO])
  assert.equal(n, 1)
})
