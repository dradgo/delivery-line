/**
 * Story 2.27 (Task 6, AC3/S6) — Playwright backend mock, seeded from the SAME
 * vendored story-1.23 fixture event streams the Vitest MSW handlers use. A single
 * `page.route('**\/api/v1/**')` interceptor fulfills reads from the fixtures and
 * returns a benign success for mutations, so the E2E journeys run against realistic
 * event sequences with NO live backend (CI must never depend on a running runner).
 *
 * This deliberately mirrors `src/test/handlers.ts`; it cannot import it directly
 * (that module uses MSW + the `@/` alias, neither available in Playwright's loader),
 * so the small derivations are re-expressed here against the same JSON source.
 */
import type { Page, Route } from '@playwright/test';

// Playwright runs these specs under Node's native ESM loader, which requires the
// `with { type: 'json' }` import attribute (the Vitest side, src/test/.../index.ts,
// is bundled by Vite and does not).
import happyPath from '../../src/test/fixtures/event-streams/happy-path-success.json' with { type: 'json' };
import specRejection from '../../src/test/fixtures/event-streams/spec-rejection-and-resubmit.json' with { type: 'json' };
import executionFailure from '../../src/test/fixtures/event-streams/execution-failure-with-retry.json' with { type: 'json' };
import clarificationIncorporated from '../../src/test/fixtures/event-streams/clarification-incorporated-happy-path.json' with { type: 'json' };
import clarificationSuperseded from '../../src/test/fixtures/event-streams/clarification-superseded-and-rejected.json' with { type: 'json' };

interface EventStream {
  workflowRun: {
    publicId: string;
    ticketRef: string;
    createdAt: string;
    terminalState: string | null;
  };
  events: { publicId: string; eventType: string; createdAt: string }[];
}

const STREAMS = [
  happyPath,
  specRejection,
  executionFailure,
  clarificationIncorporated,
  clarificationSuperseded,
] as unknown as EventStream[];

/** The happy-path run id — the journey the critical specs drive. */
export const HAPPY_RUN_ID = happyPath.workflowRun.publicId;

const PROBLEM_JSON = 'application/problem+json';

function lastEvent(stream: EventStream) {
  return stream.events[stream.events.length - 1]!;
}

/** Mirrors `src/test/handlers.ts` — count the spec rejections a stream records. */
function specRejectionLoopCount(stream: EventStream): number {
  return stream.events.filter((event) => event.eventType === 'approval.rejected').length;
}

function summary(stream: EventStream) {
  const event = lastEvent(stream);
  return {
    workflowRunId: stream.workflowRun.publicId,
    currentState: stream.workflowRun.terminalState ?? 'Inbox',
    lastEventAt: event.createdAt,
    lastEventType: event.eventType,
    specRejectionLoopCount: specRejectionLoopCount(stream),
    escalationMarker: false,
  };
}

/**
 * The spec artifact id surfaced on every run detail. Story 3a-9 made the run-detail
 * "Open the specification" link + the approval bar's `resolveSpecArtifactId` read this
 * `latestArtifacts[].artifactId` field; without it the link never renders and the J1/J2
 * journeys can never reach the artifact viewer. It is ALSO the id the artifact-read
 * endpoint below is keyed on (the critical specs assert this id on the viewer page).
 */
const SPEC_ARTIFACT_ID = 'art_sample0001';

function detail(stream: EventStream) {
  const event = lastEvent(stream);
  return {
    workflowRunId: stream.workflowRun.publicId,
    currentState: stream.workflowRun.terminalState ?? 'Inbox',
    lastEventAt: event.createdAt,
    lastEventType: event.eventType,
    specRejectionLoopCount: specRejectionLoopCount(stream),
    escalationMarker: false,
    // Story 3a-9 — the read-model spec artifact entry the detail page + approval bar
    // resolve (`resolveSpecArtifactId`). Drives the "Open the specification" link.
    latestArtifacts: [
      {
        artifactId: SPEC_ARTIFACT_ID,
        artifactType: 'spec',
        status: 'available',
        version: 1,
      },
    ],
  };
}

/**
 * Story 3a-9 (Gate 3) — the now-live artifact-read endpoint
 * (`GET /api/v1/workflows/{runId}/artifacts/{artifactId}`). Returns a redacted spec
 * `ArtifactDetail` so the Artifact Review Panel renders its `default` (spec) view on
 * the viewer route the J1/J2 journeys reach.
 */
function artifactDetail(runId: string, artifactId: string, stream: EventStream) {
  return {
    artifactId,
    artifactType: 'spec',
    status: 'available',
    version: 1,
    classification: 'shareable-redacted',
    checksum: 'SHA-256:9f86d081884c',
    createdAt: lastEvent(stream).createdAt,
    body: `# Specification\n\nSample redacted spec body for run ${runId} (E2E journey).\n`,
  };
}

const ACTIONS_BY_STATE: Record<string, string[]> = {
  WaitingForSpecApproval: ['approve_spec', 'reject_spec', 'answer_clarification'],
  WaitingForReview: ['answer_clarification'],
};

function allowedActions(stream: EventStream) {
  const state = stream.workflowRun.terminalState ?? 'Inbox';
  return {
    actions: ACTIONS_BY_STATE[state] ?? [],
    versionStamp: {
      workflowState: state,
      lastEventId: lastEvent(stream).publicId,
      currentSpecArtifactVersion: 1,
      currentContextBundleVersion: 1,
    },
  };
}

function streamByRunId(runId: string): EventStream | undefined {
  return STREAMS.find((stream) => stream.workflowRun.publicId === runId);
}

function notFound(route: Route, runId: string, instance: string) {
  return route.fulfill({
    status: 404,
    contentType: PROBLEM_JSON,
    body: JSON.stringify({
      type: 'about:blank',
      title: 'Workflow run not found',
      status: 404,
      detail: `Workflow run not found: ${runId}`,
      instance,
      code: 'RUN_NOT_FOUND',
      retryable: false,
    }),
  });
}

function json(route: Route, body: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/**
 * Tripwire (review P2/PA1): every read AND mutation the J1/J2 journeys legitimately
 * make is modelled in `mockBackend`. An unmodelled `/api/v1` request — GET or
 * mutation — means a NEW or MIS-WIRED endpoint; fail it LOUD (501) instead of a
 * benign success (there is no analogue of the Vitest `onUnhandledRequest: 'error'`
 * tripwire otherwise). Surfacing the gap beats masking it (AC11 spirit). If a real
 * new endpoint is added, model it rather than relaxing this fallthrough.
 */
function unmodelled(route: Route, method: string, path: string) {
  console.error(`[e2e mockApi] unmodelled ${method} ${path} — failing loud (501)`);
  return route.fulfill({
    status: 501,
    contentType: PROBLEM_JSON,
    body: JSON.stringify({
      type: 'about:blank',
      title: 'Unmodelled endpoint in E2E mock',
      status: 501,
      detail: `No fixture handler for ${method} ${path}`,
      instance: path,
      code: 'E2E_UNMODELLED_ENDPOINT',
      retryable: false,
    }),
  });
}

/**
 * Install the fixture-backed `/api/v1` mock on a page. Call BEFORE navigation.
 * Reads resolve from the fixtures; mutations return a benign state-change success.
 */
export async function mockBackend(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    // Mutations — a benign success for the MODELLED workflow commands; the journeys
    // assert reachability, the commit semantics are covered by the Vitest
    // mutation-hook suite (AC6). An UNMODELLED non-GET falls through to the loud 501
    // tripwire (review PA1), symmetric with the unmodelled-GET guard below — so a
    // mis-wired/new mutation endpoint reds E2E instead of passing on a benign 200.
    if (method !== 'GET') {
      const isModelledMutation =
        /\/api\/v1\/workflows\/[^/]+\/(approve-spec|reject-spec)$/.test(path) ||
        /\/api\/v1\/workflows\/[^/]+\/clarifications\/[^/]+\/answer$/.test(path) ||
        /\/api\/v1\/workflows\/submit-workflow$/.test(path);
      if (!isModelledMutation) {
        return unmodelled(route, method, path);
      }
      const runMatch = /\/api\/v1\/workflows\/([^/]+)\//.exec(path);
      const runId = runMatch?.[1] ?? HAPPY_RUN_ID;
      return json(route, { workflowRunId: runId, currentState: 'Executing' });
    }

    // GET /api/v1/workflows — the run queue.
    if (/\/api\/v1\/workflows\/?$/.test(path)) {
      return json(route, STREAMS.map(summary));
    }

    const eventsMatch = /\/api\/v1\/workflows\/([^/]+)\/events$/.exec(path);
    if (eventsMatch) {
      const stream = streamByRunId(eventsMatch[1]!);
      return stream ? json(route, stream) : notFound(route, eventsMatch[1]!, path);
    }

    const actionsMatch = /\/api\/v1\/workflows\/([^/]+)\/allowed-actions$/.exec(path);
    if (actionsMatch) {
      const stream = streamByRunId(actionsMatch[1]!);
      return stream ? json(route, allowedActions(stream)) : notFound(route, actionsMatch[1]!, path);
    }

    // Story 3a-9 (Gate 3) — the live artifact-read endpoint. Must precede the bare
    // detail matcher (whose `([^/]+)$` would not match this longer path anyway) and the
    // loud-501 fallthrough, since `useArtifact` is now live and fetches this.
    const artifactMatch = /\/api\/v1\/workflows\/([^/]+)\/artifacts\/([^/]+)$/.exec(path);
    if (artifactMatch) {
      const stream = streamByRunId(artifactMatch[1]!);
      return stream
        ? json(route, artifactDetail(artifactMatch[1]!, artifactMatch[2]!, stream))
        : notFound(route, artifactMatch[1]!, path);
    }

    const detailMatch = /\/api\/v1\/workflows\/([^/]+)$/.exec(path);
    if (detailMatch) {
      const stream = streamByRunId(detailMatch[1]!);
      return stream ? json(route, detail(stream)) : notFound(route, detailMatch[1]!, path);
    }

    // Every read the J1/J2 journeys legitimately make is modelled above; anything
    // reaching here is a NEW or MIS-WIRED read endpoint — fail it loud (501).
    return unmodelled(route, method, path);
  });
}
