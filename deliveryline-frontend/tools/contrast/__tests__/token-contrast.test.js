// Story 2.3 AC4 — automated WCAG contrast gate over the SHIPPED globals.css.
// node --test (NOT Vitest — that arrives in 2.27), mirroring the lint:rules-test
// tooling pattern that is wired into frontend-maven-plugin / CI.
//
// What it checks (every pair drawn from the parsed :root tokens, never a copy):
//   • text-on-fill pairs  → >= 4.5:1 (WCAG AA body text)
//   • de-emphasized text  → >= 3:1   (--text-tertiary, large/secondary text)
//   • state borders       → >= 3:1   vs --background (WCAG 1.4.11 component boundary)
// Plus a negative self-test proving the gate actually fails sub-threshold pairs,
// so it can never silently become a no-op (same discipline as 2.2 Task 4).
import test from 'node:test';
import assert from 'node:assert/strict';
import { contrastRatio } from '../contrast.js';
import { parseRootColorVars } from '../parse-globals.js';

const AA_BODY = 4.5;
const AA_LARGE = 3.0;

const vars = parseRootColorVars();
const value = (name) => {
  const v = vars.get(name);
  assert.ok(v, `expected token ${name} to exist in globals.css :root`);
  return v;
};

/** text-tertiary is de-emphasized / large-text only → 3:1 threshold. */
const LARGE_TEXT_FOREGROUNDS = new Set(['--text-tertiary']);

test('every text-on-fill pair meets its WCAG threshold', () => {
  /** @type {Array<[string, string, number]>} fg, bg, min */
  const pairs = [
    ['--foreground', '--background', AA_BODY],
    ['--text-primary', '--background', AA_BODY],
    ['--text-secondary', '--background', AA_BODY],
    ['--text-tertiary', '--background', AA_LARGE],
  ];

  // Every `--X-foreground` pairs with its base `--X` fill (covers shadcn
  // card/popover/primary/secondary/muted/accent/destructive AND every
  // --state-*-foreground and --state-*-hc-foreground).
  const structuralFailures = [];
  for (const name of vars.keys()) {
    if (!name.endsWith('-foreground') || name === '--foreground') {
      continue;
    }
    if (LARGE_TEXT_FOREGROUNDS.has(name)) {
      continue;
    }
    const base = name.slice(0, -'-foreground'.length);
    if (!vars.has(base)) {
      structuralFailures.push(`missing base token ${base} for foreground token ${name}`);
      continue;
    }
    pairs.push([name, base, AA_BODY]);
  }

  const failures = [];
  for (const [fg, bg, min] of pairs) {
    const ratio = contrastRatio(value(fg), value(bg));
    if (ratio < min) {
      failures.push(`${fg} on ${bg}: ${ratio.toFixed(2)}:1 (need ${min}:1)`);
    }
  }
  assert.deepEqual(
    structuralFailures.concat(failures),
    [],
    `contrast failures:\n  ${structuralFailures.concat(failures).join('\n  ')}`,
  );
});

test('every state border is >= 3:1 against the page background (WCAG 1.4.11)', () => {
  const bg = value('--background');
  const failures = [];
  for (const [name, triplet] of vars) {
    if (!/^--state-[\w-]+-border$/.test(name)) {
      continue;
    }
    const ratio = contrastRatio(triplet, bg);
    if (ratio < AA_LARGE) {
      failures.push(`${name} vs --background: ${ratio.toFixed(2)}:1 (need ${AA_LARGE}:1)`);
    }
  }
  assert.deepEqual(failures, [], `state-border contrast failures:\n  ${failures.join('\n  ')}`);
});

test('negative self-test — the gate fails a known sub-threshold pair', () => {
  // Light gray on white is ~1.2:1; if this ever "passes" 4.5, the math is broken
  // and the gate above is a no-op.
  const ratio = contrastRatio('0 0% 90%', '0 0% 100%');
  assert.ok(ratio < AA_BODY, `expected sub-threshold pair to be < ${AA_BODY}, got ${ratio}`);
  // And a known-good pair must clear it.
  assert.ok(contrastRatio('0 0% 0%', '0 0% 100%') >= AA_BODY);
});
