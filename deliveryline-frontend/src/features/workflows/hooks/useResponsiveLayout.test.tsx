/**
 * Tests for the responsive layout-mode hook (story 2.7, AC5; boundary matrix added
 * by story 2.26, AC12).
 *
 * The mode-switch coverage proves the shell can react to breakpoint crossings; the
 * boundary-width matrix (story 2.26) pins the EXACT px at which each mode begins —
 * 767/768/1023/1024 — so the documented matrix in `RESPONSIVE.md` and the hook can
 * never silently drift apart (D3).
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

  // Story 2.26 (AC12) — the exact boundary widths the documented matrix pins:
  // mobile 320-767 · tablet 768-1023 · desktop ≥1024. The pairs straddle each
  // boundary so an off-by-one in the hook's `min-width` queries fails here.
  it.each([
    [767, 'mobile'],
    [768, 'tablet'],
    [1023, 'tablet'],
    [1024, 'desktop'],
  ] as const)('reports %s as %s at the breakpoint boundary', (width, expected) => {
    installMatchMedia(width);
    const { result } = renderHook(() => useResponsiveLayout());
    expect(result.current).toBe(expected);
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
