/**
 * Two-tier prompt routing — the pure decisions behind the `CONVO_TIER` switch.
 *
 * The Worker pays ~1,900 tokens for the full agent prompt on every turn. Most
 * "normal talking" is chit-chat and questions that need none of the marker
 * protocol. This module decides, cheaply and deterministically, whether a turn can
 * be served by the slim CONVERSATION_PROMPT — and, after a slim answer, whether it
 * must be re-run with the full prompt.
 *
 * ## The one rule that governs every choice here: an errand must never run without
 * the tools. So the bias is always toward the FULL prompt:
 *
 *   - [looksActiony] is a BROAD net over the user's message. A hit means "go
 *     straight to the full prompt" — identical to the untiered path, no model
 *     judgement involved. False positives (a question that mentions an app) are
 *     harmless: they just cost the full prompt they would have cost anyway.
 *   - [shouldEscalate] is a LIBERAL net over the slim REPLY. Any sign the turn
 *     needed to act — the explicit `<<NEEDS_ACTION>>` flag, a stray marker, or an
 *     action claim in the words — re-runs the turn on the full prompt.
 *
 * A turn is only ever answered from the slim prompt when BOTH nets stay silent, so
 * a dropped errand needs the message to look conversational AND the model to not
 * raise its hand — and even then the cost is a missed reply the user re-asks, not a
 * wrong action. The full-prompt path itself is unchanged.
 *
 * Pure and string-only, so it is unit-tested rather than reasoned about.
 */

/** The latest user message's text, or '' when there isn't one. */
export function lastUserText(messages) {
  if (!Array.isArray(messages)) return ''
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]
    if (m && m.role === 'user' && typeof m.content === 'string') return m.content
  }
  return ''
}

// Imperative verbs that ask JARVIS to DO something on the device. Broad on
// purpose — a false match only spends the full prompt (which the untiered path
// spent anyway); a MISS would drop an errand onto the slim prompt.
const ACTION_VERB =
  /\b(open|launch|start|play|put on|pause|resume|stop|close|add|buy|order|purchase|send|text|message|whatsapp|call|dial|ring|snooze|remind|wake me|set|schedule|book|reserve|cancel|delete|remove|clear|turn|enable|disable|mute|unmute|silence|share|download|install|uninstall|screenshot|screengrab|capture|type|draft|compose|navigate|go to|take me|directions|pay|transfer|scan|post|upload|forward|reply|swipe|tap|record|jot)\b/i

// App / service names JARVIS drives. A bare app name ("spotify?") is almost always
// a request to open or use it, so it routes to the full prompt.
const APP_NOUN =
  /\b(blinkit|zepto|swiggy|zomato|dominos|whatsapp|telegram|signal|spotify|youtube|gaana|wynk|jiosaavn|amazon|flipkart|myntra|instagram|insta|facebook|messenger|snapchat|twitter|gmail|outlook|maps|uber|ola|rapido|phonepe|gpay|paytm|chrome|camera|gallery|calendar|alarm|timer|reminder|contacts|dialer|notepad|netflix|hotstar|settings|torch|flashlight|bluetooth|hotspot)\b/i

// Concrete task objects that imply a device action even without a clear verb.
const ACTION_NOUN = /\b(cart|checkout|playlist|screenshot|directions)\b/i

/**
 * A broad "does this message ask for a device action?" test over the USER's words.
 * True ⇒ route straight to the full prompt.
 */
export function looksActiony(text) {
  if (typeof text !== 'string' || !text.trim()) return false
  return ACTION_VERB.test(text) || APP_NOUN.test(text) || ACTION_NOUN.test(text)
}

/** The slim prompt's raise-your-hand signal. */
const NEEDS_ACTION = /<<\s*NEEDS_ACTION\s*>>/i

/** Any complete app marker (never crosses a line). The slim prompt should not emit
 * these, but if the model tries to act anyway, escalate to get a proper full plan. */
const ANY_MARKER = /<<[^\n<>]+>+/

/**
 * The model, on the slim prompt, describing a device action instead of flagging it.
 * Deliberately device-specific so ordinary prose ("I'm opening up to you") rarely
 * trips it — and a false trip is safe anyway, costing only a full re-run.
 */
const ACTION_CLAIM =
  /\b(opening|launching|adding\b[^.]*\bto\b|sending|texting|dialing|setting (an?|the) ?(alarm|timer|reminder)|turning (it )?(on|off)|creating (a )?(note|pdf|file|event)|saving (a|that|this|the))\b/i

/**
 * After a slim answer, must the turn be re-run on the full prompt? Liberal by
 * design — every trigger only ever adds a full call, never removes one.
 */
export function shouldEscalate(replyText) {
  if (typeof replyText !== 'string') return false
  const t = replyText.trim()
  if (!t) return false
  return NEEDS_ACTION.test(t) || ANY_MARKER.test(t) || ACTION_CLAIM.test(t)
}
