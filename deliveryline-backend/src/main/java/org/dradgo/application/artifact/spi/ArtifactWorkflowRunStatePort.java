package org.dradgo.application.artifact.spi;

import java.util.Optional;
import org.dradgo.domain.registry.WorkflowState;

public interface ArtifactWorkflowRunStatePort {

  Optional<WorkflowState> currentState(String workflowRunId);
}
