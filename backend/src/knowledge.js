/**
 * Knowledge for the desktop agent (AGENT_PLAN §5): the live web and "what's on my screen".
 *
 * Both run on the server-held Groq key and come out of the same daily token allowance as
 * /chat, so neither is a way around the cap. The laptop's agent calls them as tools
 * (`web_search`, `look_at_screen`); the Worker never decides to search or look by itself.
 *
 * - Web: Groq's built-in `browser_search` on the gpt-oss models. No second key or search
 *   account (AGENT_PLAN §8 decision 1: start here; Brave/Tavily only if this proves weak).
 * - Vision: the one Groq model that takes images today (console.groq.com/docs/vision,
 *   checked 2026-09-28). NEVER add a model id from memory; see models.js.
 *
 * Everything here is pure and tested (test/knowledge.test.mjs).
 */

/** browser_search runs on the gpt-oss models (both already proven alive by /chat traffic). */
export const WEB_SEARCH_MODELS = ['openai/gpt-oss-120b', 'openai/gpt-oss-20b']
export const VISION_MODELS = ['qwen/qwen3.8-27b']

export const MAX_QUERY_CHARS = 400
export const MAX_QUESTION_CHARS = 1000
/** Base64 length of the screenshot. The laptop sends ~1600px JPEGs (a few hundred KB). */
export const MAX_IMAGE_B64 = 4 * 1024 * 1024

export const WEB_SEARCH_PROMPT = `You answer with up-to-date facts from the live web. Always search before answering. Give a short, direct answer (at most a few sentences or a short list), with the key numbers and dates. Say plainly when sources disagree or nothing current was found. Do not include citation marks; the sources are listed separately.`

export const VISION_PROMPT = `You look at a screenshot of the user's Windows laptop and answer their question about it. Describe only what is actually visible; quote exact error text when there is any. If the question is about an error, explain what it means and the most likely fix in plain steps. If something is unreadable or cut off, say so instead of guessing. Keep it short.`

/** The body of POST /search → { query } or { error, status }. */
export function checkSearch(body) {
  const query = typeof body?.query === 'string' ? body.query.trim() : ''
  if (!query) return { error: 'no_query', status: 400 }
  if (query.length > MAX_QUERY_CHARS) return { error: 'query_too_long', status: 413 }
  return { query }
}

/** The body of POST /vision → { image (data URL), question } or { error, status }. */
export function checkVision(body) {
  const question = typeof body?.question === 'string' ? body.question.trim() : ''
  const image = typeof body?.image === 'string' ? body.image.trim() : ''
  if (!image) return { error: 'no_image', status: 400 }
  const m = /^data:image\/(jpeg|png);base64,([A-Za-z0-9+/=]+)$/.exec(image)
  if (!m) return { error: 'bad_image', status: 415 }
  if (m[2].length > MAX_IMAGE_B64) return { error: 'image_too_large', status: 413 }
  if (question.length > MAX_QUESTION_CHARS) return { error: 'question_too_long', status: 413 }
  return { image, question: question || 'What is on this screen?' }
}

/**
 * Groq's browser_search leaves inline citation marks like 【2†L6-L10】 in the text;
 * the laptop shows the sources separately, and the text may be spoken aloud.
 */
export function cleanAnswer(text) {
  return String(text ?? '')
    .replace(/【[^】]*】/g, '')
    .replace(/[ \t]+([.,;:!?])/g, '$1')
    .replace(/[ \t]{2,}/g, ' ')
    .trim()
}

/**
 * The pages the search actually read, from the provider's executed_tools, falling back
 * to any URLs in the answer. At most [limit], de-duplicated, http(s) only.
 */
export function sourcesOf(executedTools, text, limit = 5) {
  const out = []
  const seen = new Set()
  const add = (url, title) => {
    if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return
    const clean = url.replace(/[).,\]]+$/, '')
    if (seen.has(clean) || out.length >= limit) return
    seen.add(clean)
    out.push({ title: typeof title === 'string' && title.trim() ? title.trim().slice(0, 160) : hostOf(clean), url: clean })
  }
  for (const t of Array.isArray(executedTools) ? executedTools : []) {
    const results = t?.search_results?.results ?? t?.search_results ?? []
    if (Array.isArray(results)) for (const r of results) add(r?.url, r?.title)
  }
  for (const m of String(text ?? '').matchAll(/https?:\/\/[^\s<>"'【】]+/g)) add(m[0], null)
  return out
}

function hostOf(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

/** Some reasoning models put their thinking inline; the user only wants the answer. */
export function stripThinking(text) {
  return String(text ?? '').replace(/<think>[\s\S]*?(<\/think>|$)/gi, '').trim()
}
