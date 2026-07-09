package org.dradgo.application.integration.conflict;

import java.time.OffsetDateTime;

public record ConflictResolutionView(
    String conflictId,
    String workflowRunId,
    String integrationLinkId,
    String integrationType,
    String conflictCategory,
    String externalRef,
    OffsetDateTime resolvedAt,
    String externalStateSnapshot,
    String internalStateSnapshot) {}
