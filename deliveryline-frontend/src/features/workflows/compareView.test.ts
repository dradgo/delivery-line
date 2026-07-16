/**
 * Story 4.20 (AC3/AC4) — pure unit coverage for the Compare-Mode read model.
 */
import { describe, expect, it } from 'vitest';

import { ProblemDetailsError } from '@/lib/api/problemDetails';

import {
  compareLayout,
  normalizeRevisionDelta,
  resolveCompareState,
  type CompareState,
} from './compareView';
import type { RevisionDelta } from './hooks/useRevisionDelta';

function problem(code: string, status: number): ProblemDetailsError {
  return new ProblemDetailsError({
    type: 'about:blank',
    title: code,
    status,
    detail: '',
    instance: '',
    code,
    retryable: status === 503,
    correlationId: null,
  });
}

describe('normalizeRevisionDelta', () => {
  it('narrows a spec delta with markdown blocks + defaults absent counts to 0', () => {
    const raw: RevisionDelta = {
      artifactType: 'spec',
      revisionA: { version: 1, producedByActor: 'runner', createdAt: '2026-07-01T00:00:00Z' },
      revisionB: { version: 2, producedByActor: null, createdAt: '2026-07-02T00:00:00Z' },
      summary: { changedRegionCount: 2 },
      noMeaningfulDiff: false,
      changes: [
        {
          blockType: 'markdown',
          changeKind: 'modified',
          sectionPath: 'Overview',
          priorText: 'a',
          currentText: 'b',
        },
      ],
      linkedDiffReferences: null,
    };

    const view = normalizeRevisionDelta(raw);
    expect(view.artifactType).toBe('spec');
    expect(view.revisionA.version).toBe(1);
    expect(view.revisionB.producedByActor).toBeNull();
    expect(view.summary.changedRegionCount).toBe(2);
    expect(view.summary.addedCount).toBe(0);
    expect(view.blocks).toHaveLength(1);
    const block = view.blocks[0];
    expect(block?.kind).toBe('markdown');
    if (block?.kind === 'markdown') {
      expect(block.sectionPath).toBe('Overview');
      expect(block.currentText).toBe('b');
    }
  });

  it('narrows planStep + file blocks and skips unknown blockTypes', () => {
    const raw: RevisionDelta = {
      artifactType: 'prOutput',
      changes: [
        {
          blockType: 'planStep',
          changeKind: 'reordered',
          stepId: 's1',
          priorStepText: 'x',
          currentStepText: 'x',
          priorStepOrder: 2,
          currentStepOrder: 1,
        },
        {
          blockType: 'file',
          changeKind: 'added',
          filePath: 'a.ts',
          addedLines: 5,
          removedLines: 0,
        },
        { blockType: 'somethingNew', changeKind: 'modified' },
      ],
      linkedDiffReferences: ['art_a', 'art_b'],
    };

    const view = normalizeRevisionDelta(raw);
    // The unknown blockType is skipped — never crashes, never rendered.
    expect(view.blocks).toHaveLength(2);
    expect(view.blocks[0]?.kind).toBe('planStep');
    expect(view.blocks[1]?.kind).toBe('file');
    expect(view.linkedDiffReferences).toEqual(['art_a', 'art_b']);
  });

  it('coerces an unknown changeKind to modified and an unknown artifactType to spec', () => {
    const raw: RevisionDelta = {
      artifactType: 'mystery',
      changes: [{ blockType: 'markdown', changeKind: 'exploded', currentText: 'c' }],
    };
    const view = normalizeRevisionDelta(raw);
    expect(view.artifactType).toBe('spec');
    expect(view.blocks[0]?.changeKind).toBe('modified');
  });

  it('treats absent changes as an empty block list and absent noMeaningfulDiff as false', () => {
    const view = normalizeRevisionDelta({});
    expect(view.blocks).toEqual([]);
    expect(view.noMeaningfulDiff).toBe(false);
    expect(view.linkedDiffReferences).toBeNull();
  });
});

describe('compareLayout', () => {
  it('maps spec/implementationPlan to side-by-side and prOutput to stacked', () => {
    expect(compareLayout('spec')).toBe('side-by-side');
    expect(compareLayout('implementationPlan')).toBe('side-by-side');
    expect(compareLayout('prOutput')).toBe('stacked');
  });
});

describe('resolveCompareState', () => {
  const base = { isError: false, isLoading: false, delta: undefined, error: undefined };

  it('returns no-baseline first when no prior-version id is resolvable (OQ-2)', () => {
    expect(resolveCompareState({ ...base, hasBaseline: false })).toBe('no-baseline');
    // ...even mid-load or mid-error the missing baseline wins (the query never fires).
    expect(resolveCompareState({ ...base, hasBaseline: false, isLoading: true })).toBe(
      'no-baseline',
    );
  });

  it('sub-classifies errors by the live 4.19 ProblemDetails codes', () => {
    const cases: Array<[ProblemDetailsError | Error, CompareState]> = [
      [problem('ARTIFACT_RECORD_NOT_FOUND', 404), 'no-baseline'],
      [problem('ARTIFACT_PAYLOAD_UNAVAILABLE', 503), 'partial'],
      [problem('ARTIFACT_LINEAGE_MISMATCH', 400), 'unavailable'],
      [problem('INVALID_ID_PREFIX', 400), 'unavailable'],
      [new Error('network down'), 'unavailable'],
    ];
    for (const [error, expected] of cases) {
      expect(resolveCompareState({ ...base, hasBaseline: true, isError: true, error })).toBe(
        expected,
      );
    }
  });

  it('resolves loading → no-meaningful-diff → default in precedence', () => {
    expect(resolveCompareState({ ...base, hasBaseline: true, isLoading: true })).toBe('loading');
    expect(
      resolveCompareState({ ...base, hasBaseline: true, delta: { noMeaningfulDiff: true } }),
    ).toBe('no-meaningful-diff');
    expect(
      resolveCompareState({ ...base, hasBaseline: true, delta: { noMeaningfulDiff: false } }),
    ).toBe('default');
  });

  it('treats an enabled-but-unresolved delta as loading', () => {
    expect(resolveCompareState({ ...base, hasBaseline: true, delta: undefined })).toBe('loading');
  });
});
