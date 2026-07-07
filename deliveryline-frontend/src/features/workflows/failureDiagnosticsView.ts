/**
 * Story 4.4 (AC4/AC5/AC6) — presentational helpers for the failure-diagnostics panel.
 *
 * Kept in a sibling `.ts` (never the `.tsx`) so React Fast Refresh does not choke on a
 * component module exporting non-component functions (`frontend-react-refresh-no-fn-exports`).
 */
import { apiClient } from '@/lib/api/client';
import type { components } from '@/lib/api/schema';

export type FailureDiagnostics = components['schemas']['FailureDiagnosticsResponse'];
export type RecommendedAction = components['schemas']['RecommendedAction'];
export type IntegrationSyncStatus = components['schemas']['IntegrationSyncStatus'];

/** The one recovery action wired for one-click invocation today (story 4.4 Reconciliation 10). */
export const INVOKABLE_ACTION_TYPES: ReadonlySet<string> = new Set(['retry']);

/** Sync statuses that count as "drifted/conflicted" — the FE flags these (AC6). */
const DRIFT_SYNC_STATUSES: ReadonlySet<string> = new Set(['stale', 'failed']);

/** True when a link's sync status has drifted (stale/failed). Null link → not drifted. */
export function isSyncDrift(sync: IntegrationSyncStatus | null | undefined): boolean {
  return sync != null && DRIFT_SYNC_STATUSES.has(sync.syncStatus);
}

/** The visual tone for a safety level — drives the chip colour + non-colour label (story 2.3 AC5). */
export function safetyTone(safetyLevel: string): 'safe' | 'caution' | 'risky' | 'neutral' {
  switch (safetyLevel) {
    case 'safe':
      return 'safe';
    case 'caution':
      return 'caution';
    case 'risky':
      return 'risky';
    default:
      return 'neutral';
  }
}

/**
 * Whether a recommended action has a wired one-click invocation for this run: its type is
 * invokable AND present in the run's allowed-actions set (story 2.14 gate). `retry` maps to the
 * `retry_workflow` allowed-action; other verbs stay ranked guidance until their endpoints ship.
 */
export function isActionInvokable(
  action: RecommendedAction,
  allowedActions: readonly string[] | undefined,
): boolean {
  if (!INVOKABLE_ACTION_TYPES.has(action.actionType)) {
    return false;
  }
  // The `retry` recommendation maps to the `retry` allowed-action wire value (AllowedAction.RETRY).
  return (allowedActions ?? []).includes(action.actionType);
}

/**
 * Download a runner execution's redacted log as a `text/plain` attachment (story 4.4 AC5),
 * reusing the `ManualExecutionSurface` blob-download idiom. The endpoint is gated server-side by
 * `view_runner_logs`; the actor defaults to the local operator (no header needed).
 */
export async function downloadRedactedRunnerLog(runnerExecutionId: string): Promise<void> {
  const result = await apiClient.GET('/api/v1/runner-executions/{rexId}/logs/download', {
    params: { path: { rexId: runnerExecutionId }, query: { actorRole: 'workflow_owner' } },
    parseAs: 'text',
  });
  if (result.error !== undefined || typeof result.data !== 'string') {
    throw new Error('The redacted runner log could not be downloaded.');
  }
  const blob = new Blob([result.data], { type: 'text/plain' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `runner-${runnerExecutionId}.log`;
  // Attach to the DOM before clicking — some browsers (Firefox) ignore a click on a detached
  // anchor — and defer revoke to the next tick so the download is not aborted by revoking the
  // object URL in the same synchronous frame.
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
