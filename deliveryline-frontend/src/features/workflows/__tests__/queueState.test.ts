/**
 * Story 2.20 (AC1/AC7) — `resolveQueueState` precedence is exhaustive + pure.
 */
import { describe, expect, it } from 'vitest';

import { resolveQueueState, type ResolveQueueStateInput } from '../queueState';

const base: ResolveQueueStateInput = {
  isError: false,
  isPending: false,
  count: 0,
  filtersActive: false,
};

describe('resolveQueueState', () => {
  it('error wins over every other signal (even while pending or with rows)', () => {
    expect(resolveQueueState({ ...base, isError: true, isPending: true })).toBe('error');
    expect(resolveQueueState({ ...base, isError: true, count: 5 })).toBe('error');
  });

  it('loading when pending and not errored', () => {
    expect(resolveQueueState({ ...base, isPending: true })).toBe('loading');
  });

  it('empty when zero rows and no filters active', () => {
    expect(resolveQueueState({ ...base, count: 0, filtersActive: false })).toBe('empty');
  });

  it('filtered-empty when zero rows and filters active (Trap T6)', () => {
    expect(resolveQueueState({ ...base, count: 0, filtersActive: true })).toBe('filtered-empty');
  });

  it('populated when at least one row', () => {
    expect(resolveQueueState({ ...base, count: 1 })).toBe('populated');
    expect(resolveQueueState({ ...base, count: 3, filtersActive: true })).toBe('populated');
  });
});
