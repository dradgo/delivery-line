package org.dradgo.application.workflow.ci;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.workflow.spi.CiRunView;
import org.dradgo.application.workflow.spi.CiStatusPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Story 3h-5 (AC2/AC4) — the CI stamp is capability-gated and never throws into the tail. */
class CiPollStampServiceTest {

  private ProjectRuntimeConfigResolver runtimeResolver;
  private ProjectConnectorResolver connectorResolver;
  private CiStatusPort ciStatusPort;
  private WorkflowRunRejectionLoopPort rejectionLoopPort;
  private WorkflowEventWritePort workflowEventWritePort;
  private RepositoryHostAdapter adapter;
  private CiPollStampService service;

  @BeforeEach
  void setUp() {
    runtimeResolver = mock(ProjectRuntimeConfigResolver.class);
    connectorResolver = mock(ProjectConnectorResolver.class);
    ciStatusPort = mock(CiStatusPort.class);
    rejectionLoopPort = mock(WorkflowRunRejectionLoopPort.class);
    workflowEventWritePort = mock(WorkflowEventWritePort.class);
    adapter = mock(RepositoryHostAdapter.class);
    Project project = mock(Project.class);
    lenient().when(runtimeResolver.resolveForRun(anyString())).thenReturn(project);
    lenient().when(connectorResolver.resolveRepositoryHost(any())).thenReturn(adapter);
    service =
        new CiPollStampService(
            runtimeResolver,
            connectorResolver,
            ciStatusPort,
            rejectionLoopPort,
            workflowEventWritePort);
  }

  @Test
  void stampsWhenHostSupportsCiStatusReads() {
    when(adapter.getCapabilities()).thenReturn(RepositoryHostCapabilities.githubDefaults());
    service.stampIfCapable("run_1", "sha-abc");
    verify(ciStatusPort).markCiPollPending("run_1", "sha-abc");
  }

  @Test
  void skipsWhenHostDoesNotSupportCiStatusReads() {
    // 6th flag false → skip (a GitLab-stub-like host).
    when(adapter.getCapabilities())
        .thenReturn(new RepositoryHostCapabilities(false, false, false, false, false, false));
    service.stampIfCapable("run_1", "sha-abc");
    verify(ciStatusPort, never()).markCiPollPending(anyString(), anyString());
  }

  @Test
  void skipsAndSwallowsWhenCapabilityProbeThrows() {
    when(adapter.getCapabilities()).thenThrow(new RuntimeException("boom"));
    service.stampIfCapable("run_1", "sha-abc");
    verify(ciStatusPort, never()).markCiPollPending(anyString(), anyString());
  }

  @Test
  void skipsWhenShaBlank() {
    service.stampIfCapable("run_1", "  ");
    verify(ciStatusPort, never()).markCiPollPending(anyString(), anyString());
  }

  @Test
  void swallowsResolveFailure() {
    when(runtimeResolver.resolveForRun(eq("run_2"))).thenThrow(new RuntimeException("no project"));
    service.stampIfCapable("run_2", "sha-abc");
    verify(ciStatusPort, never()).markCiPollPending(anyString(), anyString());
  }

  // ---- D3: escalate a stalled CI-fix re-dispatch that produced no new commit ----------------

  @Test
  void escalatesStalledCiFixWhenNoCommit() {
    when(ciStatusPort.readCiView("run_1"))
        .thenReturn(Optional.of(new CiRunView("failure", "sha", 1)));
    when(rejectionLoopPort.isEscalationMarkerSet("run_1")).thenReturn(false);
    when(rejectionLoopPort.markEscalationOnce("run_1")).thenReturn(1);

    service.escalateStalledCiFixIfNoCommit("run_1");

    verify(rejectionLoopPort).markEscalationOnce("run_1");
    verify(workflowEventWritePort).append(any(WorkflowEventRecord.class));
  }

  @Test
  void doesNotEscalateWhenNotMidCiFixLoop() {
    // ci_fix_loop_count == 0 → a normal no-commit tail, not a stalled fix re-dispatch.
    when(ciStatusPort.readCiView("run_1"))
        .thenReturn(Optional.of(new CiRunView("pending", "sha", 0)));

    service.escalateStalledCiFixIfNoCommit("run_1");

    verify(rejectionLoopPort, never()).markEscalationOnce(anyString());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void doesNotEscalateWhenCiStatusNotFailure() {
    when(ciStatusPort.readCiView("run_1"))
        .thenReturn(Optional.of(new CiRunView("success", "sha", 2)));

    service.escalateStalledCiFixIfNoCommit("run_1");

    verify(rejectionLoopPort, never()).markEscalationOnce(anyString());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void doesNotAppendEventWhenMarkerAlreadySet() {
    when(ciStatusPort.readCiView("run_1"))
        .thenReturn(Optional.of(new CiRunView("failure", "sha", 2)));
    when(rejectionLoopPort.isEscalationMarkerSet("run_1")).thenReturn(true);

    service.escalateStalledCiFixIfNoCommit("run_1");

    verify(rejectionLoopPort, never()).markEscalationOnce(anyString());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void swallowsReadFailureDuringEscalation() {
    when(ciStatusPort.readCiView("run_1")).thenThrow(new RuntimeException("db down"));

    service.escalateStalledCiFixIfNoCommit("run_1"); // must not throw

    verify(workflowEventWritePort, never()).append(any());
  }
}
