// Story 2.4 (Q2) — token-presence/shape gate over the SHIPPED globals.css.
// node --test (NOT Vitest — that arrives in 2.27), mirroring the lint:rules-test
// + check:contrast tooling pattern wired into frontend-maven-plugin / CI.
//
// What it checks (read from the single source of truth, never a JS/JSON copy):
//   • presence  — every required typography/focus token exists in :root
//   • shape     — sizes carry length units, weights are 100-900 multiples of 100,
//                 leading is numeric (relaxed >= 1.5), --ring-focus is an HSL triplet
//   • .prose    — encodes a ch-based max-width in the 45-75 readable band AND a
//                 line-height >= 1.5 (resolving var(--leading-*) against :root)
// Plus a negative self-test proving the gate fails on a missing/mis-shaped token
// and a broken .prose, so it can never silently become a no-op (2.3 / 2.2 Task 4
// discipline).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { parseRootVars, GLOBALS_CSS_PATH } from '../../contrast/parse-globals.js';

const SIZE_TOKENS = [
  '--text-xs',
  '--text-sm',
  '--text-base',
  '--text-lg',
  '--text-xl',
  '--text-2xl',
];
const LEADING_TOKENS = ['--leading-tight', '--leading-normal', '--leading-relaxed'];
const WEIGHT_TOKENS = ['--weight-regular', '--weight-medium', '--weight-semibold', '--weight-bold'];

const LENGTH_UNIT = /(?:rem|em|px)$/;
const HSL_TRIPLET = /^[\d.]+\s+[\d.]+%\s+[\d.]+%$/;

/**
 * Resolve a CSS value that may be a `var(--token)` reference against the parsed
 * :root map; returns the raw string for non-var values.
 * @param {string} value
 * @param {Map<string,string>} vars
 */
function resolveVar(value, vars) {
  const m = /^var\(\s*(--[\w-]+)\s*\)$/.exec(value.trim());
  if (!m) {
    return value.trim();
  }
  return (vars.get(m[1]) ?? '').trim();
}

/**
 * Validate the presence + shape of the required tokens. Pure (operates on the
 * supplied map) so the negative self-test can feed a deliberately broken map.
 * @param {Map<string,string>} vars
 * @returns {string[]} human-readable failures (empty = pass)
 */
export function collectTokenFailures(vars) {
  const failures = [];

  // --font-sans: present + non-empty.
  const fontSans = vars.get('--font-sans');
  if (!fontSans || fontSans.length === 0) {
    failures.push('--font-sans missing or empty');
  }

  // Font-size scale: present + carries a length unit (rem/em/px).
  for (const name of SIZE_TOKENS) {
    const v = vars.get(name);
    if (!v) {
      failures.push(`${name} missing`);
    } else if (!LENGTH_UNIT.test(v)) {
      failures.push(`${name} = "${v}" must end in rem/em/px`);
    }
  }

  // Line-height scale: present + numeric; --leading-relaxed must be >= 1.5 (AC4).
  for (const name of LEADING_TOKENS) {
    const v = vars.get(name);
    if (!v) {
      failures.push(`${name} missing`);
      continue;
    }
    const n = Number.parseFloat(v);
    if (Number.isNaN(n)) {
      failures.push(`${name} = "${v}" is not numeric`);
    }
  }
  const relaxed = Number.parseFloat(vars.get('--leading-relaxed') ?? 'NaN');
  if (!(relaxed >= 1.5)) {
    failures.push(`--leading-relaxed = "${vars.get('--leading-relaxed')}" must be >= 1.5 (AC4)`);
  }

  // Font-weight scale: present + 100-900 multiple of 100.
  for (const name of WEIGHT_TOKENS) {
    const v = vars.get(name);
    if (!v) {
      failures.push(`${name} missing`);
      continue;
    }
    const n = Number.parseInt(v, 10);
    if (Number.isNaN(n) || n < 100 || n > 900 || n % 100 !== 0) {
      failures.push(`${name} = "${v}" must be a 100-900 multiple of 100`);
    }
  }

  // --ring-focus (AC6): present + HSL channel-triplet shape.
  const ringFocus = vars.get('--ring-focus');
  if (!ringFocus) {
    failures.push('--ring-focus missing');
  } else if (!HSL_TRIPLET.test(ringFocus)) {
    failures.push(`--ring-focus = "${ringFocus}" must be an HSL triplet (e.g. "186 70% 30%")`);
  }

  return failures;
}

/**
 * Extract the body of the standalone `.prose { … }` rule (NOT `.prose > * + *`).
 * @param {string} css
 * @returns {string|null}
 */
function extractProseRule(css) {
  const cssWithoutComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
  // `.prose` directly followed by optional whitespace then `{` — the descendant
  // rule `.prose > * + *` has a `>` after the name and is intentionally skipped.
  const m = /\.prose\s*\{([^}]*)\}/.exec(cssWithoutComments);
  return m ? m[1] : null;
}

/**
 * Validate the `.prose` rule's readability constraints (AC4). Pure so the
 * negative self-test can feed broken CSS.
 * @param {string} css
 * @param {Map<string,string>} vars
 * @returns {string[]} failures
 */
export function collectProseFailures(css, vars) {
  const failures = [];
  const block = extractProseRule(css);
  if (block === null) {
    failures.push('.prose rule not found in globals.css');
    return failures;
  }

  const maxWidth = /max-width:\s*([\d.]+)ch/.exec(block);
  if (!maxWidth) {
    failures.push('.prose must declare a ch-based max-width (readable line length, AC4)');
  } else {
    const ch = Number.parseFloat(maxWidth[1]);
    if (!(ch >= 45 && ch <= 75)) {
      failures.push(`.prose max-width ${ch}ch is outside the 45-75ch readable band (AC4)`);
    }
  }

  const lineHeight = /line-height:\s*([^;]+);/.exec(block);
  if (!lineHeight) {
    failures.push('.prose must declare a line-height (AC4)');
  } else {
    const resolved = resolveVar(lineHeight[1], vars);
    const lh = Number.parseFloat(resolved);
    if (!(lh >= 1.5)) {
      failures.push(`.prose line-height "${lineHeight[1].trim()}" resolves to ${lh} (need >= 1.5)`);
    }
  }

  return failures;
}

const vars = parseRootVars();
const css = readFileSync(GLOBALS_CSS_PATH, 'utf8');

test('all required typography + focus tokens exist and are well-shaped', () => {
  const failures = collectTokenFailures(vars);
  assert.deepEqual(failures, [], `token failures:\n  ${failures.join('\n  ')}`);
});

test('.prose encodes a 45-75ch line length and line-height >= 1.5 (AC4)', () => {
  const failures = collectProseFailures(css, vars);
  assert.deepEqual(failures, [], `.prose failures:\n  ${failures.join('\n  ')}`);
});

test('negative self-test — the gate fails a missing/mis-shaped token', () => {
  // Missing --ring-focus + a bad font-size unit + a sub-1.5 relaxed leading.
  const broken = new Map(vars);
  broken.delete('--ring-focus');
  broken.set('--text-base', '16'); // no unit
  broken.set('--leading-relaxed', '1.3'); // < 1.5
  broken.set('--weight-bold', '750'); // not a multiple of 100
  const failures = collectTokenFailures(broken);
  assert.ok(
    failures.length >= 4,
    `expected the gate to catch the broken tokens, got: ${failures.join('; ')}`,
  );
});

test('negative self-test — the gate fails a broken .prose', () => {
  const badCss = '.prose { max-width: 120ch; line-height: 1.2; }';
  const failures = collectProseFailures(badCss, vars);
  assert.ok(
    failures.length >= 2,
    `expected the gate to catch the broken .prose, got: ${failures.join('; ')}`,
  );
});
