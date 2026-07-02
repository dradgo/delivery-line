/**
 * Story 3g-2 (Task 2 / AC2, AC3, AC5) — the `RunOriginBlock` view-model seam.
 *
 * `toRunOriginView` is the SINGLE pure mapper from the live workflow-detail read model
 * (`WorkflowDetail.linkedTicket`, delivered by 3g-1 / FR73) to the typed `RunOriginView`
 * the Origin block renders. Kept in a NON-component `.ts` (per
 * `frontend-react-refresh-no-fn-exports`) so this pure helper stays unit-testable and the
 * component file exports only components. Mirrors the `runContextView.ts` / `prLinkageView.ts`
 * sibling-mapper pattern.
 *
 * NULL POSTURE: `title` / `url` arrive as JSON `null` on the wire (`string | null`) for a
 * pre-3g / unlinked run or a `null` url the connector could not build — guard `!= null`
 * (the `workflowdetail-wire-sends-null-not-undefined` trap), never `=== undefined`, and
 * coalesce to `undefined` for `exactOptionalPropertyTypes`.
 *
 * ORIGIN DEPTH IS LOCKED (AC3): the view carries exactly title + ref + integrationType +
 * link-out. It does NOT surface the original ticket body, the initiating prompt, or any
 * other ticket metadata — do not widen this shape.
 */
import type { WorkflowDetail } from '@/lib/api/queryOptions';

/**
 * The typed view model the `RunOriginBlock` renders. `title` is REQUIRED (the mapper
 * returns `undefined` when it is absent — there is nothing meaningful to show without it);
 * the rest are independently optional and coalesced to `undefined` via a `!= null` guard.
 */
export interface RunOriginView {
  /** Originating ticket title (present-gated — the block hides when this is absent). */
  readonly title: string;
  /** Ticket reference `externalRef` (e.g. `DEL-1234`), the machine identity. */
  readonly ticketRef: string | undefined;
  /** Connector kind (e.g. `linear`). */
  readonly integrationType: string | undefined;
  /** Link-back URL — present ONLY when it is a safe absolute `http(s)` URL (AC2 gate). */
  readonly url: string | undefined;
}

/** Coalesce an optional/blank/`null` wire string to a present, TRIMMED value or `undefined`. */
function presentOrUndefined(value: string | null | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed != null && trimmed !== '' ? trimmed : undefined;
}

/**
 * Defensive belt-and-suspenders (Task 2 optional hardening): only surface a `url` that is
 * an absolute `http(s)` URL. 3g-1 builds `https://linear.app/...` / `https://linear.mock/...`
 * and passes it through `SHAREABLE_REDACTED`, so a non-http scheme should never appear — but
 * a stored non-http value must never reach `<a href>`.
 */
function httpUrlOrUndefined(value: string | null | undefined): string | undefined {
  const present = presentOrUndefined(value);
  if (present === undefined) {
    return undefined;
  }
  // Schemes are case-insensitive (RFC 3986) — lowercase before the allowlist check so a
  // valid `HTTPS://…` is not silently dropped. `present` is already trimmed.
  const scheme = present.toLowerCase();
  return scheme.startsWith('https://') || scheme.startsWith('http://') ? present : undefined;
}

/**
 * Pure mapper `WorkflowDetail → RunOriginView | undefined`. Reads ONLY `detail.linkedTicket`
 * (`title`, `externalRef`, `integrationType`, `url`). Returns `undefined` — the block's
 * "render nothing" gate (AC2) — when there is no linked ticket OR the title is `null`/blank.
 */
export function toRunOriginView(detail: WorkflowDetail): RunOriginView | undefined {
  const ticket = detail.linkedTicket;
  if (ticket == null) {
    return undefined;
  }
  const title = presentOrUndefined(ticket.title);
  if (title === undefined) {
    return undefined;
  }
  return {
    title,
    ticketRef: presentOrUndefined(ticket.externalRef),
    integrationType: presentOrUndefined(ticket.integrationType),
    url: httpUrlOrUndefined(ticket.url),
  };
}
