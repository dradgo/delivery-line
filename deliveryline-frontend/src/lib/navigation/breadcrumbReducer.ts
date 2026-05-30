/**
 * Story 2.22 (AC3) — the pure breadcrumb-stack reducer.
 *
 * Extracted from the provider so it is testable in isolation (OQ-2) and so the
 * provider `.tsx` stays React-Fast-Refresh-clean (the repo's
 * `react-refresh/only-export-components` rule runs at `--max-warnings=0`, so a
 * `.tsx` may export components + constants only — not this function).
 *
 * Invariants enforced here:
 *  - dedup (AC3.c, Trap T5): a `push` whose entry shares kind + all ids with the
 *    current top is collapsed into a `replaceLast` (no double push on router
 *    re-renders). Dedup is on kind + ids, NOT path.
 *  - FIFO cap (AC3.d): the stack never exceeds {@link MAX_BREADCRUMB_STACK_DEPTH};
 *    the oldest entries are dropped first.
 */
import { MAX_BREADCRUMB_STACK_DEPTH, type BreadcrumbEntry } from './types';

export type BreadcrumbAction =
  | { readonly type: 'push'; readonly entry: BreadcrumbEntry }
  | { readonly type: 'replaceLast'; readonly entry: BreadcrumbEntry }
  | { readonly type: 'clear' };

/** The empty initial stack. */
export const INITIAL_BREADCRUMB_STACK: readonly BreadcrumbEntry[] = [];

/**
 * Two entries share identity when their kind + all three ids match (Trap T5).
 * The same path with a different `clarificationId` search param is a DIFFERENT
 * entry — identity is structural, not path-based.
 */
export function breadcrumbEntriesShareIdentity(a: BreadcrumbEntry, b: BreadcrumbEntry): boolean {
  return (
    a.kind === b.kind &&
    a.runId === b.runId &&
    a.artifactId === b.artifactId &&
    a.clarificationId === b.clarificationId
  );
}

/** Drop oldest entries until the stack fits the cap (AC3.d). */
function capToDepth(stack: readonly BreadcrumbEntry[]): readonly BreadcrumbEntry[] {
  if (stack.length <= MAX_BREADCRUMB_STACK_DEPTH) {
    return stack;
  }
  return stack.slice(stack.length - MAX_BREADCRUMB_STACK_DEPTH);
}

/** Pure reducer over the breadcrumb stack. */
export function breadcrumbReducer(
  stack: readonly BreadcrumbEntry[],
  action: BreadcrumbAction,
): readonly BreadcrumbEntry[] {
  switch (action.type) {
    case 'push': {
      const top = stack[stack.length - 1];
      if (top !== undefined && breadcrumbEntriesShareIdentity(top, action.entry)) {
        // Dedup — collapse into a replace of the top entry.
        return [...stack.slice(0, -1), action.entry];
      }
      return capToDepth([...stack, action.entry]);
    }
    case 'replaceLast': {
      if (stack.length === 0) {
        return [action.entry];
      }
      return [...stack.slice(0, -1), action.entry];
    }
    case 'clear':
      return INITIAL_BREADCRUMB_STACK;
  }
}
