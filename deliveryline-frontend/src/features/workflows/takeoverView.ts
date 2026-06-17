/**
 * Story 3.29 (Task 1, AC2/AC5/AC7) — the pure takeover-attribution selector.
 *
 * HEADLINE RECONCILIATION (R2): takeover attribution (who / when / why) is LIVE via
 * the workflow-events stream, NOT a REST `WorkflowDetail`/`WorkflowSummary` field —
 * those carry no `takenOverBy/At/Reason`. The takeover transition is a live
 * `workflow.stateChanged` event with `resultingState === 'TakenOver'`, carrying
 * `actorIdentity` / `actorType` / `createdAt` / `reason` / `details.reviewerRole`.
 * This is the SAME source the backend `WorkflowInspectionService.getRunSummary`
 * reads as its app-internal fallback (`WorkflowInspectionService.java:322-331`), so
 * deriving from it client-side is backend truth with zero backend/OpenAPI churn.
 *
 * Kept in a NON-component module (per `frontend-react-refresh-no-fn-exports`) so the
 * selector + view type stay unit-testable and never ship from a `.tsx`.
 */
import type { WorkflowEventsResponse } from '@/lib/api/queryOptions';

type WorkflowEvent = WorkflowEventsResponse['events'][number];

/**
 * The readonly attribution slot the takeover surfaces render. Optionals follow the
 * `exactOptionalPropertyTypes` rule (`T | undefined`, never a bare-optional with an
 * assigned `undefined`; `artifactview-variant-field-fanout`): every field is present
 * on the object, possibly `undefined`. `takenOverBy` / `actorType` / `takenOverAt` /
 * `eventId` are always populated (required on the wire event); `reviewerRole` /
 * `takenOverReason` may be absent.
 */
export interface TakeoverAttributionView {
  /** The actor who took over — `actorIdentity` (untrusted; render escaped). */
  readonly takenOverBy: string;
  /** Actor classification (`human` | `agent` | `system` | `service_account`). */
  readonly actorType: string;
  /** Reviewer role from `details.reviewerRole` (e.g. `developer`); may be absent. */
  readonly reviewerRole: string | undefined;
  /** When the takeover transition was recorded (`createdAt`, ISO-8601 UTC). */
  readonly takenOverAt: string;
  /** Reviewer-authored reason (`reason`); untrusted free text, render escaped. */
  readonly takenOverReason: string | undefined;
  /** The takeover event's `publicId` — the AC5 permalink anchor target. */
  readonly eventId: string;
}

/** Whether an event is the takeover transition (a state-change INTO `TakenOver`). */
function isTakeoverEvent(event: WorkflowEvent): boolean {
  return event.eventType === 'workflow.stateChanged' && event.resultingState === 'TakenOver';
}

/**
 * Derive the attribution from the LATEST takeover transition in the stream, or
 * `undefined` when the run was never taken over.
 *
 * Events are append-only and may arrive in any order (AC defensive): select by the
 * maximum `createdAt`, NOT array position. A run can in principle be taken over more
 * than once across its life; the most recent transition is authoritative.
 */
export function selectTakeoverAttribution(
  events: readonly WorkflowEvent[],
): TakeoverAttributionView | undefined {
  let latest: WorkflowEvent | undefined;
  let latestMs = Number.NEGATIVE_INFINITY;
  for (const event of events) {
    if (!isTakeoverEvent(event)) {
      continue;
    }
    // Guard an unparseable `createdAt` (`Date.parse` → `NaN`): a corrupt timestamp must
    // never beat a valid later takeover (mirrors the `Number.isNaN` guards in
    // `runContextFormat`). A NaN-timestamped event is still selectable as a last resort
    // (when it is the only takeover) via the `latest === undefined` fallback.
    const eventMs = Date.parse(event.createdAt);
    if (latest === undefined || (!Number.isNaN(eventMs) && eventMs > latestMs)) {
      latest = event;
      latestMs = eventMs;
    }
  }
  if (latest === undefined) {
    return undefined;
  }
  // Coalesce a blank/whitespace-only reason to `undefined` so the strip and the
  // run-event surface agree on whether a reason exists (AC7 cross-surface consistency;
  // `FailureEventSurface` already hides whitespace-only reasons).
  const reason = latest.reason?.trim();
  return {
    takenOverBy: latest.actorIdentity,
    actorType: latest.actorType,
    reviewerRole: latest.details.reviewerRole,
    takenOverAt: latest.createdAt,
    takenOverReason: reason !== undefined && reason !== '' ? reason : undefined,
    eventId: latest.publicId,
  };
}
