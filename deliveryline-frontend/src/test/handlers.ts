/**
 * Story 2.27 (Task 1, AC2/AC3, S2/S6) — the shared default MSW handlers.
 *
 * These are registered as `setupServer(...)` DEFAULTS in `src/test/server.ts`, so
 * every test starts with a realistic baseline backend without re-stubbing the read
 * endpoints. The per-test `server.use(...)` override pattern is UNCHANGED — runtime
 * handlers prepend, and `setup.ts`'s `resetHandlers()` restores these defaults after
 * each test (story 2.6 lifecycle).
 *
 * AC2 (no mock/prod drift): every response is typed by the generated OpenAPI schema
 * (`@/lib/api/schema` via the `queryOptions` re-exports) — a backend contract change
 * reds `tsc` here before any test runs.
 * AC3 (realistic sequences): the list / detail / events / allowed-actions responses
 * are DERIVED from the vendored story-1.23 fixture event streams (happy path,
 * spec-rejection-and-resubmit, execution-failure-with-retry, and the full
 * clarification lifecycle), not hand-authored happy-path-only data.
 */
import { http, HttpResponse } from 'msw';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import type {
  Project,
  WorkflowDetail,
  WorkflowEventsResponse,
  WorkflowSummary,
} from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { allEventStreams, eventStreamByRunId } from './fixtures/event-streams';

type AllowedActions = components['schemas']['AllowedActions'];
type FailureDiagnosticsResponse = components['schemas']['FailureDiagnosticsResponse'];

/** Same-origin base — matches the client's `window.location.origin` in jsdom. */
const API = 'http://localhost/api/v1/workflows';

/** Story 3c-9 — same-origin project base. */
const PROJECTS_API = 'http://localhost/api/v1/projects';

/**
 * The seeded `default` project (story 3c-6). Active, never advertises `disable`
 * (default-project guard); `ticket_source` credentialed, `repo_host` not — exercises
 * both presence states. A realistic baseline so non-project tests that incidentally
 * touch the list do not 404 under `onUnhandledRequest:'error'`.
 */
export const defaultProjectFixture: Project = {
  id: 'prj_default',
  name: 'Default project',
  slug: 'default',
  status: 'active',
  repositoryUrl: 'https://github.com/acme/widgets',
  ticketSourceKind: 'linear',
  repoHostKind: 'github',
  openspecEnabled: false,
  createdAt: '2026-06-20T00:00:00Z',
  credentials: [
    { role: 'ticket_source', status: 'configured' },
    { role: 'repo_host', status: 'not_configured' },
  ],
  allowedActions: ['edit', 'set_credential', 'test_connection'],
};

/** The last event in a stream (streams are non-empty by the 1.23 contract). */
function lastEvent(stream: WorkflowEventsResponse): WorkflowEventsResponse['events'][number] {
  const event = stream.events[stream.events.length - 1];
  if (event === undefined) {
    throw new Error('fixture event stream is unexpectedly empty');
  }
  return event;
}

/** How many spec rejections a stream records (mirrors the backend counter, story 2.10). */
function specRejectionLoopCount(stream: WorkflowEventsResponse): number {
  return stream.events.filter((event) => event.eventType === 'approval.rejected').length;
}

/** Derive the queue summary the `GET /workflows` list serves for a run. */
export function summaryFromStream(stream: WorkflowEventsResponse): WorkflowSummary {
  const event = lastEvent(stream);
  return {
    workflowRunId: stream.workflowRun.publicId,
    currentState: stream.workflowRun.terminalState ?? 'Inbox',
    lastEventAt: event.createdAt,
    lastEventType: event.eventType,
    specRejectionLoopCount: specRejectionLoopCount(stream),
    escalationMarker: false,
    projectId: defaultProjectFixture.id ?? null,
    projectName: defaultProjectFixture.name ?? null,
    projectSlug: defaultProjectFixture.slug ?? null,
  };
}

/** Derive the run detail the `GET /workflows/{id}` endpoint serves for a run. */
export function detailFromStream(stream: WorkflowEventsResponse): WorkflowDetail {
  const event = lastEvent(stream);
  return {
    workflowRunId: stream.workflowRun.publicId,
    currentState: stream.workflowRun.terminalState ?? 'Inbox',
    lastEventAt: event.createdAt,
    lastEventType: event.eventType,
    specRejectionLoopCount: specRejectionLoopCount(stream),
    escalationMarker: false,
    projectId: defaultProjectFixture.id ?? null,
    projectName: defaultProjectFixture.name ?? null,
    projectSlug: defaultProjectFixture.slug ?? null,
  };
}

/** Operator actions the Approval Decision Bar gates on, keyed by current state (story 2.14). */
const ACTIONS_BY_STATE: Record<string, string[]> = {
  WaitingForSpecApproval: ['approve_spec', 'reject_spec', 'answer_clarification'],
  WaitingForReview: ['answer_clarification'],
  // Story 3.30 / 4.4 — a `Failed` run can retry (`AllowedAction.RETRY`) + view diagnostics +
  // download its redacted runner log (`view_runner_logs`, offered wherever a runner execution
  // exists).
  Failed: ['retry', 'view_diagnostics', 'view_runner_logs'],
};

/**
 * Story 4.4 — derive the failure-diagnostics deep-dive the `GET .../failure-diagnostics` endpoint
 * serves. A `Failed` run gets the NFR7 fields + a redacted runner-log reference + a Linear sync
 * status + one safety-ranked `retry` recommendation; any other state gets a benign view (empty
 * recommendations, no runner log) so the panel self-hides.
 */
export function failureDiagnosticsFromStream(
  stream: WorkflowEventsResponse,
): FailureDiagnosticsResponse {
  // Source the failure fields from the actual failure event (not the trailing recovery marker); a
  // run is "failed" for diagnostics purposes whenever it carries a failure event OR ended Failed.
  const failureEventTypes = new Set([
    'runner.failed',
    'runner.timeout',
    'runner.orphaned',
    'recovery.dispatchFailed',
  ]);
  const failureEvent = [...stream.events]
    .reverse()
    .find((e) => failureEventTypes.has(e.eventType));
  const failed = failureEvent != null || stream.workflowRun.terminalState === 'Failed';
  const state = failed ? 'Failed' : (stream.workflowRun.terminalState ?? 'Inbox');
  const event = failureEvent ?? lastEvent(stream);
  const correlationId =
    typeof event.details.correlationId === 'string' ? event.details.correlationId : null;
  // exactOptionalPropertyTypes: OMIT absent object-typed fields (never set them to undefined).
  const runnerLogReference = failed
    ? {
        runnerExecutionId: 'rex_diag001',
        referencePath: '/home/deliveryline/runner-logs/rex_diag001',
        byteSize: 256,
        classification: 'shareable-redacted',
        redactionCount: 1,
      }
    : undefined;
  const linear = failed
    ? {
        integrationType: 'linear',
        externalRef: 'LIN-101',
        syncStatus: 'synced',
        lastSyncAt: event.createdAt,
      }
    : undefined;
  return {
    currentState: state,
    failedStage: failed ? 'execution' : null,
    lastSuccessfulStage: failed ? 'Executing' : null,
    failureCategory: failed ? (event.failureCategory ?? 'runner_crash') : null,
    failureReason: failed ? (event.reason ?? 'runner failed') : null,
    failureTimestamp: failed ? event.createdAt : null,
    lastActivityTimestamp: failed ? event.createdAt : null,
    correlationId,
    lastGoodState: failed ? 'Executing' : null,
    currentBlockingReason: null,
    nextSafeAction: failed ? 'retry' : 'view_only',
    lastActorIdentity: event.actorIdentity,
    ...(runnerLogReference ? { runnerLogReference } : {}),
    integrationSyncStatus: linear ? { linear } : {},
    recommendedRecoveryActions: failed
      ? [
          {
            actionType: 'retry',
            safetyLevel: 'safe',
            reason: 'Retry from the failed stage is expected to succeed on a transient failure.',
            precondition: 'workspace intact',
          },
        ]
      : [],
  };
}

/** Derive the allowed-actions + version stamp the `GET .../allowed-actions` endpoint serves. */
export function allowedActionsFromStream(stream: WorkflowEventsResponse): AllowedActions {
  const event = lastEvent(stream);
  const state = stream.workflowRun.terminalState ?? 'Inbox';
  return {
    actions: ACTIONS_BY_STATE[state] ?? [],
    versionStamp: {
      workflowState: state,
      lastEventId: event.publicId,
      currentSpecArtifactVersion: 1,
      currentContextBundleVersion: 1,
    },
  };
}

/** A typed RFC-9457 problem+json `RUN_NOT_FOUND` body (mirrors the backend, story 1.8). */
function runNotFound(workflowRunId: string, instance: string) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title: 'Workflow run not found',
      status: 404,
      detail: `Workflow run not found: ${workflowRunId}`,
      instance,
      code: 'RUN_NOT_FOUND',
      retryable: false,
    },
    { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
  );
}

/**
 * The shared default handlers: a realistic read-only backend seeded from the 1.23
 * fixture streams. Mutations are intentionally NOT defaulted — each mutation test
 * asserts a specific request body / error, so it installs its own `server.use(...)`.
 */
export const defaultHandlers = [
  // GET /workflows — the run queue (every fixture run, newest scenarios last).
  http.get(API, () => HttpResponse.json(allEventStreams.map(summaryFromStream))),

  // GET /workflows/{id} — run detail, or a typed RUN_NOT_FOUND for an unknown run.
  http.get(`${API}/:workflowRunId`, ({ params }) => {
    const workflowRunId = String(params.workflowRunId);
    const stream = eventStreamByRunId(workflowRunId);
    return stream
      ? HttpResponse.json(detailFromStream(stream))
      : runNotFound(workflowRunId, `/api/v1/workflows/${workflowRunId}`);
  }),

  // GET /workflows/{id}/events — the full event history for a run.
  http.get(`${API}/:workflowRunId/events`, ({ params }) => {
    const workflowRunId = String(params.workflowRunId);
    const stream = eventStreamByRunId(workflowRunId);
    return stream
      ? HttpResponse.json(stream)
      : runNotFound(workflowRunId, `/api/v1/workflows/${workflowRunId}/events`);
  }),

  // GET /workflows/{id}/allowed-actions — the state-derived operator action set.
  http.get(`${API}/:workflowRunId/allowed-actions`, ({ params }) => {
    const workflowRunId = String(params.workflowRunId);
    const stream = eventStreamByRunId(workflowRunId);
    return stream
      ? HttpResponse.json(allowedActionsFromStream(stream))
      : runNotFound(workflowRunId, `/api/v1/workflows/${workflowRunId}/allowed-actions`);
  }),

  // Story 4.4 — GET /workflows/{id}/failure-diagnostics — the operator deep-dive.
  http.get(`${API}/:workflowRunId/failure-diagnostics`, ({ params }) => {
    const workflowRunId = String(params.workflowRunId);
    const stream = eventStreamByRunId(workflowRunId);
    return stream
      ? HttpResponse.json(failureDiagnosticsFromStream(stream))
      : runNotFound(workflowRunId, `/api/v1/workflows/${workflowRunId}/failure-diagnostics`);
  }),

  // Story 3c-9 — GET /projects (bare array, no envelope) — the single seeded project.
  http.get(PROJECTS_API, () => HttpResponse.json([defaultProjectFixture])),

  // Story 3c-9 — GET /projects/{id} — the default project, or a typed PROJECT_NOT_FOUND.
  http.get(`${PROJECTS_API}/:projectId`, ({ params }) => {
    const projectId = String(params.projectId);
    if (projectId === defaultProjectFixture.id) {
      return HttpResponse.json(defaultProjectFixture);
    }
    return HttpResponse.json(
      {
        type: 'about:blank',
        title: 'Project not found',
        status: 404,
        detail: `Project not found: ${projectId}`,
        instance: `/api/v1/projects/${projectId}`,
        code: 'PROJECT_NOT_FOUND',
        retryable: false,
      },
      { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
    );
  }),
];
