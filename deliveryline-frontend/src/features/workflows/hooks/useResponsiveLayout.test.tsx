/**
 * Story 2.7 (Task 6, AC5) — tests for the responsive layout-mode hook.
 *
 * Story 2.26 hardens this hook and adds the full breakpoint matrix; this suite is
 * the minimal coverage proving the shell can switch modes (TRAP 3).
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { installMatchMedia, setViewportWidth, uninstallMatchMedia } from '@/test/matchMedia';
import { useResponsiveLayout } from './useResponsiveLayout';

describe('useResponsiveLayout', () => {
  afterEach(() => {
    uninstallMatchMedia();
  });

  it('defaults to desktop when matchMedia is unavailable (SSR-safe fallback)', () => {
    const { result } = renderHook(() => useResponsiveLayout());
    expect(result.current).toBe('desktop');
  });

  it('reports desktop at a wide viewport (≥1024px)', () => {
    installMatchMedia(1280);
    const { result } = renderHook(() => useResponsiveLayout());
    expect(result.current).toBe('desktop');
  });

  it('reports tablet between 768px and 1023px', () => {
    installMatchMedia(800);
    const { result } = renderHook(() => useResponsiveLayout());
    expect(result.current).toBe('tablet');
  });

  it('reports mobile below 768px', () => {
    installMatchMedia(375);
    const { result } = renderHook(() => useResponsiveLayout());
    expect(result.current).toBe('mobile');
  });

  it('re-reports when the viewport crosses a breakpoint boundary', () => {
    installMatchMedia(1280);
    const { result } = renderHook(() => useResponsiveLayout());
    expect(result.current).toBe('desktop');

    act(() => {
      setViewportWidth(800);
    });
    expect(result.current).toBe('tablet');

    act(() => {
      setViewportWidth(375);
    });
    expect(result.current).toBe('mobile');
  });

  it('stops responding to viewport changes after unmount', () => {
    installMatchMedia(1280);
    const { result, unmount } = renderHook(() => useResponsiveLayout());
    unmount();
    // Notifying a removed listener must neither throw nor update the stale value.
    act(() => {
      setViewportWidth(375);
    });
    expect(result.current).toBe('desktop');
  });
});
