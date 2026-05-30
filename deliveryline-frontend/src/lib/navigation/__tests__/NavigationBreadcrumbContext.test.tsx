/**
 * Story 2.22 AC11.f (dedup) + AC11.g (cap) + Trap T5 (identity, not path).
 *
 * Exercises the breadcrumb stack two ways: the pure reducer in isolation (OQ-2)
 * and the live provider + hook via `renderHook` + `act`.
 */
import type { ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { NavigationBreadcrumbProvider } from '../NavigationBreadcrumbProvider';
import { useNavigationBreadcrumb } from '../useNavigationBreadcrumb';
import {
  INITIAL_BREADCRUMB_STACK,
  breadcrumbEntriesShareIdentity,
  breadcrumbReducer,
} from '../breadcrumbReducer';
import { MAX_BREADCRUMB_STACK_DEPTH, type BreadcrumbEntry } from '../types';

function entry(over: Partial<BreadcrumbEntry> = {}): BreadcrumbEntry {
  return { kind: 'runDetail', runId: 'run_aaaa', scrollY: 0, createdAt: 0, ...over };
}

function wrapper({ children }: { children: ReactNode }) {
  return <NavigationBreadcrumbProvider>{children}</NavigationBreadcrumbProvider>;
}

describe('breadcrumbReducer (pure)', () => {
  it('AC3.c / Trap T5 — push of an identity-equal entry dedups (stack stays length 1)', () => {
    let stack = breadcrumbReducer(INITIAL_BREADCRUMB_STACK, { type: 'push', entry: entry() });
    stack = breadcrumbReducer(stack, { type: 'push', entry: entry({ scrollY: 99 }) });
    expect(stack).toHaveLength(1);
    // The replace kept the newest entry (scrollY updated).
    expect(stack[0]?.scrollY).toBe(99);
  });

  it('Trap T5 — different ids are NOT deduped (different clarificationId = different entry)', () => {
    let stack = breadcrumbReducer(INITIAL_BREADCRUMB_STACK, {
      type: 'push',
      entry: entry({ kind: 'clarification', clarificationId: 'cla_aaaa' }),
    });
    stack = breadcrumbReducer(stack, {
      type: 'push',
      entry: entry({ kind: 'clarification', clarificationId: 'cla_bbbb' }),
    });
    expect(stack).toHaveLength(2);
  });

  it('AC3.d — caps at MAX_BREADCRUMB_STACK_DEPTH, dropping the oldest (FIFO)', () => {
    let stack = INITIAL_BREADCRUMB_STACK;
    for (let i = 0; i < MAX_BREADCRUMB_STACK_DEPTH + 2; i += 1) {
      stack = breadcrumbReducer(stack, { type: 'push', entry: entry({ runId: `run_x${i}` }) });
    }
    expect(stack).toHaveLength(MAX_BREADCRUMB_STACK_DEPTH);
    // Oldest two (run_x0, run_x1) were dropped.
    expect(stack[0]?.runId).toBe('run_x2');
    expect(stack[stack.length - 1]?.runId).toBe(`run_x${MAX_BREADCRUMB_STACK_DEPTH + 1}`);
  });

  it('replaceLast on an empty stack seeds a single entry; clear empties it', () => {
    const seeded = breadcrumbReducer(INITIAL_BREADCRUMB_STACK, {
      type: 'replaceLast',
      entry: entry(),
    });
    expect(seeded).toHaveLength(1);
    expect(breadcrumbReducer(seeded, { type: 'clear' })).toHaveLength(0);
  });

  it('breadcrumbEntriesShareIdentity compares kind + all ids', () => {
    expect(breadcrumbEntriesShareIdentity(entry(), entry({ scrollY: 5 }))).toBe(true);
    expect(breadcrumbEntriesShareIdentity(entry(), entry({ runId: 'run_zzzz' }))).toBe(false);
  });
});

describe('NavigationBreadcrumbProvider + useNavigationBreadcrumb', () => {
  it('AC11.f — pushing the same context twice keeps the stack length at 1', () => {
    const { result } = renderHook(() => useNavigationBreadcrumb(), { wrapper });
    act(() => result.current.push(entry()));
    act(() => result.current.push(entry()));
    expect(result.current.stack).toHaveLength(1);
  });

  it('AC11.g — pushing 18 distinct entries caps the stack at 16', () => {
    const { result } = renderHook(() => useNavigationBreadcrumb(), { wrapper });
    act(() => {
      for (let i = 0; i < 18; i += 1) {
        result.current.push(entry({ runId: `run_y${i}` }));
      }
    });
    expect(result.current.stack).toHaveLength(16);
    expect(result.current.stack[0]?.runId).toBe('run_y2');
  });

  it('throws when used outside the provider', () => {
    let caught: unknown;
    renderHook(() => {
      try {
        useNavigationBreadcrumb();
      } catch (error) {
        caught = error;
      }
    });
    expect((caught as Error | undefined)?.message).toMatch(/NavigationBreadcrumbProvider/);
  });
});
