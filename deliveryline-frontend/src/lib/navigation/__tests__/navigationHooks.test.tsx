/**
 * Story 2.22 AC11.a–e — the three typed navigation hooks.
 *
 * Consolidated into ONE file (rather than the per-hook files the story task list
 * sketches) because all three mock the same `@tanstack/react-router` module, and
 * vitest 4 shares a module registry across files in a worker — three separate
 * `vi.mock` registrations of one module race and bind a hook to the wrong spy.
 * One file = one mock = deterministic.
 *
 * Covers: AC11.a–c (walk-back / fallback / skip-non-meaningful) + Trap T1
 * (stable callback) + Trap T4 (queue fallback); AC11.d (artifact id validation);
 * AC11.e (clarification search param, not hash).
 */
import type { ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useNavigate } from '@tanstack/react-router';

vi.mock('@tanstack/react-router', () => ({ useNavigate: vi.fn() }));
const navigate = vi.fn();
beforeEach(() => {
  navigate.mockReset();
  vi.mocked(useNavigate).mockReturnValue(navigate);
});

import { NavigationBreadcrumbProvider } from '../NavigationBreadcrumbProvider';
import { useNavigationBreadcrumb } from '../useNavigationBreadcrumb';
import { useReturnToRunContext } from '../useReturnToRunContext';
import { useNavigateToArtifact } from '../useNavigateToArtifact';
import { useNavigateToClarification } from '../useNavigateToClarification';
import { InvalidNavigationTargetError, type BreadcrumbEntry } from '../types';

function entry(over: Partial<BreadcrumbEntry>): BreadcrumbEntry {
  return { kind: 'runDetail', scrollY: 0, createdAt: 0, ...over } as BreadcrumbEntry;
}

function wrapper({ children }: { children: ReactNode }) {
  return <NavigationBreadcrumbProvider>{children}</NavigationBreadcrumbProvider>;
}

function setupBack() {
  return renderHook(() => ({ bc: useNavigationBreadcrumb(), back: useReturnToRunContext() }), {
    wrapper,
  });
}

describe('useReturnToRunContext', () => {
  it('AC11.a — walks back past the current view to the prior run-detail context', () => {
    const { result } = setupBack();
    act(() => {
      result.current.bc.push(entry({ kind: 'queue' }));
      result.current.bc.push(entry({ kind: 'runDetail', runId: 'run_aaaa' }));
      result.current.bc.push(
        entry({ kind: 'artifact', runId: 'run_aaaa', artifactId: 'art_bbbb' }),
      );
    });
    act(() => result.current.back());
    expect(navigate).toHaveBeenCalledWith({
      to: '/workflows/$workflowRunId',
      params: { workflowRunId: 'run_aaaa' },
    });
  });

  it('AC11.b / Trap T4 — empty stack falls back to /workflows', () => {
    const { result } = setupBack();
    act(() => result.current.back());
    expect(navigate).toHaveBeenCalledWith({ to: '/workflows' });
  });

  it('AC11.c — skips non-run-centered (recoveryDeepDive) to reach run-detail', () => {
    const { result } = setupBack();
    act(() => {
      result.current.bc.push(entry({ kind: 'runDetail', runId: 'run_aaaa' }));
      result.current.bc.push(entry({ kind: 'recoveryDeepDive', runId: 'run_cccc' }));
      result.current.bc.push(
        entry({ kind: 'artifact', runId: 'run_aaaa', artifactId: 'art_bbbb' }),
      );
    });
    act(() => result.current.back());
    expect(navigate).toHaveBeenCalledWith({
      to: '/workflows/$workflowRunId',
      params: { workflowRunId: 'run_aaaa' },
    });
  });

  it('Trap T1 — the callback is referentially stable across re-renders with the same stack', () => {
    const { result, rerender } = setupBack();
    const first = result.current.back;
    rerender();
    expect(result.current.back).toBe(first);
  });
});

describe('useNavigateToArtifact', () => {
  it('navigates to the typed artifact route on a valid id pair', () => {
    const { result } = renderHook(() => useNavigateToArtifact('run_aaaa', 'art_bbbb'));
    act(() => result.current());
    expect(navigate).toHaveBeenCalledWith({
      to: '/workflows/$workflowRunId/artifacts/$artifactId',
      params: { workflowRunId: 'run_aaaa', artifactId: 'art_bbbb' },
    });
  });

  it('AC11.d — throws InvalidNavigationTargetError synchronously on a malformed artifact id', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    let caught: unknown;
    renderHook(() => {
      try {
        useNavigateToArtifact('run_aaaa', 'not-an-art-id');
      } catch (error) {
        caught = error;
      }
    });
    expect(caught).toBeInstanceOf(InvalidNavigationTargetError);
    expect((caught as InvalidNavigationTargetError).target).toBe('artifact');
    warn.mockRestore();
  });

  it('throws on a malformed run id (target=run)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    let caught: unknown;
    renderHook(() => {
      try {
        useNavigateToArtifact('bad-run', 'art_bbbb');
      } catch (error) {
        caught = error;
      }
    });
    expect((caught as InvalidNavigationTargetError).target).toBe('run');
    warn.mockRestore();
  });
});

describe('useNavigateToClarification', () => {
  it('AC11.e — navigates with a typed clarificationId search param, not a hash', () => {
    const { result } = renderHook(() => useNavigateToClarification('run_aaaa', 'cla_bbbb'));
    act(() => result.current());
    expect(navigate).toHaveBeenCalledWith({
      to: '/workflows/$workflowRunId',
      params: { workflowRunId: 'run_aaaa' },
      search: { clarificationId: 'cla_bbbb' },
    });
    const call = navigate.mock.calls[0]?.[0] as Record<string, unknown> | undefined;
    expect(call).toBeDefined();
    expect(call).not.toHaveProperty('hash');
  });

  it('throws InvalidNavigationTargetError on a malformed clarification id', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    let caught: unknown;
    renderHook(() => {
      try {
        useNavigateToClarification('run_aaaa', 'nope');
      } catch (error) {
        caught = error;
      }
    });
    expect((caught as InvalidNavigationTargetError).target).toBe('clarification');
    warn.mockRestore();
  });
});
