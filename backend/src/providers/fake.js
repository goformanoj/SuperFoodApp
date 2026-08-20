/**
 * A stand-in for the real provider, so the quota and accounting can be built and
 * tested before any account, key or deploy exists.
 *
 * Deterministic on purpose: a fake that returned random token counts would make
 * every accounting assertion approximate, and approximate is exactly what this
 * layer must not be.
 */
export function fakeProvider(options = {}) {
  const calls = []
  const reply = options.reply ?? 'This is a fake reply.'
  const usage = options.usage ?? { prompt_tokens: 1000, completion_tokens: 200, total_tokens: 1200 }

  return {
    calls,
    async complete({ models, messages, system }) {
      // Takes a LIST, exactly like the real provider: choosing among candidates
      // is provider-specific knowledge (what is retired, what is cooling down),
      // and a fake with a different shape would let the Worker compile against
      // an interface nothing really implements.
      calls.push({ models, messages, system })
      if (options.fail) throw new Error(options.fail)
      return { text: reply, usage, model: models[0] }
    },
  }
}
