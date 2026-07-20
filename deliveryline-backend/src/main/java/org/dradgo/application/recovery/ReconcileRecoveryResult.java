package org.dradgo.application.recovery;

import org.dradgo.domain.registry.WorkflowState;

public record ReconcileRecoveryResult(
    String recoveryActionPublicId,
    String reconciledEventPublicId,
    String resolvedConflictId,
    WorkflowState resultingState,
    String correlationId,
    boolean replayed) {}
