/**
 * The edge server's rules, tested.
 *
 *   node --test edge/test/
 *
 * Most of these are about one thing: **not making things up**. A caller-ID
 * service that guesses is worse than one that says it does not know, because a
 * wrong name beside a number is a claim about a person.
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
  CATEGORIES,
  CONFIDENCE,
  PUBLISHABLE,
  buildLookup,
  freshnessOf,
  hashNumber,
  normaliseNumber,
  scoreFromReports,
  validateReport,
} from '../src/lib.js';

// ------------------------------------------------------------------ numbers --

describe('number handling', () => {
  test('E.164 passes through unchanged', () => {
    assert.equal(normaliseNumber('+919876543210'), '+919876543210');
  });

  test('what people paste is cleaned up', () => {
    assert.equal(normaliseNumber('+91 98765-43210'), '+919876543210');
    assert.equal(normaliseNumber('(044) 2345 6789'), '04423456789');
  });

  test('a plus is preserved, because it carries the country', () => {
    assert.equal(normaliseNumber('+44 20 7946 0958'), '+442079460958');
  });

  test('garbage is refused rather than hashed', () => {
    // A junk string that becomes a hash is a hash somebody else later collides
    // with, and a wrong answer beside a real number.
    for (const bad of ['', 'abc', '12345', '+', null, undefined, 42, '1'.repeat(20)]) {
      assert.equal(normaliseNumber(bad), null, `should refuse ${JSON.stringify(bad)}`);
    }
  });
});

// ------------------------------------------------------------------- hashing --

describe('hashing', () => {
  test('the same number under the same pepper hashes the same', async () => {
    const a = await hashNumber('+919876543210', 'pepper');
    const b = await hashNumber('+919876543210', 'pepper');
    assert.equal(a, b);
    assert.equal(a.length, 64);
  });

  test('a different pepper gives a different hash', async () => {
    // Which is what makes rotating the pepper make every stored row useless.
    const a = await hashNumber('+919876543210', 'pepper-one');
    const b = await hashNumber('+919876543210', 'pepper-two');
    assert.notEqual(a, b);
  });

  test('the hash does not contain the number', async () => {
    const hash = await hashNumber('+919876543210', 'pepper');
    assert.ok(!hash.includes('9876543210'));
  });
});

// -------------------------------------------------------------------- scores --

describe('the spam score', () => {
  test('no reports is zero', () => {
    assert.equal(scoreFromReports(0), 0);
  });

  test('it rises with reports', () => {
    const one = scoreFromReports(1);
    const five = scoreFromReports(5);
    const twenty = scoreFromReports(20);
    assert.ok(one > 0 && one < five);
    assert.ok(five < twenty);
  });

  test('it never reaches certainty', () => {
    // A score of 100 from reports alone would let a campaign of bad-faith
    // reports look like proof.
    for (const n of [50, 500, 50_000]) {
      assert.ok(scoreFromReports(n) <= 95, `score for ${n} should stay under 95`);
    }
  });

  test('it is monotonic — more reports never means less risk', () => {
    let previous = -1;
    for (let n = 0; n <= 60; n += 1) {
      const score = scoreFromReports(n);
      assert.ok(score >= previous, `score fell at ${n}`);
      previous = score;
    }
  });

  test('a negative count is treated as none', () => {
    assert.equal(scoreFromReports(-5), 0);
  });
});

// ---------------------------------------------------------------- freshness --

describe('freshness', () => {
  const now = 1_800_000_000_000;
  const day = 86_400_000;

  test('an hour old is fresh', () => {
    assert.equal(freshnessOf(now - 3_600_000, now), 'FRESH');
  });

  test('a month old is recent', () => {
    assert.equal(freshnessOf(now - 30 * day, now), 'RECENT');
  });

  test('a year old is stale', () => {
    assert.equal(freshnessOf(now - 365 * day, now), 'STALE');
  });

  test('no timestamp is unknown, not fresh', () => {
    assert.equal(freshnessOf(0, now), 'UNKNOWN');
  });

  test('a timestamp in the future is unknown rather than trusted', () => {
    // Clock skew is real, and "fresh" for something that has not happened yet is
    // a bug that would make a stale record look new.
    assert.equal(freshnessOf(now + 10 * day, now), 'UNKNOWN');
  });
});

// ------------------------------------------------------------------- lookup --

describe('the lookup response', () => {
  const number = '+919876543210';

  test('nothing known says so, rather than returning a blank card', () => {
    const out = buildLookup(number, { nameRow: null, reportCount: 0, lastUpdated: 0 });
    assert.equal(out.source, 'NONE');
    assert.equal(out.displayName, null);
    assert.ok(out.unavailableReason, 'a reason is required when nothing is known');
  });

  test('a community suggestion is never returned as a name', () => {
    // **The rule this whole service exists to keep.** A suggestion is not a fact.
    const out = buildLookup(number, {
      nameRow: { name: 'Ramesh', source: 'community', confidence: 40 },
      reportCount: 0,
      lastUpdated: Date.now(),
    });
    assert.equal(out.displayName, null, 'a community name must not be shown');
    assert.equal(out.source, 'NONE');
  });

  test('a business listing is returned, and marked as a business', () => {
    const out = buildLookup(number, {
      nameRow: { name: 'Sunrise Diagnostics', source: 'business', confidence: 95, category: 'Healthcare' },
      reportCount: 0,
      lastUpdated: Date.now(),
    });
    assert.equal(out.displayName, 'Sunrise Diagnostics');
    assert.equal(out.source, 'BUSINESS');
    assert.equal(out.businessCategory, 'Healthcare');
  });

  test('a self-declared name is the only one called verified', () => {
    const out = buildLookup(number, {
      nameRow: { name: 'Amit', source: 'self_declared', confidence: 100 },
      reportCount: 0,
      lastUpdated: Date.now(),
    });
    assert.equal(out.verificationStatus, 'VERIFIED');
  });

  test('a business listing is not called verified', () => {
    const out = buildLookup(number, {
      nameRow: { name: 'A Clinic', source: 'business', confidence: 95 },
      reportCount: 0,
      lastUpdated: Date.now(),
    });
    assert.equal(out.verificationStatus, 'UNVERIFIED');
  });

  test('reports alone produce a risk score and no name', () => {
    const out = buildLookup(number, { nameRow: null, reportCount: 12, lastUpdated: Date.now() });
    assert.equal(out.displayName, null);
    assert.ok(out.spamRisk > 0);
    assert.equal(out.reports, 12);
    assert.equal(out.unavailableReason, null, 'a score is something known');
  });

  test('every publishable source maps to something the app understands', () => {
    for (const source of PUBLISHABLE) {
      const out = buildLookup(number, {
        nameRow: { name: 'X', source, confidence: CONFIDENCE[source] },
        reportCount: 0,
        lastUpdated: Date.now(),
      });
      assert.ok(['CONTACT', 'BUSINESS'].includes(out.source), `${source} gave ${out.source}`);
      assert.equal(out.displayName, 'X');
    }
  });
});

// ------------------------------------------------------------------- reports --

describe('validating a report', () => {
  test('a good report passes', () => {
    const out = validateReport({ number: '+919876543210', category: 'telemarketing' });
    assert.equal(out.number, '+919876543210');
    assert.equal(out.category, 'telemarketing');
  });

  test('an unknown category is refused, not silently filed as other', () => {
    // Filing it as "other" would hide a typo forever.
    const out = validateReport({ number: '+919876543210', category: 'nonsense' });
    assert.ok(out.error);
  });

  test('every advertised category is accepted', () => {
    for (const category of CATEGORIES) {
      const out = validateReport({ number: '+919876543210', category });
      assert.ok(!out.error, `${category} was refused`);
    }
  });

  test('a bad number is refused', () => {
    assert.ok(validateReport({ number: 'nope', category: 'fraud' }).error);
  });

  test('a long note is bounded rather than rejected', () => {
    // Losing the report over a long note is worse than truncating it.
    const out = validateReport({ number: '+919876543210', category: 'fraud', note: 'x'.repeat(2000) });
    assert.equal(out.note.length, 500);
  });

  test('a non-object body is refused', () => {
    for (const bad of [null, 'string', 42]) {
      assert.ok(validateReport(bad).error);
    }
  });
});
