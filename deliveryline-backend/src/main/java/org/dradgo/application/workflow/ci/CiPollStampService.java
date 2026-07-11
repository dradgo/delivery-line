package org.dradgo.application.workflow.ci;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.workflow.spi.CiRunView;
import org.dradgo.application.workflow.spi.CiStatusPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3h-5 (AC2/AC4) — stamps a run pending a CI poll after a successful backend push, but only
 * when the resolved repository host reports {@code supportsCiStatusReads=true}. Encapsulated as a
 * single service so {@code RunnerBroker} injects it through <strong>one</strong> optional setter
 * (ObjectProvider) rather than fanning out its constructor with {@code ProjectConnectorResolver} +
 * {@code CiStatusPort}.
 *
 * <p>The capability probe is defensive (the {@code WorkflowOrchestrationService} completion-sync
 * precedent): a null or throwing {@code getCapabilities()} is treated conservatively as "no CI
 * read" and skipped with a WARN — a misbehaving future adapter must never strand the delivery tail.
 *
 * <p>Parity (AC4): a {@code pushMode=manual} project never reaches a backend {@code
 * captureAndPush}, so this is never called for it; a host that reports {@code
 * supportsCiStatusReads=false} is resolved here and skipped. With the sweep disabled
 * ({@code @ConditionalOnProperty} absent) a stamped {@code pending} row is simply never polled —
 * the stamp itself is harmless and unconditional on the sweep.
 */
@Service
public class CiPollStampService {

  private static final Logger log = LoggerFactory.getLogger(CiPollStampService.class);

  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final ProjectConnectorResolver projectConnectorResolver;
  private final CiStatusPort ciStatusPort;
  private final WorkflowRunRejectionLoopPort rejectionLoopPort;
  private final WorkflowEventWritePort workflowEventWritePort;

  public CiPollStampService(
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      ProjectConnectorResolver projectConnectorResolver,
      CiStatusPort ciStatusPort,
      WorkflowRunRejectionLoopPort rejectionLoopPort,
      WorkflowEventWritePort workflowEventWritePort) {
    this.projectRuntimeConfigResolver =
        Objects.requireNonNull(projectRuntimeConfigResolver, "projectRuntimeConfigResolver");
    this.projectConnectorResolver =
        Objects.requireNonNull(projectConnectorResolver, "projectConnectorResolver");
    this.ciStatusPort = Objects.requireNonNull(ciStatusPort, "ciStatusPort");
    this.rejectionLoopPort = Objects.requireNonNull(rejectionLoopPort, "rejectionLoopPort");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
  }

  /**
   * Stamp {@code ci_status='pending'} for {@code workflowRunId} at {@code commitSha} when the run's
   * repository host supports CI status reads; otherwise skip (WARN/INFO). Never throws — a resolve
   * or capability failure is swallowed and logged so the caller's delivery tail is never stranded.
   */
  public void stampIfCapable(String workflowRunId, String commitSha) {
    if (workflowRunId == null || commitSha == null || commitSha.isBlank()) {
      log.warn(
          "ci poll stamp skipped reason=missing_run_or_sha workflowRunId={} hasSha={}",
          workflowRunId,
          commitSha != null && !commitSha.isBlank());
      return;
    }
    RepositoryHostAdapter adapter;
    try {
      Project project = projectRuntimeConfigResolver.resolveForRun(workflowRunId);
      adapter = projectConnectorResolver.resolveRepositoryHost(project);
    } catch (RuntimeException resolveFailure) {
      log.warn(
          "ci poll stamp skipped reason=repo_host_unresolved workflowRunId={} cause={}",
          workflowRunId,
          resolveFailure.getClass().getSimpleName());
      return;
    }
    if (adapter == null) {
      log.warn("ci poll stamp skipped reason=repo_host_null workflowRunId={}", workflowRunId);
      return;
    }
    RepositoryHostCapabilities capabilities;
    try {
      capabilities = adapter.getCapabilities();
    } catch (RuntimeException probeFailure) {
      log.warn(
          "ci poll stamp skipped reason=capability_probe_failed workflowRunId={} cause={}",
          workflowRunId,
          probeFailure.getClass().getSimpleName());
      return;
    }
    if (capabilities == null || !capabilities.supportsCiStatusReads()) {
      log.info(
          "ci poll stamp skipped reason=ci_status_reads_unsupported workflowRunId={}",
          workflowRunId);
      return;
    }
    try {
      ciStatusPort.markCiPollPending(workflowRunId, commitSha);
    } catch (RuntimeException stampFailure) {
      log.warn(
          "ci poll stamp failed workflowRunId={} cause={}",
          workflowRunId,
          stampFailure.getClass().getSimpleName());
    }
  }

  /**
   * Story 3h-5 review (D3) — the no-commit dead-end for the CI fix loop. Called from the delivery
   * tail when a push produced <strong>no</strong> new commit ({@code committed()==false} / no repo
   * push). A run that is mid CI-fix-loop ({@code ci_status='failure'} with {@code ci_fix_loop_count
   * &gt; 0}) but whose re-dispatched execution committed nothing would otherwise sit parked at
   * {@code WaitingForReview} with a red CI, its loop budget consumed, neither re-polled (never
   * re-stamped {@code pending}) nor escalated. This flips the shared escalation marker ONCE
   * (escalate-never- fail, mirroring {@code CiStatusPollingService.runCiFixLoop}'s cap branch) and
   * emits exactly one {@code ESCALATION_REQUIRED}, leaving the run parked for Epic-4 recovery.
   *
   * <p>A no-op for every other run: a normal first execution (or any run not in a CI fix loop) has
   * {@code ci_status} null/non-{@code failure} or {@code ci_fix_loop_count == 0}. Never throws — a
   * read/append failure is swallowed and logged so the caller's delivery tail is never stranded.
   */
  public void escalateStalledCiFixIfNoCommit(String workflowRunId) {
    if (workflowRunId == null) {
      return;
    }
    try {
      Optional<CiRunView> view = ciStatusPort.readCiView(workflowRunId);
      if (view.isEmpty()) {
        return;
      }
      CiRunView ci = view.get();
      if (!"failure".equals(ci.ciStatus()) || ci.ciFixLoopCount() <= 0) {
        return; // not a stalled CI fix re-dispatch — a normal no-commit tail
      }
      boolean flipped =
          !rejectionLoopPort.isEscalationMarkerSet(workflowRunId)
              && rejectionLoopPort.markEscalationOnce(workflowRunId) == 1;
      if (flipped) {
        workflowEventWritePort.append(
            new WorkflowEventRecord(
                PublicIdPrefixes.WORKFLOW_EVENT.next(),
                workflowRunId,
                WorkflowEventType.ESCALATION_REQUIRED,
                null,
                null,
                "system",
                ActorType.SYSTEM,
                "ci fix re-dispatch produced no new commit — dead end",
                null,
                false,
                OffsetDateTime.now(),
                Map.of("ciFixLoopCount", ci.ciFixLoopCount())));
        log.warn(
            "ci fix loop no-commit dead-end — escalation marker raised workflowRunId={} "
                + "ciFixLoopCount={}",
            workflowRunId,
            ci.ciFixLoopCount());
      } else {
        log.debug(
            "ci fix loop no-commit dead-end — escalation marker already set workflowRunId={}",
            workflowRunId);
      }
    } catch (RuntimeException failure) {
      log.warn(
          "ci fix stall escalation skipped workflowRunId={} cause={}",
          workflowRunId,
          failure.getClass().getSimpleName());
    }
  }
}
