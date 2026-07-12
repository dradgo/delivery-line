package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsView;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.AllowedAction;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Story 2.14 AC3 / AC4 / AC5 / AC7 / AC8 — focused unit tests for {@link
 * WorkflowInspectionService#getAllowedActions(String, String)}. Pins the state×role decision
 * matrix, the AC4 clarification gating, the version-stamp shape (TRAP 2 included), the
 * UNKNOWN_ACTOR_ROLE rejection, the {@code product_reviewer} default, and the future-state guard
 * that fails CI if a new {@link WorkflowState} ever lands without a matrix update.
 */
class WorkflowInspectionServiceAllowedActionsTest {

  private static final String RUN = "run_allowed_a";
  private static final String SPEC_ART = "art_spec_allowed_a";
  private static final String PR_ART = "art_pr_allowed_a";
  private static final String LATEST_EVT = "evt_allowed_a";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-27T12:00:00Z");

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventReadPort events = mock(WorkflowEventReadPort.class);
  private final ArtifactRecordPort artifacts = mock(ArtifactRecordPort.class);
  private final org.dradgo.application.approval.spi.ApprovalReadPort approvals =
      mock(org.dradgo.application.approval.spi.ApprovalReadPort.class);
  private final IntegrationLinkService links = mock(IntegrationLinkService.class);
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final RunnerExecutionRecordPort runnerExecutions = mock(RunnerExecutionRecordPort.class);
  private final RunnerScratchStore scratchStore = mock(RunnerScratchStore.class);
  private final org.dradgo.application.clarification.spi.ClarificationReadPort clarifications =
      mock(org.dradgo.application.clarification.spi.ClarificationReadPort.class);
  private final SplitProposalReadPort splitProposals = mock(SplitProposalReadPort.class);

  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          runs,
          events,
          artifacts,
          mock(org.dradgo.application.artifact.spi.ArtifactPayloadStore.class),
          approvals,
          links,
          new RedactionPolicyService(new DataClassificationService()),
          recovery,
          runnerExecutions,
          scratchStore,
          clarifications,
          mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
          org.dradgo.application.runner.RunnerProperties.defaults(),
          org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults(),
          splitProposals);

  // ---------------------------------------------------------------------------
  // AC3 — parameterized matrix coverage
  // ---------------------------------------------------------------------------

  static Stream<Arguments> matrixCases() {
    // Story 3d-8 / review decision D1: archive_run / unarchive_run are gated to workflow_owner only
    // (mirroring RETRY / OPEN_DIAGNOSTIC_CONSOLE). Non-owner rows therefore carry NO archive
    // action.
    return Stream.of(
        Arguments.of(WorkflowState.INBOX, "product_reviewer", List.of(AllowedAction.VIEW_ONLY)),
        Arguments.of(
            WorkflowState.INBOX,
            "workflow_owner",
            List.of(AllowedAction.VIEW_ONLY, AllowedAction.ARCHIVE_RUN)),
        Arguments.of(WorkflowState.PLANNED, "product_reviewer", List.of(AllowedAction.VIEW_ONLY)),
        Arguments.of(
            WorkflowState.PLANNED,
            "workflow_owner",
            List.of(AllowedAction.VIEW_ONLY, AllowedAction.ARCHIVE_RUN)),
        // Story 3e-5 (AC1/AC2) — Investigating is a live spec-runner state, so view_runner_logs
        // (role-agnostic) and open_diagnostic_console (workflow_owner only) join the arm, mirroring
        // the EXECUTING role split (minus await_outcome / view_provider_usage_status — R3). These
        // rows are the no-open-clarification branch (stubNoLatestSpec).
        Arguments.of(
            WorkflowState.INVESTIGATING,
            "product_reviewer",
            List.of(AllowedAction.VIEW_ONLY, AllowedAction.VIEW_RUNNER_LOGS)),
        // Story 4.8 (AC10) — pause_workflow joins EVERY pausable-source workflow_owner arm.
        Arguments.of(
            WorkflowState.INVESTIGATING,
            "workflow_owner",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.OPEN_DIAGNOSTIC_CONSOLE,
                AllowedAction.PAUSE_WORKFLOW,
                AllowedAction.ARCHIVE_RUN)),
        Arguments.of(
            WorkflowState.WAITING_FOR_SPEC_APPROVAL,
            "product_reviewer",
            List.of(
                AllowedAction.APPROVE_SPEC,
                AllowedAction.REJECT_SPEC,
                AllowedAction.ANSWER_CLARIFICATION,
                // Story 3e-2 (AC1/AC2) — accept + regenerate joined the reviewer arm.
                AllowedAction.ACCEPT_CLARIFICATION,
                AllowedAction.REGENERATE_SPEC,
                // Story 3f-4 (AC1) — split overlay fires at the spec gate; default mock
                // (no open proposal) appends request_split.
                AllowedAction.REQUEST_SPLIT)),
        Arguments.of(
            WorkflowState.WAITING_FOR_SPEC_APPROVAL,
            "workflow_owner",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.ANSWER_CLARIFICATION,
                // Story 3e-2 (AC1/AC2) — accept + regenerate joined the reviewer arm (before the
                // workflow_owner-only archive_run wrapper action).
                AllowedAction.ACCEPT_CLARIFICATION,
                AllowedAction.REGENERATE_SPEC,
                // Story 4.8 (AC10) — pause_workflow closes the base arm (before the overlays).
                AllowedAction.PAUSE_WORKFLOW,
                // Story 3f-4 (AC1) — split overlay (request_split) is appended before the
                // workflow_owner-only archive_run wrapper action.
                AllowedAction.REQUEST_SPLIT,
                AllowedAction.ARCHIVE_RUN)),
        // Story 3d-5 (AC6) — view_runner_logs joins the runner-execution states, role-agnostic.
        Arguments.of(
            WorkflowState.EXECUTING,
            "product_reviewer",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.AWAIT_OUTCOME,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS)),
        // Story 3d-6 (AC4) — the read-only diagnostic console is offered ONLY here (EXECUTING) and
        // ONLY to the run owner (workflow_owner); product_reviewer above keeps the role-agnostic
        // set.
        Arguments.of(
            WorkflowState.EXECUTING,
            "workflow_owner",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.AWAIT_OUTCOME,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.OPEN_DIAGNOSTIC_CONSOLE,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                AllowedAction.PAUSE_WORKFLOW,
                AllowedAction.ARCHIVE_RUN)),
        Arguments.of(
            WorkflowState.WAITING_FOR_REVIEW,
            "product_reviewer",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS)),
        // Story 4.7 (AC10) — the workflow_owner at WAITING_FOR_REVIEW may rerun-from-step
        // (rerun_from_step leads the arm, before the view/log actions + the workflow_owner-only
        // archive_run wrapper action).
        Arguments.of(
            WorkflowState.WAITING_FOR_REVIEW,
            "workflow_owner",
            List.of(
                AllowedAction.RERUN_FROM_STEP,
                AllowedAction.PAUSE_WORKFLOW,
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                AllowedAction.ARCHIVE_RUN)),
        // Story 3.20 AC12 + Story 3.21 AC9 + Story 3.22 AC9 — the developer-review actor may
        // accept,
        // reject, OR take over the implementation here. Story 3d-5 (AC6) adds view_runner_logs.
        Arguments.of(
            WorkflowState.WAITING_FOR_REVIEW,
            "developer",
            List.of(
                AllowedAction.ACCEPT_IMPLEMENTATION,
                AllowedAction.REJECT_IMPLEMENTATION,
                AllowedAction.TAKEOVER_WORKFLOW,
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                // Story 3f-4 (AC1) — split overlay fires at the review gate for developer;
                // default mock (no open proposal) appends request_split.
                AllowedAction.REQUEST_SPLIT)),
        // Story 3.20 (review) — `developer` is now recognized in EVERY state; pin its role-agnostic
        // fallback outside WAITING_FOR_REVIEW so a future matrix change can't silently grant it an
        // unintended action elsewhere.
        Arguments.of(
            WorkflowState.WAITING_FOR_SPEC_APPROVAL,
            "developer",
            List.of(AllowedAction.VIEW_ONLY, AllowedAction.ANSWER_CLARIFICATION)),
        Arguments.of(
            WorkflowState.FAILED,
            "developer",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_DIAGNOSTICS,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS)),
        // Story 3d-3 (AC7) — a WaitingForManualExecution run offers the bundle-obtain + artifact-
        // submit actions to the local operator (workflow_owner); every other role gets view_only.
        Arguments.of(
            WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
            "workflow_owner",
            List.of(
                AllowedAction.OBTAIN_MANUAL_BUNDLE,
                AllowedAction.SUBMIT_MANUAL_ARTIFACT,
                AllowedAction.VIEW_ONLY,
                AllowedAction.PAUSE_WORKFLOW,
                AllowedAction.ARCHIVE_RUN)),
        Arguments.of(
            WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
            "product_reviewer",
            List.of(AllowedAction.VIEW_ONLY)),
        Arguments.of(
            WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
            "developer",
            List.of(AllowedAction.VIEW_ONLY)),
        // Story 3h-4 (AC3) — a WaitingForDelivery run offers approve_delivery to the workflow_owner
        // gate role (+ the log/usage views); every other role gets view-only + the views.
        Arguments.of(
            WorkflowState.WAITING_FOR_DELIVERY,
            "workflow_owner",
            List.of(
                AllowedAction.APPROVE_DELIVERY,
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                AllowedAction.PAUSE_WORKFLOW,
                AllowedAction.ARCHIVE_RUN)),
        // Story 4.8 (AC10) — the lint gate's matrix rows were previously unpinned here; pin both
        // roles now that pause_workflow joins the workflow_owner arm.
        Arguments.of(
            WorkflowState.WAITING_FOR_LINT_APPROVAL,
            "workflow_owner",
            List.of(
                AllowedAction.APPROVE_LINT,
                AllowedAction.REQUEST_LINT_FIX,
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                AllowedAction.PAUSE_WORKFLOW,
                AllowedAction.ARCHIVE_RUN)),
        Arguments.of(
            WorkflowState.WAITING_FOR_LINT_APPROVAL,
            "product_reviewer",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS)),
        Arguments.of(
            WorkflowState.WAITING_FOR_DELIVERY,
            "product_reviewer",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS)),
        Arguments.of(WorkflowState.COMPLETED, "product_reviewer", List.of(AllowedAction.VIEW_ONLY)),
        Arguments.of(
            WorkflowState.COMPLETED,
            "workflow_owner",
            List.of(AllowedAction.VIEW_ONLY, AllowedAction.ARCHIVE_RUN)),
        Arguments.of(
            WorkflowState.FAILED,
            "product_reviewer",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_DIAGNOSTICS,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS)),
        // Story 4.7 (AC10) — the workflow_owner at FAILED may rerun-from-step alongside retry
        // (rerun_from_step follows retry, before the diagnostics/log views + archive_run wrapper).
        Arguments.of(
            WorkflowState.FAILED,
            "workflow_owner",
            List.of(
                AllowedAction.RETRY,
                AllowedAction.RERUN_FROM_STEP,
                AllowedAction.PAUSE_WORKFLOW,
                AllowedAction.VIEW_DIAGNOSTICS,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                AllowedAction.ARCHIVE_RUN)),
        Arguments.of(
            WorkflowState.PAUSED,
            "product_reviewer",
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_DIAGNOSTICS,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS)),
        Arguments.of(
            WorkflowState.PAUSED,
            "workflow_owner",
            List.of(
                AllowedAction.RESUME_WORKFLOW,
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_DIAGNOSTICS,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                AllowedAction.ARCHIVE_RUN)),
        Arguments.of(
            WorkflowState.TAKEN_OVER, "product_reviewer", List.of(AllowedAction.VIEW_ONLY)),
        Arguments.of(
            WorkflowState.TAKEN_OVER,
            "workflow_owner",
            List.of(AllowedAction.VIEW_ONLY, AllowedAction.ARCHIVE_RUN)),
        Arguments.of(
            WorkflowState.RECONCILED, "product_reviewer", List.of(AllowedAction.VIEW_ONLY)),
        Arguments.of(
            WorkflowState.RECONCILED,
            "workflow_owner",
            List.of(AllowedAction.VIEW_ONLY, AllowedAction.ARCHIVE_RUN)));
  }

  @ParameterizedTest
  @MethodSource("matrixCases")
  void matrixCoversEveryStateAndRow(
      WorkflowState state, String role, List<AllowedAction> expected) {
    stubRunWithState(state, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, role);

    assertThat(view.actions()).containsExactlyElementsOf(expected);
    assertThat(view.versionStamp().workflowState()).isEqualTo(state.value());
  }

  @Test
  void liveRunAdvertisesArchiveRunAsFinalAction() {
    // Story 3d-8 (AC3): a non-archived run offers archive_run (mutually exclusive with
    // unarchive_run).
    stubRunWithState(WorkflowState.FAILED, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "workflow_owner");

    assertThat(view.actions()).contains(AllowedAction.ARCHIVE_RUN);
    assertThat(view.actions()).doesNotContain(AllowedAction.UNARCHIVE_RUN);
    assertThat(view.actions().get(view.actions().size() - 1)).isEqualTo(AllowedAction.ARCHIVE_RUN);
  }

  @Test
  void archivedRunAdvertisesUnarchiveRunInsteadOfArchiveRun() {
    // Story 3d-8 (AC4): an already-archived run offers unarchive_run, never archive_run.
    stubArchivedRunWithState(WorkflowState.FAILED);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "workflow_owner");

    assertThat(view.actions()).contains(AllowedAction.UNARCHIVE_RUN);
    assertThat(view.actions()).doesNotContain(AllowedAction.ARCHIVE_RUN);
  }

  @Test
  void unresolvedConflictAddsReconcileActionForWorkflowOwnerOnly() {
    org.dradgo.application.integration.conflict.IntegrationConflictService conflicts =
        mock(org.dradgo.application.integration.conflict.IntegrationConflictService.class);
    service.setIntegrationConflictService(conflicts);
    when(conflicts.listUnresolvedConflicts(
            org.mockito.ArgumentMatchers.any(
                org.dradgo.application.integration.conflict.ConflictFilter.class)))
        .thenReturn(
            List.of(
                new org.dradgo.application.integration.conflict.ConflictSummary(
                    "icf_allowed00001",
                    "ilk_allowed00001",
                    RUN,
                    org.dradgo.domain.registry.IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED
                        .value(),
                    org.dradgo.application.integration.conflict.ConflictIntegrationTypes.GITHUB_PR,
                    "octo/hello#42",
                    NOW.toInstant())));
    stubRunWithState(WorkflowState.FAILED, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView owner = service.getAllowedActions(RUN, "workflow_owner");
    AllowedActionsView reviewer = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(owner.actions())
        .containsSubsequence(AllowedAction.RECONCILE_CONFLICT, AllowedAction.ARCHIVE_RUN);
    assertThat(reviewer.actions()).doesNotContain(AllowedAction.RECONCILE_CONFLICT);
  }

  // ---------------------------------------------------------------------------
  // Story 3d-2 AC8 — the advisory Reviewer Verdict Panel adds NO governed action
  // ---------------------------------------------------------------------------

  @Test
  void waitingForReviewActionMatrixIsUnchangedByReviewerVerdictPanel() {
    // Story 3d-2 AC8 / Task 7: the reviewer verdict is surfaced advisory-only in a presentational
    // panel — it must NOT add, remove, or reorder any governed action in WaitingForReview. Pin the
    // exact pre-3d-2 matrix for every recognized role so a future change that accidentally couples
    // the verdict to allowed-actions fails here (byte-identical assertion the Task-7 text called
    // for).
    for (String role : new String[] {"product_reviewer", "workflow_owner", "developer"}) {
      stubRunWithState(WorkflowState.WAITING_FOR_REVIEW, 0);
      stubNoLatestSpec();
      stubLatestEvent(LATEST_EVT);

      AllowedActionsView view = service.getAllowedActions(RUN, role);

      List<AllowedAction> expected;
      if (role.equals("developer")) {
        expected =
            List.of(
                AllowedAction.ACCEPT_IMPLEMENTATION,
                AllowedAction.REJECT_IMPLEMENTATION,
                AllowedAction.TAKEOVER_WORKFLOW,
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                // Story 3f-4 (AC1) — split overlay (request_split) fires for the developer at
                // the review gate; it is advisory and orthogonal to the reviewer verdict panel.
                AllowedAction.REQUEST_SPLIT);
      } else if (role.equals("workflow_owner")) {
        // Story 4.7 (AC10) — rerun_from_step leads the workflow_owner arm; archive_run is
        // workflow_owner-only (3d-8/D1). Story 4.8 (AC10) — pause_workflow follows it. Still
        // orthogonal to the reviewer verdict panel.
        expected =
            List.of(
                AllowedAction.RERUN_FROM_STEP,
                AllowedAction.PAUSE_WORKFLOW,
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
                AllowedAction.ARCHIVE_RUN);
      } else {
        expected =
            List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
      }
      assertThat(view.actions())
          .as("WaitingForReview matrix for role %s must be unchanged by 3d-2 (AC8)", role)
          .containsExactlyElementsOf(expected);
    }
  }

  // ---------------------------------------------------------------------------
  // Story 3f-4 AC8 — split overlay at both gates × open/no-open proposal
  // ---------------------------------------------------------------------------

  @Test
  void specApprovalWithOpenSplitProposalOffersApproveReproposeAndDeclineNotRequest() {
    // Story 3f-4 (AC1/AC8) + 3f-5 (AC1): an OPEN split proposal flips the overlay from
    // request_split
    // to the approve/repropose/decline action set at the spec gate.
    stubRunWithState(WorkflowState.WAITING_FOR_SPEC_APPROVAL, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);
    when(splitProposals.hasOpenForRun(RUN)).thenReturn(true);

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.actions())
        .contains(
            AllowedAction.APPROVE_SPLIT, AllowedAction.REPROPOSE_SPLIT, AllowedAction.DECLINE_SPLIT)
        .doesNotContain(AllowedAction.REQUEST_SPLIT);
  }

  @Test
  void waitingForReviewWithOpenSplitProposalOffersApproveReproposeAndDeclineNotRequest() {
    // Story 3f-4 (AC1/AC8) + 3f-5 (AC1): same flip for the developer at the review gate.
    stubRunWithState(WorkflowState.WAITING_FOR_REVIEW, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);
    when(splitProposals.hasOpenForRun(RUN)).thenReturn(true);

    AllowedActionsView view = service.getAllowedActions(RUN, "developer");

    assertThat(view.actions())
        .contains(
            AllowedAction.APPROVE_SPLIT, AllowedAction.REPROPOSE_SPLIT, AllowedAction.DECLINE_SPLIT)
        .doesNotContain(AllowedAction.REQUEST_SPLIT);
  }

  @Test
  void noOpenSplitProposalDoesNotOfferApproveSplit() {
    // Story 3f-5 (AC1): approve_split appears ONLY when an open proposal exists; the no-open
    // overlay
    // offers request_split and never approve_split.
    stubRunWithState(WorkflowState.WAITING_FOR_SPEC_APPROVAL, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);
    when(splitProposals.hasOpenForRun(RUN)).thenReturn(false);

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.actions())
        .contains(AllowedAction.REQUEST_SPLIT)
        .doesNotContain(AllowedAction.APPROVE_SPLIT);
  }

  // ---------------------------------------------------------------------------
  // AC4 — clarification gating
  // ---------------------------------------------------------------------------

  @Test
  void waitingForSpecApprovalWithPendingClarificationsDropsApproveSpec() {
    stubRunWithState(WorkflowState.WAITING_FOR_SPEC_APPROVAL, 3);
    stubLatestSpec(2);
    stubBundleAvailable(SPEC_ART, 5);
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.actions())
        .containsExactly(
            AllowedAction.REJECT_SPEC,
            AllowedAction.ANSWER_CLARIFICATION,
            // Story 3e-2 (AC1/AC2) — accept + regenerate surface even while clarifications pend.
            AllowedAction.ACCEPT_CLARIFICATION,
            AllowedAction.REGENERATE_SPEC,
            // Story 3f-4 (AC1) — split overlay (request_split) fires at the spec gate regardless
            // of pending clarifications.
            AllowedAction.REQUEST_SPLIT);
    assertThat(view.versionStamp().currentSpecArtifactVersion()).isEqualTo(2);
    assertThat(view.versionStamp().currentContextBundleVersion()).isEqualTo(5);
  }

  @Test
  void waitingForSpecApprovalWithZeroPendingClarificationsKeepsApproveSpec() {
    stubRunWithState(WorkflowState.WAITING_FOR_SPEC_APPROVAL, 0);
    stubLatestSpec(1);
    stubBundleAvailable(SPEC_ART, 1);
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.actions())
        .containsExactly(
            AllowedAction.APPROVE_SPEC,
            AllowedAction.REJECT_SPEC,
            AllowedAction.ANSWER_CLARIFICATION,
            // Story 3e-2 (AC1/AC2) — accept + regenerate joined the reviewer arm.
            AllowedAction.ACCEPT_CLARIFICATION,
            AllowedAction.REGENERATE_SPEC,
            // Story 3f-4 (AC1) — split overlay (request_split) fires at the spec gate.
            AllowedAction.REQUEST_SPLIT);
  }

  // ---------------------------------------------------------------------------
  // AC3 Investigating — open-clarification semantics
  // ---------------------------------------------------------------------------

  @Test
  void investigatingWithOpenClarificationIncludesAnswerClarification() {
    stubRunWithState(WorkflowState.INVESTIGATING, 0);
    stubLatestSpec(1);
    stubBundleAvailable(SPEC_ART, 1);
    stubLatestEvent(LATEST_EVT);
    when(clarifications.listByArtifactId(SPEC_ART))
        .thenReturn(List.of(openClarification("clr_open_a", SPEC_ART)));

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    // Story 3e-5 (AC2) — view_runner_logs joins the open-clarification branch (role-agnostic),
    // alongside the existing view_only + answer_clarification.
    assertThat(view.actions())
        .containsExactly(
            AllowedAction.VIEW_ONLY,
            AllowedAction.ANSWER_CLARIFICATION,
            AllowedAction.VIEW_RUNNER_LOGS);
  }

  @Test
  void investigatingWithZeroOpenClarificationsOffersViewAndRunnerLogs() {
    // Story 3e-5 (AC2) — renamed from `...IsViewOnlyOnly`: the no-open-clarification branch now
    // also offers view_runner_logs (role-agnostic), so it is no longer view-only.
    stubRunWithState(WorkflowState.INVESTIGATING, 0);
    stubLatestSpec(1);
    stubBundleAvailable(SPEC_ART, 1);
    stubLatestEvent(LATEST_EVT);
    when(clarifications.listByArtifactId(SPEC_ART)).thenReturn(List.of());

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.actions())
        .containsExactly(AllowedAction.VIEW_ONLY, AllowedAction.VIEW_RUNNER_LOGS);
  }

  // ---------------------------------------------------------------------------
  // AC3 Failed divergence
  // ---------------------------------------------------------------------------

  @Test
  void failedAsProductReviewerYieldsViewOnlyAndDiagnostics() {
    stubRunWithState(WorkflowState.FAILED, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.actions())
        .containsExactly(
            AllowedAction.VIEW_ONLY,
            AllowedAction.VIEW_DIAGNOSTICS,
            AllowedAction.VIEW_RUNNER_LOGS,
            AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
  }

  @Test
  void failedAsWorkflowOwnerYieldsRetryAndDiagnostics() {
    stubRunWithState(WorkflowState.FAILED, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "workflow_owner");

    assertThat(view.actions())
        .containsExactly(
            AllowedAction.RETRY,
            // Story 4.7 (AC10) — rerun_from_step joins the FAILED workflow_owner arm alongside
            // retry.
            AllowedAction.RERUN_FROM_STEP,
            // Story 4.8 (AC10) — pause_workflow follows (FAILED is a mandatory pausable source).
            AllowedAction.PAUSE_WORKFLOW,
            AllowedAction.VIEW_DIAGNOSTICS,
            AllowedAction.VIEW_RUNNER_LOGS,
            AllowedAction.VIEW_PROVIDER_USAGE_STATUS,
            AllowedAction.ARCHIVE_RUN);
  }

  // ---------------------------------------------------------------------------
  // AC5 — version stamp shape
  // ---------------------------------------------------------------------------

  @Test
  void versionStampReflectsLatestSpecArtifactVersionEvenWhenUnapproved() {
    // TRAP 2: deliberately use a DRAFTED (not approved) spec — the stamp must still surface its
    // version so the reviewer's UI agrees with the backend on what's currently in play.
    stubRunWithState(WorkflowState.WAITING_FOR_SPEC_APPROVAL, 0);
    stubLatestSpec(7, ArtifactStatus.PENDING);
    stubBundleAvailable(SPEC_ART, 12);
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.versionStamp().currentSpecArtifactVersion()).isEqualTo(7);
    assertThat(view.versionStamp().currentContextBundleVersion()).isEqualTo(12);
    assertThat(view.versionStamp().lastEventId()).isEqualTo(LATEST_EVT);
  }

  @Test
  void versionStampSpecAndBundleAreNullWhenNoSpecExists() {
    // TRAP 2 second half: fresh run, no spec yet — both version fields must serialize as null.
    stubRunWithState(WorkflowState.INBOX, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.versionStamp().currentSpecArtifactVersion()).isNull();
    assertThat(view.versionStamp().currentContextBundleVersion()).isNull();
    assertThat(view.versionStamp().lastEventId()).isEqualTo(LATEST_EVT);
    assertThat(view.versionStamp().workflowState()).isEqualTo("Inbox");
  }

  @Test
  void versionStampBundleIsNullWhenSpecExistsButRunnerExecutionMissing() {
    stubRunWithState(WorkflowState.WAITING_FOR_SPEC_APPROVAL, 0);
    stubLatestSpec(1);
    // No runner-execution link recorded for this artifact — bundle lookup returns unavailable.
    when(artifacts.findRunnerExecutionIdForArtifact(SPEC_ART)).thenReturn(Optional.empty());
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.versionStamp().currentSpecArtifactVersion()).isEqualTo(1);
    assertThat(view.versionStamp().currentContextBundleVersion()).isNull();
  }

  @Test
  void versionStampBundleReflectsImplementationArtifactAtWaitingForReview() {
    // At WaitingForReview the developer decides on the IMPLEMENTATION artifact (prOutput), whose
    // producing runner-execution carries the EXECUTION context-bundle version — which diverges from
    // the spec bundle after a retry (retry mints a fresh execution version). The stamp MUST report
    // that execution bundle so the value the UI echoes back matches what the accept/reject binder
    // (ApprovalVersionBinder) demands; reporting the spec bundle (the old, spec-centric behaviour)
    // caused a PERMANENT APPROVAL_VERSION_MISMATCH 409 that no refresh could clear.
    stubRunWithState(WorkflowState.WAITING_FOR_REVIEW, 0);
    stubLatestSpec(1);
    stubBundleAvailable(SPEC_ART, 1); // the OLD spec-centric stamp would have surfaced this (1).
    stubLatestPrOutput(2, 3); // prOutput v2 produced by a retried EXECUTION → bundle version 3.
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView view = service.getAllowedActions(RUN, "developer");

    assertThat(view.versionStamp().currentContextBundleVersion()).isEqualTo(3);
  }

  @Test
  void versionStampLastEventIdIsLatestEventPublicId() {
    stubRunWithState(WorkflowState.COMPLETED, 0);
    stubNoLatestSpec();
    stubLatestEvent("evt_xyz_999");

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.versionStamp().lastEventId()).isEqualTo("evt_xyz_999");
  }

  // ---------------------------------------------------------------------------
  // AC7 — UNKNOWN_ACTOR_ROLE + default fallback
  // ---------------------------------------------------------------------------

  @Test
  void unknownActorRoleThrowsUnknownActorRoleDomainException() {
    stubRunWithState(WorkflowState.INBOX, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    assertThatThrownBy(() -> service.getAllowedActions(RUN, "auditor"))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.UNKNOWN_ACTOR_ROLE);
              assertThat(ex.details()).containsEntry("actorRole", "auditor");
            });
  }

  @Test
  void blankActorRoleDefaultsToProductReviewer() {
    stubRunWithState(WorkflowState.WAITING_FOR_SPEC_APPROVAL, 0);
    stubLatestSpec(1);
    stubBundleAvailable(SPEC_ART, 1);
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView blank = service.getAllowedActions(RUN, "  ");
    AllowedActionsView nullRole = service.getAllowedActions(RUN, null);

    // product_reviewer in WaitingForSpecApproval with zero pending clarifications → full set
    // (no archive_run — that is workflow_owner-only per 3d-8/D1). Story 3e-2 added accept + regen.
    List<AllowedAction> expected =
        List.of(
            AllowedAction.APPROVE_SPEC,
            AllowedAction.REJECT_SPEC,
            AllowedAction.ANSWER_CLARIFICATION,
            AllowedAction.ACCEPT_CLARIFICATION,
            AllowedAction.REGENERATE_SPEC,
            // Story 3f-4 (AC1) — blank/null role defaults to product_reviewer, so the split
            // overlay (request_split) fires at the spec gate.
            AllowedAction.REQUEST_SPLIT);
    assertThat(blank.actions()).containsExactlyElementsOf(expected);
    assertThat(nullRole.actions()).containsExactlyElementsOf(expected);
  }

  // ---------------------------------------------------------------------------
  // AC8 — future-state guard
  // ---------------------------------------------------------------------------

  @Test
  void futureStateGuard_everyWorkflowStateHasNonEmptyActionSet() {
    // Review P9: iterate both recognized roles so a future matrix gap that only affects
    // workflow_owner (e.g., a typo dropping that role into the `default:` IllegalStateException
    // branch) is caught here. The matrix switch's default branch throws — assertThatCode catches
    // that explicitly so the test fails as a matrix gap rather than as a generic test error.
    for (WorkflowState state : WorkflowState.values()) {
      for (String role : new String[] {"product_reviewer", "workflow_owner"}) {
        stubRunWithState(state, 0);
        stubNoLatestSpec();
        stubLatestEvent(LATEST_EVT);
        org.assertj.core.api.Assertions.assertThatCode(
                () -> {
                  AllowedActionsView view = service.getAllowedActions(RUN, role);
                  assertThat(view.actions())
                      .as(
                          "WorkflowState "
                              + state
                              + " + role "
                              + role
                              + " produced empty action set — matrix gap")
                      .isNotEmpty();
                })
            .as(
                "WorkflowState "
                    + state
                    + " + role "
                    + role
                    + " threw — matrix default branch fired")
            .doesNotThrowAnyException();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Review P9 / Auditor A2 — additional coverage
  // ---------------------------------------------------------------------------

  @Test
  void caseVariantActorRoleThrowsUnknownActorRoleDomainException() {
    // Review P9 / Auditor A2: pin AC7 case-sensitivity. `Product_Reviewer` is not in the
    // recognized set — must reject with UNKNOWN_ACTOR_ROLE rather than tolerate.
    stubRunWithState(WorkflowState.INBOX, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    assertThatThrownBy(() -> service.getAllowedActions(RUN, "Product_Reviewer"))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.UNKNOWN_ACTOR_ROLE);
              assertThat(ex.details()).containsEntry("actorRole", "Product_Reviewer");
            });
  }

  @Test
  void emptyStringActorRoleDefaultsToProductReviewer() {
    // Review P9 / Edge E12: `?actorRole=` (empty value, present key) — some Spring versions deliver
    // `""` and some deliver `null`. The service-level `.isBlank()` guard handles both; pin it.
    stubRunWithState(WorkflowState.INBOX, 0);
    stubNoLatestSpec();
    stubLatestEvent(LATEST_EVT);

    AllowedActionsView empty = service.getAllowedActions(RUN, "");

    assertThat(empty.actions()).containsExactly(AllowedAction.VIEW_ONLY);
    assertThat(empty.versionStamp().workflowState()).isEqualTo("Inbox");
  }

  @Test
  void versionStampLastEventIdIsNullWhenNoEventsExist() {
    // Review P9 / Edge E15: documented "unreachable defensive" null branch — pin it so a future
    // `.orElseThrow` refactor slips noisily.
    stubRunWithState(WorkflowState.INBOX, 0);
    stubNoLatestSpec();
    when(events.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());

    AllowedActionsView view = service.getAllowedActions(RUN, "product_reviewer");

    assertThat(view.versionStamp().lastEventId()).isNull();
    assertThat(view.versionStamp().workflowState()).isEqualTo("Inbox");
  }

  @Test
  void recognizedActorRolesSetMembershipPinned() {
    // Review P9 / Edge E14: explicit pin on the recognized-role set. Adding a role string without
    // updating the matrix would silently relax the UNKNOWN_ACTOR_ROLE contract for callers who
    // supply the new value before a matrix row exists for it. Story 3.20 (AC12) adds `developer`
    // (recognized for accept_implementation in WAITING_FOR_REVIEW).
    assertThat(WorkflowInspectionService.RECOGNIZED_ACTOR_ROLES)
        .containsExactlyInAnyOrder(
            WorkflowInspectionService.ROLE_PRODUCT_REVIEWER,
            WorkflowInspectionService.ROLE_WORKFLOW_OWNER,
            WorkflowInspectionService.ROLE_DEVELOPER);
    assertThat(WorkflowInspectionService.DEFAULT_ACTOR_ROLE)
        .isEqualTo(WorkflowInspectionService.ROLE_PRODUCT_REVIEWER);
  }

  @Test
  void investigatingWithOpenClarificationAsWorkflowOwnerIncludesAnswerClarification() {
    // Review P9 / Auditor A3: AC3 says "any role" gets `answer_clarification` when an open
    // clarification exists on the latest in-flight spec while Investigating. Existing test only
    // covered product_reviewer; pin the workflow_owner variant so a future role-conditional bug in
    // the Investigating arm doesn't slip past.
    stubRunWithState(WorkflowState.INVESTIGATING, 0);
    stubLatestSpec(1);
    stubBundleAvailable(SPEC_ART, 1);
    stubLatestEvent(LATEST_EVT);
    when(clarifications.listByArtifactId(SPEC_ART))
        .thenReturn(List.of(openClarification("clr_open_b", SPEC_ART)));

    AllowedActionsView view = service.getAllowedActions(RUN, "workflow_owner");

    // Story 3e-5 (AC2) — owner open-clarification branch: view_runner_logs +
    // open_diagnostic_console
    // join alongside view_only + answer_clarification (archive_run appended by the wrapper).
    // Story 4.8 (AC10) — pause_workflow joins the owner branch (Investigating is pausable).
    assertThat(view.actions())
        .containsExactly(
            AllowedAction.VIEW_ONLY,
            AllowedAction.ANSWER_CLARIFICATION,
            AllowedAction.VIEW_RUNNER_LOGS,
            AllowedAction.OPEN_DIAGNOSTIC_CONSOLE,
            AllowedAction.PAUSE_WORKFLOW,
            AllowedAction.ARCHIVE_RUN);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void stubRunWithState(WorkflowState state, int pendingClarifications) {
    when(runs.findByPublicId(RUN))
        .thenReturn(Optional.of(new WorkflowRunSnapshot(RUN, state, null, 1L, 0, false)));
    when(clarifications.countPendingByWorkflowRun(RUN)).thenReturn(pendingClarifications);
  }

  private void stubArchivedRunWithState(WorkflowState state) {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN,
                    state,
                    java.time.OffsetDateTime.parse("2026-06-23T00:00:00Z"),
                    1L,
                    0,
                    false)));
    when(clarifications.countPendingByWorkflowRun(RUN)).thenReturn(0);
  }

  private void stubNoLatestSpec() {
    when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.SPEC.value()))
        .thenReturn(Optional.empty());
  }

  private void stubLatestSpec(int version) {
    stubLatestSpec(version, ArtifactStatus.AVAILABLE);
  }

  private void stubLatestSpec(int version, ArtifactStatus status) {
    ArtifactRecordSnapshot snapshot =
        ArtifactRecordSnapshot.withoutFailureMetadata(
            SPEC_ART,
            RUN,
            ArtifactType.SPEC,
            version,
            null,
            DataClassification.SHAREABLE_REDACTED,
            "scratch://" + SPEC_ART,
            "sha256",
            "0".repeat(64),
            status,
            null);
    when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.SPEC.value()))
        .thenReturn(Optional.of(snapshot));
    when(artifacts.findByPublicId(SPEC_ART)).thenReturn(Optional.of(snapshot));
  }

  private void stubBundleAvailable(String artifactId, int bundleVersion) {
    String rexId = "rex_" + artifactId;
    when(artifacts.findRunnerExecutionIdForArtifact(artifactId)).thenReturn(Optional.of(rexId));
    when(runnerExecutions.findByPublicId(rexId))
        .thenReturn(
            Optional.of(
                new RunnerExecutionSnapshot(
                    rexId,
                    RUN,
                    RunnerStage.INVESTIGATION,
                    RunnerExecutionStatus.COMPLETED,
                    bundleVersion,
                    NOW,
                    NOW.plusMinutes(15),
                    null,
                    NOW,
                    NOW,
                    null)));
    // Review P8: the bundle-bytes payload is only required so `getContextBundleLookupForArtifact`
    // reports `available()==true`. The CANONICAL `contextBundleVersion` surfaced into the version
    // stamp comes from `RunnerExecutionSnapshot.contextBundleVersion()` above (the {@code
    // bundleVersion} parameter), NOT from this byte payload. Earlier revisions of this helper
    // serialized {@code {"contextBundleVersion":1}} into the bytes, which led readers to assume the
    // assertion on `versionStamp().currentContextBundleVersion() == bundleVersion` flowed through
    // JSON parsing — it does not. A non-empty opaque payload is the contract.
    when(scratchStore.tryReadContextBundle(rexId)).thenReturn(Optional.of(new byte[] {0x00}));
  }

  private void stubLatestPrOutput(int version, int executionBundleVersion) {
    ArtifactRecordSnapshot snapshot =
        ArtifactRecordSnapshot.withoutFailureMetadata(
            PR_ART,
            RUN,
            ArtifactType.PR_OUTPUT,
            version,
            null,
            DataClassification.SHAREABLE_REDACTED,
            "scratch://" + PR_ART,
            "sha256",
            "0".repeat(64),
            ArtifactStatus.AVAILABLE,
            null);
    when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.PR_OUTPUT.value()))
        .thenReturn(Optional.of(snapshot));
    String rexId = "rex_" + PR_ART;
    when(artifacts.findRunnerExecutionIdForArtifact(PR_ART)).thenReturn(Optional.of(rexId));
    when(runnerExecutions.findByPublicId(rexId))
        .thenReturn(
            Optional.of(
                new RunnerExecutionSnapshot(
                    rexId,
                    RUN,
                    RunnerStage.EXECUTION,
                    RunnerExecutionStatus.COMPLETED,
                    executionBundleVersion,
                    NOW,
                    NOW.plusMinutes(15),
                    null,
                    NOW,
                    NOW,
                    null)));
  }

  private void stubLatestEvent(String eventId) {
    when(events.findLatestByWorkflowRunPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    eventId,
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    null,
                    null,
                    "system",
                    ActorType.SYSTEM,
                    "state",
                    null,
                    false,
                    NOW,
                    java.util.Map.of())));
  }

  private static Clarification openClarification(String publicId, String artifactId) {
    return new Clarification(
        publicId,
        RUN,
        artifactId,
        1,
        "q1",
        "Question text?",
        Clarification.STATUS_OPEN,
        null,
        null,
        null,
        null,
        NOW);
  }
}
