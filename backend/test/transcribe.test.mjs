import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createWorker } from '../src/index.js'
import { memoryStore } from '../src/db.js'
import { fakeProvider } from '../src/providers/fake.js'
import { FREE_DAILY_TOKENS, PRO_DAILY_TOKENS, dayKey } from '../src/quota.js'
import {
  AUDIO_TOKENS_PER_SECOND, MAX_AUDIO_SECONDS, checkAudio, cleanTranscript, tokensForAudio, wavDurationSeconds,
} from '../src/transcribe.js'

const NOW = Date.parse('2026-09-28T12:00:00Z')

/** A real PCM WAV: 16 kHz mono 16-bit, [seconds] of silence, optional extra chunk before data. */
function wav(seconds, { extraChunk = false, dataSizeOverride = null } = {}) {
  const rate = 16000
  const dataBytes = Math.round(seconds * rate) * 2
  const extra = extraChunk ? 8 + 4 : 0
  const buf = new ArrayBuffer(44 + extra + dataBytes)
  const v = new DataView(buf)
  const put = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)))
  put(0, 'RIFF'); v.setUint32(4, 36 + extra + dataBytes, true); put(8, 'WAVE')
  put(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true)
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true)
  let off = 36
  if (extraChunk) { put(off, 'LIST'); v.setUint32(off + 4, 4, true); put(off + 8, 'INFO'); off += 12 }
  put(off, 'data'); v.setUint32(off + 4, dataSizeOverride ?? dataBytes, true)
  return new Uint8Array(buf)
}

function upload(bytes, uid = 'u1', query = '') {
  return new Request(`https://proxy/transcribe${query}`, {
    method: 'POST',
    headers: { 'X-Uid': uid, 'Content-Type': 'audio/wav' },
    body: bytes,
  })
}

function fakeTranscriber(text = 'open my calendar') {
  const calls = []
  return {
    calls,
    async transcribe(bytes, opts) { calls.push({ size: bytes.byteLength, ...opts }); return { text } },
  }
}

function build(opts = {}) {
  const store = opts.store ?? memoryStore()
  const transcriber = 'transcriber' in opts ? opts.transcriber : fakeTranscriber(opts.text)
  const worker = createWorker({
    store, provider: fakeProvider(), transcriber, proUids: opts.proUids, now: () => NOW,
  })
  return { worker, store, transcriber }
}

// --- pure pieces ------------------------------------------------------------

test('wav duration comes from the header', () => {
  assert.equal(wavDurationSeconds(wav(2.5)), 2.5)
})

test('wav duration survives chunks before data', () => {
  assert.equal(wavDurationSeconds(wav(1, { extraChunk: true })), 1)
})

test('a streaming encoder that never filled in the data size still measures', () => {
  assert.equal(wavDurationSeconds(wav(1.5, { dataSizeOverride: 0xffffffff })), 1.5)
})

test('non-wav is not measured', () => {
  assert.equal(wavDurationSeconds(new TextEncoder().encode('x'.repeat(100))), null)
  assert.equal(wavDurationSeconds(new Uint8Array(10)), null)
})

test('audio costs tokens by the (rounded-up) second, never zero', () => {
  assert.equal(tokensForAudio(0.4), AUDIO_TOKENS_PER_SECOND)
  assert.equal(tokensForAudio(5), 5 * AUDIO_TOKENS_PER_SECOND)
  assert.equal(tokensForAudio(5.1), 6 * AUDIO_TOKENS_PER_SECOND)
  assert.equal(tokensForAudio(0), 0)
})

test('uploads are checked before anything is spent', () => {
  assert.deepEqual(checkAudio(new Uint8Array(0)), { error: 'no_audio', status: 400 })
  assert.equal(checkAudio(wav(0.1)).error, 'audio_too_short')
  assert.equal(checkAudio(wav(MAX_AUDIO_SECONDS + 1)).error, 'audio_too_long')
  assert.equal(checkAudio(new TextEncoder().encode('not audio at all, just text'.repeat(3))).error, 'not_wav')
  assert.equal(checkAudio(wav(3)).seconds, 3)
})

test('whisper silence phantoms are dropped, real speech kept', () => {
  assert.equal(cleanTranscript(' Thank you. '), '')
  assert.equal(cleanTranscript('Thanks for watching!'), '')
  assert.equal(cleanTranscript('Thank you, set an alarm for seven'), 'Thank you, set an alarm for seven')
  assert.equal(cleanTranscript('  open   my calendar '), 'open my calendar')
})

// --- the route --------------------------------------------------------------

test('a clip is transcribed and charged by its length', async () => {
  const { worker, store } = build()
  const res = await worker.fetch(upload(wav(3)))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.text, 'open my calendar')
  assert.equal(body.seconds, 3)
  assert.equal(body.plan, 'free')
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 3 * AUDIO_TOKENS_PER_SECOND)
  assert.equal(body.remaining, FREE_DAILY_TOKENS - 3 * AUDIO_TOKENS_PER_SECOND)
})

test('the language hint is passed through', async () => {
  const { worker, transcriber } = build()
  await worker.fetch(upload(wav(1), 'u1', '?lang=hi'))
  assert.equal(transcriber.calls[0].language, 'hi')
})

test('over the cap: refused BEFORE the transcriber is paid', async () => {
  const store = memoryStore()
  await store.addUsage('u1', dayKey(NOW), FREE_DAILY_TOKENS, 0)
  const { worker, transcriber } = build({ store })
  const res = await worker.fetch(upload(wav(2)))
  assert.equal(res.status, 429)
  assert.equal(transcriber.calls.length, 0)
})

test('the owner allowlist makes transcription pro too', async () => {
  const { worker } = build({ proUids: ['boss'] })
  const body = await (await worker.fetch(upload(wav(1), 'boss'))).json()
  assert.equal(body.plan, 'pro')
  assert.equal(body.remaining, PRO_DAILY_TOKENS - AUDIO_TOKENS_PER_SECOND)
})

test('a provider failure charges nothing', async () => {
  const failing = { async transcribe() { const e = new Error('down'); e.status = 502; throw e } }
  const { worker, store } = build({ transcriber: failing })
  const res = await worker.fetch(upload(wav(2)))
  assert.equal(res.status, 502)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 0)
})

test('bad audio is rejected with no charge and no provider call', async () => {
  const { worker, store, transcriber } = build()
  const res = await worker.fetch(upload(new TextEncoder().encode('hello there, not a wav file'.repeat(4))))
  assert.equal(res.status, 415)
  assert.equal(transcriber.calls.length, 0)
  assert.equal(await store.usedToday('u1', dayKey(NOW)), 0)
})

test('silence is returned as empty text (and still costs its few tokens)', async () => {
  const { worker } = build({ text: 'Thank you.' })
  const body = await (await worker.fetch(upload(wav(1)))).json()
  assert.equal(body.text, '')
})

test('no transcriber configured: the route does not exist', async () => {
  const { worker } = build({ transcriber: null })
  assert.equal((await worker.fetch(upload(wav(1)))).status, 404)
})

test('auth still applies: no uid, no transcription', async () => {
  const { worker, transcriber } = build()
  const res = await worker.fetch(new Request('https://proxy/transcribe', { method: 'POST', body: wav(1) }))
  assert.equal(res.status, 401)
  assert.equal(transcriber.calls.length, 0)
})
