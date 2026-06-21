package org.dradgo.application.project;

/**
 * Story 3c-8 (AC1/AC5) — application command to create a project. A plain record (NOT a sealed
 * {@code WorkflowCommand} variant — mirrors the {@code SubmitBatchCommand} "not-a-WorkflowCommand"
 * precedent), so it carries no workflow-run identity and is fingerprinted directly via the generic
 * {@code IdempotencyService}.
 *
 * <p>The connector {@code *Kind} fields are the raw wire strings ({@code linear}/{@code github}/…);
 * {@link ProjectManagementService} parses them through {@code ConnectorKind.fromValue} so a bad
 * value surfaces as a typed 400. Carries the idempotency key + actor identity for the create
 * fingerprint (story 1.9); it never carries a credential.
 */
public record CreateProjectCommand(
    String name,
    String slug,
    String repositoryUrl,
    String ticketSourceKind,
    String repoHostKind,
    boolean openspecEnabled,
    String idempotencyKey,
    String actorIdentity) {}
