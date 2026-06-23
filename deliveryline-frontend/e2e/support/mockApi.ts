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

// ---------------------------------------------------------------------------
// Story 3.35 (AC7, Task 4) — synthetic EXECUTION-STAGE runs for the developer
// journeys (accept-implementation / reject-implementation / takeover / recovery
// retry). The five story-1.23 streams are all SPEC-stage (their decision surface
// is the spec approval bar at WaitingForSpecApproval), so the developer-review
// loop needs dedicated fixtures: a `WaitingForReview` run carrying an
// implementation artifact (drives the Decision Bar's `implementation_review`
// mode — see ImplementationReviewDecisionBarContainer) and a `Failed` run (drives
// the `recovery_operator` mode). Modelled as first-class synthetic runs, checked
// BEFORE the stream matchers, so the spec-stage streams stay byte-for-byte intact.
// ---------------------------------------------------------------------------
export const DEV_REVIEW_RUN_ID = 'run_devreview0001';
export const RECOVERY_RUN_ID = 'run_recovery0001';
/** Story 3d-6 — an EXECUTING run whose owner-scoped actions advertise open_diagnostic_console. */
export const DIAGNOSTIC_CONSOLE_RUN_ID = 'run_console00001';
/** Story 3d-8 — a soft-hidden (archived) run, surfaced in the queue ONLY with ?includeArchived=true. */
export const ARCHIVED_RUN_ID = 'run_archived00001';
/** The prOutput artifact the impl-review bar resolves accept/reject against (highest version). */
const IMPL_PR_ARTIFACT_ID = 'art_impl_pr_0001';
const IMPL_PLAN_ARTIFACT_ID = 'art_impl_plan_0001';
const SYNTH_NOW = '2026-06-19T12:00:00.000Z';

/** A minimal markdown-bodied ArtifactDetail (satisfies the frontend `isArtifactView` guard). */
function syntheticArtifactDetail(artifactId: string, artifactType: string, version: number) {
  return {
    artifactId,
    artifactType,
    status: 'available',
    version,
    classification: 'shareable-redacted',
    checksum: 'SHA-256:9f86d081884c',
    createdAt: SYNTH_NOW,
    title: artifactType === 'prOutput' ? 'PR output' : 'Implementation plan',
    body: `# ${artifactType}\n\nSynthetic redacted ${artifactType} body for ${artifactId} (E2E journey).\n`,
  };
}

// Type inferred from the literal (no explicit interface): the e2e files lint without a
// type-aware project, and the base no-unused-vars rule misfires on named params in a
// function-type signature. The handlers index this with a runtime run-id string.
const SYNTHETIC_RUNS = {
  [DEV_REVIEW_RUN_ID]: {
    summary: () => ({
      workflowRunId: DEV_REVIEW_RUN_ID,
      currentState: 'WaitingForReview',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'artifact.ingested',
      specRejectionLoopCount: 0,
      escalationMarker: false,
    }),
    detail: () => ({
      workflowRunId: DEV_REVIEW_RUN_ID,
      currentState: 'WaitingForReview',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'artifact.ingested',
      specRejectionLoopCount: 0,
      escalationMarker: false,
      currentActorIdentity: 'codex-runner',
      // The impl-review bar resolves the HIGHEST-version prOutput (then falls back to plan);
      // both ids carry a `version` so `deriveImplementationExpectedVersions` yields the ints
      // that make the Accept control `ready` (not `blocked`).
      latestArtifacts: [
        {
          artifactId: IMPL_PLAN_ARTIFACT_ID,
          artifactType: 'implementationPlan',
          status: 'available',
          version: 1,
        },
        {
          artifactId: IMPL_PR_ARTIFACT_ID,
          artifactType: 'prOutput',
          status: 'available',
          version: 2,
        },
      ],
    }),
    events: () => ({
      workflowRun: {
        publicId: DEV_REVIEW_RUN_ID,
        ticketRef: 'LIN-901',
        createdAt: SYNTH_NOW,
        terminalState: 'WaitingForReview',
      },
      events: [
        { publicId: 'evt_devreview_1', eventType: 'artifact.ingested', createdAt: SYNTH_NOW },
      ],
    }),
    // Story 3b-4: the impl-review container requests as `developer`, which returns the
    // accept/reject/takeover trio; the default product_reviewer gets only view_only.
    allowedActions: (actorRole) => ({
      actions:
        actorRole === 'developer'
          ? ['accept_implementation', 'reject_implementation', 'takeover_workflow']
          : ['view_only'],
      versionStamp: {
        workflowState: 'WaitingForReview',
        lastEventId: 'evt_devreview_1',
        currentSpecArtifactVersion: 1,
        currentContextBundleVersion: 1,
      },
    }),
    artifact: (artifactId) =>
      syntheticArtifactDetail(
        artifactId,
        artifactId === IMPL_PR_ARTIFACT_ID ? 'prOutput' : 'implementationPlan',
        artifactId === IMPL_PR_ARTIFACT_ID ? 2 : 1,
      ),
  },
  [RECOVERY_RUN_ID]: {
    summary: () => ({
      workflowRunId: RECOVERY_RUN_ID,
      currentState: 'Failed',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'runner.failed',
      specRejectionLoopCount: 0,
      escalationMarker: false,
    }),
    detail: () => ({
      workflowRunId: RECOVERY_RUN_ID,
      currentState: 'Failed',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'runner.failed',
      specRejectionLoopCount: 0,
      escalationMarker: false,
      currentActorIdentity: 'codex-runner',
      failureCategory: 'transient_infrastructure',
      nextSafeAction: 'retry',
      latestArtifacts: [],
    }),
    events: () => ({
      workflowRun: {
        publicId: RECOVERY_RUN_ID,
        ticketRef: 'LIN-902',
        createdAt: SYNTH_NOW,
        terminalState: 'Failed',
      },
      events: [{ publicId: 'evt_recovery_1', eventType: 'runner.failed', createdAt: SYNTH_NOW }],
    }),
    // The recovery bar fires only when currentState === 'Failed' AND actions includes 'retry'
    // (canRetry). The recovery container requests with no developer role, so return retry for all.
    // Story 3d-5 — a Failed run also advertises view_runner_logs (role-agnostic), so the run-detail
    // route mounts the Step Execution Log Viewer here for the runner-log e2e journey.
    allowedActions: () => ({
      actions: ['retry', 'view_runner_logs'],
      versionStamp: {
        workflowState: 'Failed',
        lastEventId: 'evt_recovery_1',
        currentSpecArtifactVersion: 1,
        currentContextBundleVersion: 1,
      },
    }),
    artifact: (artifactId) => syntheticArtifactDetail(artifactId, 'spec', 1),
  },
  [DIAGNOSTIC_CONSOLE_RUN_ID]: {
    summary: () => ({
      workflowRunId: DIAGNOSTIC_CONSOLE_RUN_ID,
      currentState: 'Executing',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'runner.started',
      specRejectionLoopCount: 0,
      escalationMarker: false,
    }),
    detail: () => ({
      workflowRunId: DIAGNOSTIC_CONSOLE_RUN_ID,
      currentState: 'Executing',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'runner.started',
      specRejectionLoopCount: 0,
      escalationMarker: false,
      currentActorIdentity: 'codex-runner',
      latestArtifacts: [],
    }),
    events: () => ({
      workflowRun: {
        publicId: DIAGNOSTIC_CONSOLE_RUN_ID,
        ticketRef: 'LIN-903',
        createdAt: SYNTH_NOW,
        terminalState: 'Executing',
      },
      events: [{ publicId: 'evt_console_1', eventType: 'runner.started', createdAt: SYNTH_NOW }],
    }),
    // Story 3d-6 — open_diagnostic_console is offered ONLY to the run owner (workflow_owner) while
    // EXECUTING; the default product_reviewer set omits it (so the route's owner-scoped read gates
    // the console while the default read gates the log viewer).
    allowedActions: (actorRole) => ({
      actions:
        actorRole === 'workflow_owner'
          ? ['view_only', 'await_outcome', 'view_runner_logs', 'open_diagnostic_console']
          : ['view_only', 'await_outcome', 'view_runner_logs'],
      versionStamp: {
        workflowState: 'Executing',
        lastEventId: 'evt_console_1',
        currentSpecArtifactVersion: 1,
        currentContextBundleVersion: 1,
      },
    }),
    artifact: (artifactId) => syntheticArtifactDetail(artifactId, 'spec', 1),
  },
};

/** Build the workflow-state-change/takeover mutation response for a modelled developer endpoint. */
function developerMutationResponse(path: string, runId: string): unknown {
  if (path.endsWith('/takeover')) {
    // The RICH TakeoverResponse (story 3.25): drives the AC7 "Continue work in PR {ref}" affordance.
    return {
      currentState: 'TakenOver',
      correlationId: 'corr_e2e_takeover',
      recoveryActionId: 'rcv_e2e_0001',
      replayed: false,
      cancelledInFlightCount: 1,
      cancelledQueuedCount: 0,
      preservedPrReference: 'octo/widgets#7',
    };
  }
  if (path.endsWith('/retry-workflow')) {
    return { workflowRunId: runId, currentState: 'Executing', correlationId: 'corr_e2e_retry' };
  }
  // accept-implementation / reject-implementation both re-enter Executing.
  return { workflowRunId: runId, currentState: 'Executing', correlationId: 'corr_e2e_impl' };
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

// ---------------------------------------------------------------------------
// Story 3c-9 — stateful `/api/v1/projects` mock for the project-management journey
// (create → set-credential → test-connection). The store is seeded fresh per
// `mockBackend` call (a closure, not module state) so tests never leak into each
// other. `allowedActions` is status-derived (default project never advertises
// `disable`), mirroring the backend `ProjectManagementService.allowedActionsFor`.
// ---------------------------------------------------------------------------
interface MockProject {
  id: string;
  name: string;
  slug: string;
  status: 'active' | 'disabled';
  repositoryUrl: string | null;
  ticketSourceKind: string;
  repoHostKind: string;
  openspecEnabled: boolean;
  createdAt: string;
  credentials: { role: string; status: 'configured' | 'not_configured' }[];
}

function projectAllowedActions(project: MockProject): string[] {
  if (project.status === 'disabled') {
    return ['edit', 'enable', 'set_credential', 'test_connection'];
  }
  const actions = ['edit', 'set_credential', 'test_connection'];
  if (project.slug !== 'default') {
    actions.push('disable');
  }
  return actions;
}

function projectResponse(project: MockProject): unknown {
  return { ...project, allowedActions: projectAllowedActions(project) };
}

/**
 * Install the fixture-backed `/api/v1` mock on a page. Call BEFORE navigation.
 * Reads resolve from the fixtures; mutations return a benign state-change success.
 */
export async function mockBackend(page: Page): Promise<void> {
  // Story 3c-9 — per-call project store (seeded with the `default` project, 3c-6).
  const projects: MockProject[] = [
    {
      id: 'prj_default',
      name: 'Default project',
      slug: 'default',
      status: 'active',
      repositoryUrl: 'https://github.com/acme/widgets',
      ticketSourceKind: 'linear',
      repoHostKind: 'github',
      openspecEnabled: false,
      createdAt: '2026-06-20T00:00:00.000Z',
      credentials: [
        { role: 'ticket_source', status: 'configured' },
        { role: 'repo_host', status: 'not_configured' },
      ],
    },
  ];
  let projectSeq = 0;

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    // Story 3c-9 — the `/api/v1/projects` surface (stateful, handled before the
    // workflow matchers + the loud-501 tripwire).
    if (/\/api\/v1\/projects(\/|$)/.test(path)) {
      // List.
      if (method === 'GET' && /\/api\/v1\/projects\/?$/.test(path)) {
        return json(route, projects.map(projectResponse));
      }
      // Create.
      if (method === 'POST' && /\/api\/v1\/projects\/?$/.test(path)) {
        const body = (await request.postDataJSON()) as Partial<MockProject>;
        projectSeq += 1;
        const created: MockProject = {
          id: `prj_${projectSeq}`,
          name: body.name ?? 'Project',
          slug: body.slug ?? `project-${projectSeq}`,
          status: 'active',
          repositoryUrl: body.repositoryUrl ?? null,
          ticketSourceKind: body.ticketSourceKind ?? 'linear',
          repoHostKind: body.repoHostKind ?? 'github',
          openspecEnabled: body.openspecEnabled ?? false,
          createdAt: SYNTH_NOW,
          credentials: [
            { role: 'ticket_source', status: 'not_configured' },
            { role: 'repo_host', status: 'not_configured' },
          ],
        };
        projects.push(created);
        return route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(projectResponse(created)),
        });
      }
      const idMatch = /\/api\/v1\/projects\/([^/]+)/.exec(path);
      const project = idMatch ? projects.find((p) => p.id === idMatch[1]) : undefined;
      const credMatch = /\/api\/v1\/projects\/([^/]+)\/credentials\/([^/]+)$/.exec(path);
      if (method === 'PUT' && credMatch && project) {
        const role = credMatch[2]!;
        const entry = project.credentials.find((c) => c.role === role);
        if (entry) entry.status = 'configured';
        else project.credentials.push({ role, status: 'configured' });
        return json(route, { role, status: 'configured', credentialId: `cred_${projectSeq + 1}` });
      }
      if (method === 'POST' && /\/test-connection$/.test(path) && project) {
        return json(route, {
          checks: [
            { check: 'repository_reachable', status: 'pass', detail: 'reachable' },
            { check: 'ticket_source_auth', status: 'pass', detail: 'authenticated' },
            {
              check: 'repository_host_auth',
              status: project.credentials.some(
                (c) => c.role === 'repo_host' && c.status === 'configured',
              )
                ? 'pass'
                : 'skipped',
              detail: 'no credential',
            },
          ],
        });
      }
      if (method === 'POST' && /\/disable$/.test(path) && project) {
        project.status = 'disabled';
        return json(route, projectResponse(project));
      }
      if (method === 'POST' && /\/enable$/.test(path) && project) {
        project.status = 'active';
        return json(route, projectResponse(project));
      }
      if (method === 'PUT' && project && /\/api\/v1\/projects\/[^/]+$/.test(path)) {
        const body = (await request.postDataJSON()) as Partial<MockProject>;
        project.name = body.name ?? project.name;
        project.repositoryUrl = body.repositoryUrl ?? null;
        project.ticketSourceKind = body.ticketSourceKind ?? project.ticketSourceKind;
        project.repoHostKind = body.repoHostKind ?? project.repoHostKind;
        project.openspecEnabled = body.openspecEnabled ?? false;
        return json(route, projectResponse(project));
      }
      if (method === 'GET' && project) {
        return json(route, projectResponse(project));
      }
      return unmodelled(route, method, path);
    }

    // Mutations — a benign success for the MODELLED workflow commands; the journeys
    // assert reachability, the commit semantics are covered by the Vitest
    // mutation-hook suite (AC6). An UNMODELLED non-GET falls through to the loud 501
    // tripwire (review PA1), symmetric with the unmodelled-GET guard below — so a
    // mis-wired/new mutation endpoint reds E2E instead of passing on a benign 200.
    if (method !== 'GET') {
      // Story 3.35 (Task 4) — the developer-journey mutations (accept/reject/takeover/retry)
      // join the modelled set; each returns its real response shape (takeover is the rich
      // TakeoverResponse). An UNMODELLED non-GET still falls through to the loud 501 tripwire.
      const developerMutation =
        /\/api\/v1\/workflows\/[^/]+\/(accept-implementation|reject-implementation|takeover|retry-workflow)$/.exec(
          path,
        );
      if (developerMutation) {
        const runId = /\/api\/v1\/workflows\/([^/]+)\//.exec(path)?.[1] ?? DEV_REVIEW_RUN_ID;
        return json(route, developerMutationResponse(path, runId));
      }
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

    // GET /api/v1/workflows — the run queue (spec-stage streams + the synthetic
    // execution-stage developer-journey runs, story 3.35). Story 3d-8: a soft-hidden (archived)
    // run is included ONLY when ?includeArchived=true, mirroring the backend default-hide.
    if (/\/api\/v1\/workflows\/?$/.test(path)) {
      const base = [
        ...STREAMS.map(summary),
        ...Object.values(SYNTHETIC_RUNS).map((run) => run.summary()),
      ];
      if (url.searchParams.get('includeArchived') === 'true') {
        base.push({
          workflowRunId: ARCHIVED_RUN_ID,
          currentState: 'Failed',
          ticketRef: 'DEL-ARCHIVED',
          lastEventAt: '2026-06-23T00:00:00Z',
          lastEventType: 'workflow.archived',
          specRejectionLoopCount: 0,
          escalationMarker: false,
          archivedAt: '2026-06-23T00:00:00Z',
        });
      }
      return json(route, base);
    }

    const eventsMatch = /\/api\/v1\/workflows\/([^/]+)\/events$/.exec(path);
    if (eventsMatch) {
      const runId = eventsMatch[1]!;
      const synth = SYNTHETIC_RUNS[runId];
      if (synth) return json(route, synth.events());
      const stream = streamByRunId(runId);
      return stream ? json(route, stream) : notFound(route, runId, path);
    }

    const actionsMatch = /\/api\/v1\/workflows\/([^/]+)\/allowed-actions$/.exec(path);
    if (actionsMatch) {
      const runId = actionsMatch[1]!;
      const synth = SYNTHETIC_RUNS[runId];
      if (synth) return json(route, synth.allowedActions(url.searchParams.get('actorRole')));
      const stream = streamByRunId(runId);
      return stream ? json(route, allowedActions(stream)) : notFound(route, runId, path);
    }

    // Story 3a-9 (Gate 3) — the live artifact-read endpoint. Must precede the bare
    // detail matcher (whose `([^/]+)$` would not match this longer path anyway) and the
    // loud-501 fallthrough, since `useArtifact` is now live and fetches this.
    const artifactMatch = /\/api\/v1\/workflows\/([^/]+)\/artifacts\/([^/]+)$/.exec(path);
    if (artifactMatch) {
      const runId = artifactMatch[1]!;
      const synth = SYNTHETIC_RUNS[runId];
      if (synth) return json(route, synth.artifact(artifactMatch[2]!));
      const stream = streamByRunId(runId);
      return stream
        ? json(route, artifactDetail(runId, artifactMatch[2]!, stream))
        : notFound(route, runId, path);
    }

    // Story 3d-5 — the runner-log SSE stream. Fulfilled as a complete `text/event-stream` body
    // (status → log → end); the viewer's EventSource parses the frames and `end` closes it (no
    // reconnect). Modelled before the bare detail matcher + the loud-501 tripwire.
    const streamMatch = /\/api\/v1\/workflows\/([^/]+)\/runner-logs\/stream$/.exec(path);
    if (streamMatch) {
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body:
          'event: status\ndata: {"phase":"finished","rex":"rex_e2e00000001"}\n\n' +
          'event: log\ndata: {"stream":"stdout","line":"e2e runner log line","seq":0}\n\n' +
          'event: end\ndata: {"reason":"finished-replay-complete"}\n\n',
      });
    }

    // Story 3d-6 — the read-only diagnostic-console SSE stream. Fulfilled as a complete
    // `text/event-stream` body (status:live → console chunk); the console's EventSource parses the
    // frames. Modelled before the bare detail matcher + the loud-501 tripwire.
    const consoleMatch = /\/api\/v1\/workflows\/([^/]+)\/diagnostic-console\/stream$/.exec(path);
    if (consoleMatch) {
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body:
          'event: status\ndata: {"phase":"live","rex":"rex_e2e00000001"}\n\n' +
          'event: console\ndata: {"stream":"stdout","chunk":"e2e console output","seq":0}\n\n',
      });
    }

    const detailMatch = /\/api\/v1\/workflows\/([^/]+)$/.exec(path);
    if (detailMatch) {
      const runId = detailMatch[1]!;
      const synth = SYNTHETIC_RUNS[runId];
      if (synth) return json(route, synth.detail());
      const stream = streamByRunId(runId);
      return stream ? json(route, detail(stream)) : notFound(route, runId, path);
    }

    // Every read the J1/J2 journeys legitimately make is modelled above; anything
    // reaching here is a NEW or MIS-WIRED read endpoint — fail it loud (501).
    return unmodelled(route, method, path);
  });
}
