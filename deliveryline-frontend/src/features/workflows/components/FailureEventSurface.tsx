/**
 * Story 3.30 (Task 4, AC1, AC6) — the minimal failure-event surface + diagnostics
 * panel.
 *
 * SCOPE DISCIPLINE (AC5, OQ-2): there is no run-timeline UI yet, and Epic 4 owns the
 * full operator console. This is a deliberately MINIMAL event list — it renders ONLY
 * failure events, the `recovery.retried` marker, and (story 3.29) the developer-takeover
 * transition, from `useWorkflowEvents` — never a general-purpose event timeline.
 *
 * Story 3.29 (Task 5, AC5/R6): the takeover transition is a LIVE
 * `workflow.stateChanged` event with `resultingState === 'TakenOver'` — NOT a
 * `recovery.takeover` event type (adding one triggers the `WorkflowEventType` fixture
 * fan-out + backend churn; `new-workfloweventtype-fixture-sites`). It renders as a
 * prominent recovery-styled row carrying actor + reason + timestamp + the event
 * `publicId` as a permalink anchor (FR19 reconstruction, story 3.22 AC12).
 *
 * RECONCILIATION (Dev Notes #5 / live `WorkflowEvent.eventType` enum): AC1's
 * `runner.crash` / `runner.contractViolation` / `git.pushFailed` are NOT event types in
 * the live enum — they are `FailureCategory` VALUES carried on a `runner.failed` event.
 * So failure events are matched by their live event TYPE
 * (`runner.failed` / `runner.timeout` / `runner.orphaned` / `recovery.dispatchFailed`),
 * and each event's `failureCategory` supplies the human-readable category badge.
 *
 * Clicking a failure event opens a `BoundedDetailSheet` (story 2.23) diagnostics panel:
 * failure category (humanized) + reason text + a selectable `correlationId` (for log
 * grep / story 3.7 ELK) + a PLACEHOLDER runner-logs affordance (story 3.6 endpoint not
 * wired — a disabled control, never a fabricated URL).
 */
import { useState } from 'react';

import { Stack } from '@/components/layout';
import { Button } from '@/components/ui/button';
import { BoundedDetailSheet } from '@/components/overlays/BoundedDetailSheet';
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion';
import { cn } from '@/lib/utils';

import { useWorkflowEvents } from '../hooks/useWorkflowEvents';
import { useFailureDiagnostics } from '../hooks/useFailureDiagnostics';
import { useAllowedActions } from '../hooks/useAllowedActions';
import { useRetryWorkflow } from '../hooks/useRetryWorkflow';
import { humanizeFailureCategory } from '../failureCategoryView';
import {
  downloadRedactedRunnerLog,
  isActionInvokable,
  isSyncDrift,
  safetyTone,
  type FailureDiagnostics,
  type IntegrationSyncStatus,
} from '../failureDiagnosticsView';
import type { WorkflowEventsResponse } from '@/lib/api/queryOptions';
import { formatRelativeTime, formatUtcTimestamp } from '../runContextFormat';
import { StateSignifierChip } from './WorkflowStateBadge';

type WorkflowEvent = WorkflowEventsResponse['events'][number];

export interface FailureEventSurfaceProps {
  workflowRunId: string;
}

/**
 * Live failure event types (the `WorkflowEvent.eventType` enum). `recovery.retried`
 * is surfaced too (Task 4) but rendered as a recovery marker, not a failure.
 */
const FAILURE_EVENT_TYPES: ReadonlySet<string> = new Set([
  'runner.failed',
  'runner.timeout',
  'runner.orphaned',
  'recovery.dispatchFailed',
]);
const RECOVERY_RETRIED = 'recovery.retried';

/** The takeover transition is a state-change INTO `TakenOver` (story 3.29, R6). */
function isTakeoverEvent(event: WorkflowEvent): boolean {
  return event.eventType === 'workflow.stateChanged' && event.resultingState === 'TakenOver';
}

/**
 * A surface event is a failure event, the recovery-retried marker, OR the takeover
 * transition (scope discipline — exactly these classes, never a general timeline).
 */
function isSurfaceEvent(event: WorkflowEvent): boolean {
  return (
    FAILURE_EVENT_TYPES.has(event.eventType) ||
    event.eventType === RECOVERY_RETRIED ||
    isTakeoverEvent(event)
  );
}

function isRecoveryEvent(event: WorkflowEvent): boolean {
  return event.eventType === RECOVERY_RETRIED;
}

/** A single clickable failure / recovery / takeover row. Opens the diagnostics panel. */
function FailureRow({
  event,
  nowMs,
  onSelect,
}: {
  event: WorkflowEvent;
  nowMs: number;
  onSelect: (event: WorkflowEvent) => void;
}) {
  const recovery = isRecoveryEvent(event);
  const takeover = isTakeoverEvent(event);
  const category = humanizeFailureCategory(event.failureCategory ?? undefined);
  const relative = formatRelativeTime(event.createdAt, nowMs);
  const utc = formatUtcTimestamp(event.createdAt);
  // Story 3.29 — the takeover row leads with the actor (AC5 "actor name"); the reason
  // (untrusted reviewer text — React-escaped) renders as a dimmer secondary span. The
  // recovery/failure rows keep their existing single-line label.
  const reason =
    event.reason !== null && event.reason !== undefined && event.reason.trim() !== ''
      ? event.reason
      : undefined;
  const chipLabel = takeover ? 'Taken over' : recovery ? 'Retry' : 'Failed';
  const primaryText = takeover ? event.actorIdentity : recovery ? 'Retry' : (category ?? 'Failed');

  return (
    <li>
      <button
        type="button"
        // AC5 permalink anchor — the event `publicId` as the element `id`
        // (`#evt_…` deep links) plus a stable `data-event-id` hook. No fabricated URL.
        id={event.publicId}
        data-event-id={event.publicId}
        data-testid="failure-event-row"
        data-event-type={event.eventType}
        onClick={() => onSelect(event)}
        className={cn(
          'flex min-h-touch w-full items-center gap-3 rounded-md border px-3 py-2 text-left',
          recovery || takeover ? 'border-state-recovery-border' : 'border-state-error-border',
          'bg-surface hover:bg-surface-elevated',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
        )}
      >
        <StateSignifierChip
          stateName={recovery || takeover ? 'recovery' : 'error'}
          label={chipLabel}
        />
        <span className="flex min-w-0 flex-1 items-baseline gap-2">
          <span className="min-w-0 shrink-0 truncate text-sm text-text-primary">{primaryText}</span>
          {takeover && reason !== undefined ? (
            <span className="min-w-0 truncate text-meta text-text-secondary">{reason}</span>
          ) : null}
        </span>
        {relative !== null ? (
          <time
            className="shrink-0 text-meta text-text-tertiary"
            dateTime={event.createdAt}
            title={utc ?? undefined}
          >
            {relative}
          </time>
        ) : null}
      </button>
    </li>
  );
}

/** A small labelled field block (uppercase annotation + escaped value). */
function DiagnosticsField({
  label,
  value,
  testId,
  fallback = 'Not reported',
}: {
  label: string;
  value: string | null | undefined;
  testId?: string;
  fallback?: string;
}) {
  const hasValue = value !== null && value !== undefined && value.trim() !== '';
  return (
    <div data-testid={testId}>
      <span className="text-annotation uppercase tracking-wide text-text-tertiary">{label}</span>
      <p className={cn('text-sm', hasValue ? 'text-text-primary' : 'text-text-tertiary')}>
        {hasValue ? value : fallback}
      </p>
    </div>
  );
}

/** The takeover drill-down (a takeover is not a runner failure — no diagnostics deep-dive). */
function TakeoverDiagnosticsBody({ event }: { event: WorkflowEvent }) {
  const correlationId = event.details.correlationId;
  return (
    <Stack gap="3">
      <div data-testid="failure-diagnostics-actor">
        <span className="text-annotation uppercase tracking-wide text-text-tertiary">
          Taken over by
        </span>
        <p className="text-sm text-text-primary">{event.actorIdentity}</p>
      </div>
      <DiagnosticsField
        label="Reason"
        testId="failure-diagnostics-reason"
        value={event.reason ?? undefined}
        fallback="No reason recorded"
      />
      <DiagnosticsField
        label="Correlation ID"
        testId="failure-diagnostics-correlation"
        value={typeof correlationId === 'string' ? correlationId : undefined}
      />
    </Stack>
  );
}

/** One ranked recommended-action row (story 4.4 AC4). */
function RecommendedActionRow({
  action,
  invokable,
  onInvoke,
  invoking,
}: {
  action: FailureDiagnostics['recommendedRecoveryActions'][number];
  invokable: boolean;
  onInvoke: () => void;
  invoking: boolean;
}) {
  const tone = safetyTone(action.safetyLevel);
  return (
    <li
      data-testid="failure-diagnostics-recommendation"
      data-action-type={action.actionType}
      data-safety={action.safetyLevel}
      className="flex items-start gap-3 rounded-md border border-border px-3 py-2"
    >
      <StateSignifierChip
        stateName={tone === 'safe' ? 'success' : tone === 'risky' ? 'error' : 'warning'}
        // Color-independent bracketed label survives greyscale (story 2.3 AC5).
        label={`[${action.safetyLevel.toUpperCase()}]`}
      />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-sm font-medium text-text-primary">{action.actionType}</span>
        <span className="text-meta text-text-secondary">{action.reason}</span>
        <span className="text-annotation text-text-tertiary">
          Precondition: {action.precondition}
        </span>
      </span>
      {invokable ? (
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={invoking}
          onClick={onInvoke}
          data-testid="failure-diagnostics-invoke"
          data-action-type={action.actionType}
        >
          {invoking ? 'Retrying…' : 'Retry'}
        </Button>
      ) : (
        <span className="shrink-0 text-annotation text-text-tertiary">
          Surfaced in a later release
        </span>
      )}
    </li>
  );
}

/** An integration sync-status row with a drift flag + static tooltip (AC6). */
function SyncStatusRow({
  label,
  sync,
}: {
  label: string;
  sync: IntegrationSyncStatus | null | undefined;
}) {
  const drift = isSyncDrift(sync);
  return (
    <div data-testid="failure-diagnostics-sync" data-integration={label.toLowerCase()}>
      <span className="text-annotation uppercase tracking-wide text-text-tertiary">{label}</span>
      {sync == null ? (
        <p className="text-sm text-text-tertiary">Not linked</p>
      ) : (
        <p className="flex flex-wrap items-center gap-2 text-sm text-text-primary">
          <span className="break-all">{sync.externalRef ?? '(no reference)'}</span>
          <span
            data-testid="failure-diagnostics-sync-status"
            data-drift={drift ? 'true' : 'false'}
            className={cn(
              'rounded px-1.5 py-0.5 text-annotation',
              drift
                ? 'bg-state-error-surface text-state-error-text'
                : 'bg-surface-elevated text-text-secondary',
            )}
            // AC6 — the interactive reconcile modal is deferred (4.17/4.23); a static tooltip
            // explaining the drift is the shipped affordance.
            title={
              drift
                ? `Integration drift: sync status is "${sync.syncStatus}"` +
                  (sync.lastSyncAt !== null && sync.lastSyncAt !== undefined
                    ? `, last synced ${sync.lastSyncAt}.`
                    : '.')
                : `Sync status: ${sync.syncStatus}.`
            }
          >
            {drift ? `⚠ ${sync.syncStatus}` : sync.syncStatus}
          </span>
        </p>
      )}
    </div>
  );
}

/**
 * The enriched failure-diagnostics deep-dive (story 4.4 AC4/AC5/AC6/AC7/AC8). Fetches the run's
 * `getFailureDiagnostics` and renders: the NFR7 five questions above the fold, a copy-correlationId
 * button, the safety-ranked recommended actions (an active Retry button only when `retry` is in the
 * run's allowed-actions), and expandable sections for the runner-log download, integration sync
 * status, and last good state.
 */
function FailureDiagnosticsBody({
  event,
  workflowRunId,
}: {
  event: WorkflowEvent;
  workflowRunId: string;
}) {
  const diagnosticsQuery = useFailureDiagnostics(workflowRunId);
  const allowedActionsQuery = useAllowedActions(workflowRunId, 'workflow_owner');
  const retry = useRetryWorkflow(workflowRunId);
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  const diagnostics = diagnosticsQuery.data;
  const allowedActions = allowedActionsQuery.data?.actions;
  // The allowed-actions gate is only KNOWN once the query resolves; while it is loading or errored
  // `allowedActions` is undefined and we must not claim "not permitted" (that misleads for a run the
  // operator can in fact act on).
  const gateKnown = allowedActions !== undefined;

  // Fall back to the clicked event's fields while the deep-dive loads or if it errors, so the panel
  // is never empty (AC7 — the five questions must still answer "what happened / what failed").
  const failureCategory = humanizeFailureCategory(
    diagnostics?.failureCategory ?? event.failureCategory ?? undefined,
  );
  const failureReason = diagnostics?.failureReason ?? event.reason ?? undefined;
  const correlationId =
    diagnostics?.correlationId ??
    (typeof event.details.correlationId === 'string' ? event.details.correlationId : undefined);
  const runnerLog = diagnostics?.runnerLogReference ?? null;
  const canDownloadLog =
    runnerLog !== null && gateKnown && allowedActions.includes('view_runner_logs');

  async function handleCopy() {
    if (correlationId === undefined) {
      return;
    }
    try {
      await navigator.clipboard.writeText(correlationId);
      setCopyFailed(false);
      setCopied(true);
      // Reset the "Copied" affirmation so the button gives feedback on a subsequent copy.
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can reject (insecure context / denied permission) — surface a hint rather
      // than silently doing nothing so the operator knows to select the id manually.
      setCopied(false);
      setCopyFailed(true);
    }
  }

  async function handleDownload() {
    if (runnerLog === null) {
      return;
    }
    setDownloading(true);
    try {
      await downloadRedactedRunnerLog(runnerLog.runnerExecutionId);
      setDownloadError(null);
    } catch {
      setDownloadError('The redacted runner log could not be downloaded. Try again.');
    } finally {
      setDownloading(false);
    }
  }

  return (
    <Stack gap="3">
      {/* NFR7 five questions — above the fold, never inside an accordion (AC7). */}
      <div
        data-testid="failure-diagnostics-summary"
        className="rounded-md border border-state-error-border bg-state-error-surface/40 p-3"
      >
        <StateSignifierChip stateName="error" label="Failed" />
        <div className="mt-2 flex flex-col gap-2">
          <DiagnosticsField
            label="What happened"
            testId="failure-diagnostics-category"
            value={failureCategory ?? undefined}
          />
          <DiagnosticsField
            label="Reason"
            testId="failure-diagnostics-reason"
            value={failureReason}
            fallback="No reason recorded"
          />
          <DiagnosticsField
            label="What changed"
            value={`${diagnostics?.lastSuccessfulStage ?? '(unknown)'} → ${
              diagnostics?.failedStage ?? '(unknown)'
            }`}
          />
          <DiagnosticsField label="Who acted" value={diagnostics?.lastActorIdentity ?? 'system'} />
          <DiagnosticsField
            label="What is next"
            value={
              diagnostics?.recommendedRecoveryActions[0]?.actionType ??
              diagnostics?.nextSafeAction ??
              undefined
            }
          />
        </div>
      </div>

      {/* Correlation ID with a real copy-to-clipboard button (AC8). */}
      <div data-testid="failure-diagnostics-correlation">
        <span className="text-annotation uppercase tracking-wide text-text-tertiary">
          Correlation ID
        </span>
        {correlationId !== undefined ? (
          <div className="flex items-center gap-2">
            <code className="min-w-0 break-all text-sm text-text-primary">{correlationId}</code>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => void handleCopy()}
              data-testid="failure-diagnostics-copy-correlation"
            >
              {copied ? 'Copied' : 'Copy'}
            </Button>
            {copyFailed ? (
              <span
                className="text-meta text-state-error-text"
                data-testid="failure-diagnostics-copy-error"
              >
                Copy failed — select the id manually.
              </span>
            ) : null}
          </div>
        ) : (
          <p className="text-sm text-text-tertiary">(none)</p>
        )}
      </div>

      {/* Recommended recovery actions, ranked by safety (AC4). */}
      <div data-testid="failure-diagnostics-recommendations">
        <span className="text-annotation uppercase tracking-wide text-text-tertiary">
          Recommended recovery actions
        </span>
        {(diagnostics?.recommendedRecoveryActions ?? []).length === 0 ? (
          <p className="text-sm text-text-tertiary">No recommendations.</p>
        ) : (
          <ul className="mt-1 flex flex-col gap-2">
            {diagnostics?.recommendedRecoveryActions.map((action, index) => {
              const invokable = isActionInvokable(action, allowedActions);
              return (
                <RecommendedActionRow
                  key={`${action.actionType}-${index}`}
                  action={action}
                  invokable={invokable}
                  invoking={retry.isPending}
                  onInvoke={() => retry.mutate({})}
                />
              );
            })}
          </ul>
        )}
        {retry.isError ? (
          <p
            className="mt-1 text-meta text-state-error-text"
            data-testid="failure-diagnostics-retry-error"
          >
            The retry could not be submitted. Refresh and try again.
          </p>
        ) : null}
      </div>

      {/* Expandable detail sections (AC6) — runner-log download, integration sync, last good state. */}
      <Accordion type="multiple" className="border-t border-border">
        <AccordionItem value="runner-log">
          <AccordionTrigger data-testid="failure-diagnostics-runner-log-trigger">
            Runner log
          </AccordionTrigger>
          <AccordionContent>
            {runnerLog === null ? (
              <p className="text-sm text-text-tertiary">No runner log captured for this run.</p>
            ) : (
              <Stack gap="2">
                <p className="text-meta text-text-secondary">
                  {runnerLog.classification} · {runnerLog.byteSize} bytes ·{' '}
                  {runnerLog.redactionCount} redactions
                </p>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={!canDownloadLog || downloading}
                  onClick={() => void handleDownload()}
                  data-testid="failure-diagnostics-download-log"
                >
                  {downloading ? 'Downloading…' : 'Download redacted runner log'}
                </Button>
                {!gateKnown ? (
                  <p className="text-annotation text-text-tertiary">Checking permissions…</p>
                ) : !canDownloadLog ? (
                  <p className="text-annotation text-text-tertiary">
                    Downloading logs is not permitted in this run state.
                  </p>
                ) : null}
                {downloadError !== null ? (
                  <p className="text-meta text-state-error-text">{downloadError}</p>
                ) : null}
              </Stack>
            )}
          </AccordionContent>
        </AccordionItem>
        <AccordionItem value="integration-sync">
          <AccordionTrigger data-testid="failure-diagnostics-sync-trigger">
            Integration sync status
          </AccordionTrigger>
          <AccordionContent>
            <Stack gap="2">
              <SyncStatusRow label="Linear" sync={diagnostics?.integrationSyncStatus.linear} />
              <SyncStatusRow label="GitHub" sync={diagnostics?.integrationSyncStatus.github} />
            </Stack>
          </AccordionContent>
        </AccordionItem>
        <AccordionItem value="last-good-state">
          <AccordionTrigger data-testid="failure-diagnostics-last-good-trigger">
            Last good state
          </AccordionTrigger>
          <AccordionContent>
            <Stack gap="2">
              <DiagnosticsField
                label="Last good state"
                value={diagnostics?.lastGoodState ?? undefined}
              />
              <DiagnosticsField
                label="Current blocking reason"
                value={diagnostics?.currentBlockingReason ?? undefined}
                fallback="Nothing is blocking recovery."
              />
            </Stack>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </Stack>
  );
}

/** The diagnostics panel body for a selected failure/recovery/takeover event (AC4/AC6). */
function DiagnosticsBody({
  event,
  workflowRunId,
}: {
  event: WorkflowEvent;
  workflowRunId: string;
}) {
  if (isTakeoverEvent(event)) {
    return <TakeoverDiagnosticsBody event={event} />;
  }
  return <FailureDiagnosticsBody event={event} workflowRunId={workflowRunId} />;
}

export function FailureEventSurface({ workflowRunId }: FailureEventSurfaceProps) {
  const query = useWorkflowEvents(workflowRunId);
  const [selected, setSelected] = useState<WorkflowEvent | undefined>(undefined);
  const [sheetOpen, setSheetOpen] = useState(false);
  const nowMs = Date.now();

  const surfaceEvents = (query.data?.events ?? []).filter(isSurfaceEvent);

  // Scope discipline: no failure history → render nothing (this is not a general
  // timeline; a non-failed run shows no failure surface).
  if (surfaceEvents.length === 0) {
    return null;
  }

  const handleSelect = (event: WorkflowEvent) => {
    setSelected(event);
    setSheetOpen(true);
  };

  return (
    <section aria-label="Run events" data-testid="failure-event-surface" className="w-full">
      <h2 className="mb-2 text-meta uppercase tracking-wide text-text-tertiary">Run events</h2>
      <ul className="flex flex-col gap-2">
        {surfaceEvents.map((event) => (
          <FailureRow key={event.publicId} event={event} nowMs={nowMs} onSelect={handleSelect} />
        ))}
      </ul>
      <BoundedDetailSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        title="Event diagnostics"
        description="Details for this run event, including the correlation id for log search."
        testId="failure-diagnostics-sheet"
      >
        {selected !== undefined ? (
          <DiagnosticsBody event={selected} workflowRunId={workflowRunId} />
        ) : null}
      </BoundedDetailSheet>
    </section>
  );
}
