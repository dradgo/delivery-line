/**
 * Story 2.22 AC11.r — `<RunContextBoundary>` restores window scroll + the main
 * pane's scrollTop on unmount (Trap T12 — restore in useLayoutEffect cleanup).
 */
import type { ReactNode } from 'react';
import { render, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RunContextSnapshotProvider } from '../RunContextSnapshotProvider';
import { RunContextBoundary } from '../RunContextBoundary';

afterEach(cleanup);

function wrapper({ children }: { children: ReactNode }) {
  return <RunContextSnapshotProvider>{children}</RunContextSnapshotProvider>;
}

describe('RunContextBoundary', () => {
  it('AC11.r — captures scroll on mount and restores it on unmount', () => {
    // Patch the (layout-less) JSDOM scroll surfaces.
    const scrollToSpy = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 250 });

    const main = document.createElement('main');
    main.id = 'main-content';
    let mainScrollTop = 120;
    Object.defineProperty(main, 'scrollTop', {
      configurable: true,
      get: () => mainScrollTop,
      set: (v: number) => {
        mainScrollTop = v;
      },
    });
    document.body.appendChild(main);

    const { unmount } = render(
      <RunContextBoundary runId="run_aaaa" artifactId="art_bbbb">
        <div>sub-state content</div>
      </RunContextBoundary>,
      { wrapper },
    );

    // Simulate the user scrolling around inside the sub-state.
    mainScrollTop = 999;

    unmount();

    expect(scrollToSpy).toHaveBeenCalledWith(0, 250);
    expect(mainScrollTop).toBe(120);

    scrollToSpy.mockRestore();
    main.remove();
  });
});
