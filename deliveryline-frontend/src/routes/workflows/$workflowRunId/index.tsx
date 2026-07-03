import { useState } from 'react';

import { Link, createFileRoute, notFound } from '@tanstack/react-router';

import { Stack } from '@/components/layout';
import { manualArtifactSubmitted } from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import { detailQueryOptions } from '@/lib/api/queryOptions';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { isValidClarificationId } from '@/lib/routing/publicId';
import {
  InvalidRouteParamError,
  assertValidRunRouteParams,
} from '@/lib/routing/routeParamValidation';
import { useWorkflowDetail } from '@/features/workflows/hooks/useWorkflowDetail';
import {
  resolveImplementationArtifact,
  resolveSpecArtifactId,
} from '@/features/workflows/approvalDecisionView';
import { RunContextStrip } from '@/features/workflows/components/RunContextStrip';
import { RunOriginBlock } from '@/features/workflows/components/RunOriginBlock';
import { ContextPanelSlot } from '@/features/workflows/ContextPanelSlot';
import { ClarificationRegionContainer } from '@/features/workflows/components/ClarificationRegionContainer';
import { WorkflowDecisionBar } from '@/features/workflows/components/WorkflowDecisionBar';
import { FailureEventSurface } from '@/features/workflows/components/FailureEventSurface';
import { StepExecutionLogViewer } from '@/features/workflows/components/StepExecutionLogViewer';
import { ProviderLimitStatus } from '@/features/workflows/components/ProviderLimitStatus';
import { RunDependencyPanel } from '@/features/workflows/components/RunDependencyPanel';
import { RunStepTokensPanel } from '@/features/workflows/components/RunStepTokensPanel';
import { SplitLineagePanel } from '@/features/workflows/components/SplitLineagePanel';
import { ManualExecutionSurface } from '@/features/workflows/components/ManualExecutionSurface';
import { ReadOnlyDiagnosticConsole } from '@/features/workflows/components/ReadOnlyDiagnosticConsole';
import { ReviewerVerdictPanelContainer } from '@/features/workflows/components/ReviewerVerdictPanel';
import { SplitProposalPanelContainer } from '@/features/workflows/components/SplitProposalPanel';
import { useAllowedActions } from '@/features/workflows/hooks/useAllowedActions';
import {
  GenericErrorState,
  InvalidLinkState,
  RunNotFoundState,
  UnrecognizedRunStateState,
} from '../../-states/DeadEndState';

/**
 * WorkflowDetailRoute (`/workflows/$workflowRunId`).
 *
 * AC10 — DO NOT split this route per stage. The backend-reported state selects the
 * Artifact Review Panel variant INSIDE this route; Epic 3 adds stages by widening
 * the recognized set, never by forking the route tree.
 *
 * Story 2.6 replaced the story-2.5 typed stub with a real prefetch:
 *   • the loader warms the TanStack Query cache via
 *     `context.queryClient.ensureQueryData(detailQueryOptions(id))`, so a deep link
 *     renders without a loading flash (AC3) and the component's `useWorkflowDetail`
 *     reads the SAME cache entry (dedup / structural sharing, AC10);
 *   • a backend `RUN_NOT_FOUND` (typed `ProblemDetailsError`) becomes
 *     `throw notFound()` → `RunNotFoundState` (AC4 / the story-2.6/2.28 SEAM);
 *   • the X-Correlation-Id header rides every request automatically via the client
 *     middleware (story 2.6/1.19 SEAM) — no per-loader header code.
 */

/**
 * Workflow states this build recognizes (the backend `currentState` enum, from
 * 6.9's OpenAPI schema). AC8a: a run reported in a state OUTSIDE this set (e.g. a
 * future Epic-3+ state seen by an older build) renders the explicit "newer state"
 * panel rather than crashing.
 *
 * NOTE (story 2.6 deliberate deviation): story 2.5's stub guarded a `currentStage`
 * ∈ {spec, implementation-plan, pr-output} and a `viewerAuthorized` flag — neither
 * field exists on the real backend `WorkflowDetail` (it exposes `currentState`, and
 * reports no per-viewer authorization). Keeping those guards verbatim would be dead
 * code failing `no-unnecessary-condition` (max-warnings=0), so the AC8 unrecognized-
 * state guard is re-pointed at the real `currentState` enum — strictly more correct
 * against the live contract. The recorded-role `PermissionRestrictedState` defers to
 * the story that ships role context; it stays exported and reachable via that story.
 */
const RECOGNIZED_STATES = new Set([
  'Inbox',
  'Planned',
  'Investigating',
  'WaitingForSpecApproval',
  'Executing',
  'WaitingForReview',
  'WaitingForManualExecution',
  // Story 3f-3 — dependency-gating state; also fills the pre-existing 'Split' omission so the
  // unrecognized-state guard does not flag real backend states.
  'WaitingForDependencies',
  'Split',
  'Completed',
  'Failed',
  'Paused',
  'TakenOver',
  'Reconciled',
]);

/**
 * Story 2.22 (AC1) — typed `?clarificationId=cla_xxx` deep-link search param.
 * `useNavigateToClarification` writes it; the Clarification Region (story 2.18)
 * reads it. Validated at the route boundary via `isValidClarificationId`, mirroring
 * the `beforeLoad` param defense — a malformed value is dropped, not surfaced.
 */
interface WorkflowDetailSearch {
  clarificationId?: string;
}

export const Route = createFileRoute('/workflows/$workflowRunId/')({
  validateSearch: (search: Record<string, unknown>): WorkflowDetailSearch =>
    typeof search.clarificationId === 'string' && isValidClarificationId(search.clarificationId)
      ? { clarificationId: search.clarificationId }
      : {},
  beforeLoad: ({ params }) => {
    // AC2 — reject malformed IDs at the route boundary so loaders never run for
    // impossible deep links.
    assertValidRunRouteParams(params.workflowRunId);
  },
  loader: async ({ context, params }) => {
    try {
      // AC3 — flash-free deep link: warm the cache the component reads.
      return await context.queryClient.ensureQueryData(detailQueryOptions(params.workflowRunId));
    } catch (error) {
      // AC4 — a well-formed id the backend has no run for → dedicated not-found state.
      if (isProblemDetailsError(error) && error.code === 'RUN_NOT_FOUND') {
        // `notFound()` returns a TanStack Router control-flow signal, not an Error;
        // throwing it is the documented router pattern for triggering notFoundComponent.
        // eslint-disable-next-line @typescript-eslint/only-throw-error
        throw notFound();
      }
      throw error;
    }
  },
  notFoundComponent: () => <RunNotFoundState />,
  errorComponent: ({ error }) =>
    error instanceof InvalidRouteParamError ? <InvalidLinkState /> : <GenericErrorState />,
  component: WorkflowDetailRoute,
});

function WorkflowDetailRoute() {
  const { workflowRunId } = Route.useParams();
  // Story 2.22 — the validated `?clarificationId` deep-link the Clarification Region reads.
  const { clarificationId } = Route.useSearch();
  // Reads the cache the loader already warmed (AC10 — one shared entry).
  const { data } = useWorkflowDetail(workflowRunId);
  // Story 3a-9 (AC6) — the real spec artifact id from the read model (story 2.19 resolver);
  // `undefined` until the run produces a spec artifact, which hides the artifact link.
  const specArtifactId = resolveSpecArtifactId(data);
  // Story 3b-3 (AC4) — the execution-stage implementation artifact id (story 3.28 resolver: prefers
  // the highest-version `prOutput`, falls back to `implementationPlan`); `undefined` until the
  // execution stage produces an implementation artifact, which hides the implementation-output link.
  const implArtifactId = resolveImplementationArtifact(data)?.artifactId;
  // Story 3d-5 (AC6) — the Step Execution Log Viewer is gated on the backend-reported
  // `view_runner_logs` action ONLY (flowing through `useAllowedActions`, never role-inferred —
  // eslint `local-rules/no-role-based-action-gating`). The action is offered in the
  // runner-execution states (Investigating / Executing / Failed / Paused / WaitingForReview); the
  // stream itself resolves the latest runner execution and ends gracefully when none exists.
  const allowedActions = useAllowedActions(workflowRunId);
  const canViewRunnerLogs = allowedActions.data?.actions.includes('view_runner_logs') ?? false;
  // Story 3d-7 (AC5) — the Provider Limit Status indicator is gated on the backend-reported
  // `view_provider_usage_status` action ONLY (via `useAllowedActions`, never role-inferred — eslint
  // `local-rules/no-role-based-action-gating`). The action is offered in the runner-execution states
  // (Executing / WaitingForReview / Failed / Paused); the read endpoint resolves the latest snapshot
  // and returns present=false when none exists, so broad state coverage is safe.
  const canViewProviderUsage =
    allowedActions.data?.actions.includes('view_provider_usage_status') ?? false;
  // Story 3d-6 (AC4/AC6) — the Read-only Diagnostic Console is gated on the backend-reported
  // `open_diagnostic_console` action ONLY (flowing through `useAllowedActions`, never role-inferred —
  // eslint `local-rules/no-role-based-action-gating`). The action is offered only in the EXECUTING
  // or INVESTIGATING states, and only to the run owner (workflow_owner), so we resolve the
  // owner-scoped action set; the stream is opened with the same role so the backend resolves the
  // SERVER-SIDE gate too. The endpoint re-checks liveness at attach and rejects a non-live execution
  // with console-not-live (LIVE-ONLY).
  const ownerActions = useAllowedActions(workflowRunId, 'workflow_owner');
  const canOpenDiagnosticConsole =
    ownerActions.data?.actions.includes('open_diagnostic_console') ?? false;
  // Story 3d-4 (AC7) — the Manual Execution Surface is gated on the backend-reported
  // `obtain_manual_bundle` / `submit_manual_artifact` actions ONLY (flowing through
  // `useAllowedActions`, never role-inferred — eslint `local-rules/no-role-based-action-gating`).
  // The matrix offers them ONLY in WAITING_FOR_MANUAL_EXECUTION to the run owner (workflow_owner).
  const canObtainManualBundle =
    ownerActions.data?.actions.includes('obtain_manual_bundle') ?? false;
  const canSubmitManualArtifact =
    ownerActions.data?.actions.includes('submit_manual_artifact') ?? false;
  // Story 3d-4 re-review — the Manual Execution Surface unmounts the instant a successful submit
  // advances the run out of WaitingForManualExecution, so the submit-RESULT announcement is owned
  // HERE (route-level, always mounted) and fired via the surface's `onSubmitted` callback so it
  // reaches assistive tech reliably (AC7 — announce submit result).
  const [manualArtifactWasSubmitted, setManualArtifactWasSubmitted] = useState(false);
  const manualSubmitResultAnnouncement = useLiveAnnouncement(
    manualArtifactWasSubmitted ? manualArtifactSubmitted : '',
  );

  // AC8a — a run reported in a state this build doesn't recognize.
  if (data?.currentState !== undefined && !RECOGNIZED_STATES.has(data.currentState)) {
    return <UnrecognizedRunStateState currentStage={data.currentState} />;
  }

  return (
    <Stack gap="4" className="items-start">
      {/* Story 2.16 — persistent run-context orientation strip, above the (future
          2.17) artifact body. Reads the same warmed `useWorkflowDetail` cache. */}
      <RunContextStrip workflowRunId={workflowRunId} />
      {/* Story 3g-2 (FR73) — the self-hiding Origin block: the originating ticket's title,
          ref, connector, and a gated link-out to the source ticket. Reads the same warmed
          `useWorkflowDetail` cache (no second fetch); renders nothing for an unlinked run. */}
      <RunOriginBlock detail={data} />
      {/* Story 3.30 (AC1/AC6) — the minimal failure-event surface + diagnostics panel.
          Renders nothing unless the run's event stream carries failure / recovery
          events (scope discipline — Epic 4 owns the full timeline). */}
      <FailureEventSurface workflowRunId={workflowRunId} />
      {/* Story 3f-3 (AC8) — the Run Dependency panel: prerequisites this run waits on, dependents
          waiting on it, and an explicit blocked-on signifier. Reads the embedded
          `WorkflowDetail.dependencies` read model; renders nothing for ordinary unlinked runs. */}
      <RunDependencyPanel dependencies={data?.dependencies} />
      {/* Story 3f-7 (AC6) — the Split Lineage panel: a Split parent's "decomposed — N of M
          descendants complete" progress. Reads the embedded `WorkflowDetail.decompositionStatus`;
          renders nothing for non-Split runs and disappears once the parent rolls up to Completed. */}
      <SplitLineagePanel decompositionStatus={data?.decompositionStatus} />
      {/* Story 3d-7 (AC5) — the Provider Limit Status indicator: latest provider 5h/weekly usage
          status (or the documented "not exposed" state), gated on the backend
          `view_provider_usage_status` action. Color-independent signifier; provider-reported + as-of. */}
      {canViewProviderUsage ? <ProviderLimitStatus workflowRunId={workflowRunId} /> : null}
      {/* Story 3g-4 (FR74, AC1/AC2/AC3) — per-step token usage + the run-level total. Read-only
          diagnostic surface (no AllowedAction gate — mirrors reviewer-verdict); the panel owns its
          own GET /steps query and the run total is the backend-computed `WorkflowDetail.totalTokens`
          (never re-summed on the FE). Self-hides when the run has no runner executions yet. */}
      <RunStepTokensPanel workflowRunId={workflowRunId} totalTokens={data?.totalTokens} />

      {/* Story 3d-4 (AC7) — the Manual Execution Surface: for a run parked in
          WaitingForManualExecution, download/copy the input bundle + submit the operator-produced
          artifact. Gated on the backend `obtain_manual_bundle` / `submit_manual_artifact` actions. */}
      {data?.currentState === 'WaitingForManualExecution' &&
      (canObtainManualBundle || canSubmitManualArtifact) ? (
        <ManualExecutionSurface
          workflowRunId={workflowRunId}
          canObtainBundle={canObtainManualBundle}
          canSubmitArtifact={canSubmitManualArtifact}
          onSubmitted={() => setManualArtifactWasSubmitted(true)}
        />
      ) : null}
      {/* Story 3d-4 re-review — PERSISTENT submit-result announcer (always mounted): the surface above
          unmounts as soon as the run advances, so the result announcement fires from here (AC7). */}
      <div
        role="status"
        aria-live="polite"
        className="sr-only"
        data-testid="manual-execution-result-announcer"
      >
        {manualSubmitResultAnnouncement}
      </div>
      {/* Story 2.18 — the Clarification Region, projected into the right context
          panel via the AppShell slot (sidebar subregion, AC4). The main pane stays
          artifact-primary; today the region renders the calm `no open questions`
          empty state (the disabled `useClarifications` stub maps to an empty view). */}
      <ContextPanelSlot>
        <ClarificationRegionContainer
          workflowRunId={workflowRunId}
          clarificationId={clarificationId}
        />
      </ContextPanelSlot>
      <Link
        to="/workflows"
        className="text-meta text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
      >
        &larr; Back to queue
      </Link>
      <h1 className="text-page-title">Workflow run</h1>
      <p className="text-meta text-text-tertiary">
        <code>{workflowRunId}</code>
        {data?.currentState !== undefined ? (
          <>
            {' '}
            &middot; state <code>{data.currentState}</code>
          </>
        ) : null}
      </p>
      <p className="text-body text-text-secondary max-w-prose">
        Navigation skeleton (story 2.5) now backed by the live data layer (story 2.6) and hosted in
        the tri-pane shell (story 2.7). The Artifact Review Panel (story 2.17) renders in the
        artifact-viewer route — open it via the link below.
      </p>
      {specArtifactId !== undefined ? (
        <Link
          to="/workflows/$workflowRunId/artifacts/$artifactId"
          params={{ workflowRunId, artifactId: specArtifactId }}
          className="text-body text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
        >
          Open the specification &rarr;
        </Link>
      ) : null}
      {/* Story 3b-3 (AC4) — the execution-stage twin of the spec link: once the run has produced an
          implementation artifact (plan or pr-output), surface a link to the artifact-viewer route so
          the developer reviewer at WaitingForReview can reach the implementation output. Gated on a
          resolved id; the generic viewer renders the raw artifact (the rich prOutput PR/diff renderer
          is story 3b-5). */}
      {implArtifactId !== undefined ? (
        <Link
          to="/workflows/$workflowRunId/artifacts/$artifactId"
          params={{ workflowRunId, artifactId: implArtifactId }}
          className="text-body text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
        >
          Open the implementation output &rarr;
        </Link>
      ) : null}
      {/* Decision Bar (AC4 sticky footer). Story 3.30 makes the mode state-driven: a
          `Failed` run gets the `recovery_operator` bar (the "Retry failed step" action);
          every other state gets the story-2.19 `spec_approval` bar. The selector owns the
          retry mutation so the recovery bar survives the post-retry Failed→Executing flip
          (success panel + AC7 announcement) — see `WorkflowDecisionBar`. */}
      {/* Story 3d-2 (AC3) / 3e-3 (AC4) — the advisory Reviewer Verdict Panel, beside the Decision
          Bar at WaitingForReview (execution gate, 3d-2) AND WaitingForSpecApproval (spec gate,
          3e-3). Presentational + advisory-only (never gates the human decision); the panel + its
          run-scoped GET /reviewer-verdict fetch are stage-agnostic, so the same container renders
          the latest verdict at whichever gate the run is parked. Renders nothing for a
          no-reviewer-binding project (AC6, self-hides via the endpoint state). */}
      {data?.currentState === 'WaitingForReview' ||
      data?.currentState === 'WaitingForSpecApproval' ? (
        <ReviewerVerdictPanelContainer workflowRunId={workflowRunId} />
      ) : null}
      {/* Story 3f-4 (advisory split-proposal channel) — beside the Decision Bar at the same two
          gates (WaitingForSpecApproval / WaitingForReview). Front-half / advisory ONLY: surfaces a
          model-proposed decomposition + the governed request / re-propose / continue-as-single
          loop actions (allowed-actions-driven). Renders nothing when no proposal exists and no
          split action is advertised. */}
      {data?.currentState === 'WaitingForReview' ||
      data?.currentState === 'WaitingForSpecApproval' ? (
        <SplitProposalPanelContainer workflowRunId={workflowRunId} />
      ) : null}
      <WorkflowDecisionBar workflowRunId={workflowRunId} />
      {/* Story 3d-5 (AC4/AC6) — the Step Execution Log Viewer: live-follow + finished replay of
          the latest runner execution's logs, gated on the backend `view_runner_logs` action. Story
          3e-5 (AC4) relocates it BELOW the Decision Bar so the runner's console output sits beneath
          the action controls (and is now offered in the Investigating/spec-generation state too). */}
      {canViewRunnerLogs ? <StepExecutionLogViewer workflowRunId={workflowRunId} /> : null}
      {/* Story 3d-6 (AC4/AC6) — the Read-only Diagnostic Console: a LIVE-ONLY, read-only attach to
          the running runner container's stdio, gated on the backend `open_diagnostic_console` action
          (workflow_owner; Executing + — story 3e-5 — Investigating). Input is disabled end-to-end
          (no write path). Relocated below the Decision Bar by story 3e-5 (AC4). */}
      {canOpenDiagnosticConsole ? (
        <ReadOnlyDiagnosticConsole workflowRunId={workflowRunId} actorRole="workflow_owner" />
      ) : null}
    </Stack>
  );
}
