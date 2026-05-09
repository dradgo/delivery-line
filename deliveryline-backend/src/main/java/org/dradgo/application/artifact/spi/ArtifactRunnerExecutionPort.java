package org.dradgo.application.artifact.spi;

public interface ArtifactRunnerExecutionPort {

	boolean isTimedOut(String runnerExecutionId);
}
