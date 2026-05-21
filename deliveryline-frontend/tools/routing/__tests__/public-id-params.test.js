// Story 2.5 (AC2/AC7) — route-param validation gate.
//
// `node --test` (NOT Vitest — that arrives in story 2.27), mirroring the
// lint:rules-test / check:contrast / check:tokens tooling wired into the
// frontend-maven-plugin / CI path. It imports the SHIPPED, framework-free
// `src/lib/routing/publicId.js` DIRECTLY (zero build step — Node 20 cannot strip
// TS types), so it exercises the exact predicates the TanStack Router route tree
// uses. No parallel regex copy to drift (the `tools/contrast/parse-globals.js`
// "single source of truth" discipline).
//
// What it checks:
//   • accept  — well-formed run_/art_ ids of varied legal shapes
//   • reject  — wrong prefix, too-short/too-long suffix, whitespace, injection-y
//               input, empty / non-string, and the bare prefix
//   • negative self-test — a validator loosened to `startsWith` MUST fail the
//     reject suite, so the gate can never silently no-op (2.3/2.4/1.4 discipline)
//   • source guard — the shipped publicId.js still encodes the canonical V1
//     anchored regex, so a future edit that loosens the pattern fails the gate
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import {
  isValidRunId,
  isValidArtifactId,
  matchesPublicId,
  RUN_ID_PATTERN,
  ART_ID_PATTERN,
} from '../../../src/lib/routing/publicId.js';

const here = dirname(fileURLToPath(import.meta.url));
const PUBLIC_ID_JS_PATH = resolve(here, '../../../src/lib/routing/publicId.js');
const ROUTE_TREE_PATH = resolve(here, '../../../src/routeTree.gen.ts');

// 64-char and 65-char suffixes for the length-boundary cases.
const SUFFIX_64 = 'a'.repeat(64);
const SUFFIX_65 = 'a'.repeat(65);

const VALID_RUN_IDS = [
  'run_abcd', // minimum 4-char suffix
  'run_AB-_12', // mixed case + the two special chars
  'run_0123456789', // digits
  `run_${SUFFIX_64}`, // maximum 64-char suffix
];

const VALID_ART_IDS = ['art_abcd', 'art_AB-_12', `art_${SUFFIX_64}`];

// Shared malformed inputs (the prefix is substituted per-validator below).
function rejectsFor(prefix) {
  return [
    `evt_abcd`, // wrong (but registered) prefix
    `x${prefix}_abcd`, // prefix not at the start
    `${prefix}_abc`, // suffix too short (<4)
    `${prefix}_${SUFFIX_65}`, // suffix too long (>64)
    `${prefix}_  `, // whitespace-only suffix
    `${prefix}_abcd `, // trailing whitespace
    ` ${prefix}_abcd`, // leading whitespace
    `${prefix}_'; DROP`, // injection-y input (space + quote rejected)
    `${prefix}_`, // bare prefix, no suffix
    '', // empty string
    prefix, // prefix without underscore
  ];
}

test('accepts well-formed run_ ids', () => {
  for (const id of VALID_RUN_IDS) {
    assert.ok(isValidRunId(id), `expected ${JSON.stringify(id)} to be a valid run id`);
  }
});

test('accepts well-formed art_ ids', () => {
  for (const id of VALID_ART_IDS) {
    assert.ok(isValidArtifactId(id), `expected ${JSON.stringify(id)} to be a valid artifact id`);
  }
});

test('rejects malformed run_ ids', () => {
  for (const id of rejectsFor('run')) {
    assert.ok(!isValidRunId(id), `expected ${JSON.stringify(id)} to be rejected as a run id`);
  }
  // a valid art id is NOT a valid run id (prefix isolation)
  assert.ok(!isValidRunId('art_abcd'));
});

test('rejects malformed art_ ids', () => {
  for (const id of rejectsFor('art')) {
    assert.ok(!isValidArtifactId(id), `expected ${JSON.stringify(id)} to be rejected as an art id`);
  }
  assert.ok(!isValidArtifactId('run_abcd'));
});

test('rejects non-string input without throwing', () => {
  for (const value of [undefined, null, 42, {}, [], NaN, Symbol('run_abcd')]) {
    assert.equal(isValidRunId(value), false);
    assert.equal(isValidArtifactId(value), false);
    assert.equal(matchesPublicId('run', value), false);
  }
});

test('matchesPublicId mirrors the per-prefix validators', () => {
  assert.ok(matchesPublicId('run', 'run_abcd'));
  assert.ok(matchesPublicId('art', 'art_abcd'));
  assert.ok(!matchesPublicId('run', 'art_abcd'));
  assert.ok(!matchesPublicId('art', 'run_abcd'));
});

test('negative self-test — a startsWith-loosened validator fails the reject suite', () => {
  // If a future refactor replaced the anchored-regex check with a prefix
  // `startsWith`, malformed suffixes (too short, whitespace, injection) would
  // sneak through. Prove the reject suite actually catches that regression.
  const loosened = (value) => typeof value === 'string' && value.startsWith('run_');
  const escaped = rejectsFor('run').filter((id) => loosened(id) && !isValidRunId(id));
  assert.ok(
    escaped.length > 0,
    'expected at least one malformed id that startsWith-loosening would wrongly accept',
  );
});

test('source guard — publicId.js still encodes the canonical anchored V1 regex', () => {
  // The shipped module is the source of truth; assert its pattern shape so a silent
  // loosening (dropping the anchors / widening the charset / length) fails the gate.
  const src = readFileSync(PUBLIC_ID_JS_PATH, 'utf8');
  assert.match(src, /\[A-Za-z0-9_-\]\{4,64\}/, 'canonical suffix charset/length must be present');
  assert.match(src, /\^run_/, 'run pattern must be start-anchored to `run_`');
  assert.match(src, /\^art_/, 'art pattern must be start-anchored to `art_`');
  // And the compiled patterns must be anchored at both ends.
  assert.equal(RUN_ID_PATTERN.source.startsWith('^run_'), true);
  assert.equal(RUN_ID_PATTERN.source.endsWith('$'), true);
  assert.equal(ART_ID_PATTERN.source.startsWith('^art_'), true);
  assert.equal(ART_ID_PATTERN.source.endsWith('$'), true);
});

test('route-tree generation guard — routeTree.gen.ts exists, is non-empty, and includes the governed routes', () => {
  const routeTree = readFileSync(ROUTE_TREE_PATH, 'utf8');

  assert.ok(routeTree.trim().length > 0, 'expected routeTree.gen.ts to be non-empty');
  assert.match(
    routeTree,
    /\.\/routes\/workflows\/\$workflowRunId\/artifacts\/\$artifactId'/,
    'expected generated tree to include the artifact viewer route import',
  );
  assert.match(
    routeTree,
    /'\/workflows\/\$workflowRunId\/artifacts\/\$artifactId'/,
    'expected generated tree to include the artifact viewer route path',
  );
});
