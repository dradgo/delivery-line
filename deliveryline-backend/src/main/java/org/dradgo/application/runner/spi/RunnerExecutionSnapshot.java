package org.dradgo.application.runner.spi;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;

public record RunnerExecutionSnapshot(
	String publicId,
	String workflowRunPublicId,
	RunnerStage stage,
	RunnerExecutionStatus status,
	int contextBundleVersion,
	OffsetDateTime lastActivityAt,
	OffsetDateTime timeoutAt,
	FailureCategory failureCategory,
	OffsetDateTime completedAt,
	OffsetDateTime createdAt,
	OffsetDateTime archivedAt
) {
}
