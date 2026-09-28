/**
 * Speech-to-text for the desktop (and later the phone): the client POSTs a short WAV,
 * the Worker transcribes it with the server-held Groq key, and the cost comes out of
 * the same daily TOKEN allowance as chat — so voice cannot be used to get around it.
 *
 * Whisper is billed by audio time, not tokens. The exchange rate below is set so a
 * minute of audio costs about what the model spend it replaces would: Groq's
 * whisper-large-v3-turbo is ~$0.04/hour, i.e. roughly the price of 60–70k input tokens
 * on the 70B chat model, so ~20 tokens per second keeps the allowance honest without
 * making voice feel expensive (a 5-second command = 100 tokens; a chat turn ≈ 2,000).
 *
 * Everything here is pure and tested (test/transcribe.test.mjs).
 */

export const AUDIO_TOKENS_PER_SECOND = 20
/** A voice command, not a podcast. Longer uploads are refused before any spend. */
export const MAX_AUDIO_SECONDS = 120
export const MAX_AUDIO_BYTES = 8 * 1024 * 1024

/**
 * Duration of a PCM WAV from its header, or null if it is not one we understand.
 * Walks the RIFF chunks rather than assuming the canonical 44-byte header, because
 * encoders are free to put LIST/fact chunks before `data`.
 */
export function wavDurationSeconds(buffer) {
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer)
  if (bytes.length < 44) return null
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const tag = (o) => String.fromCharCode(bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3])
  if (tag(0) !== 'RIFF' || tag(8) !== 'WAVE') return null

  let byteRate = null
  let dataSize = null
  let off = 12
  while (off + 8 <= bytes.length) {
    const id = tag(off)
    const size = view.getUint32(off + 4, true)
    if (id === 'fmt ' && off + 20 <= bytes.length) {
      byteRate = view.getUint32(off + 16, true)
    } else if (id === 'data') {
      // A streaming encoder may leave the size as 0 or 0xFFFFFFFF: use what arrived.
      const available = bytes.length - (off + 8)
      dataSize = size === 0 || size === 0xffffffff || size > available ? available : size
      break
    }
    off += 8 + size + (size % 2) // chunks are word-aligned
  }
  if (!byteRate || dataSize === null) return null
  return dataSize / byteRate
}

/** What a clip of [seconds] costs against the daily allowance. Never zero for real audio. */
export function tokensForAudio(seconds) {
  if (!(seconds > 0)) return 0
  return Math.max(1, Math.ceil(seconds)) * AUDIO_TOKENS_PER_SECOND
}

/**
 * Checks an upload before anything is spent. Returns { seconds } or { error, status }.
 */
export function checkAudio(buffer) {
  const size = buffer?.byteLength ?? 0
  if (size === 0) return { error: 'no_audio', status: 400 }
  if (size > MAX_AUDIO_BYTES) return { error: 'audio_too_large', status: 413 }
  const seconds = wavDurationSeconds(buffer)
  if (seconds === null) return { error: 'not_wav', status: 415 }
  if (seconds < 0.3) return { error: 'audio_too_short', status: 400 }
  if (seconds > MAX_AUDIO_SECONDS) return { error: 'audio_too_long', status: 413 }
  return { seconds }
}

/**
 * Whisper's well-known hallucinations on near-silence: it "hears" a sign-off that
 * nobody said. A clip that transcribes to only one of these is treated as silence
 * rather than sent to the model as if the user had spoken.
 */
const SILENCE_PHANTOMS = [
  'thank you', 'thanks for watching', 'thank you for watching', 'you', 'bye',
  'thank you very much', 'please subscribe', '.', '',
]

export function cleanTranscript(text) {
  const t = String(text ?? '').replace(/\s+/g, ' ').trim()
  const bare = t.toLowerCase().replace(/[.!?,…\s]+$/g, '').trim()
  return SILENCE_PHANTOMS.includes(bare) ? '' : t
}

/** Groq's OpenAI-compatible transcription endpoint, with the server key. */
export function groqTranscriber(apiKey, { model = 'whisper-large-v3-turbo' } = {}) {
  return {
    async transcribe(wavBytes, { language } = {}) {
      const form = new FormData()
      form.append('file', new Blob([wavBytes], { type: 'audio/wav' }), 'speech.wav')
      form.append('model', model)
      form.append('response_format', 'json')
      form.append('temperature', '0')
      if (language) form.append('language', language)
      const res = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}` },
        body: form,
      })
      const body = await res.text()
      if (!res.ok) {
        const err = new Error(`transcription_failed_${res.status}`)
        err.status = res.status === 429 ? 429 : 502
        throw err
      }
      let parsed
      try {
        parsed = JSON.parse(body)
      } catch {
        const err = new Error('bad_json_from_provider')
        err.status = 502
        throw err
      }
      return { text: String(parsed.text ?? '') }
    },
  }
}
