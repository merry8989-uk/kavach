/**
 * The edge server's logic, kept out of `index.js`.
 *
 * Same reason as the monitoring Worker: a Worker's **named exports are entry
 * points**, so exporting a constant from the entry module makes workerd refuse
 * to start. Everything testable lives here.
 */

export const SOURCE = {
  SELF_DECLARED: 'self_declared',
  BUSINESS: 'business',
  COMMUNITY: 'community',
  NONE: 'NONE',
};

/** Confidence by provenance, 0-100. Same numbers as the Python backend. */
export const CONFIDENCE = {
  self_declared: 100,
  business: 95,
  community: 40,
};

/** Only these may be presented to a user as a name. */
export const PUBLISHABLE = new Set(['self_declared', 'business']);

/** Report categories the app can send. */
export const CATEGORIES = [
  'telemarketing', 'fraud', 'loan', 'insurance', 'survey',
  'robocall', 'silence', 'other',
];

/** How long a report counts for. Old reports should fade, not accumulate forever. */
export const REPORT_WINDOW_DAYS = 180;

/**
 * A score from a report count.
 *
 * Deliberately conservative and deliberately capped short of 100: the app treats
 * a high score as a reason to warn, and a score that can reach 100 on reports
 * alone would let a campaign of bad-faith reports look like certainty.
 */
export function scoreFromReports(count) {
  if (count <= 0) return 0;
  // Saturating curve: 1 report ≈ 12, 5 ≈ 45, 20 ≈ 80, 50 ≈ 94.
  const score = Math.round(100 * (1 - Math.exp(-count / 12)));
  return Math.min(score, 95);
}

/** FRESH / RECENT / STALE / UNKNOWN, from when the record last changed. */
export function freshnessOf(lastUpdated, now = Date.now()) {
  if (!lastUpdated) return 'UNKNOWN';
  const days = (now - lastUpdated) / 86_400_000;
  if (days < 0) return 'UNKNOWN';
  if (days <= 7) return 'FRESH';
  if (days <= 90) return 'RECENT';
  return 'STALE';
}

/**
 * Hash a number. HMAC-SHA256 under the pepper.
 *
 * The server never stores or logs the raw number, so this is the only form of it
 * that exists anywhere after the request ends.
 */
export async function hashNumber(number, pepper) {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(pepper),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(number));
  return [...new Uint8Array(signature)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Normalise a number to E.164-ish form.
 *
 * Not a full parser — the app already sends E.164. This strips what people paste
 * (spaces, dashes, brackets) and refuses anything that cannot be a number, so a
 * garbage string never becomes a hash somebody else later collides with.
 */
export function normaliseNumber(raw) {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  const plus = trimmed.startsWith('+');
  const digits = trimmed.replace(/[^\d]/g, '');
  if (digits.length < 6 || digits.length > 15) return null;
  return plus ? `+${digits}` : digits;
}

/**
 * The lookup response.
 *
 * **This is where the honesty rules live**, so they are in one function rather
 * than spread across a handler:
 *
 * - a name is only returned when its provenance is publishable;
 * - a community suggestion is never returned as a name, only counted;
 * - nothing known means `source: NONE` and an explicit reason, not a blank card.
 */
export function buildLookup(number, { nameRow, reportCount, lastUpdated, now = Date.now() }) {
  const count = reportCount ?? 0;
  const score = scoreFromReports(count);

  const publishable = nameRow && PUBLISHABLE.has(nameRow.source);

  return {
    number,
    displayName: publishable ? nameRow.name : null,
    numberType: null,
    businessCategory: nameRow?.source === 'business' ? nameRow.category ?? null : null,
    source: publishable ? (nameRow.source === 'business' ? 'BUSINESS' : 'CONTACT') : 'NONE',
    confidence: publishable ? (nameRow.confidence ?? CONFIDENCE[nameRow.source] ?? 0) / 100 : 0,
    // Only a verified owner may be described as verified, and there is no OTP
    // path on the edge server yet — so this is never "VERIFIED" here.
    verificationStatus: nameRow?.source === 'self_declared' ? 'VERIFIED' : 'UNVERIFIED',
    spamRisk: score,
    reports: count,
    lastUpdated: lastUpdated ?? 0,
    freshness: freshnessOf(lastUpdated, now),
    unavailableReason: publishable || count > 0
      ? null
      : 'Nothing is known about this number yet.',
  };
}

/** Reject a report that is not about a real number or a known category. */
export function validateReport(body) {
  if (!body || typeof body !== 'object') return { error: 'body must be JSON' };
  const number = normaliseNumber(body.number);
  if (!number) return { error: 'invalid phone number' };
  const category = String(body.category ?? '').toLowerCase();
  if (!CATEGORIES.includes(category)) {
    return { error: `category must be one of: ${CATEGORIES.join(', ')}` };
  }
  const note = typeof body.note === 'string' ? body.note.slice(0, 500) : null;
  return { number, category, note };
}
