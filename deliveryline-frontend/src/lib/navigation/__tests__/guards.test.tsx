/**
 * Story 2.22 AC11.h (loaded) + AC11.i (runNotFound) + error/loading classification.
 *
 * Mocks the two input hooks directly so the guard's classification logic is
 * tested in isolation (independent of the `useAllowedActions` disabled stub).
 */
import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/features/workflows/hooks/useWorkflowDetail', () => ({ useWorkflowDetail: vi.fn() }));
vi.mock('@/features/workflows/hooks/useAllowedActions', () => ({ useAllowedActions: vi.fn() }));

import { useWorkflowDetail } from '@/features/workflows/hooks/useWorkflowDetail';
import { useAllowedActions } from '@/features/workflows/hooks/useAllowedActions';
import { ProblemDetailsError, toProblemDetails } from '@/lib/api/problemDetails';
import { useAssertRunContextLoaded } from '../guards';

type QueryLike = Record<string, unknown>;

function query(over: QueryLike = {}): QueryLike {
  return {
    isPending: false,
    isError: false,
    error: null,
    data: undefined,
    refetch: vi.fn(() => Promise.resolve()),
    ...over,
  };
}

function setDetail(over: QueryLike) {
  vi.mocked(useWorkflowDetail).mockReturnValue(
    query(over) as unknown as ReturnType<typeof useWorkflowDetail>,
  );
}
function setActions(over: QueryLike) {
  vi.mocked(useAllowedActions).mockReturnValue(
    query(over) as unknown as ReturnType<typeof useAllowedActions>,
  );
}

beforeEach(() => {
  vi.mocked(useWorkflowDetail).mockReset();
  vi.mocked(useAllowedActions).mockReset();
});

describe('useAssertRunContextLoaded', () => {
  it('AC11.h — both queries resolved → loaded with the typed payload', () => {
    setDetail({ data: { workflowRunId: 'run_aaaa', currentState: 'Executing' } });
    setActions({ data: { actions: ['approve_spec'] } });
    const { result } = renderHook(() => useAssertRunContextLoaded('run_aaaa'));
    expect(result.current.kind).toBe('loaded');
    if (result.current.kind === 'loaded') {
      expect(result.current.detail.workflowRunId).toBe('run_aaaa');
      expect(result.current.actions).toEqual({ actions: ['approve_spec'] });
    }
  });

  it('AC11.i — RUN_NOT_FOUND problem-details on detail → runNotFound', () => {
    const problem = new ProblemDetailsError(
      toProblemDetails({ code: 'RUN_NOT_FOUND', status: 404, title: 'Run not found' }, 404),
    );
    setDetail({ isError: true, error: problem });
    setActions({ data: {} });
    const { result } = renderHook(() => useAssertRunContextLoaded('run_aaaa'));
    expect(result.current.kind).toBe('runNotFound');
  });

  it('a non-problem detail error → error (with combined refetch)', () => {
    setDetail({ isError: true, error: new Error('boom') });
    setActions({ data: {} });
    const { result } = renderHook(() => useAssertRunContextLoaded('run_aaaa'));
    expect(result.current.kind).toBe('error');
    if (result.current.kind === 'error') {
      expect(result.current.error.message).toBe('boom');
      expect(typeof result.current.refetch).toBe('function');
    }
  });

  it('either query pending → loading', () => {
    setDetail({ isPending: true });
    setActions({ data: {} });
    const { result } = renderHook(() => useAssertRunContextLoaded('run_aaaa'));
    expect(result.current.kind).toBe('loading');
  });
});
