package org.dradgo.application.integration.conflict;

import java.time.Instant;

/**
 * Story 4.17 (AC6) — typed read view of one unresolved {@code integration_conflicts} row, joined to
 * its {@code integration_links} row for the {@code integrationType} + {@code externalRef} display
 * facets. Placed in the {@code application.integration.conflict} package (NOT {@code .spi}) so
 * future consumers (story 4.18's REST surface) can map it without tripping the REST-stays-thin
 * ArchUnit pin — mirrors {@code SplitProposalView} placement. Carries only non-secret
 * ids/refs/states.
 */
public record ConflictSummary(
    String conflictId,
    String integrationLinkId,
    String workflowRunId,
    String conflictCategory,
    String integrationType,
    String externalRef,
    Instant detectedAt) {}
