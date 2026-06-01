/**
 * Story 2.15 (AC12) — `WorkflowSummary` list-row fixture mirroring the TERMINAL
 * state of the backend `spec-rejection-and-resubmit` stream (story 1.23):
 * `run_fix_rej_001` reaches `Completed` after a spec rejection-and-resubmit loop
 * (`specRejectionLoopCount` ≥ 1), triggered by Linear ticket `DEL-9002`.
 *
 * Sits alongside the 2.16 `runContext` fixture but is the LIST read model
 * (`WorkflowSummary`, 7 fields), NOT the detail `WorkflowDetail`.
 */
import type { WorkflowSummary } from '@/lib/api/queryOptions';

export const specRejectAndResubmitSummary: WorkflowSummary = {
  workflowRunId: 'run_fix_rej_001',
  currentState: 'Completed',
  lastEventAt: '2026-05-30T12:00:00Z',
  lastEventType: 'workflow.completed',
  escalationMarker: false,
  specRejectionLoopCount: 1,
  ticketRef: 'DEL-9002',
};
