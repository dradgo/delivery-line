/**
 * Story 2.6 (AC3, Task 7a) — query-key factory stability + prefix contract.
 */
import { describe, expect, it } from 'vitest';

import { workflowKeys, type WorkflowListFilters } from './workflowKeys';

describe('workflowKeys', () => {
  it('exposes every AC3-mandated member', () => {
    expect(workflowKeys.all).toEqual(['workflows']);
    expect(typeof workflowKeys.list).toBe('function');
    expect(typeof workflowKeys.detail).toBe('function');
    expect(typeof workflowKeys.events).toBe('function');
    expect(typeof workflowKeys.artifacts).toBe('function');
    expect(typeof workflowKeys.artifact).toBe('function');
    expect(typeof workflowKeys.allowedActions).toBe('function');
  });

  it('produces stable keys for equal inputs', () => {
    expect(workflowKeys.detail('run_abcd')).toEqual(workflowKeys.detail('run_abcd'));
    expect(workflowKeys.list({ state: 'Executing' })).toEqual(
      workflowKeys.list({ state: 'Executing' }),
    );
  });

  it('normalizes list filters so undefined fields and key order do not matter', () => {
    expect(workflowKeys.list({})).toEqual(workflowKeys.list(undefined));
    // An explicit `state: undefined` (built dynamically — exactOptionalPropertyTypes
    // forbids the literal) normalizes to the same key as an absent filter.
    const withExplicitUndefined: WorkflowListFilters = {};
    Object.assign(withExplicitUndefined, { state: undefined });
    expect(workflowKeys.list(withExplicitUndefined)).toEqual(workflowKeys.list({}));
  });

  it('makes detail(id) a structural PREFIX of events/artifacts/allowedActions(id)', () => {
    const id = 'run_abcd';
    const detail = workflowKeys.detail(id);
    for (const sub of [
      workflowKeys.events(id),
      workflowKeys.artifacts(id),
      workflowKeys.allowedActions(id),
    ]) {
      expect(sub.slice(0, detail.length)).toEqual([...detail]);
      expect(sub.length).toBeGreaterThan(detail.length);
    }
  });

  it('keeps different runs collision-free', () => {
    expect(workflowKeys.detail('run_aaaa')).not.toEqual(workflowKeys.detail('run_bbbb'));
    expect(workflowKeys.events('run_aaaa')).not.toEqual(workflowKeys.detail('run_aaaa'));
  });

  it('keys artifact() by artifact id, distinct from run detail', () => {
    expect(workflowKeys.artifact('art_abcd')).toEqual(['workflows', 'artifact', 'art_abcd']);
  });
});
