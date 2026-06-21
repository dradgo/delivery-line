/**
 * Story 3c-9 (Task 2) — `projectKeys` factory contract.
 *
 * The `src/lib/queryKeys/**` path carries a 90% coverage floor (vitest.config.ts),
 * so the factory is exercised exhaustively: every member's shape + the hierarchical
 * prefix invariant the invalidation cascade relies on.
 */
import { describe, expect, it } from 'vitest';

import { projectKeys } from './projectKeys';

describe('projectKeys', () => {
  it('roots every key under ["projects"]', () => {
    expect(projectKeys.all).toEqual(['projects']);
  });

  it('lists() / list() both namespace under the root', () => {
    expect(projectKeys.lists()).toEqual(['projects', 'list']);
    // The list is unfiltered today, so list() collapses onto lists() (R3).
    expect(projectKeys.list()).toEqual(['projects', 'list']);
  });

  it('details() namespaces detail queries', () => {
    expect(projectKeys.details()).toEqual(['projects', 'detail']);
  });

  it('detail(id) extends details() with the project id', () => {
    expect(projectKeys.detail('prj_abc123')).toEqual(['projects', 'detail', 'prj_abc123']);
  });

  it('detail(id) is a structural PREFIX of details() — the invalidation cascade invariant', () => {
    const details = projectKeys.details();
    const detail = projectKeys.detail('prj_abc123');
    expect(detail.slice(0, details.length)).toEqual([...details]);
  });

  it('distinct ids yield distinct, non-aliasing detail keys', () => {
    expect(projectKeys.detail('prj_a')).not.toEqual(projectKeys.detail('prj_b'));
  });
});
