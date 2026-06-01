/**
 * Story 2.15 (AC12) — `WorkflowSummary` list-row fixture for an execution-failure
 * TERMINAL state: a run that reached `Failed` with the `escalationMarker` set
 * (the only attention signal the live list can actually yield today). Proves the
 * row renders the right identity + state badge + escalation trust signal for a
 * diverse real-fixture signal combination.
 */
import type { WorkflowSummary } from '@/lib/api/queryOptions';

export const executionFailureSummary: WorkflowSummary = {
  workflowRunId: 'run_exec_fail_001',
  currentState: 'Failed',
  lastEventAt: '2026-05-30T11:45:00Z',
  lastEventType: 'workflow.failed',
  escalationMarker: true,
  specRejectionLoopCount: 0,
  ticketRef: 'DEL-9003',
};
