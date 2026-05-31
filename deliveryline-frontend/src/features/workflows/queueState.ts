/**
 * Story 2.20 (AC1) — the single source of truth for which queue shell state shows.
 *
 * `resolveQueueState` is a PURE function mapping the observable query signals
 * (`isError` / `isPending`) plus the result count and whether filters are active
 * to EXACTLY ONE of five states (AC1/AC7). Centralizing the precedence here makes
 * the states mutually exclusive BY CONSTRUCTION — `QueueShell`'s JSX switches on a
 * single value instead of stacking overlapping `&&` conditionals (Trap T8).
 *
 * Precedence (highest first): error → loading → (empty | filtered-empty) →
 * populated. Error wins over loading so a refetch failure never masquerades as a
 * still-loading skeleton; the count branches split on `filtersActive` so a
 * zero-row success with filters reads as `filtered-empty`, never `empty` (Trap T6).
 *
 * The returned literals double as the stable `data-queue-state` attribute values
 * (AC7) — keep them in sync with that attribute.
 */

/** Skeleton rows shown while the queue loads (OQ-5). */
export const QUEUE_SKELETON_ROW_COUNT = 4;

/**
 * Min-height reserved per queue row, shared with the loading skeletons so the
 * layout does not shift when real rows arrive. `[SEAM story 2.15]` — the
 * `RunReviewQueueItem` story imports THIS same constant so rows and skeletons stay
 * the same height + density.
 */
export const QUEUE_ROW_MIN_HEIGHT = '4.5rem';

export type QueueState = 'loading' | 'empty' | 'filtered-empty' | 'error' | 'populated';

export interface ResolveQueueStateInput {
  readonly isError: boolean;
  readonly isPending: boolean;
  readonly count: number;
  readonly filtersActive: boolean;
}

export function resolveQueueState({
  isError,
  isPending,
  count,
  filtersActive,
}: ResolveQueueStateInput): QueueState {
  if (isError) {
    return 'error';
  }
  if (isPending) {
    return 'loading';
  }
  if (count === 0) {
    return filtersActive ? 'filtered-empty' : 'empty';
  }
  return 'populated';
}
