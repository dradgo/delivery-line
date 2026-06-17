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
        <StateSignifierChip stateName={recovery || takeover ? 'recovery' : 'error'} label={chipLabel} />
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

/** The diagnostics panel body for a selected failure/recovery/takeover event (AC6). */
function DiagnosticsBody({ event }: { event: WorkflowEvent }) {
  const category = humanizeFailureCategory(event.failureCategory ?? undefined);
  const correlationId = event.details.correlationId;
  const takeover = isTakeoverEvent(event);
  return (
    <Stack gap="3">
      {takeover ? (
        // Story 3.29 — a takeover has no failure category; surface the actor instead.
        <div data-testid="failure-diagnostics-actor">
          <span className="text-annotation uppercase tracking-wide text-text-tertiary">
            Taken over by
          </span>
          <p className="text-sm text-text-primary">{event.actorIdentity}</p>
        </div>
      ) : (
        <div data-testid="failure-diagnostics-category">
          <span className="text-annotation uppercase tracking-wide text-text-tertiary">
            Failure category
          </span>
          <p className="text-sm text-text-primary">{category ?? 'Not reported'}</p>
        </div>
      )}
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
      {takeover ? null : (
        <div data-testid="failure-diagnostics-logs">
          {/* PLACEHOLDER (AC6) — the story-3.6 redacted-runner-logs endpoint is not wired
              yet (Epic 4). A disabled affordance, never a fabricated URL. Not applicable
              to a takeover (no runner failure). */}
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
      )}
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
        {selected !== undefined ? <DiagnosticsBody event={selected} /> : null}
      </BoundedDetailSheet>
    </section>
  );
}
