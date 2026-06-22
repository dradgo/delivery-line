package org.dradgo.application.project;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.dradgo.application.integration.repohost.RepositoryHostProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceProperties;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Story 3c-6 — the config-inversion keystone: at {@link ApplicationReadyEvent} it seeds today's
 * global configuration into a reserved {@code default} {@link Project} (the first project
 * read/write side) and backfills every pre-existing run to it, so a single-project deployment
 * behaves byte-identically to pre-3c while the {@code default} project — not the global
 * {@code @ConfigurationProperties} — becomes the run-time source of truth (consumed by {@link
 * ProjectRuntimeConfigResolver}).
 *
 * <p><strong>Why a startup seeder, not a Flyway migration (R1/R2):</strong> the seed values come
 * from env-var-backed Spring beans ({@link WorkflowProperties}/{@link RunnerProperties}/…) that a
 * pure-SQL or Flyway {@code JavaMigration} (which runs before the context is ready) cannot read.
 * The only data-bootstrap pattern with access to the config beans is an {@code
 * ApplicationReadyEvent} listener — the same precedent as {@code
 * RunnerConfiguration.recoverRunnerExecutionsOnStartup}.
 *
 * <p><strong>Idempotency + concurrent-startup race (AC5):</strong> the seed is keyed on the
 * reserved {@code default} slug ({@link #findBySlug} short-circuits on restart). A concurrent
 * instance that wins the insert trips the {@code uq_projects_slug}/{@code uq_projects_public_id}
 * unique constraints; the resulting {@link DataIntegrityViolationException} is caught and treated
 * as already-seeded. The backfill UPDATE is naturally idempotent ({@code where project_id is
 * null}).
 *
 * <p><strong>Transaction posture:</strong> the listener method is deliberately NOT
 * {@code @Transactional}. {@link ProjectStore#insert}/{@link ProjectStore#backfillNullProjectIds}
 * each run in their own adapter-level transaction, so the caught {@code
 * DataIntegrityViolationException} rolls back only the failed insert's transaction — a single
 * surrounding transaction would be poisoned (rollback-only) by the caught exception and the
 * subsequent backfill would then fail.
 *
 * <p><strong>Credentials (R4):</strong> the default project writes ZERO {@code project_credentials}
 * rows — it relies on the unchanged adapter host-env secret path ({@code LINEAR_API_TOKEN} / {@code
 * GITHUB_TOKEN}); the encrypted store is 3c-5. The seeder logs kinds/flags/refs only, never a
 * secret.
 */
@Component
public class DefaultProjectSeeder {

  private static final Logger log = LoggerFactory.getLogger(DefaultProjectSeeder.class);

  /** Reserved deterministic public id for the migrated default project (R3). */
  public static final String DEFAULT_PROJECT_PUBLIC_ID = "prj_default";

  /** Reserved slug the seeder is keyed on for idempotency (R3). */
  public static final String DEFAULT_PROJECT_SLUG = "default";

  /** Display name for the migrated default project. */
  public static final String DEFAULT_PROJECT_NAME = "Default Project";

  /** Human-readable list of the accepted connector kinds, for the fail-fast startup message. */
  private static final String ALLOWED_CONNECTOR_KINDS =
      Arrays.stream(ConnectorKind.values())
          .map(ConnectorKind::value)
          .collect(Collectors.joining(", "));

  private final WorkflowProperties workflowProperties;
  private final TicketSourceProperties ticketSourceProperties;
  private final RepositoryHostProperties repositoryHostProperties;
  private final RunnerProperties runnerProperties;
  private final ProjectStore projectStore;

  public DefaultProjectSeeder(
      WorkflowProperties workflowProperties,
      TicketSourceProperties ticketSourceProperties,
      RepositoryHostProperties repositoryHostProperties,
      RunnerProperties runnerProperties,
      ProjectStore projectStore) {
    this.workflowProperties = workflowProperties;
    this.ticketSourceProperties = ticketSourceProperties;
    this.repositoryHostProperties = repositoryHostProperties;
    this.runnerProperties = runnerProperties;
    this.projectStore = projectStore;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void seedDefaultProjectOnStartup() {
    String defaultPublicId =
        projectStore
            .findBySlug(DEFAULT_PROJECT_SLUG)
            .map(
                existing -> {
                  log.info(
                      "default project already present, skipping seed publicId={}",
                      existing.publicId());
                  return existing.publicId();
                })
            .orElseGet(this::seedDefaultFromGlobalConfig);
    // Backfill to the resolved default project's public id rather than the reserved constant: a
    // pre-existing `default`-slug row carrying a non-reserved public id (manual insert / future
    // 3c-8 CRUD) would otherwise orphan the fk_workflow_runs_projects FK or mis-bind runs.
    int backfilled = projectStore.backfillNullProjectIds(defaultPublicId);
    log.info("backfilled {} runs to default project publicId={}", backfilled, defaultPublicId);
  }

  private String seedDefaultFromGlobalConfig() {
    ConnectorKind ticketSourceKind =
        parseSeedConnectorKind(
            ticketSourceProperties.kind(), "deliveryline.integration.ticket-source.kind");
    ConnectorKind repoHostKind =
        parseSeedConnectorKind(
            repositoryHostProperties.kind(), "deliveryline.integration.repo-host.kind");
    // Raw (unnormalized, nullable) configured url — the resolver normalizes to owner/repo at use
    // time via the shared RepositoryRef.normalizeRepositoryUrl, exactly like the global path did.
    String repositoryUrl = workflowProperties.repos().url();
    boolean openspecEnabled = runnerProperties.openSpecEnabled();

    log.info(
        "seeding default project from global config repoRef={} ticketKind={} repoKind={} openspec={}",
        repositoryUrl,
        ticketSourceKind.value(),
        repoHostKind.value(),
        openspecEnabled);

    Project defaultProject =
        new Project(
            DEFAULT_PROJECT_PUBLIC_ID,
            DEFAULT_PROJECT_NAME,
            DEFAULT_PROJECT_SLUG,
            ProjectStatus.ACTIVE,
            repositoryUrl,
            ticketSourceKind,
            repoHostKind,
            openspecEnabled,
            // Story 3d-1 (AC4) — the seeded default has no reviewer (null kind, gating off).
            null,
            false,
            // Story 3d-3 (AC1/R7) — no per-project runner-kind override: the default project uses
            // the global per-stage RunnerProperties kind, so a single-project deployment is
            // byte-identical to pre-3d (never parks, never emits manual.executionRequested).
            null,
            OffsetDateTime.now(ZoneOffset.UTC),
            null);
    try {
      Project seeded = projectStore.insert(defaultProject);
      log.info("seeded default project publicId={}", seeded.publicId());
      return seeded.publicId();
    } catch (DataIntegrityViolationException race) {
      // AC5 concurrent-startup backstop: another instance won the insert; the unique constraints
      // rejected ours. Treat as already-seeded and re-read the winner's public id (confirming the
      // default actually exists post-race) so the backfill targets a real row.
      log.warn(
          "default project already seeded concurrently (slug/public_id unique), continuing", race);
      return projectStore
          .findBySlug(DEFAULT_PROJECT_SLUG)
          .map(Project::publicId)
          .orElse(DEFAULT_PROJECT_PUBLIC_ID);
    }
  }

  /**
   * Parses a configured connector kind, failing startup with an actionable message when the value
   * is unrecognized. Decision (2026-06-20 review): the seeder intentionally fails fast on a
   * misconfigured kind rather than normalizing — but the abort must name the offending property,
   * its bad value, and the accepted set instead of surfacing a bare {@code UNKNOWN_REGISTRY_VALUE}
   * stack.
   */
  private ConnectorKind parseSeedConnectorKind(String rawValue, String propertyKey) {
    try {
      return ConnectorKind.fromValue(rawValue, propertyKey);
    } catch (DomainException invalid) {
      log.error(
          "default project seed aborted — invalid connector kind for {}: value={} (allowed: {})",
          propertyKey,
          rawValue,
          ALLOWED_CONNECTOR_KINDS);
      throw new IllegalStateException(
          "Cannot seed the default project: "
              + propertyKey
              + " has an unrecognized connector kind '"
              + rawValue
              + "'. Allowed values: "
              + ALLOWED_CONNECTOR_KINDS
              + ". Fix the configuration and restart.",
          invalid);
    }
  }
}
