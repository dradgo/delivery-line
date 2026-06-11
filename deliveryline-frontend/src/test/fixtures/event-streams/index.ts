/**
 * Story 2.27 (Task 1, AC2/AC3, S6) — the vendored story-1.23 fixture event streams,
 * typed by the committed OpenAPI schema and exposed as a single registry.
 *
 * SINGLE SOURCE OF TRUTH (OQ-2 resolution — the documented "vendor + drift gate"
 * alternative): the canonical streams live in
 * `deliveryline-backend/src/test/resources/fixture-event-streams/`. They are
 * COPIED here verbatim so the frontend test layer imports them as plain in-`src`
 * JSON modules (no cross-module TS rootDir / eslint-resolver coupling). The copies
 * are NOT allowed to drift — `npm run check:fixtures`
 * (`tools/fixtures/__tests__/event-stream-drift.test.js`) asserts byte-for-content
 * parity against the backend originals on the enforced Maven/CI path, mirroring the
 * `check:api` schema-drift gate. Re-run it (or re-copy) whenever 1.23 changes.
 *
 * Each stream is the exact `WorkflowEventsResponse` wire shape the (future) story-6.9
 * `GET /workflows/{id}/events` serves; the MSW handlers (`src/test/handlers.ts`)
 * derive the list / detail / allowed-actions responses from these so mocked data
 * matches BOTH the real backend schema (no mock/prod drift) AND realistic event
 * sequences (no synthetic happy-path-only data).
 */
import type { WorkflowEventsResponse } from '@/lib/api/queryOptions';

import happyPathSuccess from './happy-path-success.json';
import specRejectionAndResubmit from './spec-rejection-and-resubmit.json';
import executionFailureWithRetry from './execution-failure-with-retry.json';
import clarificationIncorporatedHappyPath from './clarification-incorporated-happy-path.json';
import clarificationSupersededAndRejected from './clarification-superseded-and-rejected.json';

/**
 * Cast through `unknown`: the JSON modules infer literal-narrowed types that do not
 * structurally unify with the generated enum unions (e.g. `eventType` widened to a
 * literal). The shape is guaranteed by the backend's story-1.23 schema-conformance
 * contract tests + the `check:fixtures` drift gate, so a single assertion at the
 * boundary is the right seam rather than per-field coercion.
 */
function asStream(raw: unknown): WorkflowEventsResponse {
  return raw as WorkflowEventsResponse;
}

/** The happy-path lifecycle (submit → Completed): stories 2.15/2.16/2.17/2.19. */
export const happyPathStream = asStream(happyPathSuccess);
/** Spec v1 rejected with structured feedback, v2 approved, run completes: 2.17–2.20. */
export const specRejectionStream = asStream(specRejectionAndResubmit);
/** Runner crash → Failed → recovery.retried → completes: 2.15/2.16/2.19/2.20. */
export const executionFailureStream = asStream(executionFailureWithRetry);
/** Full clarification lifecycle ending in `clarification.incorporated` (2.12 AC8). */
export const clarificationIncorporatedStream = asStream(clarificationIncorporatedHappyPath);
/** Clarification superseded then rejected-invalid — the lifecycle's negative arc. */
export const clarificationSupersededStream = asStream(clarificationSupersededAndRejected);

/** Every fixture stream, in a stable order (newest scenarios last). */
export const allEventStreams: readonly WorkflowEventsResponse[] = [
  happyPathStream,
  specRejectionStream,
  executionFailureStream,
  clarificationIncorporatedStream,
  clarificationSupersededStream,
];

/** Lookup a stream by its run public id (the `workflowRun.publicId`). */
export function eventStreamByRunId(workflowRunId: string): WorkflowEventsResponse | undefined {
  return allEventStreams.find((stream) => stream.workflowRun.publicId === workflowRunId);
}
