package org.dradgo.adapters.runner;

import org.dradgo.application.runner.TicketSummary;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.springframework.stereotype.Component;

/**
 * Epic-1 fallback {@link TicketSummaryProvider} so the application context can boot in profiles
 * that have not yet wired a real Linear-fetching adapter. Returns a deterministic placeholder
 * summary keyed by {@code workflowRunPublicId}. Story 1.14 replaces this with the production
 * adapter that reads from {@code integration_links} and Linear.
 *
 * <p>Guarded by {@link ConditionalOnMissingBean} so any environment that registers a real
 * {@link TicketSummaryProvider} bean takes precedence automatically.
 */
@Component
public class StubTicketSummaryProvider implements TicketSummaryProvider {

	@Override
	public TicketSummary fetchByWorkflowRun(String workflowRunPublicId) {
		return new TicketSummary(
			"ticket-" + workflowRunPublicId,
			"Pending ticket summary",
			"Ticket-summary lookup is not yet wired for this workflow run; story 1.14 ships the real adapter.");
	}
}
