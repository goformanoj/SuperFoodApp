/**
 * Makes every model's reply look the same to the rest of JARVIS.
 *
 * Different models are sloppy in different ways: one wraps its answer in a
 * `<think>` block, one prints a tool call as plain text instead of making it, one
 * invents a tool that doesn't exist, one forgets to say how many tokens it used.
 * The app was written against a well-behaved model. This is the layer that either
 * repairs a reply into that shape or REJECTS it — and a rejected reply makes the
 * router try the next model instead of handing the user something wrong.
 *
 * This is deliberately not "fine-tuning". Fine-tuning retrains a model's internal
 * numbers, which you cannot do to a hosted free model. This layer does what
 * actually works in front of one: a fixed contract, repairs, and checks.
 *
 * Pure functions, no network: every rule below is unit-tested.
 */
import { stripThinking } from '../knowledge.js'

const CALL_KEYS = ['parameters', 'arguments', 'params', 'args', 'input']

/** Names of the plain function tools the client offered (built-in tools like browser_search have no name here). */
export function toolNames(tools) {
  return new Set((tools ?? []).filter((t) => t?.type === 'function').map((t) => t.function?.name).filter(Boolean))
}

function schemaFor(tools, name) {
  return (tools ?? []).find((t) => t?.function?.name === name)?.function?.parameters ?? null
}

/**
 * `{name, parameters|arguments}` — the shape models print when they "call" a tool in words.
 * A call needs a parameters-like key, unless the name is a real tool (a tool with no
 * arguments is often written as just `{"name":"get_time"}`). Without that rule, ordinary
 * data such as `{"name":"Bob","age":3}` would be mistaken for a call.
 */
function asCall(obj, names = new Set()) {
  if (!obj || typeof obj !== 'object' || typeof obj.name !== 'string') return null
  const key = CALL_KEYS.find((k) => obj[k] !== undefined)
  if (!key && !names.has(obj.name)) return null
  const args = key ? obj[key] : {}
  return { name: obj.name, arguments: typeof args === 'string' ? args : JSON.stringify(args ?? {}) }
}

/** True if the text is shaped like a printed tool call (used to refuse one we could not honour). */
function looksLikeCall(text) {
  if (/<tool_call>/i.test(text)) return true
  const whole = parseJson(String(text).trim().replace(/^```(?:json)?\s*|\s*```$/g, ''))
  const list = Array.isArray(whole) ? whole : [whole]
  return list.some((o) => o && typeof o === 'object' && typeof o.name === 'string' && CALL_KEYS.some((k) => o[k] !== undefined))
}

function parseJson(text) {
  try { return JSON.parse(text) } catch { return undefined }
}

/**
 * Tool calls a model wrote INTO its text instead of making properly: a bare JSON
 * object/array, or `<tool_call>{...}</tool_call>` blocks (the Qwen/Hermes habit).
 * Returns { calls, rest } — `rest` is the text with those parts removed.
 */
export function rescueToolCalls(text, names = new Set()) {
  const calls = []
  let rest = String(text ?? '')

  rest = rest.replace(/<tool_call>\s*([\s\S]*?)\s*<\/tool_call>/gi, (whole, body) => {
    const call = asCall(parseJson(body), names)
    if (!call) return whole
    calls.push(call)
    return ''
  })

  const whole = parseJson(rest.trim().replace(/^```(?:json)?\s*|\s*```$/g, ''))
  if (whole !== undefined) {
    const list = Array.isArray(whole) ? whole : [whole]
    const found = list.map((o) => asCall(o, names))
    if (found.length && found.every(Boolean)) {
      calls.push(...found)
      rest = ''
    }
  }
  return { calls, rest: rest.trim() }
}

/** A tool call is usable only if the tool exists, its arguments are a JSON object, and required fields are present. */
export function checkCall(call, tools, names = toolNames(tools)) {
  if (!names.has(call.name)) return `unknown_tool:${call.name}`
  const args = parseJson(call.arguments)
  if (args === null || typeof args !== 'object' || Array.isArray(args)) return `bad_arguments:${call.name}`
  const required = schemaFor(tools, call.name)?.required ?? []
  const missing = required.filter((k) => args[k] === undefined || args[k] === null)
  return missing.length ? `missing:${call.name}.${missing.join(',')}` : null
}

const approxTokens = (chars) => Math.max(1, Math.ceil(chars / 4))

/**
 * Turns one provider result into the standard shape, or says why not.
 *
 * @returns {{ ok: true, result: object } | { ok: false, reason: string }}
 */
export function normalizeResult(raw, { tools = null, inputChars = 0 } = {}) {
  const names = toolNames(tools)
  const hasTools = names.size > 0
  let text = stripThinking(raw?.text ?? '')
  let calls = (raw?.toolCalls ?? []).map((c) => ({ id: c.id, name: c.name, arguments: c.arguments }))

  // Some models print the call instead of making it. Rescue it if it names a real tool.
  if (hasTools && calls.length === 0) {
    const rescued = rescueToolCalls(text, names)
    if (rescued.calls.length) {
      calls = rescued.calls
      text = rescued.rest
    } else if (looksLikeCall(text)) {
      // Looks like a tool call but could not be rescued (e.g. an invented tool): never show that to the user.
      return { ok: false, reason: 'unusable_tool_text' }
    }
  }

  for (const call of calls) {
    const problem = checkCall(call, tools, names)
    if (problem) return { ok: false, reason: problem }
  }
  if (!text && calls.length === 0) return { ok: false, reason: 'empty' }

  // The client sends each call's id back with its result; a blank or repeated id would confuse that.
  const seen = new Set()
  calls = calls.map((c, i) => {
    let id = c.id && !seen.has(c.id) ? c.id : `call_${i + 1}_${Math.random().toString(36).slice(2, 8)}`
    seen.add(id)
    return { id, name: c.name, arguments: c.arguments }
  })

  // Billing reads these numbers. A platform that omits them would make the turn free, so estimate.
  const u = raw?.usage ?? {}
  const outChars = text.length + calls.reduce((n, c) => n + c.arguments.length, 0)
  const usage = {
    ...u,
    prompt_tokens: u.prompt_tokens > 0 ? u.prompt_tokens : approxTokens(inputChars),
    completion_tokens: u.completion_tokens > 0 ? u.completion_tokens : approxTokens(outChars),
  }

  const result = { ...raw, text, usage }
  if (calls.length) result.toolCalls = calls
  else delete result.toolCalls
  return { ok: true, result }
}

/**
 * Rewrites tool-call ids in a conversation with `remap`. Some platforms (Mistral)
 * reject ids that another platform produced, and a turn can change platform partway
 * through an errand — so the ids have to be made acceptable on the way in.
 */
export function remapToolIds(messages, remap) {
  return messages.map((m) => {
    if (m?.role === 'assistant' && Array.isArray(m.tool_calls)) {
      return { ...m, tool_calls: m.tool_calls.map((tc) => ({ ...tc, id: remap(tc.id) })) }
    }
    if (m?.role === 'tool' && m.tool_call_id) return { ...m, tool_call_id: remap(m.tool_call_id) }
    return m
  })
}

/** Exactly 9 letters/digits, stable for a given id (same input, same output, so pairs still match). */
export function nineCharId(id) {
  let h = 2166136261
  const s = String(id)
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619) >>> 0
  const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
  let out = ''
  let x = h
  for (let i = 0; i < 9; i++) {
    out += chars[x % chars.length]
    x = (Math.imul(x, 1103515245) + 12345 + i) >>> 0
  }
  return out
}
