/**
 * Story 3.30 (Task 4, AC1, AC6) — the minimal failure-event surface + diagnostics
 * panel.
 *
 * SCOPE DISCIPLINE (AC5, OQ-2): there is no run-timeline UI yet, and Epic 4 owns the
 * full operator console. This is a deliberately MINIMAL failure list — it renders ONLY
 * failure events and the `recovery.retried` marker from `useWorkflowEvents`, never a
 * general-purpose event timeline.
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
import { cn } from '@/lib/utils';

import { useWorkflowEvents } from '../hooks/useWorkflowEvents';
import { humanizeFailureCategory } from '../failureCategoryView';
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

/** A surface event is a failure event OR the recovery-retried marker (scope discipline). */
function isSurfaceEvent(event: WorkflowEvent): boolean {
  return FAILURE_EVENT_TYPES.has(event.eventType) || event.eventType === RECOVERY_RETRIED;
}

function isRecoveryEvent(event: WorkflowEvent): boolean {
  return event.eventType === RECOVERY_RETRIED;
}

/** A single clickable failure/recovery row. Opens the diagnostics panel on activation. */
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
  const category = humanizeFailureCategory(event.failureCategory ?? undefined);
  const label = recovery ? 'Retry' : (category ?? 'Failed');
  const relative = formatRelativeTime(event.createdAt, nowMs);
  const utc = formatUtcTimestamp(event.createdAt);

  return (
    <li>
      <button
        type="button"
        data-testid="failure-event-row"
        data-event-type={event.eventType}
        onClick={() => onSelect(event)}
        className={cn(
          'flex min-h-touch w-full items-center gap-3 rounded-md border px-3 py-2 text-left',
          recovery ? 'border-state-recovery-border' : 'border-state-error-border',
          'bg-surface hover:bg-surface-elevated',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
        )}
      >
        <StateSignifierChip
          stateName={recovery ? 'recovery' : 'error'}
          label={recovery ? 'Retry' : 'Failed'}
        />
        <span className="min-w-0 flex-1 truncate text-sm text-text-primary">{label}</span>
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

/** The diagnostics panel body for a selected failure/recovery event (AC6). */
function DiagnosticsBody({ event }: { event: WorkflowEvent }) {
  const category = humanizeFailureCategory(event.failureCategory ?? undefined);
  const correlationId = event.details.correlationId;
  return (
    <Stack gap="3">
      <div data-testid="failure-diagnostics-category">
        <span className="text-annotation uppercase tracking-wide text-text-tertiary">
          Failure category
        </span>
        <p className="text-sm text-text-primary">{category ?? 'Not reported'}</p>
      </div>
      <div data-testid="failure-diagnostics-reason">
        <span className="text-annotation uppercase tracking-wide text-text-tertiary">Reason</span>
        {/* Runner-supplied text — rendered as React-escaped plain text (never raw HTML). */}
        <p className="text-sm text-text-primary">
          {event.reason !== null && event.reason !== undefined && event.reason.trim() !== ''
            ? event.reason
            : 'No reason recorded'}
        </p>
      </div>
      <div data-testid="failure-diagnostics-correlation">
        <span className="text-annotation uppercase tracking-wide text-text-tertiary">
          Correlation ID
        </span>
        {correlationId !== undefined ? (
          // Selectable for log grep (story 3.7 ELK). Plain escaped text — safe charset.
          <code className="block select-all break-all text-sm text-text-primary">
            {correlationId}
          </code>
        ) : (
          <p className="text-sm text-text-tertiary">Not reported</p>
        )}
      </div>
      <div data-testid="failure-diagnostics-logs">
        {/* PLACEHOLDER (AC6) — the story-3.6 redacted-runner-logs endpoint is not wired
            yet (Epic 4). A disabled affordance, never a fabricated URL. */}
        <Button
          type="button"
          variant="outline"
          disabled
          data-testid="failure-diagnostics-logs-link"
        >
          Download runner logs
        </Button>
        <p className="mt-1 text-meta text-text-tertiary">
          Redacted runner logs become available in a later release.
        </p>
      </div>
    </Stack>
  );
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
    <section aria-label="Failure timeline" data-testid="failure-event-surface" className="w-full">
      <h2 className="mb-2 text-meta uppercase tracking-wide text-text-tertiary">
        Failure timeline
      </h2>
      <ul className="flex flex-col gap-2">
        {surfaceEvents.map((event) => (
          <FailureRow key={event.publicId} event={event} nowMs={nowMs} onSelect={handleSelect} />
        ))}
      </ul>
      <BoundedDetailSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        title="Failure diagnostics"
        description="Failure details for this run, including the correlation id for log search."
        testId="failure-diagnostics-sheet"
      >
        {selected !== undefined ? <DiagnosticsBody event={selected} /> : null}
      </BoundedDetailSheet>
    </section>
  );
}
