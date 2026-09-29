/**
 * Phase 7 (AGENT_PLAN §7): syncing tasks, reminders, notes and memory between a
 * user's devices through the Worker. This file holds everything that is pure —
 * what a row is allowed to look like, and who wins when two devices disagree —
 * so the decision logic is tested without a database (test/sync.test.mjs).
 *
 * The rule is last-write-wins by `updated_at`, the clock each DEVICE stamped on
 * its own edit. That is a real trade-off, not a technicality: if a task is
 * edited on the phone and the laptop within the same few seconds, whichever
 * device's clock reads later simply overwrites the other, silently. Good
 * enough for one person's own tasks/reminders/notes/memory, where edits are
 * rare and the two devices are rarely touched in the same instant; wrong for
 * anything where losing an edit is expensive.
 */

export const KINDS = ['task', 'reminder', 'note', 'memory']
/** A guard on request size, not a feature limit — one push is a burst of local edits, not a full resync. */
export const MAX_ROWS_PER_PUSH = 300
export const MAX_DATA_BYTES = 8_000
export const MAX_PULL_LIMIT = 500

/**
 * One row as the client sent it, checked. Returns the clean row or an error
 * string naming what was wrong — never throws, so the caller can report which
 * row of a batch failed instead of rejecting the whole push on one bad id.
 */
export function checkRow(row) {
  if (typeof row !== 'object' || row === null) return 'not_an_object'
  const { kind, id, updatedAt, deleted, data } = row
  if (!KINDS.includes(kind)) return 'bad_kind'
  if (typeof id !== 'string' || id.length === 0 || id.length > 100) return 'bad_id'
  if (!Number.isFinite(updatedAt) || updatedAt <= 0) return 'bad_updated_at'
  if (deleted !== undefined && typeof deleted !== 'boolean') return 'bad_deleted'
  if (deleted) return null // a tombstone carries no data
  if (typeof data !== 'object' || data === null || Array.isArray(data)) return 'bad_data'
  let json
  try {
    json = JSON.stringify(data)
  } catch {
    return 'unserialisable_data'
  }
  if (json.length > MAX_DATA_BYTES) return 'data_too_large'
  return null
}

/** The whole push body: an array, sanely sized. */
export function checkPush(rows) {
  if (!Array.isArray(rows)) return 'not_an_array'
  if (rows.length === 0) return 'empty'
  if (rows.length > MAX_ROWS_PER_PUSH) return 'too_many_rows'
  return null
}

/**
 * Who wins when a device pushes a row the server already has. The server's
 * copy wins on a tie (>=, not >) so the outcome never depends on which side of
 * a race a request landed on — the SAME two rows always resolve the SAME way,
 * however many times they are retried.
 */
export function incomingWins(existingUpdatedAt, incomingUpdatedAt) {
  return existingUpdatedAt === undefined || incomingUpdatedAt > existingUpdatedAt
}

/** `kinds=task,reminder` → a clean list, or all of [KINDS] if absent/empty/junk. */
export function parseKinds(param) {
  if (!param) return KINDS
  const asked = param.split(',').map((s) => s.trim()).filter((s) => KINDS.includes(s))
  return asked.length ? [...new Set(asked)] : KINDS
}

export function parseSince(param) {
  const n = Number(param)
  return Number.isFinite(n) && n >= 0 ? Math.floor(n) : 0
}
