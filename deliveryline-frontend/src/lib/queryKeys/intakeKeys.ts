/**
 * Story 3i-2 (AC5) — the ticket-intake query-key factory + its URL-owned filter model.
 *
 * A dedicated sibling of `operatorKeys`: the intake browse is scoped to ONE project and filters on
 * an opaque assignee, a multi-valued component set, and an opaque source state. Every TanStack Query
 * key comes from a factory call (the `no-inline-query-keys` rule); the component array is normalized
 * (deduped + sorted) so two structurally-equal filter sets share ONE cache entry.
 *
 * Unlike the operator queue's filters, these tokens have NO closed vocabulary to validate against —
 * assignee ids, component names and workflow-state names are per-project values defined in the
 * ticket source, opaque to us end to end. So there is no `retainKnownTokens` equivalent here: an
 * unknown token is a legitimate filter that simply matches nothing, not a 400.
 */

/** The default page size; mirrors the backend `TicketQuery.DEFAULT_LIMIT`. */
export const INTAKE_DEFAULT_LIMIT = 50;

/** The hard ceiling; mirrors the backend `TicketQuery.MAX_LIMIT`. A larger value is a 400. */
export const INTAKE_MAX_LIMIT = 200;

/** The URL-owned intake filter model. `projectId` scopes the browse; the rest narrow it. */
export interface IntakeFilters {
  /** The project whose ticket source is browsed. Undefined until one is selected. */
  projectId: string | undefined;
  /** Opaque source assignee identity (JIRA Cloud: an accountId, or a resolvable email). */
  assignee: string | undefined;
  /** Component names; a ticket matching ANY of them is returned. */
  components: string[];
  /** Opaque source workflow-state name, e.g. `To Do`. */
  state: string | undefined;
}

/** The default (no active filter, no project) shape. */
export const EMPTY_INTAKE_FILTERS: IntakeFilters = {
  projectId: undefined,
  assignee: undefined,
  components: [],
  state: undefined,
};

/** Sort + de-duplicate a token array so equal filter sets produce an equal, stable cache key. */
function normalizeTokens(tokens: readonly string[]): string[] {
  return Array.from(new Set(tokens.map((t) => t.trim()).filter((t) => t !== ''))).sort();
}

/** Collapse a blank/whitespace scalar to `undefined` — mirrors the backend's blank-to-absent rule. */
function normalizeScalar(value: string | undefined): string | undefined {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? undefined : trimmed;
}

/** Normalize the filter model into a stable, serializable shape (mirrors `normalizeOperatorFilters`). */
export function normalizeIntakeFilters(
  filters: IntakeFilters = EMPTY_INTAKE_FILTERS,
): IntakeFilters {
  return {
    projectId: normalizeScalar(filters.projectId),
    assignee: normalizeScalar(filters.assignee),
    components: normalizeTokens(filters.components),
    state: normalizeScalar(filters.state),
  };
}

/** True when any NARROWING filter is set (the project scope alone is not a filter). */
export function intakeFiltersActive(filters: IntakeFilters): boolean {
  const normalized = normalizeIntakeFilters(filters);
  return (
    normalized.assignee !== undefined ||
    normalized.components.length > 0 ||
    normalized.state !== undefined
  );
}

export const intakeKeys = {
  /** Root of every intake key. */
  all: ['intake'] as const,
  /** All candidate-ticket list queries (any project, any filter). */
  lists: () => [...intakeKeys.all, 'tickets'] as const,
  /** A specific project's filtered candidate-ticket list. */
  list: (filters?: IntakeFilters) =>
    [...intakeKeys.lists(), normalizeIntakeFilters(filters)] as const,
} as const;
