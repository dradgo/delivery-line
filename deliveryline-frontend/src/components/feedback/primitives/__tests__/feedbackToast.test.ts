/**
 * Story 2.21 (AC1, AC9, AC10 + logging) — `feedbackToast` typed wrapper.
 *
 * Router-free, query-free. Sonner is fully mocked so the variant map's dispatch
 * is observable and the anomaly-branch log is pinned with an exact-key negative
 * assertion (no `message` text ever logged).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('sonner', () => {
  const base = vi.fn();
  return {
    toast: Object.assign(base, {
      info: vi.fn(),
      success: vi.fn(),
      warning: vi.fn(),
      error: vi.fn(),
    }),
  };
});

import { toast } from 'sonner';
import { feedbackToast, emitFeedbackToast, FEEDBACK_TOAST_POSITION } from '../feedbackToast';

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('feedbackToast', () => {
  it('AC1 — each variant calls the matching sonner level with design-system defaults', () => {
    feedbackToast.info('i');
    feedbackToast.success('s');
    feedbackToast.warning('w');
    feedbackToast.error('e');

    expect(toast.info).toHaveBeenCalledWith('i', expect.objectContaining({ duration: 4000 }));
    expect(toast.success).toHaveBeenCalledWith('s', expect.objectContaining({ duration: 4000 }));
    expect(toast.warning).toHaveBeenCalledWith('w', expect.objectContaining({ duration: 4000 }));
    expect(toast.error).toHaveBeenCalledWith('e', expect.objectContaining({ duration: 4000 }));
  });

  it('AC1 — caller opts override the defaults', () => {
    feedbackToast.info('i', { duration: 1000, description: 'extra' });
    expect(toast.info).toHaveBeenCalledWith(
      'i',
      expect.objectContaining({ duration: 1000, description: 'extra' }),
    );
  });

  it('AC9 — exposes the documented toast position', () => {
    expect(FEEDBACK_TOAST_POSITION).toBe('top-right');
  });

  it('logging — an unknown variant logs feedback.toastSuppressedFallback (key-only) and falls back', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    emitFeedbackToast('bogus', 'secret message body');

    expect(warnSpy).toHaveBeenCalledTimes(1);
    const logged = warnSpy.mock.calls[0]?.[0] as Record<string, unknown>;
    // Exact key set — event + variant ONLY, never the message text.
    expect(Object.keys(logged).sort()).toEqual(['event', 'variant']);
    expect(logged).toEqual({ event: 'feedback.toastSuppressedFallback', variant: 'bogus' });
    expect(logged).not.toHaveProperty('message');
    // The confirmation is never silently dropped — a base toast still fires.
    expect(toast).toHaveBeenCalledWith('secret message body', expect.anything());
  });
});
