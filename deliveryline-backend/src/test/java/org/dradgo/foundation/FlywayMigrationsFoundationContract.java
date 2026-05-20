package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Foundation contract #2 — Flyway V1 migrations apply cleanly on a <em>fresh</em> PostgreSQL
 * container (no shared singleton, no Spring auto-configuration carry-over).
 *
 * <p>Story 1.23 AC2 #2 plus review patch P13 require this contract to boot its own dedicated {@link
 * PostgreSQLContainer} per test class and invoke {@link Flyway#migrate()} directly so the assertion
 * proves migrations succeed on a database that has never seen them before. The repo's canonical
 * Postgres image is {@code postgres:17.2} (pinned in {@code
 * org.dradgo.TestcontainersConfiguration}); we reuse that tag here rather than re-introducing a
 * divergent version per AC2 #2's anti-pattern note. If the canonical pin moves, this contract
 * follows it.
 *
 * <p>Two assertions: (1) {@link MigrateResult#success} is true AND {@code successCount} equals the
 * total migration count, AND (2) every {@link MigrationInfo} in the post-migration {@link
 * Flyway#info()} reports {@link MigrationState#SUCCESS}. The second assertion is the same invariant
 * the prior shared-container test made; keeping it preserves visibility into per- migration state
 * even when the aggregate success flag is true.
 */
@Tag("foundation-gate")
@Testcontainers
class FlywayMigrationsFoundationContract {

  // Repo-canonical Postgres tag — keep in lockstep with
  // org.dradgo.TestcontainersConfiguration.CONTAINER. If you change one, change the other.
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.2"));

  @Test
  void freshContainerFlywayMigrateSucceedsAndEveryMigrationInfoReportsSuccess() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load();

    MigrateResult result;
    try {
      result = flyway.migrate();
    } catch (RuntimeException re) {
      fail(
          FoundationGateAssertions.tagged(
              "1.3",
              "Flyway.migrate() threw "
                  + re.getClass().getSimpleName()
                  + " on a fresh container: "
                  + re.getMessage()));
      return;
    }

    if (!result.success) {
      fail(
          FoundationGateAssertions.tagged(
              "1.3",
              "Flyway.migrate() reported success=false on a fresh container — applied="
                  + result.migrationsExecuted
                  + ", initial="
                  + result.initialSchemaVersion
                  + ", target="
                  + result.targetSchemaVersion));
      return;
    }
    if (result.migrationsExecuted != result.migrations.size()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.3",
              "Flyway.migrate() executed "
                  + result.migrationsExecuted
                  + " migrations, but the result.migrations list size is "
                  + result.migrations.size()
                  + " — partial-run state, fresh container should apply ALL migrations"));
      return;
    }
    if (result.migrationsExecuted == 0) {
      fail(
          FoundationGateAssertions.tagged(
              "1.3",
              "Flyway.migrate() executed zero migrations against a fresh container — V1 schema"
                  + " did not run"));
      return;
    }

    MigrationInfo[] applied = flyway.info().applied();
    if (applied.length == 0) {
      fail(
          FoundationGateAssertions.tagged(
              "1.3",
              "Flyway.info().applied() reported zero migrations after a successful migrate()"
                  + " call — inconsistent state"));
      return;
    }
    List<String> violations = new ArrayList<>();
    Arrays.stream(applied)
        .filter(m -> m.getState() != MigrationState.SUCCESS)
        .forEach(
            m ->
                violations.add(
                    "migration "
                        + m.getScript()
                        + " (version "
                        + m.getVersion()
                        + ") reported state "
                        + m.getState()));
    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.3",
              "Flyway non-SUCCESS migrations on fresh container ("
                  + violations.size()
                  + "): "
                  + String.join("; ", violations)));
    }
  }
}
