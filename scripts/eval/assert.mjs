/**
 * Pure marker-shape assertions for the Phase 2 eval harness.
 *
 * The model is not deterministic and app names vary, so we never assert on exact
 * reply text — only on the SHAPE of the markers it emitted: which kinds are
 * present, which are absent, and a loose pattern on their argument. This is the
 * "per-row marker-shape assertion" from BACKEND_PLAN.md Phase 2.
 *
 * A marker is `<<TYPE|arg1|arg2|...>>`, one per line (see app SystemPrompt.kt).
 * This file is PURE (no network) so its logic is unit-tested in assert.test.mjs.
 */

/** Parse every `<<...>>` marker out of a reply into {type, arg, args, raw}. */
export function parseMarkers(text) {
  const markers = []
  const re = /<<\s*([^>]+?)\s*>>/g
  let m
  while ((m = re.exec(text ?? '')) !== null) {
    const parts = m[1].split('|').map((s) => s.trim())
    markers.push({
      type: parts[0].toUpperCase(),
      arg: parts.slice(1).join('|'), // everything after the type, rejoined
      args: parts.slice(1),
      raw: m[0],
    })
  }
  return markers
}

/** Does one marker satisfy a matcher {type?, arg?}? */
function matches(marker, matcher) {
  if (matcher.type && marker.type !== matcher.type.toUpperCase()) return false
  if (matcher.arg instanceof RegExp && !matcher.arg.test(marker.arg)) return false
  return true
}

function describe(matcher) {
  const t = matcher.type ? `<<${matcher.type}>>` : 'any marker'
  return matcher.arg instanceof RegExp ? `${t} matching ${matcher.arg}` : t
}

/**
 * Check one scenario against a reply.
 * Scenario fields (all optional except id):
 *   must     - every matcher needs >=1 matching marker
 *   mustAny  - at least one of these matchers must be present
 *   mustNot  - none of these matchers may be present
 *   noMarkers- the reply must contain zero markers (it should ask, not act)
 * Returns { id, pass, markerCount, failures: string[] }.
 */
export function checkScenario(scenario, reply) {
  const markers = parseMarkers(reply)
  const failures = []

  // askOk: for a genuinely under-specified prompt (e.g. "add apples" with no
  // variety), a clarifying question with NO markers is a valid outcome — the
  // assistant is right to ask rather than guess. Only a 0-marker reply counts;
  // if it emitted markers we still check them (asking AND acting is the bug the
  // system prompt forbids).
  if (scenario.askOk && markers.length === 0) {
    return { id: scenario.id, pass: true, markerCount: 0, failures: [], asked: true }
  }

  for (const matcher of scenario.must ?? []) {
    if (!markers.some((mk) => matches(mk, matcher))) {
      failures.push(`missing required ${describe(matcher)}`)
    }
  }

  if (scenario.mustAny?.length) {
    const anyHit = scenario.mustAny.some((matcher) => markers.some((mk) => matches(mk, matcher)))
    if (!anyHit) {
      failures.push(`none of the expected options present: ${scenario.mustAny.map(describe).join(', ')}`)
    }
  }

  for (const matcher of scenario.mustNot ?? []) {
    const hit = markers.find((mk) => matches(mk, matcher))
    if (hit) failures.push(`forbidden ${describe(matcher)} present as ${hit.raw}`)
  }

  if (scenario.noMarkers && markers.length > 0) {
    failures.push(`expected no markers (a question), got ${markers.length}`)
  }

  return { id: scenario.id, pass: failures.length === 0, markerCount: markers.length, failures }
}
