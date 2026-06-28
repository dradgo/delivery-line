package org.dradgo.application.workflow;

import org.dradgo.domain.registry.WorkflowState;

/**
 * A run referenced by a run-dependency edge, paired with its current workflow state (story 3f-3).
 * Used for the read-model prerequisite/dependent/blocked-on lists so the UI can render which runs a
 * dependent is waiting on and whether each is finished. A prerequisite is a "blocker" while its
 * {@code state} is anything other than {@link WorkflowState#COMPLETED}. This is a read-view (not a
 * persistence-facing port type), so it lives in {@code application.workflow} alongside {@code
 * WorkflowStatusView} where the REST layer may legally map it.
 */
public record BlockedDependencyView(String runId, WorkflowState state) {}
