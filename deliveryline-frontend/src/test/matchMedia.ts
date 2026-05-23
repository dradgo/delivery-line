/**
 * Story 2.7 — a deterministic `window.matchMedia` mock for the shell + responsive
 * hook tests. jsdom does not implement `matchMedia`, which `useResponsiveLayout`
 * depends on; without this, the shell silently falls back to its `desktop`
 * default and the tablet/mobile branches cannot be exercised.
 *
 * Minimal by design — story 2.26 (the full responsive matrix) extends the shell's
 * responsive test coverage, mirroring how story 2.6's minimal Vitest runner is
 * extended by 2.27.
 */
import { vi } from 'vitest';

type MediaListener = () => void;

interface MediaEntry {
  readonly media: string;
  readonly listeners: Set<MediaListener>;
}

const entries = new Map<string, MediaEntry>();
let viewportWidth = 1280;

/** Evaluate a `min-width` / `max-width` media query against the simulated width. */
function evaluateQuery(media: string): boolean {
  const min = /min-width:\s*(\d+)px/.exec(media);
  const max = /max-width:\s*(\d+)px/.exec(media);
  if (min !== null && viewportWidth < Number(min[1])) {
    return false;
  }
  if (max !== null && viewportWidth > Number(max[1])) {
    return false;
  }
  return min !== null || max !== null;
}

/** Install a deterministic `window.matchMedia` mock at the given viewport width. */
export function installMatchMedia(initialWidth: number): void {
  viewportWidth = initialWidth;
  entries.clear();
  vi.stubGlobal('matchMedia', (media: string): MediaQueryList => {
    let entry = entries.get(media);
    if (entry === undefined) {
      entry = { media, listeners: new Set<MediaListener>() };
      entries.set(media, entry);
    }
    const { listeners } = entry;
    return {
      media,
      get matches(): boolean {
        return evaluateQuery(media);
      },
      onchange: null,
      addEventListener: (_type: string, listener: MediaListener): void => {
        listeners.add(listener);
      },
      removeEventListener: (_type: string, listener: MediaListener): void => {
        listeners.delete(listener);
      },
      addListener: (listener: MediaListener): void => {
        listeners.add(listener);
      },
      removeListener: (listener: MediaListener): void => {
        listeners.delete(listener);
      },
      dispatchEvent: (): boolean => true,
    } as unknown as MediaQueryList;
  });
}

/** Simulate a viewport resize — notifies every subscribed media query. */
export function setViewportWidth(width: number): void {
  viewportWidth = width;
  for (const entry of entries.values()) {
    for (const listener of entry.listeners) {
      listener();
    }
  }
}

/** Remove the mock and clear all registered queries. */
export function uninstallMatchMedia(): void {
  vi.unstubAllGlobals();
  entries.clear();
}
