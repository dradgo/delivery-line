package org.dradgo.application.approval;

import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 3.20 (Task 2) — shared version-binding collaborator extracted from {@link ApprovalService}
 * so the technical-approval twin ({@link TechnicalApprovalService#acceptImplementation}) resolves
 * the current context-bundle version and compares against the reviewer's expected versions through
 * exactly the same code path (anti-reinvention). The byte-identical behavior of {@code
 * ApprovalService.approveSpec / rejectSpec} is pinned by {@code ApprovalServiceApproveSpecTest} /
 * {@code ApprovalServiceRejectSpecTest}, which construct a REAL binder over the same mocked ports.
 *
 * <p>This collaborator intentionally does <strong>no</strong> WARN logging or {@code
 * APPROVAL_VERSION_MISMATCH} exception building: each calling service builds that typed rejection
 * (with its own {@code details} map) and logs the WARN under <em>its own</em> logger so the
 * per-service Logback {@code ListAppender} assertions stay valid. The shared surface is just the
 * mechanical "what is the current bundle version / do the four numbers line up" logic.
 */
@Component
class ApprovalVersionBinder {

  private static final Logger log = LoggerFactory.getLogger(ApprovalVersionBinder.class);

  private final ArtifactRecordPort artifactRecordPort;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;

  ApprovalVersionBinder(
      ArtifactRecordPort artifactRecordPort, RunnerExecutionRecordPort runnerExecutionRecordPort) {
    this.artifactRecordPort = artifactRecordPort;
    this.runnerExecutionRecordPort = runnerExecutionRecordPort;
  }

  /**
   * Resolve the artifact's current context-bundle version. Mirrors the story 2.9/2.10 source
   * exactly: an artifact with no linked {@code runner_execution_id} is bootstrap (version {@code
   * 1}); otherwise the version is read from the linked {@code runner_executions.context_bundle_
   * version}. A linked-but-missing runner execution row also degrades to bootstrap {@code 1}.
   */
  int resolveCurrentContextBundleVersion(String artifactId) {
    Optional<String> runnerExecutionId =
        artifactRecordPort.findRunnerExecutionIdForArtifact(artifactId);
    if (runnerExecutionId.isEmpty()) {
      log.debug(
          "approval bundle version bootstrap path artifactId={} reason=no_runner_execution_id",
          artifactId);
      return 1;
    }
    Optional<RunnerExecutionSnapshot> snapshot =
        runnerExecutionRecordPort.findByPublicId(runnerExecutionId.get());
    if (snapshot.isEmpty()) {
      log.debug(
          "approval bundle version bootstrap path artifactId={} runnerExecutionId={} reason=runner_execution_not_found",
          artifactId,
          runnerExecutionId.get());
      return 1;
    }
    return snapshot.get().contextBundleVersion();
  }

  /**
   * True when the reviewer's expected artifact + context-bundle versions both match the current
   * persisted versions. A false result is a stale-version reviewer error that the caller surfaces
   * as {@code APPROVAL_VERSION_MISMATCH} (with the canonical story 2.9 {@code details} shape).
   */
  boolean versionsMatch(
      int expectedArtifactVersion,
      int currentArtifactVersion,
      int expectedContextBundleVersion,
      int currentContextBundleVersion) {
    return currentArtifactVersion == expectedArtifactVersion
        && currentContextBundleVersion == expectedContextBundleVersion;
  }
}
