// Story 2.3 AC5 — token <-> signifier parity gate. "Enforced in component-test
// fixtures" satisfied at the token layer (before composites exist) via node --test.
//
// Asserts:
//   (a) every --state-{name} group in globals.css has a signifier entry, and
//       every signifier entry has a matching token group (no orphans either way);
//   (b) every entry has a non-empty `icon` (lucide-react name) and `label`.
//
// The signifier map lives in TypeScript (src/lib/state-signifiers.ts). node --test
// has no TS loader (Vitest is 2.27), so we parse the STATE_SIGNIFIERS object
// literal textually — robust because it is a flat object of string fields.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { parseStateNames } from '../parse-globals.js';

const here = dirname(fileURLToPath(import.meta.url));
const signifiersPath = resolve(here, '../../../src/lib/state-signifiers.ts');

/** Extract { name -> { icon, label } } from the STATE_SIGNIFIERS object literal. */
function parseSignifiers() {
  const src = readFileSync(signifiersPath, 'utf8');
  const start = src.indexOf('STATE_SIGNIFIERS');
  assert.ok(start !== -1, 'STATE_SIGNIFIERS not found in state-signifiers.ts');
  const open = src.indexOf('{', start);
  const close = src.indexOf('};', open);
  const body = src.slice(open + 1, close);

  /** @type {Record<string, { icon: string, label: string }>} */
  const out = {};
  // key (bare or 'quoted') : { icon: 'X', label: 'Y' ... }
  const entryRe = /(?:'([\w-]+)'|([\w-]+))\s*:\s*\{([^}]*)\}/g;
  let m;
  while ((m = entryRe.exec(body)) !== null) {
    const name = m[1] ?? m[2];
    const fields = m[3];
    const icon = /icon\s*:\s*'([^']*)'/.exec(fields);
    const label = /label\s*:\s*'([^']*)'/.exec(fields);
    out[name] = { icon: icon ? icon[1] : '', label: label ? label[1] : '' };
  }
  return out;
}

test('signifier entries and globals.css state tokens are in 1:1 parity', () => {
  const tokenStates = new Set(parseStateNames());
  const signifiers = parseSignifiers();
  const signifierStates = new Set(Object.keys(signifiers));

  assert.ok(tokenStates.size === 12, `expected 12 state token groups, found ${tokenStates.size}`);

  const orphanTokens = [...tokenStates].filter((s) => !signifierStates.has(s));
  const orphanSignifiers = [...signifierStates].filter((s) => !tokenStates.has(s));
  assert.deepEqual(
    orphanTokens,
    [],
    `state tokens missing a signifier: ${orphanTokens.join(', ')}`,
  );
  assert.deepEqual(
    orphanSignifiers,
    [],
    `signifiers with no matching token: ${orphanSignifiers.join(', ')}`,
  );
});

test('every signifier has a non-empty icon and label', () => {
  const signifiers = parseSignifiers();
  const bad = [];
  for (const [name, { icon, label }] of Object.entries(signifiers)) {
    if (!icon || !label) {
      bad.push(`${name} (icon="${icon}", label="${label}")`);
    }
  }
  assert.deepEqual(bad, [], `signifiers with empty icon/label: ${bad.join('; ')}`);
});
