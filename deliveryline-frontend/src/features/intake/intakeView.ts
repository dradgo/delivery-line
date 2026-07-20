/**
 * Story 3i-2 — non-JSX view-model helpers for the intake browse.
 *
 * These live in a sibling `.ts` (not the `.tsx`) because a `.tsx` module that exports a non-component
 * function fails the `react-refresh/only-export-components` lint rule under `--max-warnings=0`.
 */
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { intakeFiltersActive, type IntakeFilters } from '@/lib/queryKeys/intakeKeys';

/** The mutually-exclusive render states of the intake browse. */
export type IntakeState =
  | 'no-project'
  | 'loading'
  | 'not-supported'
  | 'error'
  | 'empty'
  | 'filtered-empty'
  | 'populated';

/**
 * True when the query failed because the project's ticket source cannot be browsed. Branch on the
 * typed `code`, never the message or a hardcoded connector kind (AC3/AC5).
 */
export function isTicketQueryUnsupported(error: unknown): boolean {
  return isProblemDetailsError(error) && error.code === 'TICKET_QUERY_NOT_SUPPORTED';
}

/**
 * Resolve which surface to render. `not-supported` takes precedence over the generic error branch so
 * a capability-gated project hides the browse instead of showing a retryable failure.
 */
export function resolveIntakeState({
  projectId,
  isPending,
  error,
  ticketCount,
  filters,
}: {
  projectId: string | undefined;
  isPending: boolean;
  error: unknown;
  ticketCount: number;
  filters: IntakeFilters;
}): IntakeState {
  if (projectId === undefined) {
    return 'no-project';
  }
  if (error != null) {
    return isTicketQueryUnsupported(error) ? 'not-supported' : 'error';
  }
  if (isPending) {
    return 'loading';
  }
  if (ticketCount > 0) {
    return 'populated';
  }
  return intakeFiltersActive(filters) ? 'filtered-empty' : 'empty';
}

/**
 * A human summary of the ACTIVE narrowing filters, for the live announcement. Values are the
 * operator's own filter inputs (never ticket free-text), so echoing them back is safe here — this is
 * the browser DOM, not a server log.
 */
export function intakeFilterSummary(filters: IntakeFilters): string {
  const parts: string[] = [];
  if (filters.assignee !== undefined && filters.assignee.trim() !== '') {
    parts.push(`assignee ${filters.assignee.trim()}`);
  }
  if (filters.components.length > 0) {
    parts.push(`components ${filters.components.join(', ')}`);
  }
  if (filters.state !== undefined && filters.state.trim() !== '') {
    parts.push(`state ${filters.state.trim()}`);
  }
  return parts.join('; ');
}

/** Add/remove a token immutably (mirrors `OperatorFilterSidebar.toggleToken`). */
export function toggleToken(tokens: readonly string[], token: string): string[] {
  return tokens.includes(token) ? tokens.filter((t) => t !== token) : [...tokens, token];
}

/** Append a component if it is non-blank and not already present (case-sensitive, order-preserving). */
export function addComponent(tokens: readonly string[], raw: string): string[] {
  const trimmed = raw.trim();
  if (trimmed === '' || tokens.includes(trimmed)) {
    return [...tokens];
  }
  return [...tokens, trimmed];
}

/**
 * Trim, drop blanks, and de-duplicate a token array, preserving insertion order.
 *
 * The sidebar renders one checkbox per component token, keyed and `id`-ed by the token. A repeated
 * token would emit duplicate React keys and two DOM nodes sharing one `id`, leaving `<label htmlFor>`
 * ambiguous — a WCAG failure. `addComponent` already blocks duplicates from inside the app, so the
 * only way to introduce one is a crafted URL (`?components=billing,billing`); the route's search
 * parser funnels through here. Order is preserved, unlike the query-key `normalizeTokens`, which
 * also sorts — the sidebar must not reshuffle under the operator.
 */
export function dedupeTokens(tokens: readonly string[]): string[] {
  return Array.from(new Set(tokens.map((token) => token.trim()).filter((token) => token !== '')));
}
