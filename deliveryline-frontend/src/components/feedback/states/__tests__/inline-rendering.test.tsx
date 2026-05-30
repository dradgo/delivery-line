/**
 * Story 2.22 AC11.v — the primitives render INLINE inside their parent region:
 * no `fixed`/`absolute` positioning (AC8.a). JSDOM has no layout engine, so the
 * structural-class assertion is the contract.
 */
import { render, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));

import { EmptyState } from '../EmptyState';
import { LoadingState } from '../LoadingState';
import { ErrorState } from '../ErrorState';

afterEach(cleanup);

describe('inline rendering (AC11.v)', () => {
  it('no primitive uses fixed/absolute positioning', () => {
    const { container } = render(
      <div style={{ width: 400 }}>
        <EmptyState variant="queue" />
        <LoadingState variant="fetchingData" />
        <ErrorState variant="failedRetrieval" nextAction={{ kind: 'NavigateBack' }} />
      </div>,
    );
    for (const el of container.querySelectorAll('[data-testid]')) {
      // The component's OWN box must stay inline-friendly: no out-of-flow
      // positioning and no viewport-relative sizing (it is "sized to fit the
      // parent", AC8.a). Arbitrary variants that position an INNER element (e.g.
      // `[&>svg]:absolute` on the Alert) are a distinct concern, not flagged.
      const tokens = el.className.split(/\s+/);
      for (const token of tokens) {
        expect(['fixed', 'absolute', 'sticky']).not.toContain(token);
        expect(token).not.toMatch(/^(?:min-)?[hw]-screen$/);
        expect(token).not.toMatch(/\[\d+v[hw]\]$/);
      }
      // No inline positioning style either.
      expect(['fixed', 'absolute', 'sticky']).not.toContain((el as HTMLElement).style.position);
    }
  });
});
