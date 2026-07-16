/**
 * Story 4.20 (AC1, logging) — `CompareModeContainer` maps the `useRevisionDelta` result to the
 * presentational `CompareMode` state and owns the field-only structured logs (code + transport
 * only, NEVER the raw error/body).
 */
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ProblemDetailsError } from '@/lib/api/problemDetails';

vi.mock('../hooks/useRevisionDelta', () => ({ useRevisionDelta: vi.fn() }));
vi.mock('../hooks/useArtifact', () => ({ useArtifact: vi.fn(() => ({ data: undefined })) }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));

import { useRevisionDelta } from '../hooks/useRevisionDelta';
import { CompareModeContainer } from './CompareModeContainer';

const mockUseRevisionDelta = vi.mocked(useRevisionDelta);

function fakeQuery(overrides: Record<string, unknown> = {}) {
  return {
    data: undefined,
    isError: false,
    isFetching: false,
    error: null,
    refetch: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useRevisionDelta>;
}

function problem(code: string, status: number): ProblemDetailsError {
  return new ProblemDetailsError({
    type: 'about:blank',
    title: code,
    status,
    detail: 'sensitive detail that must never be logged',
    instance: '',
    code,
    retryable: status === 503,
    correlationId: null,
  });
}

let warnSpy: ReturnType<typeof vi.spyOn>;
let infoSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
  infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
  mockUseRevisionDelta.mockReturnValue(fakeQuery());
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const IDS = { workflowRunId: 'run_1', artifactIdA: 'art_prior', artifactIdB: 'art_current' };

describe('CompareModeContainer', () => {
  it('maps a resolved delta to the default state and logs compare.opened (ids only)', () => {
    mockUseRevisionDelta.mockReturnValue(
      fakeQuery({
        data: {
          artifactType: 'spec',
          revisionA: { version: 1 },
          revisionB: { version: 2 },
          summary: { changedRegionCount: 1 },
          noMeaningfulDiff: false,
          changes: [{ blockType: 'markdown', changeKind: 'modified', currentText: 'x' }],
        },
      }),
    );
    render(<CompareModeContainer {...IDS} onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'default');
    expect(infoSpy).toHaveBeenCalledWith(
      expect.objectContaining({ event: 'compare.opened', artifactIdA: 'art_prior' }),
    );
  });

  it('maps an in-flight fetch to loading', () => {
    mockUseRevisionDelta.mockReturnValue(fakeQuery({ isFetching: true }));
    render(<CompareModeContainer {...IDS} onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'loading');
  });

  it('maps 503 → partial and logs compare.loadError with the code only (never the message)', () => {
    mockUseRevisionDelta.mockReturnValue(
      fakeQuery({ isError: true, error: problem('ARTIFACT_PAYLOAD_UNAVAILABLE', 503) }),
    );
    render(<CompareModeContainer {...IDS} onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'partial');
    expect(warnSpy).toHaveBeenCalledWith({
      event: 'compare.loadError',
      code: 'ARTIFACT_PAYLOAD_UNAVAILABLE',
      transport: false,
    });
    // The sensitive ProblemDetails `detail` text is NEVER logged.
    const logged = JSON.stringify(
      (warnSpy as unknown as { mock: { calls: unknown[] } }).mock.calls,
    );
    expect(logged).not.toContain('sensitive detail');
  });

  it('maps 404 → no-baseline and 400 → unavailable', () => {
    mockUseRevisionDelta.mockReturnValue(
      fakeQuery({ isError: true, error: problem('ARTIFACT_RECORD_NOT_FOUND', 404) }),
    );
    const { rerender } = render(<CompareModeContainer {...IDS} onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'no-baseline');

    mockUseRevisionDelta.mockReturnValue(
      fakeQuery({ isError: true, error: problem('ARTIFACT_LINEAGE_MISMATCH', 400) }),
    );
    rerender(<CompareModeContainer {...IDS} onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'unavailable');
  });

  it('an unresolved baseline (empty artifactIdA) → no-baseline WITHOUT an error log', () => {
    render(<CompareModeContainer {...IDS} artifactIdA="" onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'no-baseline');
    expect(warnSpy).not.toHaveBeenCalledWith(
      expect.objectContaining({ event: 'compare.loadError' }),
    );
  });

  it('exit logs compare.exit and calls onExit', async () => {
    const onExit = vi.fn();
    render(<CompareModeContainer {...IDS} onExit={onExit} />);
    await userEvent.click(screen.getByTestId('compare-exit'));
    expect(infoSpy).toHaveBeenCalledWith({ event: 'compare.exit' });
    expect(onExit).toHaveBeenCalledTimes(1);
  });
});
