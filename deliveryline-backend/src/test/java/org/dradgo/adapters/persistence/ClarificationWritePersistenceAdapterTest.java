package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.ClarificationEntityMapper;
import org.dradgo.adapters.persistence.repository.ArtifactRepository;
import org.dradgo.adapters.persistence.repository.ClarificationRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.clarification.spi.ClarificationWritePort.NewClarification;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class ClarificationWritePersistenceAdapterTest {

  private final ClarificationRepository clarifications = mock(ClarificationRepository.class);
  private final ClarificationEntityMapper mapper = mock(ClarificationEntityMapper.class);
  private final WorkflowRunRepository workflowRuns = mock(WorkflowRunRepository.class);
  private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
  private final org.dradgo.adapters.persistence.repository.WorkflowEventRepository workflowEvents =
      mock(org.dradgo.adapters.persistence.repository.WorkflowEventRepository.class);
  private final ClarificationWritePersistenceAdapter adapter =
      new ClarificationWritePersistenceAdapter(
          clarifications, mapper, workflowRuns, artifacts, workflowEvents);

  @Test
  void insertOpenRejectsArtifactFromADifferentWorkflowRun() {
    WorkflowRunEntity requestedRun =
        WorkflowRunEntity.create("run_requested12", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    WorkflowRunEntity artifactRun =
        WorkflowRunEntity.create("run_artifact123", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    ArtifactEntity artifact = new ArtifactEntity();
    artifact.setPublicId("art_mismatch123");
    artifact.setWorkflowRun(artifactRun);
    when(workflowRuns.findByPublicId("run_requested12")).thenReturn(Optional.of(requestedRun));
    when(artifacts.findByPublicId("art_mismatch123")).thenReturn(Optional.of(artifact));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                adapter.insertOpen(
                    new NewClarification(
                        "clr_mismatch123",
                        "run_requested12",
                        "art_mismatch123",
                        1,
                        "Q1",
                        "What is the boundary?",
                        "idem-mismatch-1")));

    assertEquals("run_requested12", error.details().get("workflowRunId"));
    assertEquals("art_mismatch123", error.details().get("artifactId"));
    assertEquals("run_artifact123", error.details().get("artifactWorkflowRunId"));
    verifyNoInteractions(clarifications);
  }
}
