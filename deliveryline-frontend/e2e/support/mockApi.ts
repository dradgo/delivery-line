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
/** Story 3d-4 — a run parked in WaitingForManualExecution; owner-scoped actions advertise the manual flow. */
export const MANUAL_EXEC_RUN_ID = 'run_manual000001';
/** The prOutput artifact the impl-review bar resolves accept/reject against (highest version). */
const IMPL_PR_ARTIFACT_ID = 'art_impl_pr_0001';
const IMPL_PLAN_ARTIFACT_ID = 'art_impl_plan_0001';
const SYNTH_NOW = '2026-06-19T12:00:00.000Z';

// ---------------------------------------------------------------------------
// Story 4.26 (AC7, Task 2) — Epic-4 operator/reviewer journey fixtures. The
// three keyboard-only journeys drive: (A) a Failed operator run → classify; (B)
// a Paused run with an integration conflict → reconcile → resume; (C) a v2 spec
// artifact → Compare Mode. Modelled as first-class synthetic runs + dedicated
// endpoint handlers so the spec-stage streams stay byte-for-byte intact and the
// `unmodelled()` 501 tripwire still guards any still-unmodelled path.
// ---------------------------------------------------------------------------
/** Story 4.2/4.24 — a Failed run surfaced in the operator queue; classify target (Journey A). */
export const OPERATOR_FAILED_RUN_ID = 'run_opfailed00001';
/** Story 4.18/4.23 — a Paused run carrying an unresolved integration conflict (Journey B). */
export const PAUSED_CONFLICT_RUN_ID = 'run_pausedconf0001';
/** Story 4.19/4.20 — a run whose current spec artifact is v2 (has a comparable prior) (Journey C). */
export const COMPARE_RUN_ID = 'run_compare000001';
/** The prior (v1) + current (v2) spec artifacts the Compare journey diffs. */
const COMPARE_ARTIFACT_A = 'art_cmp_prior0001';
const COMPARE_ARTIFACT_B = 'art_cmp_current01';
/** The unresolved conflict the reconcile journey resolves. */
const JOURNEY_CONFLICT_ID = 'icf_journey000001';

/** Story 4.24 — the failure-taxonomy registry (`GET /registries/failure-taxonomy`). */
const FAILURE_TAXONOMY = {
  values: [
    {
      value: 'agent_execution_failure',
      humanReadableName: 'Agent Execution Failure',
      description: 'The agent failed to produce a valid result.',
      examples: ['The runner produced malformed output.'],
      deprecated: false,
    },
    {
      value: 'context_gap',
      humanReadableName: 'Context Gap',
      description: 'The agent lacked repository context.',
      examples: ['A convention was not surfaced.'],
      deprecated: false,
    },
  ],
};

/** Story 4.23 — the conflict detail the reconcile dialog renders (snapshots + safe-first suggestions). */
const JOURNEY_CONFLICT_DETAIL = {
  conflictId: JOURNEY_CONFLICT_ID,
  workflowRunId: PAUSED_CONFLICT_RUN_ID,
  integrationLinkId: 'ilk_journey00001',
  integrationType: 'github_pr',
  conflictCategory: 'external_state_advanced',
  externalRef: 'octo/repo#7',
  resolvedAt: null,
  internalStateSnapshot: '{"state":"open","branch":"feat/x"}',
  externalStateSnapshot: '{"state":"merged","commitSha":"abc123"}',
  suggestedDecisions: [
    { decision: 'accept_external_state', safety: 'safe' },
    { decision: 'accept_internal_state', safety: 'risky' },
  ],
};

/** Story 4.19 — the revision delta the Compare journey renders (≥2 changed regions for J/K). */
function compareDelta() {
  return {
    artifactType: 'spec',
    revisionA: {
      version: 1,
      createdAt: SYNTH_NOW,
      producedByActor: 'codex-runner',
      checksum: null,
    },
    revisionB: {
      version: 2,
      createdAt: SYNTH_NOW,
      producedByActor: 'codex-runner',
      checksum: null,
    },
    summary: { changedRegionCount: 2, addedCount: 1, removedCount: 0, modifiedCount: 1 },
    noMeaningfulDiff: false,
    changes: [
      {
        blockType: 'markdown',
        changeKind: 'modified',
        sectionPath: 'Overview',
        priorText: 'Original overview.',
        currentText: 'Rewritten overview.',
      },
      {
        blockType: 'markdown',
        changeKind: 'added',
        sectionPath: 'New section',
        priorText: null,
        currentText: 'A newly added section.',
      },
    ],
    linkedDiffReferences: null,
  };
}

/** A v1/v2 spec ArtifactDetail for the Compare journey — v2 carries the `parentArtifactId` baseline. */
function compareArtifactDetail(artifactId: string) {
  const isCurrent = artifactId === COMPARE_ARTIFACT_B;
  return {
    artifactId,
    artifactType: 'spec',
    status: 'available',
    version: isCurrent ? 2 : 1,
    classification: 'shareable-redacted',
    checksum: 'SHA-256:9f86d081884c',
    createdAt: SYNTH_NOW,
    title: 'Specification',
    // The current (v2) artifact resolves its prior revision → enables Compare (hasComparableRevision).
    parentArtifactId: isCurrent ? COMPARE_ARTIFACT_A : null,
    body: `# Specification\n\nSpec body v${isCurrent ? 2 : 1} for the Compare journey.\n`,
  };
}

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
  [MANUAL_EXEC_RUN_ID]: {
    summary: () => ({
      workflowRunId: MANUAL_EXEC_RUN_ID,
      currentState: 'WaitingForManualExecution',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'manual.executionRequested',
      specRejectionLoopCount: 0,
      escalationMarker: false,
    }),
    detail: () => ({
      workflowRunId: MANUAL_EXEC_RUN_ID,
      currentState: 'WaitingForManualExecution',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'manual.executionRequested',
      specRejectionLoopCount: 0,
      escalationMarker: false,
      currentActorIdentity: 'operator',
      latestArtifacts: [],
    }),
    events: () => ({
      workflowRun: {
        publicId: MANUAL_EXEC_RUN_ID,
        ticketRef: 'LIN-904',
        createdAt: SYNTH_NOW,
        terminalState: 'WaitingForManualExecution',
      },
      events: [
        { publicId: 'evt_manual_1', eventType: 'manual.executionRequested', createdAt: SYNTH_NOW },
      ],
    }),
    // Story 3d-4 — obtain_manual_bundle / submit_manual_artifact are offered ONLY to the run owner
    // (workflow_owner) while WaitingForManualExecution; the default product_reviewer set omits them.
    allowedActions: (actorRole) => ({
      actions:
        actorRole === 'workflow_owner'
          ? ['view_only', 'obtain_manual_bundle', 'submit_manual_artifact']
          : ['view_only'],
      versionStamp: {
        workflowState: 'WaitingForManualExecution',
        lastEventId: 'evt_manual_1',
        currentSpecArtifactVersion: 1,
        currentContextBundleVersion: 1,
      },
    }),
    artifact: (artifactId) => syntheticArtifactDetail(artifactId, 'spec', 1),
  },
  // Story 4.26 Journey A — a Failed operator run (classify target).
  [OPERATOR_FAILED_RUN_ID]: {
    summary: () => ({
      workflowRunId: OPERATOR_FAILED_RUN_ID,
      currentState: 'Failed',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'runner.failed',
      specRejectionLoopCount: 0,
      escalationMarker: false,
    }),
    detail: () => ({
      workflowRunId: OPERATOR_FAILED_RUN_ID,
      currentState: 'Failed',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'runner.failed',
      specRejectionLoopCount: 0,
      escalationMarker: false,
      currentActorIdentity: 'codex-runner',
      failureCategory: 'runner_timeout',
      failedStage: 'execution',
      latestArtifacts: [],
    }),
    events: () => ({
      workflowRun: {
        publicId: OPERATOR_FAILED_RUN_ID,
        ticketRef: 'LIN-940',
        createdAt: SYNTH_NOW,
        terminalState: 'Failed',
      },
      events: [{ publicId: 'evt_opfailed_1', eventType: 'runner.failed', createdAt: SYNTH_NOW }],
    }),
    allowedActions: (actorRole) => ({
      actions:
        actorRole === 'workflow_owner'
          ? ['view_only', 'classify_failure', 'retry', 'view_runner_logs']
          : ['view_only'],
      versionStamp: {
        workflowState: 'Failed',
        lastEventId: 'evt_opfailed_1',
        currentSpecArtifactVersion: 1,
        currentContextBundleVersion: 1,
      },
    }),
    artifact: (artifactId) => syntheticArtifactDetail(artifactId, 'spec', 1),
  },
  // Story 4.26 Journey B — a Paused run with an unresolved integration conflict (reconcile + resume).
  [PAUSED_CONFLICT_RUN_ID]: {
    summary: () => ({
      workflowRunId: PAUSED_CONFLICT_RUN_ID,
      currentState: 'Paused',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'workflow.paused',
      specRejectionLoopCount: 0,
      escalationMarker: false,
    }),
    detail: () => ({
      workflowRunId: PAUSED_CONFLICT_RUN_ID,
      currentState: 'Paused',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'workflow.paused',
      specRejectionLoopCount: 0,
      escalationMarker: false,
      currentActorIdentity: 'system',
      latestArtifacts: [],
    }),
    events: () => ({
      workflowRun: {
        publicId: PAUSED_CONFLICT_RUN_ID,
        ticketRef: 'LIN-941',
        createdAt: SYNTH_NOW,
        terminalState: 'Paused',
      },
      events: [{ publicId: 'evt_paused_1', eventType: 'workflow.paused', createdAt: SYNTH_NOW }],
    }),
    allowedActions: (actorRole) => ({
      actions:
        actorRole === 'workflow_owner'
          ? ['view_only', 'resume_workflow', 'reconcile_conflict']
          : ['view_only'],
      versionStamp: {
        workflowState: 'Paused',
        lastEventId: 'evt_paused_1',
        currentSpecArtifactVersion: 1,
        currentContextBundleVersion: 1,
      },
    }),
    artifact: (artifactId) => syntheticArtifactDetail(artifactId, 'spec', 1),
  },
  // Story 4.26 Journey C — a run whose current spec artifact is v2 (has a comparable prior revision).
  [COMPARE_RUN_ID]: {
    summary: () => ({
      workflowRunId: COMPARE_RUN_ID,
      currentState: 'WaitingForSpecApproval',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'artifact.ingested',
      specRejectionLoopCount: 0,
      escalationMarker: false,
    }),
    detail: () => ({
      workflowRunId: COMPARE_RUN_ID,
      currentState: 'WaitingForSpecApproval',
      lastEventAt: SYNTH_NOW,
      lastEventType: 'artifact.ingested',
      specRejectionLoopCount: 0,
      escalationMarker: false,
      currentActorIdentity: 'codex-runner',
      latestArtifacts: [
        { artifactId: COMPARE_ARTIFACT_B, artifactType: 'spec', status: 'available', version: 2 },
      ],
    }),
    events: () => ({
      workflowRun: {
        publicId: COMPARE_RUN_ID,
        ticketRef: 'LIN-942',
        createdAt: SYNTH_NOW,
        terminalState: 'WaitingForSpecApproval',
      },
      events: [{ publicId: 'evt_compare_1', eventType: 'artifact.ingested', createdAt: SYNTH_NOW }],
    }),
    // Compare entry needs the `enter_compare_mode` action; offer it regardless of actorRole so the
    // artifact viewer's ARP renders the ENABLED Compare control (it reads allowed-actions default-role).
    allowedActions: () => ({
      actions: ['view_only', 'enter_compare_mode', 'approve_spec', 'reject_spec'],
      versionStamp: {
        workflowState: 'WaitingForSpecApproval',
        lastEventId: 'evt_compare_1',
        currentSpecArtifactVersion: 2,
        currentContextBundleVersion: 1,
      },
    }),
    artifact: (artifactId) => compareArtifactDetail(artifactId),
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

    // Story 4.26 (AC7) — top-level Epic-4 read endpoints (operator queue, taxonomy registry,
    // integration conflicts, revision-delta compare). Handled before the workflow matchers + the
    // loud-501 tripwire. Still GET-only; any Epic-4 POST is handled in the mutation section below.
    if (method === 'GET' && path === '/api/v1/operator/runs') {
      return json(route, {
        runs: [
          {
            runId: OPERATOR_FAILED_RUN_ID,
            currentState: 'Failed',
            operatorSignifier: 'FAILED',
            failureCategory: 'runner_timeout',
            lastTransitionAt: SYNTH_NOW,
            oldestEventAt: SYNTH_NOW,
            escalationMarker: false,
            actorIdentity: 'codex-runner',
            linkedPrRef: null,
            linkedTicketRef: 'LIN-940',
            runnerKind: 'codex',
            unresolvedConflictCount: 0,
          },
        ],
        total: 1,
        nextCursor: null,
        oldestEntryAt: SYNTH_NOW,
        byState: { Failed: 1 },
        byFailureCategory: { runner_timeout: 1 },
      });
    }

    if (method === 'GET' && path === '/api/v1/registries/failure-taxonomy') {
      return json(route, FAILURE_TAXONOMY);
    }

    // Conflict detail BEFORE the list (the detail path carries a trailing id segment).
    const conflictDetailMatch = /\/api\/v1\/integration-conflicts\/([^/]+)$/.exec(path);
    if (method === 'GET' && conflictDetailMatch) {
      return conflictDetailMatch[1] === JOURNEY_CONFLICT_ID
        ? json(route, JOURNEY_CONFLICT_DETAIL)
        : unmodelled(route, method, path);
    }
    if (method === 'GET' && path === '/api/v1/integration-conflicts') {
      // Honor the run-scoping filter the real endpoint applies: a workflowRunId
      // that doesn't match the seeded conflict's run returns an empty list, so a
      // reused fixture can't mask a run-scoping regression by returning it globally.
      const scopedRun = url.searchParams.get('workflowRunId');
      const matchesRun = scopedRun === null || scopedRun === PAUSED_CONFLICT_RUN_ID;
      const conflicts = matchesRun
        ? [
            {
              conflictId: JOURNEY_CONFLICT_ID,
              integrationLinkId: 'ilk_journey00001',
              workflowRunId: PAUSED_CONFLICT_RUN_ID,
              conflictCategory: 'external_state_advanced',
              integrationType: 'github_pr',
              externalRef: 'octo/repo#7',
              detectedAt: SYNTH_NOW,
            },
          ]
        : [];
      return json(route, {
        conflicts,
        totalUnresolved: conflicts.length,
        totalResolved: 0,
        totalUnresolvedByCategory: matchesRun ? { external_state_advanced: 1 } : {},
        totalUnresolvedByIntegration: matchesRun ? { github: 1 } : {},
        nextCursor: null,
      });
    }

    // Revision-delta compare (`GET /api/v1/artifacts/{a}/compare/{b}`).
    if (method === 'GET' && /\/api\/v1\/artifacts\/[^/]+\/compare\/[^/]+$/.test(path)) {
      return json(route, compareDelta());
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
      // Story 4.26 (AC7) — the Epic-4 recovery mutations (classify / reconcile / resume). Each
      // returns its real response shape; an UNMODELLED non-GET still falls through to the loud 501.
      const epic4Mutation =
        /\/api\/v1\/workflows\/([^/]+)\/(classify-failure|reconcile|resume)$/.exec(path);
      if (epic4Mutation) {
        const runId = epic4Mutation[1]!;
        const action = epic4Mutation[2]!;
        if (action === 'resume') {
          return json(route, {
            workflowRunId: runId,
            currentState: 'Executing',
            recoveryActionId: 'rcv_e2e_resume',
            replayed: false,
            correlationId: 'corr_e2e_resume',
          });
        }
        if (action === 'reconcile') {
          return json(route, {
            workflowRunId: runId,
            currentState: 'Paused',
            recoveryActionId: 'rcv_e2e_reconcile',
            replayed: false,
            correlationId: 'corr_e2e_reconcile',
          });
        }
        // classify-failure
        return json(route, {
          workflowRunId: runId,
          taxonomyValue: 'agent_execution_failure',
          recoveryActionId: 'rcv_e2e_classify',
          replayed: false,
        });
      }

      // Story 3d-4 — manual-artifact submission re-enters the spec-approval flow.
      const manualSubmit = /\/api\/v1\/workflows\/([^/]+)\/manual-artifact$/.exec(path);
      if (manualSubmit) {
        return json(route, {
          workflowRunId: manualSubmit[1]!,
          currentState: 'WaitingForSpecApproval',
          correlationId: 'corr_e2e_manual',
        });
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

    // Story 4.26 (AC7) — the run-scoped failure diagnostics deep-dive + prior classification reads
    // the classify dialog + diagnostics surface consume. Modelled before the bare detail matcher.
    const diagnosticsMatch = /\/api\/v1\/workflows\/([^/]+)\/failure-diagnostics$/.exec(path);
    if (diagnosticsMatch) {
      const runId = diagnosticsMatch[1]!;
      // Complete FailureDiagnosticsResponse shape (schema): `lastActorIdentity` is REQUIRED, and
      // `integrationSyncStatus` is an IntegrationSyncStatusPair whose `linear`/`github` keys are
      // OMITTED when absent — never set to null (the deep-dive's sync renderer reads `.syncStatus`
      // on a present entry, so a null entry throws into the error boundary).
      return json(route, {
        workflowRunId: runId,
        currentState: runId === PAUSED_CONFLICT_RUN_ID ? 'Paused' : 'Failed',
        failedStage: 'execution',
        failureCategory: 'runner_timeout',
        failureReason: 'container exited with a non-zero status',
        failureTimestamp: SYNTH_NOW,
        lastActivityTimestamp: SYNTH_NOW,
        lastActorIdentity: 'codex-runner',
        correlationId: 'corr_opfailed_e2e',
        lastGoodState: 'Executing',
        currentBlockingReason: null,
        nextSafeAction: 'retry',
        integrationSyncStatus: {},
        recommendedRecoveryActions: [],
      });
    }
    const classificationMatch = /\/api\/v1\/workflows\/([^/]+)\/failure-classification$/.exec(path);
    if (classificationMatch) {
      return json(route, {
        workflowRunId: classificationMatch[1]!,
        deprecated: false,
        priorClassifications: [],
      });
    }

    // Story 3d-4 — the run-scoped manual input bundle read (GET, no Idempotency-Key).
    const manualBundleMatch = /\/api\/v1\/workflows\/([^/]+)\/manual-bundle$/.exec(path);
    if (manualBundleMatch) {
      const runId = manualBundleMatch[1]!;
      return json(route, {
        workflowRunId: runId,
        runnerExecutionId: 'rex_manual000001',
        available: true,
        unavailableReason: null,
        contextBundleVersion: 1,
        bundleBase64: Buffer.from('{"contextBundle":"e2e"}', 'utf8').toString('base64'),
      });
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
