/**
 * Story 2.22 (AC8.b) — global catastrophic-error context object.
 *
 * Backs the app-level overlay reserved for catastrophic auth/network/render
 * failures only (inline region errors use `<ErrorState>`, never this). Split into
 * a `.ts` (object) + `.tsx` (provider) + hook file per the Fast-Refresh convention.
 */
import { createContext } from 'react';

export interface CatastrophicErrorContextValue {
  /** The active catastrophic error, or null when the overlay is dismissed. */
  readonly activeError: Error | null;
  /** Raise the overlay (called by the error boundary and imperative failures). */
  readonly signalCatastrophic: (error: Error) => void;
  /** Dismiss the overlay (preserves app + breadcrumb state — OQ-8). */
  readonly dismiss: () => void;
  /**
   * Monotonic token that increments on every `dismiss()`. The
   * `CatastrophicErrorBoundary` feeds it to `resetKeys` so dismissing re-mounts
   * the crashed subtree (retry render) — without it, the boundary stays latched
   * and Dismiss would leave a blank app. Bumped ONLY on dismiss (never on
   * `signalCatastrophic`) so raising the overlay does not prematurely reset.
   */
  readonly resetToken: number;
}

export const CatastrophicErrorContext = createContext<CatastrophicErrorContextValue | null>(null);
