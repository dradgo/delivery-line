/**
 * Story 3g-4 (FR74) — `stepTokensView` pure-mapper coverage.
 *
 * Asserts the null-token posture end to end: nullable wire counts coalesce to `undefined` (never
 * `0`), a reported `0` is preserved (data, not absence), and `formatTokenCount` renders a number for
 * a present value and the literal "Not reported" for `undefined`.
 */
import { describe, expect, it } from 'vitest';

import type { StepExecution } from './hooks/useStepExecutions';
import { formatTokenCount, toStepTokenRows } from './stepTokensView';

describe('stepTokensView.toStepTokenRows (story 3g-4)', () => {
  it('maps stage/status/createdAt and passes through reported token counts', () => {
    const steps: StepExecution[] = [
      {
        runnerExecutionId: 'rex_a',
        stage: 'execution',
        status: 'completed',
        createdAt: '2026-07-02T10:00:00Z',
        inputTokens: 100,
        outputTokens: 200,
        totalTokens: 300,
      },
    ];

    const rows = toStepTokenRows(steps);

    expect(rows).toHaveLength(1);
    expect(rows[0]).toEqual({
      runnerExecutionId: 'rex_a',
      stage: 'execution',
      status: 'completed',
      createdAt: '2026-07-02T10:00:00Z',
      inputTokens: 100,
      outputTokens: 200,
      totalTokens: 300,
    });
  });

  it('coalesces null token counts to undefined (never 0)', () => {
    const steps: StepExecution[] = [
      {
        runnerExecutionId: 'rex_b',
        stage: 'execution',
        status: 'running',
        createdAt: '2026-07-02T10:00:00Z',
        inputTokens: null,
        outputTokens: null,
        totalTokens: null,
      },
    ];

    expect(toStepTokenRows(steps)).toEqual([
      expect.objectContaining({
        inputTokens: undefined,
        outputTokens: undefined,
        totalTokens: undefined,
      }),
    ]);
  });

  it('preserves a reported zero (distinct from absent)', () => {
    const steps: StepExecution[] = [
      {
        runnerExecutionId: 'rex_c',
        stage: 'execution',
        status: 'completed',
        createdAt: '2026-07-02T10:00:00Z',
        inputTokens: 0,
        outputTokens: 0,
        totalTokens: 0,
      },
    ];

    expect(toStepTokenRows(steps)).toEqual([
      expect.objectContaining({ inputTokens: 0, outputTokens: 0, totalTokens: 0 }),
    ]);
  });

  it('returns an empty list for undefined input', () => {
    expect(toStepTokenRows(undefined)).toEqual([]);
  });
});

describe('stepTokensView.formatTokenCount (story 3g-4)', () => {
  it('renders the number for a present value', () => {
    expect(formatTokenCount(12345)).toBe('12345');
  });

  it('renders a reported zero as "0", never "Not reported"', () => {
    expect(formatTokenCount(0)).toBe('0');
  });

  it('renders "Not reported" for undefined (never a blank / 0)', () => {
    expect(formatTokenCount(undefined)).toBe('Not reported');
  });
});
