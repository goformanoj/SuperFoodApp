/**
 * Server-side safety guards applied to the model's reply before it leaves the
 * Worker.
 *
 * Rule 6 (CLAUDE.md): a safety-critical action needs a guard in CODE, not just
 * prompt wording — prompts are probabilistic. The eval caught the model storing
 * a one-time code (`<<REMEMBER|458213>>`) despite the prompt forbidding it, so
 * this is the SendGuard idea applied to memory: a code/PIN/password/card must
 * never reach the device's memory store, whatever the model emits.
 *
 * Pure and tested (guards.test.mjs).
 */

// A memory payload that names a secret, or carries a 4+ digit run (OTP/PIN/card).
// The asymmetry is deliberate: dropping a rare numeric "fact" is far cheaper than
// persisting a secret, so a 4-digit run is enough to trip it.
const SECRET_WORD = /\b(otp|code|passcode|password|pin|cvv|card|secret)\b/i
const DIGIT_RUN = /\d{4,}/

/**
 * Removes any line carrying a `<<REMEMBER|…>>` marker whose payload looks like a
 * secret — taking the marker AND the "I've saved the code…" sentence it rides
 * on, so neither the stored fact nor a spoken echo of the code survives. Normal
 * memories ("call me Sam") pass through untouched.
 */
export function dropSecretMemories(text) {
  if (!text) return text
  const kept = text.split('\n').filter((line) => {
    const m = /<<\s*REMEMBER\s*\|([^>]*)>>/i.exec(line)
    if (!m) return true
    const payload = m[1]
    return !(SECRET_WORD.test(payload) || DIGIT_RUN.test(payload))
  })
  return kept.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}
