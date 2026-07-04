package org.dradgo.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.review.spi.StepReviewWritePort;
import org.dradgo.application.review.spi.StepReviewWritePort.NewStepReview;
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.ReviewOutcome;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * Story 3d-2 (AC2/AC4/AC6/AC7, Task 10) — fast unit coverage for {@link ReviewResultHarvester}
 * using the REAL {@link RunnerContractValidator} (so the {@code review-result.v1} schema is
 * exercised too) with mocked collaborators. Pins: a valid verdict persists + finalizes COMPLETED;
 * the rationale is routed through redaction before persistence (AC7); a same-model self-review is
 * flagged (AC4); and each failure mode degrades gracefully (markFailed, NO verdict, NO run change —
 * AC6).
 */
class ReviewResultHarvesterTest {

  private static final String RUN_ID = "run_review0001";
  private static final String REX_ID = "rex_review0001";
  private static final String REDACTED = "[rationale-redacted]";

  private StepReviewWritePort stepReviewWritePort;
  private RedactionPolicyService redactionPolicyService;
  private ProjectRuntimeConfigResolver resolver;
  private ContextBundleService contextBundleService;
  private RunnerExecutionService runnerExecutionService;
  private RunnerProperties runnerProperties;
  private ReviewResultHarvester harvester;

  @BeforeEach
  void setUp() {
    stepReviewWritePort = org.mockito.Mockito.mock(StepReviewWritePort.class);
    redactionPolicyService = org.mockito.Mockito.mock(RedactionPolicyService.class);
    resolver = org.mockito.Mockito.mock(ProjectRuntimeConfigResolver.class);
    contextBundleService = org.mockito.Mockito.mock(ContextBundleService.class);
    runnerExecutionService = org.mockito.Mockito.mock(RunnerExecutionService.class);
    runnerProperties = org.mockito.Mockito.mock(RunnerProperties.class);
    // A mock PlatformTransactionManager makes the harvester's TransactionTemplate run its callback
    // inline (getTransaction→null, commit/rollback are no-ops), so insert + finalize execute and
    // exceptions propagate exactly as in production — without a real datasource.
    harvester =
        new ReviewResultHarvester(
            new RunnerContractValidator(),
            stepReviewWritePort,
            redactionPolicyService,
            resolver,
            contextBundleService,
            runnerExecutionService,
            runnerProperties,
            org.mockito.Mockito.mock(
                org.springframework.transaction.PlatformTransactionManager.class));

    // Reviewed artifact = a prOutput (so producer sub-stage = PR_OUTPUT). The pin-absent fallback
    // re-derives via the EXECUTION-only resolver (3e-3 code-review D1), so stub that one.
    when(contextBundleService.resolveExecutionReviewedArtifact(RUN_ID))
        .thenReturn(reviewedArtifact());
    // imageTagFor unavailable in unit → identityFor falls back to the bare kind value.
    when(runnerProperties.docker()).thenThrow(new RuntimeException("no docker in unit test"));
    when(redactionPolicyService.redact(anyString(), anyString()))
        .thenReturn(
            new RedactionResult(
                REDACTED,
                null,
                DataClassification.SHAREABLE_REDACTED,
                DataClassification.SHAREABLE_REDACTED,
                true,
                Set.of()));
    when(stepReviewWritePort.insert(any()))
        .thenAnswer(
            inv -> {
              NewStepReview n = inv.getArgument(0);
              return new StepReviewSnapshot(
                  n.publicId(),
                  n.workflowRunPublicId(),
                  n.runnerExecutionPublicId(),
                  n.reviewedArtifactPublicId(),
                  n.reviewedArtifactVersion(),
                  n.outcome(),
                  n.rationale(),
                  n.reviewerModelIdentity(),
                  n.producerModelIdentity(),
                  OffsetDateTime.parse("2026-06-22T10:00:00Z"));
            });
  }

  @Test
  void validVerdictPersistsRedactedRationaleAndCompletes() {
    when(resolver.resolveReviewerKind(RUN_ID)).thenReturn(Optional.of(RunnerKind.CLAUDE));
    when(resolver.resolveRunnerKind(any(), any(), any())).thenReturn(RunnerKind.CODEX);

    String outcome = harvester.harvest(REX_ID, RUN_ID, review("pass", "looks good", null));

    assertThat(outcome).isEqualTo("success");
    ArgumentCaptor<NewStepReview> captor = ArgumentCaptor.forClass(NewStepReview.class);
    verify(stepReviewWritePort).insert(captor.capture());
    NewStepReview persisted = captor.getValue();
    assertThat(persisted.outcome()).isEqualTo(ReviewOutcome.PASS);
    assertThat(persisted.rationale()).isEqualTo(REDACTED); // AC7 — redacted before persist
    assertThat(persisted.reviewerModelIdentity()).isEqualTo("claude");
    assertThat(persisted.producerModelIdentity()).isEqualTo("codex");
    verify(runnerExecutionService).recordCompleted(REX_ID);
    verify(runnerExecutionService, never()).recordFailed(any(), any());
  }

  @Test
  void sameModelIsFlaggedSelfReviewNotRefused() {
    when(resolver.resolveReviewerKind(RUN_ID)).thenReturn(Optional.of(RunnerKind.CLAUDE));
    when(resolver.resolveRunnerKind(any(), any(), any())).thenReturn(RunnerKind.CLAUDE);

    String outcome = harvester.harvest(REX_ID, RUN_ID, review("concern", "same model", null));

    assertThat(outcome).isEqualTo("success"); // surfaced, not refused (AC4)
    ArgumentCaptor<NewStepReview> captor = ArgumentCaptor.forClass(NewStepReview.class);
    verify(stepReviewWritePort).insert(captor.capture());
    // Equal identities ⇒ the snapshot derives selfReview()=true (surfaced by the endpoint).
    assertThat(captor.getValue().reviewerModelIdentity())
        .isEqualTo(captor.getValue().producerModelIdentity());
  }

  @Test
  void contractInvalidResultDegradesWithoutVerdict() {
    String outcome =
        harvester.harvest(
            REX_ID, RUN_ID, "{\"not\":\"a review result\"}".getBytes(StandardCharsets.UTF_8));

    assertThat(outcome).isEqualTo("failure");
    verify(stepReviewWritePort, never()).insert(any());
    verify(runnerExecutionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(runnerExecutionService, never()).recordCompleted(any());
  }

  @Test
  void reviewerSelfReportedFailureDegradesWithoutVerdict() {
    String outcome = harvester.harvest(REX_ID, RUN_ID, review("fail", null, "runner_crash"));

    assertThat(outcome).isEqualTo("failure");
    verify(stepReviewWritePort, never()).insert(any());
    verify(runnerExecutionService).recordFailed(REX_ID, FailureCategory.RUNNER_CRASH);
  }

  @Test
  void unknownFailureCategoryIsRejectedByContractAndDegrades() {
    // Code-review hardening: review-result.v1 now constrains failureCategory to an enum, so an
    // unconstrained taxonomy value is rejected at the contract layer (degrade) rather than silently
    // collapsing to runner_contract_violation after parsing.
    String outcome = harvester.harvest(REX_ID, RUN_ID, review("fail", null, "made_up_category"));

    assertThat(outcome).isEqualTo("failure");
    verify(stepReviewWritePort, never()).insert(any());
    verify(runnerExecutionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(runnerExecutionService, never()).recordCompleted(any());
  }

  @Test
  void alreadyTerminalReviewerExecutionAtFinalizeIsBenignNoOpNotDegrade() {
    // D3: the atomic insert+finalize tx. If the reviewer execution is already terminal when the
    // finalize runs (a concurrent harvest/degrade), recordCompleted throws ILLEGAL_TRANSITION, the
    // verdict insert rolls back with it, and the harvest treats it as a benign no-op — NOT a
    // degrade.
    when(resolver.resolveReviewerKind(RUN_ID)).thenReturn(Optional.of(RunnerKind.CLAUDE));
    when(resolver.resolveRunnerKind(any(), any(), any())).thenReturn(RunnerKind.CODEX);
    org.mockito.Mockito.doThrow(
            new org.dradgo.domain.DomainException(
                org.dradgo.domain.registry.DomainErrorCode.ILLEGAL_TRANSITION, "already terminal"))
        .when(runnerExecutionService)
        .recordCompleted(REX_ID);

    String outcome = harvester.harvest(REX_ID, RUN_ID, review("pass", "ok", null));

    assertThat(outcome).isEqualTo("success");
    verify(runnerExecutionService, never()).recordFailed(any(), any());
  }

  @Test
  void reviewedArtifactPinIsReusedInsteadOfReDeriving() {
    // D1: when the reviewer execution carries an enqueue-time reviewed-artifact pin, the harvest
    // pins
    // the verdict FK to THAT artifact and never re-derives from the run.
    when(resolver.resolveReviewerKind(RUN_ID)).thenReturn(Optional.of(RunnerKind.CLAUDE));
    when(resolver.resolveRunnerKind(any(), any(), any())).thenReturn(RunnerKind.CODEX);
    when(runnerExecutionService.findReviewedArtifactPin(REX_ID))
        .thenReturn(
            Optional.of(
                new org.dradgo.application.runner.spi.RunnerExecutionRecordPort.ReviewedArtifactPin(
                    "art_pinned0001", 3, "implementationPlan")));

    String outcome = harvester.harvest(REX_ID, RUN_ID, review("pass", "ok", null));

    assertThat(outcome).isEqualTo("success");
    ArgumentCaptor<NewStepReview> captor = ArgumentCaptor.forClass(NewStepReview.class);
    verify(stepReviewWritePort).insert(captor.capture());
    assertThat(captor.getValue().reviewedArtifactPublicId()).isEqualTo("art_pinned0001");
    assertThat(captor.getValue().reviewedArtifactVersion()).isEqualTo(3);
    // The pin short-circuits re-derivation entirely.
    verify(contextBundleService, never()).resolveExecutionReviewedArtifact(any());
  }

  @Test
  void specPhaseHarvestPinsSpecArtifactAndResolvesInvestigationProducer() {
    // Story 3e-3 (AC3/AC5) — a spec-phase reviewer execution pins the SPEC artifact; the verdict
    // persists against it (no schema change — step_reviews is generic over the reviewed artifact),
    // and the producer identity resolves the INVESTIGATION runner (the spec's producer), NOT the
    // EXECUTION runner — so a same-model self-review at the spec gate is detected correctly.
    when(resolver.resolveReviewerKind(RUN_ID)).thenReturn(Optional.of(RunnerKind.CLAUDE));
    when(resolver.resolveRunnerKind(RUN_ID, org.dradgo.domain.registry.RunnerStage.INVESTIGATION))
        .thenReturn(RunnerKind.CODEX);
    when(runnerExecutionService.findReviewedArtifactPin(REX_ID))
        .thenReturn(
            Optional.of(
                new org.dradgo.application.runner.spi.RunnerExecutionRecordPort.ReviewedArtifactPin(
                    "art_spec00000001", 1, "spec")));

    String outcome = harvester.harvest(REX_ID, RUN_ID, review("pass", "spec is reasonable", null));

    assertThat(outcome).isEqualTo("success");
    ArgumentCaptor<NewStepReview> captor = ArgumentCaptor.forClass(NewStepReview.class);
    verify(stepReviewWritePort).insert(captor.capture());
    assertThat(captor.getValue().reviewedArtifactPublicId()).isEqualTo("art_spec00000001");
    assertThat(captor.getValue().reviewedArtifactVersion()).isEqualTo(1);
    assertThat(captor.getValue().reviewerModelIdentity()).isEqualTo("claude");
    // Producer = the INVESTIGATION runner (spec producer), resolved via the SPEC branch.
    assertThat(captor.getValue().producerModelIdentity()).isEqualTo("codex");
    verify(contextBundleService, never()).resolveExecutionReviewedArtifact(any());
    verify(runnerExecutionService).recordCompleted(REX_ID);
  }

  @Test
  void specReviewerWithLostPinDegradesInsteadOfMisAttributing() {
    // Story 3e-3 (code-review D1) — a spec-phase reviewer whose enqueue-time pin was lost (pre-V22
    // row or a rare pin-write failure). The harvest must NOT re-derive the spec via the
    // spec-inclusive resolver (which, on an advanced run, could return a plan and mis-attribute the
    // producer as EXECUTION). The EXECUTION-only fallback finds no plan/prOutput at the spec gate
    // and throws, so the harvest degrades cleanly — the gate stays human-reviewable (AC7), no
    // verdict is persisted against the wrong artifact.
    when(runnerExecutionService.findReviewedArtifactPin(REX_ID)).thenReturn(Optional.empty());
    when(contextBundleService.resolveExecutionReviewedArtifact(RUN_ID))
        .thenThrow(
            new org.dradgo.domain.DomainException(
                org.dradgo.domain.registry.DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                "No available artifact to review for run " + RUN_ID));

    String outcome = harvester.harvest(REX_ID, RUN_ID, review("pass", "spec is reasonable", null));

    assertThat(outcome).isEqualTo("failure");
    verify(stepReviewWritePort, never()).insert(any());
    verify(runnerExecutionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(runnerExecutionService, never()).recordCompleted(any());
  }

  @Test
  void duplicateVerdictIsBenignIdempotentNoOpNotDegrade() {
    // Code-review hardening: a duplicate/late harvest of the SAME reviewer execution (V21 unique
    // index) is an idempotent re-delivery, NOT a contract violation. The harvest must NOT degrade —
    // it finalizes the execution COMPLETED and reports success.
    when(resolver.resolveReviewerKind(RUN_ID)).thenReturn(Optional.of(RunnerKind.CLAUDE));
    when(resolver.resolveRunnerKind(any(), any(), any())).thenReturn(RunnerKind.CODEX);
    // doThrow (not when(...).thenThrow) so re-stubbing does not re-invoke the setUp thenAnswer.
    org.mockito.Mockito.doThrow(
            new org.dradgo.application.review.spi.DuplicateStepReviewException(REX_ID))
        .when(stepReviewWritePort)
        .insert(any());

    String outcome = harvester.harvest(REX_ID, RUN_ID, review("pass", "already reviewed", null));

    assertThat(outcome).isEqualTo("success");
    verify(runnerExecutionService).recordCompleted(REX_ID);
    verify(runnerExecutionService, never()).recordFailed(any(), any());
  }

  @Test
  void reviewerTokenUsageIsPersistedOntoTheReviewerExecution() {
    // Story 3g-3 follow-up (FR74) — a review-result.v1 carrying `usage` persists the reviewer's
    // per-execution token counts (the ONLY channel for a REVIEW step, which emits review-result.v1
    // rather than runner-result.v1 and so never flows through the broker's captureTokenUsage).
    when(resolver.resolveReviewerKind(RUN_ID)).thenReturn(Optional.of(RunnerKind.CLAUDE));
    when(resolver.resolveRunnerKind(any(), any(), any())).thenReturn(RunnerKind.CODEX);

    // Story 3g-5 (Task 5) — pin the reviewer-usage-persisted INFO line (values are governed-numeric
    // counts, never secret) with a focused Logback ListAppender assertion.
    Logger harvesterLogger = (Logger) LoggerFactory.getLogger(ReviewResultHarvester.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    harvesterLogger.addAppender(appender);
    String outcome;
    try {
      outcome = harvester.harvest(REX_ID, RUN_ID, reviewWithUsage("pass", 173092, 1868, 174960));
    } finally {
      harvesterLogger.detachAppender(appender);
    }

    assertThat(outcome).isEqualTo("success");
    verify(runnerExecutionService).recordTokenUsage(REX_ID, 173092, 1868, 174960);
    verify(runnerExecutionService).recordCompleted(REX_ID);
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.INFO);
              assertThat(event.getFormattedMessage())
                  .isEqualTo(
                      "reviewer token-usage persisted runnerExecutionId="
                          + REX_ID
                          + " workflowRunId="
                          + RUN_ID
                          + " input=173092 output=1868 total=174960");
            });
  }

  @Test
  void noUsageVerdictNeverTouchesTokenColumns() {
    when(resolver.resolveReviewerKind(RUN_ID)).thenReturn(Optional.of(RunnerKind.CLAUDE));
    when(resolver.resolveRunnerKind(any(), any(), any())).thenReturn(RunnerKind.CODEX);

    String outcome = harvester.harvest(REX_ID, RUN_ID, review("pass", "ok", null));

    assertThat(outcome).isEqualTo("success");
    verify(runnerExecutionService, never()).recordTokenUsage(any(), any(), any(), any());
  }

  private static ArtifactRecordSnapshot reviewedArtifact() {
    return ArtifactRecordSnapshot.withoutFailureMetadata(
        "art_reviewed0001",
        RUN_ID,
        ArtifactType.PR_OUTPUT,
        2,
        null,
        DataClassification.SHAREABLE_REDACTED,
        "artifacts/run/pr.json",
        "SHA-256",
        "abc123",
        ArtifactStatus.AVAILABLE,
        null);
  }

  private static byte[] review(String outcome, String rationale, String failureCategory) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"schemaVersion\":1,\"workflowRunId\":\"")
        .append(RUN_ID)
        .append("\",\"runnerExecutionId\":\"")
        .append(REX_ID)
        .append("\",\"outcome\":\"")
        .append(outcome)
        .append("\",\"classification\":\"shareable-redacted\"");
    if (rationale != null) {
      sb.append(",\"rationale\":\"").append(rationale).append("\"");
    }
    if (failureCategory != null) {
      sb.append(",\"failureCategory\":\"").append(failureCategory).append("\"");
    }
    sb.append("}");
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] reviewWithUsage(String outcome, int input, int output, int total) {
    String json =
        "{\"schemaVersion\":1,\"workflowRunId\":\""
            + RUN_ID
            + "\",\"runnerExecutionId\":\""
            + REX_ID
            + "\",\"outcome\":\""
            + outcome
            + "\",\"classification\":\"shareable-redacted\",\"usage\":{\"inputTokens\":"
            + input
            + ",\"outputTokens\":"
            + output
            + ",\"totalTokens\":"
            + total
            + "}}";
    return json.getBytes(StandardCharsets.UTF_8);
  }
}
