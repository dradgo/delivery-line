/**
 * Story 2.6 (AC7, Task 7) — UUIDv7 idempotency-key minting.
 */
import { describe, expect, it } from 'vitest';

import { newIdempotencyKey } from './idempotency';

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

describe('newIdempotencyKey', () => {
  it('emits a well-formed UUID string', () => {
    expect(newIdempotencyKey()).toMatch(UUID_SHAPE);
  });

  it('sets the version nibble to 7 (NOT v4 — distinct from the correlation minter)', () => {
    for (let i = 0; i < 50; i++) {
      // 15th hex char (index 14) is the version nibble.
      expect(newIdempotencyKey()[14]).toBe('7');
    }
  });

  it('sets the RFC-4122 variant bits (8/9/a/b)', () => {
    for (let i = 0; i < 50; i++) {
      // 20th hex char (index 19) carries the variant.
      expect('89ab').toContain(newIdempotencyKey()[19]);
    }
  });

  it('is collision-free across many mints', () => {
    const keys = new Set(Array.from({ length: 1000 }, () => newIdempotencyKey()));
    expect(keys.size).toBe(1000);
  });

  it('is time-ordered — later keys sort lexicographically after earlier ones', () => {
    const a = newIdempotencyKey();
    // Same millisecond is possible, so only assert non-decreasing prefix ordering
    // by comparing the time-prefix portion across a small spread.
    const b = newIdempotencyKey();
    expect(a.slice(0, 8) <= b.slice(0, 8)).toBe(true);
  });
});
