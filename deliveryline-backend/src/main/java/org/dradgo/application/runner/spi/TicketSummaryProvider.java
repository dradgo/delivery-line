package org.dradgo.application.runner.spi;

import org.dradgo.application.runner.TicketSummary;

/**
 * Application-owned port that resolves the ticket-summary projection for context-bundle assembly.
 *
 * <p>Epic 1 ships a stub implementation that reads from {@code integration_links} (or a registered
 * in-memory fallback). Story 1.14 replaces it with a real Linear-fetching adapter. The port stays
 * the same so {@code ContextBundleService} does not need to change shape.
 */
public interface TicketSummaryProvider {

	TicketSummary fetchByWorkflowRun(String workflowRunPublicId);
}
