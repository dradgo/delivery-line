/**
 * Story 4.2 (AC2) — the ONE `OperatorRunRow` (wire DTO) → `RunQueueRow` seam for the operator
 * queue variant. Kept SEPARATE from `toRunQueueRow` (which maps `WorkflowSummary` for the reviewer
 * queue) so the two mappers never overload one another ([[artifactview-variant-field-fanout]]
 * discipline). Lives in a NON-component module (`frontend-react-refresh-no-fn-exports`).
 *
 * Nullable wire fields (`failureCategory`/`runnerKind`/`operatorSignifier`/`linkedTicketRef`/…)
 * serialize as JSON `null` ([[workflowdetail-wire-sends-null-not-undefined]]); each is collapsed to
 * `undefined` via a present/trim guard (the 3g-2 posture) so a blank never renders an empty chip.
 */
import type { components } from '@/lib/api/schema';

import type { RunQueueRow } from './runQueueRow';

/** The operator fleet row wire DTO (from `GET /api/v1/operator/runs`). */
export type OperatorRunRowResponse = components['schemas']['OperatorRunRow'];

/** Collapse a nullable/blank wire string to `undefined` (never a blank chip/label). */
function present(value: string | null | undefined): string | undefined {
  return value != null && value.trim() !== '' ? value : undefined;
}

/**
 * Map one operator fleet row onto the shared `RunQueueRow` view model. Only the fields the operator
 * variant renders are populated; everything else stays `undefined`. `lastOperatorActionAt` proxies
 * `lastTransitionAt` for E4 (OQ-LASTACTION — no distinct operator-action column yet).
 */
export function toOperatorQueueRow(row: OperatorRunRowResponse): RunQueueRow {
  const lastTransitionAt = present(row.lastTransitionAt);
  return {
    runId: row.runId,
    currentState: row.currentState,
    operatorSignifier: present(row.operatorSignifier),
    failureCategory: present(row.failureCategory),
    runnerKind: present(row.runnerKind),
    escalationMarker: row.escalationMarker === true,
    linearTicketReference: present(row.linkedTicketRef),
    lastTransitionAt,
    lastOperatorActionAt: lastTransitionAt,
  };
}
