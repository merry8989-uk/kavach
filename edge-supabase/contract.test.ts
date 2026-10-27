// Contract tests: the two server implementations must agree.
//
// The audit's point was that three backends drift. These assertions are the
// cheapest way to notice: if the Cloudflare Worker and the Supabase function
// disagree about which categories exist or which provenance may be published,
// one of them is wrong and the app will behave differently depending on where it
// happens to point.
//
// Run with: deno test edge-supabase/contract.test.ts

import { assertEquals } from 'jsr:@std/assert@1';

const here = new URL('.', import.meta.url).pathname;

async function readLib(): Promise<string> {
  return await Deno.readTextFile(`${here}../edge/src/lib.js`);
}

async function readSupabase(): Promise<string> {
  return await Deno.readTextFile(`${here}index.ts`);
}

Deno.test('both implementations list the same report categories', async () => {
  const lib = await readLib();
  const supabase = await readSupabase();

  const libCats = [...lib.matchAll(/'([a-z]+)',?\s*(?=\n|\])/g)].map((m) => m[1]);
  const block = supabase.split('const CATEGORIES = [')[1].split('];')[0];
  const supCats = [...block.matchAll(/'([a-z]+)'/g)].map((m) => m[1]);

  const canonical = ['telemarketing', 'fraud', 'loan', 'insurance', 'survey', 'robocall', 'silence', 'other'];
  for (const c of canonical) {
    assertEquals(supCats.includes(c), true, `supabase is missing category ${c}`);
  }
  assertEquals(supCats.length, canonical.length, 'supabase has extra categories');
  // libCats is a loose scrape; only assert the canonical set is present in it.
  for (const c of canonical) {
    assertEquals(libCats.includes(c), true, `worker is missing category ${c}`);
  }
});

Deno.test('both publish the same provenance', async () => {
  const lib = await readLib();
  const supabase = await readSupabase();

  for (const source of ['self_declared', 'business']) {
    assertEquals(lib.includes(`'${source}'`), true, `worker is missing ${source}`);
    assertEquals(supabase.includes(`'${source}'`), true, `supabase is missing ${source}`);
  }
  // A community suggestion must never be publishable in either.
  for (const text of [lib, supabase]) {
    const publishable = text.split('PUBLISHABLE')[1]?.slice(0, 120) ?? '';
    assertEquals(publishable.includes("'community'"), false, 'community must not be publishable');
  }
});

Deno.test('the score caps short of certainty in both', async () => {
  const lib = await readLib();
  const supabase = await readSupabase();
  assertEquals(lib.includes('Math.min(') && lib.includes('95'), true, 'worker must cap at 95');
  assertEquals(supabase.includes('Math.min(') && supabase.includes('95'), true, 'supabase must cap at 95');
});

Deno.test('the same report window in both', async () => {
  const lib = await readLib();
  const supabase = await readSupabase();
  assertEquals(lib.includes('REPORT_WINDOW_DAYS = 180'), true, 'worker window drifted');
  assertEquals(supabase.includes('REPORT_WINDOW_DAYS = 180'), true, 'supabase window drifted');
});
