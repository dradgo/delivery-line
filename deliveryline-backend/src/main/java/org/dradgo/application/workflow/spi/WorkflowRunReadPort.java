package org.dradgo.application.workflow.spi;

import java.util.Optional;

public interface WorkflowRunReadPort {

	/**
	 * Look up a workflow run by its public id.
	 *
	 * @param publicId non-null public id
	 * @return present snapshot if a row exists; empty Optional if no row matches
	 */
	Optional<WorkflowRunSnapshot> findByPublicId(String publicId);
}
