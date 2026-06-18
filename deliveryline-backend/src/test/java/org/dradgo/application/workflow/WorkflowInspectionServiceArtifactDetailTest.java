package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.ArtifactDetailView;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3a-9 (Gate 3): focused tests for {@link WorkflowInspectionService#getArtifactDetail} — the
 * artifact-content read that backs {@code GET /api/v1/workflows/{runId}/artifacts/{artifactId}}.
 * Covers the happy path (redacted body + metadata + short-form checksum), each typed-rejection
 * branch (run/artifact not found, cross-run guard, classification guard, payload unreadable), and
 * the required structured log lines.
 */
class WorkflowInspectionServiceArtifactDetailTest {

  private static final String RUN = "run_inspect12345";
  private static final String OTHER_RUN = "run_other987654";
  private static final String ARTIFACT = "art_specread0001";
  private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-06-14T09:00:00Z");
  private static final String SPEC_BODY = "# Specification\n\nRedacted spec body for LIN-18.\n";

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventReadPort events = mock(WorkflowEventReadPort.class);
  private final ArtifactRecordPort artifacts = mock(ArtifactRecordPort.class);
  private final ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
  private final ApprovalReadPort approvals = mock(ApprovalReadPort.class);
  private final IntegrationLinkService links = mock(IntegrationLinkService.class);
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final RunnerExecutionRecordPort runnerExecutions = mock(RunnerExecutionRecordPort.class);
  private final RunnerScratchStore scratch = mock(RunnerScratchStore.class);
  private final ClarificationReadPort clarifications = mock(ClarificationReadPort.class);
  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          runs,
          events,
          artifacts,
          payloadStore,
          approvals,
          links,
          redaction,
          recovery,
          runnerExecutions,
          scratch,
          clarifications,
          mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
          org.dradgo.application.runner.RunnerProperties.defaults(),
          org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults());

  @Test
  void returnsRedactedBodyAndMetadataForOwnedArtifact() {
    ListAppender<ILoggingEvent> appender = attachListAppender();
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT))
        .thenReturn(
            Optional.of(
                snapshot(
                    RUN,
                    DataClassification.SHAREABLE_REDACTED,
                    "spec/ref",
                    ArtifactStatus.AVAILABLE)));
    when(payloadStore.readBytes("spec/ref"))
        .thenReturn(Optional.of(SPEC_BODY.getBytes(StandardCharsets.UTF_8)));

    ArtifactDetailView view = service.getArtifactDetail(RUN, ARTIFACT);

    assertThat(view.artifactId()).isEqualTo(ARTIFACT);
    assertThat(view.artifactType()).isEqualTo(ArtifactType.SPEC.value());
    assertThat(view.version()).isEqualTo(3);
    assertThat(view.status()).isEqualTo(ArtifactStatus.AVAILABLE.value());
    assertThat(view.classification()).isEqualTo(DataClassification.SHAREABLE_REDACTED.value());
    assertThat(view.createdAt()).isEqualTo(CREATED);
    // Short-form checksum is <canonical-algorithm>:<first 12 hex> — never the full digest. The
    // algorithm is normalized to its canonical upper-case form regardless of stored casing.
    assertThat(view.checksumShortForm()).isEqualTo("SHA-256:0123456789ab");
    assertThat(view.body()).isEqualTo(SPEC_BODY);

    assertThat(logMessages(appender))
        .anyMatch(m -> m.contains("getArtifactDetail success") && m.contains(ARTIFACT));
    // The body bytes must never appear in any log line.
    assertThat(logMessages(appender)).noneMatch(m -> m.contains("Redacted spec body"));
  }

  @Test
  void nullChecksumShortFormWhenArtifactHasNoChecksum() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    ArtifactRecordSnapshot pending =
        new ArtifactRecordSnapshot(
            ARTIFACT,
            RUN,
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            "spec/ref",
            null,
            null,
            null,
            null,
            ArtifactStatus.AVAILABLE,
            null,
            false,
            CREATED);
    when(artifacts.findByPublicId(ARTIFACT)).thenReturn(Optional.of(pending));
    when(payloadStore.readBytes("spec/ref"))
        .thenReturn(Optional.of(SPEC_BODY.getBytes(StandardCharsets.UTF_8)));

    ArtifactDetailView view = service.getArtifactDetail(RUN, ARTIFACT);

    assertThat(view.checksumShortForm()).isNull();
  }

  @Test
  void raisesRunNotFoundWhenRunAbsent() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getArtifactDetail(RUN, ARTIFACT))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.RUN_NOT_FOUND);
  }

  @Test
  void raisesArtifactRecordNotFoundWhenArtifactAbsent() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getArtifactDetail(RUN, ARTIFACT))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND);
  }

  @Test
  void crossRunArtifactReportedAsNotFoundAndWarnLogged() {
    ListAppender<ILoggingEvent> appender = attachListAppender();
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    // Artifact exists but belongs to a DIFFERENT run — must never leak existence.
    when(artifacts.findByPublicId(ARTIFACT))
        .thenReturn(
            Optional.of(
                snapshot(
                    OTHER_RUN,
                    DataClassification.SHAREABLE_REDACTED,
                    "spec/ref",
                    ArtifactStatus.AVAILABLE)));

    assertThatThrownBy(() -> service.getArtifactDetail(RUN, ARTIFACT))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND);

    assertThat(warnMessages(appender)).anyMatch(m -> m.contains("cross-run reject"));
  }

  @Test
  void localOnlyArtifactReportedAsNotFoundAndWarnLogged() {
    ListAppender<ILoggingEvent> appender = attachListAppender();
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT))
        .thenReturn(
            Optional.of(
                snapshot(
                    RUN, DataClassification.LOCAL_ONLY, "spec/ref", ArtifactStatus.AVAILABLE)));

    assertThatThrownBy(() -> service.getArtifactDetail(RUN, ARTIFACT))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND);

    assertThat(warnMessages(appender)).anyMatch(m -> m.contains("classification reject"));
  }

  @Test
  void nonAvailableArtifactReportedAsNotFoundAndWarnLogged() {
    ListAppender<ILoggingEvent> appender = attachListAppender();
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT))
        .thenReturn(
            Optional.of(
                snapshot(
                    RUN,
                    DataClassification.SHAREABLE_REDACTED,
                    "spec/ref",
                    ArtifactStatus.PENDING)));

    assertThatThrownBy(() -> service.getArtifactDetail(RUN, ARTIFACT))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND);

    assertThat(warnMessages(appender)).anyMatch(m -> m.contains("status reject"));
  }

  @Test
  void emptyPayloadReportedAsPayloadUnavailable() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT))
        .thenReturn(
            Optional.of(
                snapshot(
                    RUN,
                    DataClassification.SHAREABLE_REDACTED,
                    "spec/ref",
                    ArtifactStatus.AVAILABLE)));
    when(payloadStore.readBytes("spec/ref")).thenReturn(Optional.of(new byte[0]));

    assertThatThrownBy(() -> service.getArtifactDetail(RUN, ARTIFACT))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE);
  }

  @Test
  void raisesPayloadUnavailableWhenBytesUnreadable() {
    ListAppender<ILoggingEvent> appender = attachListAppender();
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT))
        .thenReturn(
            Optional.of(
                snapshot(
                    RUN,
                    DataClassification.SHAREABLE_REDACTED,
                    "spec/ref",
                    ArtifactStatus.AVAILABLE)));
    when(payloadStore.readBytes("spec/ref")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getArtifactDetail(RUN, ARTIFACT))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE);

    assertThat(warnMessages(appender)).anyMatch(m -> m.contains("payload unreadable"));
  }

  @Test
  void rejectsMalformedArtifactIdAtValidationBoundary() {
    assertThatThrownBy(() -> service.getArtifactDetail(RUN, "not_an_artifact"))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_ID_PREFIX);
  }

  // ===== Story 3b-5 — structured prOutput projection + co-present prState from the github_pr link =

  private static final String PR_OUTPUT_PAYLOAD =
      "{\"artifactId\":\"art_pr00000001\",\"artifactType\":\"prOutput\","
          + "\"branch\":\"feature/x\","
          + "\"commitSha\":\"abcdef1234567890abcdef1234567890abcdef12\","
          + "\"prReference\":\"PR-1\",\"diffReference\":\"diffs/run/pr-1.diff\","
          + "\"diff\":\"diff --git a/x b/x\\n+hello\\n\"}";

  @Test
  void prOutputReturnsStructuredFieldsWithPrStateFromGitHubLink() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT)).thenReturn(Optional.of(prOutputSnapshot("pr/ref")));
    when(payloadStore.readBytes("pr/ref"))
        .thenReturn(Optional.of(PR_OUTPUT_PAYLOAD.getBytes(StandardCharsets.UTF_8)));
    when(links.findActiveGitHubPrLinkView(RUN))
        .thenReturn(
            Optional.of(
                new IntegrationLinkService.GitHubPrLinkView(
                    "acme/app#42", "open")));

    ArtifactDetailView view = service.getArtifactDetail(RUN, ARTIFACT);

    assertThat(view.artifactType()).isEqualTo(ArtifactType.PR_OUTPUT.value());
    assertThat(view.branch()).isEqualTo("feature/x");
    assertThat(view.commitSha()).isEqualTo("abcdef1234567890abcdef1234567890abcdef12");
    assertThat(view.diff()).isEqualTo("diff --git a/x b/x\n+hello\n");
    // prReference + prState co-present from the github_pr link (the authoritative source).
    assertThat(view.prReference()).isEqualTo("acme/app#42");
    assertThat(view.prState()).isEqualTo("open");
    // body is blanked for a prOutput — the diff travels in the typed field, never the markdown body.
    assertThat(view.body()).isEmpty();
  }

  @Test
  void prOutputWithNoGitHubLinkHasNullPrReferenceAndState() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT)).thenReturn(Optional.of(prOutputSnapshot("pr/ref")));
    when(payloadStore.readBytes("pr/ref"))
        .thenReturn(Optional.of(PR_OUTPUT_PAYLOAD.getBytes(StandardCharsets.UTF_8)));
    // links.findActiveGitHubPrLinkView is unstubbed → Optional.empty() (no linked PR).

    ArtifactDetailView view = service.getArtifactDetail(RUN, ARTIFACT);

    assertThat(view.branch()).isEqualTo("feature/x");
    assertThat(view.prReference()).isNull();
    assertThat(view.prState()).isNull();
    assertThat(view.diff()).isEqualTo("diff --git a/x b/x\n+hello\n");
    assertThat(view.body()).isEmpty();
  }

  @Test
  void prOutputLinkPresentButStateMissingTreatedAsNoLinkage() {
    // DD3 — a link with a null state is treated as no-linkage so the wire never carries a
    // half-populated linkage (prReference present, prState null) the frontend can't validate.
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT)).thenReturn(Optional.of(prOutputSnapshot("pr/ref")));
    when(payloadStore.readBytes("pr/ref"))
        .thenReturn(Optional.of(PR_OUTPUT_PAYLOAD.getBytes(StandardCharsets.UTF_8)));
    when(links.findActiveGitHubPrLinkView(RUN))
        .thenReturn(
            Optional.of(
                new IntegrationLinkService.GitHubPrLinkView(
                    "acme/app#42", null)));

    ArtifactDetailView view = service.getArtifactDetail(RUN, ARTIFACT);

    assertThat(view.prReference()).isNull();
    assertThat(view.prState()).isNull();
  }

  @Test
  void prOutputMalformedPayloadFallsBackToNullStructuredFieldsWithoutError() {
    ListAppender<ILoggingEvent> appender = attachListAppender();
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT)).thenReturn(Optional.of(prOutputSnapshot("pr/ref")));
    when(payloadStore.readBytes("pr/ref"))
        .thenReturn(Optional.of("not valid json {{{".getBytes(StandardCharsets.UTF_8)));

    ArtifactDetailView view = service.getArtifactDetail(RUN, ARTIFACT);

    assertThat(view.branch()).isNull();
    assertThat(view.commitSha()).isNull();
    assertThat(view.diff()).isNull();
    assertThat(view.body()).isEmpty();
    assertThat(warnMessages(appender)).anyMatch(m -> m.contains("malformed prOutput payload"));
  }

  @Test
  void specReadIsUnchangedAllStructuredFieldsNullAndBodyIntact() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(runSnapshot(RUN)));
    when(artifacts.findByPublicId(ARTIFACT))
        .thenReturn(
            Optional.of(
                snapshot(
                    RUN,
                    DataClassification.SHAREABLE_REDACTED,
                    "spec/ref",
                    ArtifactStatus.AVAILABLE)));
    when(payloadStore.readBytes("spec/ref"))
        .thenReturn(Optional.of(SPEC_BODY.getBytes(StandardCharsets.UTF_8)));

    ArtifactDetailView view = service.getArtifactDetail(RUN, ARTIFACT);

    assertThat(view.body()).isEqualTo(SPEC_BODY);
    assertThat(view.branch()).isNull();
    assertThat(view.commitSha()).isNull();
    assertThat(view.prReference()).isNull();
    assertThat(view.prState()).isNull();
    assertThat(view.diff()).isNull();
  }

  // --- helpers --------------------------------------------------------------

  private static ArtifactRecordSnapshot prOutputSnapshot(String storageRef) {
    return new ArtifactRecordSnapshot(
        ARTIFACT,
        RUN,
        ArtifactType.PR_OUTPUT,
        2,
        null,
        DataClassification.SHAREABLE_REDACTED,
        storageRef,
        "sha-256",
        "0123456789abcdeffedcba9876543210",
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        CREATED);
  }

  private static WorkflowRunSnapshot runSnapshot(String runId) {
    return new WorkflowRunSnapshot(
        runId, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, 4L, 0, false);
  }

  private static ArtifactRecordSnapshot snapshot(
      String ownerRunId,
      DataClassification classification,
      String storageRef,
      ArtifactStatus status) {
    return new ArtifactRecordSnapshot(
        ARTIFACT,
        ownerRunId,
        ArtifactType.SPEC,
        3,
        null,
        classification,
        storageRef,
        "sha-256",
        "0123456789abcdeffedcba9876543210",
        null,
        null,
        status,
        null,
        false,
        CREATED);
  }

  private static ListAppender<ILoggingEvent> attachListAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(WorkflowInspectionService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static java.util.List<String> logMessages(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static java.util.List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream()
        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
