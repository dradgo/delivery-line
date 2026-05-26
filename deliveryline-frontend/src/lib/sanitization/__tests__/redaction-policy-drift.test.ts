/// <reference types="node" />
/**
 * Story 2.24 AC16 — frontend cross-side drift contract test.
 *
 * Asserts that `deliveryline-frontend/src/lib/sanitization/redaction-policy.generated.json`
 * is byte-for-byte identical to the canonical runner-contracts source at
 * `deliveryline-runner-contracts/src/main/resources/runner-contracts/redaction-policy.json`.
 *
 * Drift in either direction fails CI per AC8. The regen scripts at
 * `scripts/regen-redaction-policy.{sh,ps1}` are pure copies; if this test
 * fails the fix is `./scripts/regen-redaction-policy.sh` (or `.ps1`) followed
 * by a commit of the updated mirror.
 *
 * Code review P3 (2026-05-26) — closes the AC16 frontend-side gap; previously
 * only Java-side parity tests existed. The triple-slash node reference is
 * scoped to this file so we don't widen the production tsconfig's `types`.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND_MIRROR = resolve(HERE, '..', 'redaction-policy.generated.json');
const CANONICAL_SOURCE = resolve(
  HERE,
  '..',
  '..',
  '..',
  '..',
  '..',
  'deliveryline-runner-contracts',
  'src',
  'main',
  'resources',
  'runner-contracts',
  'redaction-policy.json',
);

function readNormalized(path: string): string {
  // Normalize line endings so a Windows checkout doesn't fail vs Linux CI.
  return readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
}

describe('AC16 — frontend redaction-policy mirror parity', () => {
  it('matches the canonical runner-contracts source byte-for-byte', () => {
    const mirror = readNormalized(FRONTEND_MIRROR);
    const canonical = readNormalized(CANONICAL_SOURCE);
    expect(
      mirror,
      `\n${FRONTEND_MIRROR} has drifted from\n${CANONICAL_SOURCE}.\n` +
        'Run scripts/regen-redaction-policy.{sh,ps1} from the repository root\n' +
        'to copy the canonical source into the frontend mirror, then commit\n' +
        'both files together.',
    ).toBe(canonical);
  });

  it('parses as JSON in both files', () => {
    const mirror = JSON.parse(readNormalized(FRONTEND_MIRROR));
    const canonical = JSON.parse(readNormalized(CANONICAL_SOURCE));
    expect(mirror).toEqual(canonical);
  });
});
