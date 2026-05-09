package org.dradgo.application.artifact;

import java.util.List;

public record ArtifactReconciliationResult(
	List<ArtifactOperationSnapshot> orphanedOperations
) {
}
