// Story 2.25 (Task 3 — AC7) — announcement-vocabulary discipline gate.
//
// Enforces that every `aria-live` region sources its announced text from the
// shared vocabulary (src/lib/a11y/announcements.ts), mirroring the node --test
// style of check:contrast / check:routes (no TS loader — textual scan).
//
// The rule, allowlist-based:
//   • A COMPOSITE (src/features/**) that renders an `aria-live=` region MUST
//     import from '@/lib/a11y/announcements' — composites never inline workflow
//     announcement text.
//   • A PRIMITIVE (src/components/{feedback,overlays,actions}/**) that renders an
//     `aria-live=` region MUST be on the ALLOWLIST below — its live text comes
//     from a sanctioned non-vocabulary source (the STATE_SIGNIFIERS presentation
//     vocabulary, a typed variant-default, or consumer-passed content), with a
//     documented reason. A NEW primitive live region fails until justified here.
//
// Run: `npm run check:a11y`.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, relative, sep } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const frontendRoot = resolve(here, '../../..');
const srcRoot = resolve(frontendRoot, 'src');

/** The dirs whose `aria-live` regions this gate governs (AC7). */
const SCAN_ROOTS = [
  'src/features',
  'src/components/feedback',
  'src/components/overlays',
  'src/components/actions',
];

/**
 * Primitive files allowed to carry an `aria-live=` region WITHOUT importing the
 * vocabulary. Each renders generic, centralized, or consumer-passed text — not
 * workflow-lifecycle announcements. Adding a NEW primitive live region requires a
 * deliberate entry here (with a reason), which is the point of the gate.
 */
const PRIMITIVE_ALLOWLIST = new Map([
  [
    'src/components/feedback/primitives/InlineFeedback.tsx',
    'live text = STATE_SIGNIFIERS label + consumer-authored children (generic primitive)',
  ],
  [
    'src/components/feedback/primitives/PersistentStateBadge.tsx',
    'live text = STATE_SIGNIFIERS label (the 2.3 signifier vocabulary)',
  ],
  [
    'src/components/feedback/states/LoadingState.tsx',
    'live text = typed variant default (the 4-meaning split) or consumer message',
  ],
  [
    'src/components/feedback/states/ErrorState.tsx',
    'live text = typed variant default or consumer message (generic primitive)',
  ],
  [
    'src/components/actions/GovernedButton.tsx',
    'live region = outcome checkmark + consumer children (no inlined announcement string)',
  ],
]);

const VOCAB_IMPORT = '@/lib/a11y/announcements';

/** Recursively collect non-test .ts/.tsx files under `dir`. */
function collectSourceFiles(dir) {
  /** @type {string[]} */
  const out = [];
  if (!existsSync(dir)) {
    return out;
  }
  for (const entry of readdirSync(dir)) {
    const full = resolve(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === '__tests__' || entry === 'node_modules') {
        continue;
      }
      out.push(...collectSourceFiles(full));
      continue;
    }
    if (!/\.(ts|tsx)$/.test(entry) || /\.test\.(ts|tsx)$/.test(entry)) {
      continue;
    }
    out.push(full);
  }
  return out;
}

/** POSIX-style path relative to the frontend root (stable across OSes). */
function relPosix(full) {
  return relative(frontendRoot, full).split(sep).join('/');
}

/** Files (relPosix) under the scan roots that render an `aria-live=` attribute. */
function filesWithLiveRegions() {
  /** @type {string[]} */
  const found = [];
  for (const root of SCAN_ROOTS) {
    for (const full of collectSourceFiles(resolve(frontendRoot, root))) {
      const src = readFileSync(full, 'utf8');
      // Match the JSX ATTRIBUTE (`aria-live=`), not prose mentions in comments.
      if (/aria-live=/.test(src)) {
        found.push(relPosix(full));
      }
    }
  }
  return found.sort();
}

test('the announcement vocabulary module exists and exports its core entries', () => {
  const vocabPath = resolve(srcRoot, 'lib/a11y/announcements.ts');
  assert.ok(existsSync(vocabPath), 'src/lib/a11y/announcements.ts is missing');
  const src = readFileSync(vocabPath, 'utf8');
  // A representative set spanning queue / clarification / decision lifecycles
  // (AC7's named examples are illustrative; these are the ones consumers use).
  const required = [
    'queueLoading',
    'queueEmpty',
    'queueFilteredEmpty',
    'queueLoaded',
    'clarificationAdvanced',
    'clarificationsAdvanced',
    'decisionOptionsLoadFailed',
    'decisionStale',
    'decisionSubmitFailed',
    'specApproved',
    'specRejected',
  ];
  const missing = required.filter(
    (name) => !new RegExp(`export (const|function) ${name}\\b`).test(src),
  );
  assert.deepEqual(missing, [], `announcements.ts is missing exports: ${missing.join(', ')}`);
});

test('every composite aria-live region imports the shared vocabulary (AC7)', () => {
  const offenders = [];
  for (const rel of filesWithLiveRegions()) {
    if (!rel.startsWith('src/features/')) {
      continue;
    }
    const src = readFileSync(resolve(frontendRoot, rel), 'utf8');
    if (!src.includes(VOCAB_IMPORT)) {
      offenders.push(rel);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `composite(s) with an aria-live region must import from '${VOCAB_IMPORT}': ${offenders.join(', ')}`,
  );
});

test('every primitive aria-live region is on the documented allowlist (AC7)', () => {
  const unlisted = [];
  for (const rel of filesWithLiveRegions()) {
    if (rel.startsWith('src/features/')) {
      continue;
    }
    if (!PRIMITIVE_ALLOWLIST.has(rel)) {
      unlisted.push(rel);
    }
  }
  assert.deepEqual(
    unlisted,
    [],
    `primitive(s) with an aria-live region must be justified in PRIMITIVE_ALLOWLIST: ${unlisted.join(', ')}`,
  );
});

test('the allowlist has no stale entries (every listed file still has a live region)', () => {
  const live = new Set(filesWithLiveRegions());
  const stale = [...PRIMITIVE_ALLOWLIST.keys()].filter((rel) => !live.has(rel));
  assert.deepEqual(
    stale,
    [],
    `allowlist entries no longer carry an aria-live region: ${stale.join(', ')}`,
  );
});
