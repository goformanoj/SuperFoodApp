/**
 * The waitlist's rules, kept apart from the routes so they can be tested without a request in hand.
 *
 * Privacy is the design constraint: what is stored is the email, when, where the visitor came from (a short
 * campaign tag the page chose) and a salted HASH of their IP — enough to stop one machine from flooding the
 * list, not enough to find out where anyone is. The IP itself is never written anywhere.
 */

export const MAX_EMAIL_LENGTH = 254
export const MAX_BODY_BYTES = 2048
/** Signups one network may make per hour before it is asked to slow down. */
export const MAX_PER_IP_PER_HOUR = 8
export const HOUR_MS = 60 * 60 * 1000

/** Lower-cased, trimmed, or null when it is not an address worth keeping. A pure shape check: nobody is emailed to confirm here. */
export function normaliseEmail(raw) {
  if (typeof raw !== 'string') return null
  const email = raw.trim().toLowerCase()
  if (email.length < 6 || email.length > MAX_EMAIL_LENGTH) return null
  if (/\s/.test(email)) return null
  const at = email.indexOf('@')
  if (at < 1 || at !== email.lastIndexOf('@')) return null
  const [local, domain] = [email.slice(0, at), email.slice(at + 1)]
  if (local.length > 64) return null
  if (local.startsWith('.') || local.endsWith('.') || email.includes('..')) return null
  if (!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local)) return null
  if (!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(domain)) return null
  if (domain.split('.').pop().length < 2) return null
  return email
}

/** A short campaign tag from the page address (?ref=…); anything else collapses to "". Never trusted as text. */
export function cleanSource(raw) {
  if (typeof raw !== 'string') return ''
  return raw.toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 40)
}

/** SHA-256 of salt+ip as hex. With no salt configured the hash is empty, so no rate limit is applied rather than a weak one. */
export async function hashIp(ip, salt) {
  if (!ip || !salt) return ''
  const bytes = new TextEncoder().encode(`${salt}|${ip}`)
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** Whether this network has used up its hour. An empty hash (no salt, no IP) is never limited. */
export function overLimit(recentCount, ipHash) {
  return Boolean(ipHash) && recentCount >= MAX_PER_IP_PER_HOUR
}
