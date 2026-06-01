/**
 * Story 2.16 (AC11) — frontend fixture mirroring the TERMINAL state of the backend
 * `spec-rejection-and-resubmit.json` event stream (story 1.23): `run_fix_rej_001`
 * reaches `Completed` after a v1 spec rejection-and-resubmit advanced the latest
 * spec to **v2**, triggered by Linear ticket `DEL-9002`.
 *
 * Frontend-owned copy: the backend fixtures are not yet served to the SPA (story
 * 6.9 / OQ-5). This is a `WorkflowDetail` (the read model `useWorkflowDetail`
 * returns), NOT the raw event stream — the strip renders the read-model projection.
 */
import type { WorkflowDetail } from '@/lib/api/queryOptions';

export const specRejectAndResubmitDetail: WorkflowDetail = {
  workflowRunId: 'run_fix_rej_001',
  currentState: 'Completed',
  currentActorIdentity: 'Alex',
  currentActorType: 'human',
  escalationMarker: false,
  specRejectionLoopCount: 1,
  lastEventAt: '2026-05-30T12:00:00Z',
  lastEventType: 'WORKFLOW_COMPLETED',
  // Recent activity so the run reads as NOT stale (terminal-but-fresh).
  lastActivityTimestamp: '2026-05-30T12:00:00Z',
  latestArtifacts: [
    // The spec advanced past the v1 rejection to v2 (proves the version bump).
    { artifactType: 'spec', status: 'approved', version: 2 },
  ],
  linkedTicket: {
    externalRef: 'DEL-9002',
    integrationType: 'linear',
    syncStatus: 'synced',
  },
};
