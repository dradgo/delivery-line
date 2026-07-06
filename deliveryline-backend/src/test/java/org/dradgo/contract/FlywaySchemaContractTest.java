package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.dradgo.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class FlywaySchemaContractTest {

  private static final List<String> CORE_TABLES =
      List.of(
          "workflow_runs",
          "workflow_events",
          "artifacts",
          "artifact_operations",
          "approvals",
          "clarifications",
          "runner_executions",
          "integration_links",
          "recovery_actions",
          "idempotency_records",
          "batch_submissions",
          "projects",
          "project_credentials",
          "step_reviews",
          "provider_usage_snapshots",
          "spec_clarification_acknowledgements",
          "split_proposals",
          "split_proposal_feedback");

  // Story 3e-4 / V26: project_runner_kinds is a pure mapping/association table (composite PK
  // (project_id, step)) — NOT a core table: it deliberately carries no bigserial id / public_id /
  // created_at / archived_at, so it is excluded from the CORE_TABLES-driven shape loops and
  // asserted
  // by projectRunnerKindsSchemaCarriesExpectedColumnsConstraintsAndChecks instead. It must still be
  // accounted for in the "exactly these tables" check, so it is unioned in there.
  // Story 3f-3 / V28: run_dependencies is a pure association table too (composite PK (run_id,
  // depends_on_run_id), no bigserial id / public_id / created_at-only). Its columns/constraints are
  // probed by runDependenciesSchemaCarriesExpectedColumnsConstraintsForeignKeysAndIndexes; it is
  // unioned here only to satisfy the "exactly these tables" check.
  private static final List<String> ASSOCIATION_TABLES =
      List.of("project_runner_kinds", "run_dependencies");

  private static final Map<String, String> EXPECTED_PUBLIC_ID_PREFIX =
      Map.ofEntries(
          Map.entry("workflow_runs", "run_"),
          Map.entry("workflow_events", "evt_"),
          Map.entry("artifacts", "art_"),
          Map.entry("artifact_operations", "op_"),
          Map.entry("approvals", "apr_"),
          Map.entry("clarifications", "clr_"),
          Map.entry("runner_executions", "rex_"),
          Map.entry("integration_links", "ilk_"),
          Map.entry("recovery_actions", "rcv_"),
          Map.entry("idempotency_records", "idm_"),
          Map.entry("batch_submissions", "bat_"),
          Map.entry("projects", "prj_"),
          Map.entry("project_credentials", "cred_"),
          Map.entry("step_reviews", "rev_"),
          Map.entry("provider_usage_snapshots", "pul_"),
          Map.entry("spec_clarification_acknowledgements", "sca_"),
          Map.entry("split_proposals", "splprop_"),
          Map.entry("split_proposal_feedback", "splfb_"));

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private Flyway flyway;

  @Autowired private DataSource dataSource;

  // Monotonic per-instance salt so repeated inserts within the same (possibly coarse-resolution)
  // nanosecond tick still get distinct public_id/slug values — avoids spurious uq_* collisions.
  private final AtomicLong rowSalt = new AtomicLong();

  private String uniqueRowSuffix() {
    return Math.abs(System.nanoTime()) + "_" + rowSalt.incrementAndGet();
  }

  @Test
  void startupCreatesExactlyTheExpectedCoreTables() {
    Set<String> actualUserTables =
        new HashSet<>(
            jdbcTemplate.queryForList(
                """
				select table_name
				from information_schema.tables
				where table_schema = 'public'
				  and table_name <> 'flyway_schema_history'
				""",
                String.class));

    Set<String> expectedTables = new HashSet<>(CORE_TABLES);
    expectedTables.addAll(ASSOCIATION_TABLES);
    assertEquals(
        expectedTables,
        actualUserTables,
        () ->
            "Public schema must contain exactly the core + association tables (excluding flyway_schema_history). Found "
                + actualUserTables);
  }

  @Test
  void coreTablesUseBigserialIdsAndTextPublicIdsAndRetentionColumns() {
    for (String table : CORE_TABLES) {
      assertColumnType(table, "id", "bigint");
      assertColumnType(table, "public_id", "text");
      assertColumnNullable(table, "id", false);
      assertColumnNullable(table, "public_id", false);
      assertColumnNullable(table, "created_at", false);
      assertColumnNullable(table, "archived_at", true);
      assertColumnType(table, "created_at", "timestamp with time zone");
      assertColumnType(table, "archived_at", "timestamp with time zone");
    }
  }

  @Test
  void publicIdPrefixCheckConstraintsExistForEveryCoreTable() {
    for (var entry : EXPECTED_PUBLIC_ID_PREFIX.entrySet()) {
      String table = entry.getKey();
      String prefix = entry.getValue();
      String constraint = "ck_" + table + "_public_id_format";
      List<String> defs =
          jdbcTemplate.queryForList(
              """
					select pg_get_constraintdef(oid)
					from pg_constraint
					where conname = ?
					""",
              String.class,
              constraint);
      assertEquals(1, defs.size(), () -> "Missing or duplicate constraint " + constraint);
      assertTrue(
          defs.get(0).contains(prefix),
          () ->
              "Constraint "
                  + constraint
                  + " should reference prefix '"
                  + prefix
                  + "' but was: "
                  + defs.get(0));
    }
  }

  @Test
  void workflowStateAndMeasurementColumnsUseExpectedTypesAndChecks() {
    assertColumnType("workflow_runs", "current_state", "text");
    assertColumnType("workflow_runs", "version", "bigint");
    assertColumnNullable("workflow_runs", "current_state", false);
    assertColumnNullable("workflow_runs", "version", false);
    // V7: spec rejection loop tracking + escalation marker (story 2.10).
    assertColumnType("workflow_runs", "spec_rejection_loop_count", "integer");
    assertColumnNullable("workflow_runs", "spec_rejection_loop_count", false);
    assertColumnType("workflow_runs", "escalation_marker_set", "boolean");
    assertColumnNullable("workflow_runs", "escalation_marker_set", false);
    // Story 3h-1 / V33: bounded build auto-fix loop counter (mirrors implementation loop count).
    assertColumnType("workflow_runs", "build_fix_loop_count", "integer");
    assertColumnNullable("workflow_runs", "build_fix_loop_count", false);
    // Story 3h-2 / V34: operator-driven lint fix loop counter + jsonb findings on
    // runner_executions.
    assertColumnType("workflow_runs", "lint_fix_loop_count", "integer");
    assertColumnNullable("workflow_runs", "lint_fix_loop_count", false);
    assertColumnType("runner_executions", "lint_findings", "jsonb");
    assertColumnNullable("runner_executions", "lint_findings", true);
    assertColumnType("workflow_events", "stage_duration_ms", "bigint");
    assertColumnType("workflow_events", "rejection_taxonomy", "text");
    assertColumnType("approvals", "rejection_taxonomy", "text");
    assertColumnType("runner_executions", "status", "text");
    assertColumnType("integration_links", "sync_status", "text");
    assertColumnType("recovery_actions", "action_type", "text");
    assertColumnType("idempotency_records", "status", "text");
    assertColumnType("idempotency_records", "expires_at", "timestamp with time zone");
    assertColumnNullable("idempotency_records", "expires_at", true);
    assertColumnType("artifacts", "failure_category", "text");
    assertColumnType("artifacts", "failure_reason", "text");
    assertColumnNullable("artifacts", "failure_category", true);
    assertColumnNullable("artifacts", "failure_reason", true);
    assertColumnType("artifact_operations", "artifact_type", "text");
    assertColumnNullable("artifact_operations", "artifact_type", false);

    // Probe the state CHECK structurally rather than string-matching pg_get_constraintdef output.
    List<String> requiredStates =
        List.of(
            "Inbox",
            "Planned",
            "Investigating",
            "WaitingForSpecApproval",
            "Executing",
            "WaitingForReview",
            "WaitingForManualExecution",
            "WaitingForDependencies",
            "WaitingForLintApproval",
            "Split",
            "Completed",
            "Failed",
            "Paused",
            "TakenOver",
            "Reconciled");
    for (String state : requiredStates) {
      assertStateAccepted(state);
    }
    assertStateRejected("NotARealState");

    List<String> requiredTaxonomy =
        List.of(
            // Product-rejection taxonomy (story 2.10 / V1).
            "missing_scope",
            "unclear_specification",
            "misunderstood_implementation",
            // Developer-rejection taxonomy (story 3.21 / V13 widened the two CHECK constraints).
            "incorrect_approach",
            "incomplete_implementation",
            "quality_issue",
            "breaks_existing_functionality",
            "out_of_scope");
    for (String value : requiredTaxonomy) {
      assertRejectionTaxonomyAccepted(value);
    }
    assertRejectionTaxonomyRejected("not_a_real_taxonomy");
  }

  @Test
  void runnerExecutionsCarriesTheV10HeartbeatStaleEmittedAtColumn() {
    // Story 3.2 V10 (pinned by 3.2a AC12): heartbeat_stale_emitted_at gates RUNNER_HEARTBEAT_STALE
    // re-emission to at most once per stale window (Trap T4). Nullable; cleared on activity.
    assertColumnType("runner_executions", "heartbeat_stale_emitted_at", "timestamp with time zone");
    assertColumnNullable("runner_executions", "heartbeat_stale_emitted_at", true);
  }

  @Test
  void runnerExecutionsCarriesTheV22ReviewedArtifactPinColumns() {
    // Story 3d-2 (code-review D1) V22: REVIEW executions pin the reviewed artifact (public id +
    // version + wire type) resolved ONCE at enqueue, so the harvest reuses the exact artifact the
    // reviewer saw instead of independently re-deriving. All nullable + REVIEW-only.
    assertColumnType("runner_executions", "reviewed_artifact_id", "text");
    assertColumnType("runner_executions", "reviewed_artifact_version", "integer");
    assertColumnType("runner_executions", "reviewed_artifact_type", "text");
    assertColumnNullable("runner_executions", "reviewed_artifact_id", true);
    assertColumnNullable("runner_executions", "reviewed_artifact_version", true);
    assertColumnNullable("runner_executions", "reviewed_artifact_type", true);
  }

  @Test
  void runnerExecutionsCarriesTheV31TokenColumns() {
    // Story 3g-3 (FR74) V31: per-execution agent token accounting. All three columns are plain
    // nullable integers — no default, no CHECK (a CHECK would surface as a swallowed
    // DataIntegrityViolation in the broker's best-effort capture); pre-3g rows + no-usage results
    // stay NULL (null = "not reported", never 0).
    assertColumnType("runner_executions", "input_tokens", "integer");
    assertColumnType("runner_executions", "output_tokens", "integer");
    assertColumnType("runner_executions", "total_tokens", "integer");
    assertColumnNullable("runner_executions", "input_tokens", true);
    assertColumnNullable("runner_executions", "output_tokens", true);
    assertColumnNullable("runner_executions", "total_tokens", true);
  }

  @Test
  void clarificationsSchemaCarriesTheExpectedV8ColumnsConstraintsAndIndexes() {
    assertColumnType("clarifications", "artifact_version", "integer");
    assertColumnType("clarifications", "question_id", "text");
    assertColumnType("clarifications", "question_text", "text");
    assertColumnType("clarifications", "status", "text");
    assertColumnType("clarifications", "answer_text", "text");
    assertColumnType("clarifications", "answered_by_actor", "text");
    assertColumnType("clarifications", "answered_by_actor_type", "text");
    assertColumnType("clarifications", "answered_at", "timestamp with time zone");
    assertColumnType("clarifications", "incorporation_event_id", "bigint");
    assertColumnType("clarifications", "idempotency_key", "text");
    assertColumnNullable("clarifications", "workflow_run_id", false);
    assertColumnNullable("clarifications", "artifact_id", false);
    assertColumnNullable("clarifications", "artifact_version", false);
    assertColumnNullable("clarifications", "question_id", false);
    assertColumnNullable("clarifications", "question_text", false);
    assertColumnNullable("clarifications", "status", false);
    assertColumnNullable("clarifications", "answer_text", true);
    assertColumnNullable("clarifications", "answered_by_actor", true);
    assertColumnNullable("clarifications", "answered_by_actor_type", true);
    assertColumnNullable("clarifications", "answered_at", true);
    assertColumnNullable("clarifications", "incorporation_event_id", true);
    assertColumnNullable("clarifications", "idempotency_key", false);

    assertConstraintDefinitionContains("fk_clarifications_workflow_runs", "workflow_run_id");
    assertConstraintDefinitionContains("fk_clarifications_artifacts", "artifact_id");
    assertConstraintDefinitionContains("fk_clarifications_artifacts", "artifact_version");
    assertConstraintDefinitionContains(
        "fk_clarifications_incorporation_event", "incorporation_event_id");
    assertConstraintDefinitionContains("ck_clarifications_status", "rejected_invalid");
    // Postgres's pg_get_constraintdef() returns the canonical form with uppercase
    // SQL keywords (`IS NULL`), even though V8 wrote the constraint with lowercase
    // `is null`. Match the canonical Postgres rendering, not the source SQL.
    assertConstraintDefinitionContains(
        "ck_clarifications_answered_fields_paired", "answer_text IS NULL");
    assertConstraintDefinitionContains(
        "ck_clarifications_answered_by_actor_type", "service_account");
    assertConstraintDefinitionContains("ck_clarifications_question_id_format", "A-Za-z0-9._-");
    assertConstraintDefinitionContains("ck_clarifications_artifact_version", "artifact_version");
    assertIndexDefinitionContains(
        "idx_clarifications_workflow_run_id_status_created_at", "workflow_run_id");
    assertIndexDefinitionContains(
        "idx_clarifications_workflow_run_id_status_created_at", "created_at");
    assertIndexDefinitionContains("idx_clarifications_artifact_id_created_at", "artifact_id");
    assertIndexDefinitionContains("idx_clarifications_archived_at", "archived_at");
  }

  @Test
  void clarificationsSchemaCarriesTheExpectedV9LifecycleColumnsAndChecks() {
    // V9 (story 2.12): five lifecycle metadata columns + composite FK + status-derivable CHECK +
    // partial pending-index.
    assertColumnType("clarifications", "accepted_at", "timestamp with time zone");
    assertColumnType("clarifications", "incorporated_at", "timestamp with time zone");
    assertColumnType("clarifications", "superseded_by_artifact_id", "bigint");
    assertColumnType("clarifications", "superseded_by_artifact_version", "integer");
    assertColumnType("clarifications", "no_effect_reason", "text");
    assertColumnNullable("clarifications", "accepted_at", true);
    assertColumnNullable("clarifications", "incorporated_at", true);
    assertColumnNullable("clarifications", "superseded_by_artifact_id", true);
    assertColumnNullable("clarifications", "superseded_by_artifact_version", true);
    assertColumnNullable("clarifications", "no_effect_reason", true);
    assertConstraintDefinitionContains(
        "fk_clarifications_superseded_by_artifact", "superseded_by_artifact_id");
    assertConstraintDefinitionContains(
        "fk_clarifications_superseded_by_artifact", "superseded_by_artifact_version");
    assertConstraintDefinitionContains(
        "ck_clarifications_supersedes_pair", "superseded_by_artifact_id");
    assertConstraintDefinitionContains("ck_clarifications_status_fields_paired", "incorporated");
    assertConstraintDefinitionContains("ck_clarifications_status_fields_paired", "superseded");
    assertConstraintDefinitionContains(
        "ck_clarifications_status_fields_paired", "rejected_invalid");
    assertConstraintDefinitionContains(
        "ck_clarifications_status_fields_paired", "no_effect_reason");
    assertIndexDefinitionContains("idx_clarifications_pending_by_workflow_run", "workflow_run_id");
    assertIndexDefinitionContains("idx_clarifications_pending_by_workflow_run", "incorporated");
    assertIndexDefinitionContains("idx_clarifications_pending_by_workflow_run", "archived_at");
  }

  @Test
  void everyConstraintAndIndexNameFitsPostgresIdentifierLimit() {
    List<String> overlongConstraints =
        jdbcTemplate.queryForList(
            """
				select conname
				from pg_constraint
				where connamespace = 'public'::regnamespace
				  and length(conname) > 63
				""",
            String.class);
    List<String> overlongIndexes =
        jdbcTemplate.queryForList(
            """
				select indexname
				from pg_indexes
				where schemaname = 'public'
				  and length(indexname) > 63
				""",
            String.class);
    assertTrue(
        overlongConstraints.isEmpty(),
        () -> "Constraint names exceed 63 bytes: " + overlongConstraints);
    assertTrue(overlongIndexes.isEmpty(), () -> "Index names exceed 63 bytes: " + overlongIndexes);
  }

  @Test
  void foreignKeysReferenceExpectedTablesAndColumns() {
    List<Map<String, Object>> fks =
        jdbcTemplate.queryForList(
            """
				select tc.table_name as child_table,
				       kcu.column_name as child_column,
				       ccu.table_name as parent_table,
				       ccu.column_name as parent_column,
				       rc.delete_rule,
				       rc.update_rule,
				       tc.constraint_name
				from information_schema.table_constraints tc
				join information_schema.key_column_usage kcu
				  on tc.constraint_name = kcu.constraint_name
				 and tc.table_schema = kcu.table_schema
				join information_schema.referential_constraints rc
				  on tc.constraint_name = rc.constraint_name
				 and tc.table_schema = rc.constraint_schema
				join information_schema.constraint_column_usage ccu
				  on rc.unique_constraint_name = ccu.constraint_name
				 and rc.unique_constraint_schema = ccu.constraint_schema
				where tc.constraint_type = 'FOREIGN KEY'
				  and tc.table_schema = 'public'
				""");

    // Sanity: every workflow_run_id FK must point to workflow_runs and be RESTRICT on delete.
    long workflowRunFks =
        fks.stream()
            .filter(row -> "workflow_run_id".equals(row.get("child_column")))
            .peek(
                row -> {
                  assertEquals(
                      "workflow_runs",
                      row.get("parent_table"),
                      () ->
                          "FK on "
                              + row.get("child_table")
                              + ".workflow_run_id must point to workflow_runs");
                  // Story 3f-4 / V29: split_proposals.workflow_run_id references
                  // workflow_runs.public_id (text) — the 3f-2/3f-3 convention for new Epic-3f
                  // tables that store the opaque run public id — whereas the other 9 reference .id.
                  String expectedParentColumn =
                      "split_proposals".equals(row.get("child_table")) ? "public_id" : "id";
                  assertEquals(expectedParentColumn, row.get("parent_column"));
                  assertEquals(
                      "RESTRICT",
                      row.get("delete_rule"),
                      () ->
                          "Audit-critical FK on "
                              + row.get("child_table")
                              + ".workflow_run_id must be ON DELETE RESTRICT");
                })
            .count();
    assertEquals(
        10,
        workflowRunFks,
        () ->
            "Expected 10 workflow_run_id FKs (events, artifacts, artifact_operations, approvals, clarifications, runner_executions, integration_links, recovery_actions, step_reviews, split_proposals). Found "
                + workflowRunFks);

    // recovery_actions soft event references: SET NULL.
    fks.stream()
        .filter(
            row ->
                "recovery_actions".equals(row.get("child_table"))
                    && (row.get("child_column").equals("triggering_event_id")
                        || row.get("child_column").equals("resulting_event_id")))
        .forEach(
            row ->
                assertEquals(
                    "SET NULL",
                    row.get("delete_rule"),
                    () ->
                        "Soft event FK on recovery_actions."
                            + row.get("child_column")
                            + " must be ON DELETE SET NULL"));

    // artifacts.parent_artifact_id soft self-FK: SET NULL.
    fks.stream()
        .filter(
            row ->
                "artifacts".equals(row.get("child_table"))
                    && "parent_artifact_id".equals(row.get("child_column")))
        .forEach(row -> assertEquals("SET NULL", row.get("delete_rule")));
  }

  @Test
  void notNullExpectationsHoldForAuditCriticalColumns() {
    assertColumnNullable("workflow_runs", "current_state", false);
    assertColumnNullable("workflow_runs", "version", false);
    assertColumnNullable("workflow_events", "workflow_run_id", false);
    assertColumnNullable("workflow_events", "actor_identity", false);
    assertColumnNullable("workflow_events", "actor_type", false);
    assertColumnNullable("approvals", "actor_identity", false);
    assertColumnNullable("approvals", "actor_type", false);
    assertColumnNullable("approvals", "decision", false);
    assertColumnNullable("approvals", "decided_at", false);
    assertColumnNullable("recovery_actions", "actor_identity", false);
    assertColumnNullable("recovery_actions", "actor_type", false);
    assertColumnNullable("idempotency_records", "key", false);
    assertColumnNullable("idempotency_records", "command_fingerprint", false);
    assertColumnNullable("integration_links", "last_sync_at", true);
  }

  @Test
  void uniqueConstraintsCoverPublicIdsAndIdempotencyKeys() {
    Set<String> uniqueConstraintNames =
        new HashSet<>(
            jdbcTemplate.queryForList(
                """
				select conname
				from pg_constraint
				where connamespace = 'public'::regnamespace
				  and contype = 'u'
				""",
                String.class));
    for (String table : CORE_TABLES) {
      assertTrue(
          uniqueConstraintNames.contains("uq_" + table + "_public_id"),
          () ->
              "Missing unique constraint uq_"
                  + table
                  + "_public_id. Found "
                  + uniqueConstraintNames);
    }
    assertTrue(uniqueConstraintNames.contains("uq_approvals_idempotency_key"));
    assertTrue(uniqueConstraintNames.contains("uq_clarifications_idempotency_key"));
    assertTrue(uniqueConstraintNames.contains("uq_recovery_actions_idempotency_key"));
    assertTrue(uniqueConstraintNames.contains("uq_idempotency_records_key"));
    assertTrue(
        uniqueConstraintNames.contains("uq_artifact_operations_idem_key_op_type_workflow_run"));
    assertTrue(uniqueConstraintNames.contains("uq_artifacts_id_version"));
  }

  @Test
  void artifactOperationsPartialUniqueIndexEnforcesSinglePendingOperationPerArtifact() {
    // V4 added a partial unique index so the application can rely on "≤1 pending op per artifact"
    // instead of resolving the unique pending op via a createdAt tiebreak.
    List<Map<String, Object>> indexes =
        jdbcTemplate.queryForList(
            """
				select indexname, indexdef
				from pg_indexes
				where schemaname = 'public'
				  and tablename = 'artifact_operations'
				  and indexname = 'uq_artifact_operations_pending_per_artifact'
				""");
    assertEquals(
        1,
        indexes.size(),
        () -> "V4 partial unique index uq_artifact_operations_pending_per_artifact must exist");
    String indexDef = (String) indexes.get(0).get("indexdef");
    assertTrue(
        indexDef.toLowerCase().contains("unique"), () -> "Index must be UNIQUE: " + indexDef);
    assertTrue(indexDef.contains("artifact_id"), () -> "Index must cover artifact_id: " + indexDef);
    assertTrue(
        indexDef.toLowerCase().contains("where") && indexDef.contains("'pending'"),
        () -> "Index must be partial on status='pending': " + indexDef);
  }

  @Test
  void
      artifactFailureColumnsKeepStructuralChecksAndOperationIdempotencyScopeWidensByArtifactType() {
    assertConstraintDefinitionContains("ck_artifacts_failure_category", "failure_category");
    assertConstraintDefinitionContains("ck_artifacts_failure_category", "length");
    assertConstraintDefinitionContains("ck_artifacts_failure_reason_paired", "failure_reason");
    assertConstraintDefinitionContains("ck_artifacts_failure_reason_paired", "failure_category");
    assertConstraintDefinitionContains(
        "uq_artifact_operations_idem_key_op_type_workflow_run", "workflow_run_id");
    assertConstraintDefinitionContains(
        "uq_artifact_operations_idem_key_op_type_workflow_run", "artifact_type");
    assertConstraintDefinitionContains(
        "uq_artifact_operations_idem_key_op_type_workflow_run", "idempotency_key");
    assertConstraintDefinitionContains(
        "uq_artifact_operations_idem_key_op_type_workflow_run", "operation_type");
    // V7 (story 2.10): non-negative guard on the spec rejection loop counter.
    assertConstraintDefinitionContains(
        "ck_workflow_runs_spec_rejection_loop_count_nonneg", "spec_rejection_loop_count");
    // Story 3h-1 / V33: non-negative guard on the build-fix loop counter.
    assertConstraintDefinitionContains(
        "ck_workflow_runs_build_fix_loop_count_nonneg", "build_fix_loop_count");
    // Story 3h-2 / V34: non-negative guard on the lint-fix loop counter.
    assertConstraintDefinitionContains(
        "ck_workflow_runs_lint_fix_loop_count_nonneg", "lint_fix_loop_count");
  }

  @Test
  void flywayMigrateIsReplaySafeAndChecksumStable() {
    Integer appliedBefore =
        jdbcTemplate.queryForObject(
            """
				select count(*)
				from flyway_schema_history
				where success = true
				""",
            Integer.class);
    assertNotNull(appliedBefore);
    assertTrue(appliedBefore >= 1, "Expected at least one applied migration before replay test");

    var migrateResult = flyway.migrate();
    assertEquals(0, migrateResult.migrationsExecuted);

    ValidateResult validateResult = flyway.validateWithResult();
    assertTrue(
        validateResult.validationSuccessful,
        () -> "Flyway validate detected drift / checksum mismatch: " + validateResult.errorDetails);

    Integer appliedAfter =
        jdbcTemplate.queryForObject(
            """
				select count(*)
				from flyway_schema_history
				where success = true
				""",
            Integer.class);
    assertEquals(appliedBefore, appliedAfter);
  }

  @Test
  void projectsSchemaCarriesExpectedColumnsConstraintsAndIndexes() {
    // Story 3c-1 / V17: projects is a core table (bigserial id + public_id prj_ + retention pair
    // are
    // asserted by the CORE_TABLES-driven tests above). Probe the project-specific columns + CHECKs.
    assertColumnType("projects", "name", "text");
    assertColumnType("projects", "slug", "text");
    assertColumnType("projects", "status", "text");
    assertColumnType("projects", "repository_url", "text");
    assertColumnType("projects", "ticket_source_kind", "text");
    assertColumnType("projects", "repo_host_kind", "text");
    assertColumnType("projects", "openspec_enabled", "boolean");
    assertColumnNullable("projects", "name", false);
    assertColumnNullable("projects", "slug", false);
    assertColumnNullable("projects", "status", false);
    assertColumnNullable("projects", "repository_url", true);
    assertColumnNullable("projects", "ticket_source_kind", false);
    assertColumnNullable("projects", "repo_host_kind", false);
    assertColumnNullable("projects", "openspec_enabled", false);

    // Story 3h-1 / V33: per-project build config. build_command is nullable text (no CHECK);
    // build_stage_enabled is a NOT NULL boolean defaulting false (mirrors openspec_enabled).
    assertColumnType("projects", "build_command", "text");
    assertColumnNullable("projects", "build_command", true);
    assertColumnType("projects", "build_stage_enabled", "boolean");
    assertColumnNullable("projects", "build_stage_enabled", false);
    String buildStageDefault =
        jdbcTemplate.queryForObject(
            """
				select column_default
				from information_schema.columns
				where table_schema = 'public'
				  and table_name = 'projects'
				  and column_name = 'build_stage_enabled'
				""",
            String.class);
    assertEquals(
        "false",
        buildStageDefault,
        () -> "projects.build_stage_enabled must default to false but was: " + buildStageDefault);

    // Story 3h-2 / V34: per-project lint config. lint_commands is nullable text (no CHECK);
    // lint_stage_enabled is a NOT NULL boolean defaulting false (mirrors build_stage_enabled).
    assertColumnType("projects", "lint_commands", "text");
    assertColumnNullable("projects", "lint_commands", true);
    assertColumnType("projects", "lint_stage_enabled", "boolean");
    assertColumnNullable("projects", "lint_stage_enabled", false);
    String lintStageDefault =
        jdbcTemplate.queryForObject(
            """
					select column_default
					from information_schema.columns
					where table_schema = 'public'
					  and table_name = 'projects'
					  and column_name = 'lint_stage_enabled'
					""",
            String.class);
    assertEquals(
        "false",
        lintStageDefault,
        () -> "projects.lint_stage_enabled must default to false but was: " + lintStageDefault);

    // uq_projects_slug enforces a unique slug.
    assertTrue(
        uniqueConstraintNames().contains("uq_projects_slug"),
        "Missing unique constraint uq_projects_slug");

    // openspec_enabled defaults to false.
    String openspecDefault =
        jdbcTemplate.queryForObject(
            """
				select column_default
				from information_schema.columns
				where table_schema = 'public'
				  and table_name = 'projects'
				  and column_name = 'openspec_enabled'
				""",
            String.class);
    assertEquals(
        "false",
        openspecDefault,
        () -> "projects.openspec_enabled must default to false but was: " + openspecDefault);

    // ck_projects_status — 'active'/'disabled' accepted; 'archived'/'bogus' rejected.
    assertProjectInsertAccepted("active", "linear", "github");
    assertProjectInsertAccepted("disabled", "linear", "github");
    assertProjectInsertRejected("ck_projects_status", "archived", "linear", "github");
    assertProjectInsertRejected("ck_projects_status", "bogus", "linear", "github");

    // ck_projects_ticket_source_kind / ck_projects_repo_host_kind — 'linear'/'github' accepted,
    // 'bogus' rejected (the connector_kind value set).
    assertProjectInsertAccepted("active", "github", "linear");
    assertProjectInsertRejected("ck_projects_ticket_source_kind", "active", "bogus", "github");
    assertProjectInsertRejected("ck_projects_repo_host_kind", "active", "linear", "bogus");
  }

  @Test
  void projectCredentialsSchemaCarriesExpectedColumnsConstraintsAndIndexes() {
    // Story 3c-1 / V17: credential storage is write-only ciphertext — no plaintext column ever.
    assertColumnType("project_credentials", "project_id", "text");
    assertColumnType("project_credentials", "connector_role", "text");
    assertColumnType("project_credentials", "ciphertext", "bytea");
    assertColumnType("project_credentials", "key_id", "text");
    assertColumnType("project_credentials", "algo", "text");
    assertColumnNullable("project_credentials", "project_id", false);
    assertColumnNullable("project_credentials", "connector_role", false);
    assertColumnNullable("project_credentials", "ciphertext", false);
    assertColumnNullable("project_credentials", "key_id", false);
    assertColumnNullable("project_credentials", "algo", false);

    // Defense: no plaintext-bearing column ever exists on the credential table.
    assertColumnAbsent("project_credentials", "plaintext");
    assertColumnAbsent("project_credentials", "secret");
    assertColumnAbsent("project_credentials", "value");

    // uq_project_credentials_project_role is a PARTIAL unique index (active rows only): exactly one
    // *active* secret per (project, role), so an archived credential frees the slot for rotation.
    List<Map<String, Object>> projectRoleIndex =
        jdbcTemplate.queryForList(
            """
				select indexname, indexdef
				from pg_indexes
				where schemaname = 'public'
				  and tablename = 'project_credentials'
				  and indexname = 'uq_project_credentials_project_role'
				""");
    assertEquals(
        1,
        projectRoleIndex.size(),
        () -> "Missing partial unique index uq_project_credentials_project_role");
    String projectRoleIndexDef = ((String) projectRoleIndex.get(0).get("indexdef")).toLowerCase();
    assertTrue(
        projectRoleIndexDef.contains("unique"),
        () -> "uq_project_credentials_project_role must be UNIQUE: " + projectRoleIndexDef);
    assertTrue(
        projectRoleIndexDef.contains("archived_at is null"),
        () ->
            "uq_project_credentials_project_role must be partial on archived_at IS NULL: "
                + projectRoleIndexDef);

    String projectPid = seedProject();
    try {
      // ck_project_credentials_connector_role — 'ticket_source'/'repo_host' accepted, 'bogus'
      // rejected.
      String accepted1 = insertCredentialRow(projectPid, "ticket_source");
      jdbcTemplate.update("delete from project_credentials where public_id = ?", accepted1);
      String accepted2 = insertCredentialRow(projectPid, "repo_host");
      jdbcTemplate.update("delete from project_credentials where public_id = ?", accepted2);
      assertThrows(
          Exception.class,
          () -> insertCredentialRow(projectPid, "bogus"),
          "Expected CHECK violation for connector_role 'bogus'");

      // One *active* per (project, role): a second active ticket_source secret violates the index.
      String active = insertCredentialRow(projectPid, "ticket_source");
      assertThrows(
          Exception.class,
          () -> insertCredentialRow(projectPid, "ticket_source"),
          "Expected uq_project_credentials_project_role violation for a duplicate active (project, role)");

      // Rotation: archiving the active secret frees the slot for a fresh one of the same role.
      jdbcTemplate.update(
          "update project_credentials set archived_at = now() where public_id = ?", active);
      String rotated = insertCredentialRow(projectPid, "ticket_source");
      assertNotNull(rotated, "Rotation must succeed once the prior secret is archived");
    } finally {
      jdbcTemplate.update("delete from project_credentials where project_id = ?", projectPid);
      jdbcTemplate.update("delete from projects where public_id = ?", projectPid);
    }
  }

  @Test
  void stepReviewsSchemaCarriesExpectedColumnsConstraintsAndForeignKeys() {
    // Story 3d-1 / V19 (AC3): step_reviews is a core table (bigserial id + public_id rev_ + the
    // created_at/archived_at retention pair are asserted by the CORE_TABLES-driven tests above).
    // Probe the advisory-verdict-specific columns + outcome CHECK + the three FKs.
    assertColumnType("step_reviews", "workflow_run_id", "bigint");
    assertColumnType("step_reviews", "runner_execution_id", "bigint");
    assertColumnType("step_reviews", "reviewed_artifact_id", "bigint");
    assertColumnType("step_reviews", "reviewed_artifact_version", "integer");
    assertColumnType("step_reviews", "outcome", "text");
    assertColumnType("step_reviews", "rationale", "text");
    assertColumnType("step_reviews", "reviewer_model_identity", "text");
    assertColumnType("step_reviews", "producer_model_identity", "text");
    assertColumnNullable("step_reviews", "workflow_run_id", false);
    assertColumnNullable("step_reviews", "runner_execution_id", false);
    assertColumnNullable("step_reviews", "reviewed_artifact_id", false);
    assertColumnNullable("step_reviews", "reviewed_artifact_version", false);
    assertColumnNullable("step_reviews", "outcome", false);
    assertColumnNullable("step_reviews", "rationale", true);
    assertColumnNullable("step_reviews", "reviewer_model_identity", true);
    assertColumnNullable("step_reviews", "producer_model_identity", true);

    // ck_step_reviews_outcome — the 'pass'/'concern'/'fail' value set (drift-tested against the
    // ReviewOutcome registry by
    // RegistryContractTest.reviewOutcomeStaysAlignedWithSqlCheckAndApiManifest).
    assertConstraintDefinitionContains("ck_step_reviews_outcome", "pass");
    assertConstraintDefinitionContains("ck_step_reviews_outcome", "concern");
    assertConstraintDefinitionContains("ck_step_reviews_outcome", "fail");

    // The workflow_run_id FK is counted by foreignKeysReferenceExpectedTablesAndColumns (9 now).
    // Probe the runner_execution_id FK and the composite (reviewed_artifact_id, version) ->
    // artifacts
    // FK that pins the verdict to the exact reviewed artifact version (the approvals precedent).
    assertConstraintDefinitionContains("fk_step_reviews_runner_executions", "runner_execution_id");
    assertConstraintDefinitionContains("fk_step_reviews_artifacts", "reviewed_artifact_id");
    assertConstraintDefinitionContains("fk_step_reviews_artifacts", "reviewed_artifact_version");

    // Story 3d-2 / V21: one advisory verdict per reviewer execution — a PARTIAL unique index
    // (active rows only) so re-review of a new artifact version (a distinct runner_execution_id)
    // and archived verdicts (Epic 5 retention) are never blocked.
    List<Map<String, Object>> runnerExecIndex =
        jdbcTemplate.queryForList(
            """
				select indexname, indexdef
				from pg_indexes
				where schemaname = 'public'
				  and tablename = 'step_reviews'
				  and indexname = 'uq_step_reviews_runner_execution'
				""");
    assertEquals(
        1,
        runnerExecIndex.size(),
        () -> "Missing V21 partial unique index uq_step_reviews_runner_execution");
    String runnerExecIndexDef = ((String) runnerExecIndex.get(0).get("indexdef")).toLowerCase();
    assertTrue(
        runnerExecIndexDef.contains("unique"),
        () -> "uq_step_reviews_runner_execution must be UNIQUE: " + runnerExecIndexDef);
    assertTrue(
        runnerExecIndexDef.contains("runner_execution_id"),
        () ->
            "uq_step_reviews_runner_execution must cover runner_execution_id: "
                + runnerExecIndexDef);
    assertTrue(
        runnerExecIndexDef.contains("archived_at is null"),
        () ->
            "uq_step_reviews_runner_execution must be partial on archived_at IS NULL: "
                + runnerExecIndexDef);
  }

  @Test
  void projectsCarryReviewerBindingColumnsWithGatingDefaultOff() {
    // Story 3d-1 / V19 (AC4): reviewer_model_kind is nullable opaque text (no DB CHECK, DD-1);
    // reviewer_gating_enabled defaults false and is read by NO gating logic in Epic 3d (ADR 0026
    // D3).
    assertColumnType("projects", "reviewer_model_kind", "text");
    assertColumnNullable("projects", "reviewer_model_kind", true);
    assertColumnType("projects", "reviewer_gating_enabled", "boolean");
    assertColumnNullable("projects", "reviewer_gating_enabled", false);

    String gatingDefault =
        jdbcTemplate.queryForObject(
            """
            select column_default
            from information_schema.columns
            where table_schema = 'public'
              and table_name = 'projects'
              and column_name = 'reviewer_gating_enabled'
            """,
            String.class);
    assertEquals(
        "false",
        gatingDefault,
        () -> "projects.reviewer_gating_enabled must default false but was: " + gatingDefault);

    // DD-1: deliberately NO CHECK constrains reviewer_model_kind to a value set (the
    // ProjectConnectorResolver validates it at execution time in 3d-2).
    Integer kindChecks =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_constraint where conname like 'ck_projects_reviewer_model_kind%'",
            Integer.class);
    assertEquals(
        0,
        kindChecks,
        () -> "reviewer_model_kind must have NO DB CHECK (DD-1) but found " + kindChecks);
  }

  @Test
  void projectCredentialsConnectorRoleCheckAcceptsReviewer() {
    // Story 3d-1 / V19 (AC2): the widened ck_project_credentials_connector_role adds 'reviewer'
    // alongside 'ticket_source'/'repo_host'. The existing partial unique index already scopes "one
    // active reviewer credential per project" — no index change was needed.
    assertConstraintDefinitionContains("ck_project_credentials_connector_role", "reviewer");
    assertConstraintDefinitionContains("ck_project_credentials_connector_role", "ticket_source");
    assertConstraintDefinitionContains("ck_project_credentials_connector_role", "repo_host");
  }

  @Test
  void runsAndLinksCarryNullableProjectIdForeignKeys() {
    // Story 3c-1 / V17: workflow_runs + integration_links gain a nullable text project_id FK to
    // projects.public_id (RESTRICT on delete). Nullable now; story 3c-6 backfills the default
    // project.
    assertColumnType("workflow_runs", "project_id", "text");
    assertColumnNullable("workflow_runs", "project_id", true);
    assertColumnType("integration_links", "project_id", "text");
    assertColumnNullable("integration_links", "project_id", true);

    assertProjectForeignKey("fk_workflow_runs_projects");
    assertProjectForeignKey("fk_integration_links_projects");

    assertIndexDefinitionContains("idx_workflow_runs_project_id", "project_id");
    assertIndexDefinitionContains("idx_integration_links_project_id", "project_id");
  }

  @Test
  void workflowRunsCarryTheV23PartialArchivedAtIndex() {
    // Story 3d-8 / V23: the soft-hide marker (archived_at) already existed (V1); V23 adds ONLY the
    // partial index backing the include-archived list path + future Epic 5 sweep, mirroring the V1
    // workflow_events / artifacts / recovery_actions precedent.
    assertIndexDefinitionContains("idx_workflow_runs_archived_at", "archived_at");
    assertIndexDefinitionContains("idx_workflow_runs_archived_at", "archived_at IS NOT NULL");
  }

  @Test
  void workflowRunsCarryNullableParentRunIdForeignKeyAndPartialIndex() {
    assertColumnType("workflow_runs", "parent_run_id", "text");
    assertColumnNullable("workflow_runs", "parent_run_id", true);
    assertConstraintDefinitionContains("fk_workflow_runs_parent_run", "parent_run_id");
    assertConstraintDefinitionContains("fk_workflow_runs_parent_run", "public_id");
    assertConstraintDefinitionContains("ck_workflow_runs_parent_run_not_self", "parent_run_id");
    assertConstraintDefinitionContains("ck_workflow_runs_parent_run_not_self", "public_id");
    assertIndexDefinitionContains("idx_workflow_runs_parent_run_id", "parent_run_id");
    assertIndexDefinitionContains("idx_workflow_runs_parent_run_id", "parent_run_id IS NOT NULL");

    String parent = "run_parent" + uniqueRowSuffix();
    String child = "run_child" + uniqueRowSuffix();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", parent);
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, parent_run_id) values (?, 'Inbox', ?)",
        child,
        parent);
    assertEquals(
        parent,
        jdbcTemplate.queryForObject(
            "select parent_run_id from workflow_runs where public_id = ?", String.class, child));
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into workflow_runs (public_id, current_state, parent_run_id) values (?, 'Inbox', 'run_missing_parent')",
                "run_dangling" + uniqueRowSuffix()),
        "Expected parent_run_id FK to reject a missing parent run public id");
    String self = "run_self" + uniqueRowSuffix();
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into workflow_runs (public_id, current_state, parent_run_id) values (?, 'Inbox', ?)",
                self,
                self),
        "Expected parent_run_id CHECK to reject a self-parent row");
  }

  @Test
  void runDependenciesSchemaCarriesExpectedColumnsConstraintsChecksForeignKeysAndIndexes() {
    // Story 3f-3 / V28: run-dependency DAG join table. A directed edge run_id -> depends_on_run_id
    // (the dependent depends on the prerequisite). Composite PK makes duplicate edges idempotent;
    // two FKs to workflow_runs.public_id (ON DELETE RESTRICT ON UPDATE CASCADE); a self-edge CHECK;
    // and an index for each lookup direction.
    assertColumnType("run_dependencies", "run_id", "text");
    assertColumnType("run_dependencies", "depends_on_run_id", "text");
    assertColumnType("run_dependencies", "created_at", "timestamp with time zone");
    assertColumnNullable("run_dependencies", "run_id", false);
    assertColumnNullable("run_dependencies", "depends_on_run_id", false);
    assertColumnNullable("run_dependencies", "created_at", false);

    assertConstraintDefinitionContains("pk_run_dependencies", "PRIMARY KEY");
    assertConstraintDefinitionContains("pk_run_dependencies", "run_id");
    assertConstraintDefinitionContains("pk_run_dependencies", "depends_on_run_id");
    assertConstraintDefinitionContains("fk_run_dependencies_run", "run_id");
    assertConstraintDefinitionContains("fk_run_dependencies_run", "public_id");
    assertConstraintDefinitionContains("fk_run_dependencies_depends_on_run", "depends_on_run_id");
    assertConstraintDefinitionContains("fk_run_dependencies_depends_on_run", "public_id");
    // AC1 — both FKs must be ON DELETE RESTRICT ON UPDATE CASCADE (review 3f-3 P8).
    // pg_get_constraintdef renders the referential actions in canonical uppercase.
    assertConstraintDefinitionContains("fk_run_dependencies_run", "ON DELETE RESTRICT");
    assertConstraintDefinitionContains("fk_run_dependencies_run", "ON UPDATE CASCADE");
    assertConstraintDefinitionContains("fk_run_dependencies_depends_on_run", "ON DELETE RESTRICT");
    assertConstraintDefinitionContains("fk_run_dependencies_depends_on_run", "ON UPDATE CASCADE");
    assertConstraintDefinitionContains("ck_run_dependencies_not_self", "run_id");
    assertConstraintDefinitionContains("ck_run_dependencies_not_self", "depends_on_run_id");
    assertIndexDefinitionContains("idx_run_dependencies_run_id", "run_id");
    assertIndexDefinitionContains("idx_run_dependencies_depends_on_run_id", "depends_on_run_id");

    String n = uniqueRowSuffix();
    String dependent = "run_dep_dependent" + n;
    String prerequisite = "run_dep_prereq" + n;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", dependent);
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", prerequisite);
    jdbcTemplate.update(
        "insert into run_dependencies (run_id, depends_on_run_id) values (?, ?)",
        dependent,
        prerequisite);
    // Lookup by dependent (find prerequisites).
    assertEquals(
        prerequisite,
        jdbcTemplate.queryForObject(
            "select depends_on_run_id from run_dependencies where run_id = ?",
            String.class,
            dependent));
    // Lookup by prerequisite (find dependents).
    assertEquals(
        dependent,
        jdbcTemplate.queryForObject(
            "select run_id from run_dependencies where depends_on_run_id = ?",
            String.class,
            prerequisite));
    // Duplicate edge is rejected by the composite PK.
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into run_dependencies (run_id, depends_on_run_id) values (?, ?)",
                dependent,
                prerequisite),
        "Expected pk_run_dependencies to reject a duplicate edge");
    // Self-edge CHECK rejects run_id = depends_on_run_id.
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into run_dependencies (run_id, depends_on_run_id) values (?, ?)",
                dependent,
                dependent),
        "Expected ck_run_dependencies_not_self to reject a self-edge");
    // FK rejects a dangling run id on either side.
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into run_dependencies (run_id, depends_on_run_id) values (?, 'run_missing_prereq')",
                dependent),
        "Expected fk_run_dependencies_depends_on_run to reject a dangling prerequisite id");
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into run_dependencies (run_id, depends_on_run_id) values ('run_missing_dependent', ?)",
                prerequisite),
        "Expected fk_run_dependencies_run to reject a dangling dependent id");

    // Clean up the probe rows. This @SpringBootTest shares its cached context's Testcontainers
    // Postgres with every other contract test, so a leaked run_dependencies edge would block their
    // `delete from workflow_runs` cleanups via the ON DELETE RESTRICT FK. Delete the child edge
    // first, then the two workflow_runs.
    jdbcTemplate.update("delete from run_dependencies where run_id = ?", dependent);
    jdbcTemplate.update(
        "delete from workflow_runs where public_id in (?, ?)", dependent, prerequisite);
  }

  @Test
  void splitProposalsSchemaCarriesExpectedColumnsConstraintsChecksForeignKeyAndOpenIndex() {
    // Story 3f-4 / V29: advisory split-proposal table. The id/public_id/created_at/archived_at
    // retention shape + the uq/ck public_id format are asserted by the CORE_TABLES tests above
    // (split_proposals is a core table with prefix splprop_). Probe the story-specific columns, the
    // status + loop_count CHECKs, the workflow_runs.public_id FK (ON DELETE RESTRICT ON UPDATE
    // CASCADE — 3f-3 review lesson), and the partial unique "one open proposal per run" index.
    assertColumnType("split_proposals", "workflow_run_id", "text");
    assertColumnNullable("split_proposals", "workflow_run_id", false);
    assertColumnType("split_proposals", "reviewed_artifact_id", "text");
    assertColumnNullable("split_proposals", "reviewed_artifact_id", true);
    assertColumnType("split_proposals", "reviewed_artifact_version", "integer");
    assertColumnNullable("split_proposals", "reviewed_artifact_version", true);
    assertColumnType("split_proposals", "status", "text");
    assertColumnNullable("split_proposals", "status", false);
    assertColumnType("split_proposals", "loop_count", "integer");
    assertColumnNullable("split_proposals", "loop_count", false);
    assertColumnType("split_proposals", "proposal_json", "text");
    assertColumnNullable("split_proposals", "proposal_json", false);
    assertColumnType("split_proposals", "reviewer_model_identity", "text");
    assertColumnNullable("split_proposals", "reviewer_model_identity", true);
    assertColumnType("split_proposals", "producer_model_identity", "text");
    assertColumnNullable("split_proposals", "producer_model_identity", true);

    // Lifecycle CHECK (status value-set) + non-negative loop_count CHECK.
    assertConstraintDefinitionContains("ck_split_proposals_status", "open");
    assertConstraintDefinitionContains("ck_split_proposals_status", "superseded");
    assertConstraintDefinitionContains("ck_split_proposals_status", "dismissed");
    assertConstraintDefinitionContains("ck_split_proposals_status", "approved");
    assertConstraintDefinitionContains("ck_split_proposals_loop_count_nonneg", "loop_count");

    // FK to workflow_runs.public_id, ON DELETE RESTRICT ON UPDATE CASCADE (3f-3 review P8 lesson).
    assertConstraintDefinitionContains("fk_split_proposals_workflow_runs", "workflow_run_id");
    assertConstraintDefinitionContains("fk_split_proposals_workflow_runs", "public_id");
    assertConstraintDefinitionContains("fk_split_proposals_workflow_runs", "ON DELETE RESTRICT");
    assertConstraintDefinitionContains("fk_split_proposals_workflow_runs", "ON UPDATE CASCADE");

    // New re-propose loop counter on workflow_runs (mirrors spec_rejection_loop_count, V7).
    assertColumnType("workflow_runs", "split_proposal_loop_count", "integer");
    assertColumnNullable("workflow_runs", "split_proposal_loop_count", false);
    assertConstraintDefinitionContains(
        "ck_workflow_runs_split_proposal_loop_count_nonneg", "split_proposal_loop_count");

    // Partial unique "one OPEN proposal per run" index (where status = 'open').
    List<Map<String, Object>> openIndex =
        jdbcTemplate.queryForList(
            """
            select indexname, indexdef
            from pg_indexes
            where schemaname = 'public'
              and tablename = 'split_proposals'
              and indexname = 'uq_split_proposals_open_per_run'
            """);
    assertEquals(
        1,
        openIndex.size(),
        () -> "Missing V29 partial unique index uq_split_proposals_open_per_run");
    String openIndexDef = ((String) openIndex.get(0).get("indexdef")).toLowerCase();
    assertTrue(openIndexDef.contains("unique"), () -> "must be UNIQUE: " + openIndexDef);
    assertTrue(
        openIndexDef.contains("workflow_run_id"),
        () -> "must cover workflow_run_id: " + openIndexDef);
    assertTrue(
        openIndexDef.contains("status = 'open'") || openIndexDef.contains("(status = 'open'"),
        () -> "must be partial on status = 'open': " + openIndexDef);

    // Functional probe: the partial unique index allows at most one OPEN proposal per run but
    // unlimited superseded/dismissed/approved rows.
    String n = uniqueRowSuffix();
    String run = "run_split_schema" + n;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForSpecApproval')",
        run);
    jdbcTemplate.update(
        "insert into split_proposals (public_id, workflow_run_id, status, proposal_json)"
            + " values (?, ?, 'open', '{}')",
        "splprop_first" + n,
        run);
    // A second OPEN proposal for the same run is rejected by the partial unique index.
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into split_proposals (public_id, workflow_run_id, status, proposal_json)"
                    + " values (?, ?, 'open', '{}')",
                "splprop_second" + n,
                run),
        "Expected uq_split_proposals_open_per_run to reject a second open proposal");
    // A superseded row alongside the open one is fine (the loop accumulates history).
    jdbcTemplate.update(
        "insert into split_proposals (public_id, workflow_run_id, status, proposal_json)"
            + " values (?, ?, 'superseded', '{}')",
        "splprop_superseded" + n,
        run);
    // FK rejects a dangling run id.
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into split_proposals (public_id, workflow_run_id, status, proposal_json)"
                    + " values (?, 'run_missing_split', 'open', '{}')",
                "splprop_dangling" + n),
        "Expected fk_split_proposals_workflow_runs to reject a dangling run id");
    // status CHECK rejects an out-of-set value.
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into split_proposals (public_id, workflow_run_id, status, proposal_json)"
                    + " values (?, ?, 'bogus', '{}')",
                "splprop_badstatus" + n,
                run),
        "Expected ck_split_proposals_status to reject an out-of-set status");

    // Clean up the probe rows BEFORE deleting the workflow_runs row — the ON DELETE RESTRICT FK
    // would otherwise leak the edge into every later contract test's `delete from workflow_runs`
    // (the FlywaySchema RESTRICT-FK probe-row leak lesson).
    jdbcTemplate.update("delete from split_proposals where workflow_run_id = ?", run);
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
  }

  @Test
  void splitProposalFeedbackSchemaCarriesColumnsAndRunnerExecutionForeignKey() {
    // Story 3f-4 / V29: the redacted re-propose feedback store, keyed by the reviewer execution
    // that carries the dispatch (R3 materialize-by-reference). Core retention shape + public_id
    // format are asserted by the CORE_TABLES tests above (prefix splfb_). Probe the story-specific
    // column + the runner_executions FK (ON DELETE RESTRICT ON UPDATE CASCADE).
    assertColumnType("split_proposal_feedback", "runner_execution_id", "text");
    assertColumnNullable("split_proposal_feedback", "runner_execution_id", false);
    assertColumnType("split_proposal_feedback", "feedback_text", "text");
    assertColumnNullable("split_proposal_feedback", "feedback_text", false);
    assertConstraintDefinitionContains(
        "fk_split_proposal_feedback_runner_executions", "runner_execution_id");
    assertConstraintDefinitionContains("fk_split_proposal_feedback_runner_executions", "public_id");
    assertConstraintDefinitionContains(
        "fk_split_proposal_feedback_runner_executions", "ON DELETE RESTRICT");
    assertConstraintDefinitionContains(
        "fk_split_proposal_feedback_runner_executions", "ON UPDATE CASCADE");
    assertIndexDefinitionContains(
        "idx_split_proposal_feedback_runner_execution_id", "runner_execution_id");
  }

  @Test
  void specClarificationAcknowledgementsSchemaCarriesExpectedColumnsAndDedupUnique() {
    // Story 3e-2 / V25: structured spec-runner acknowledgements side-store (the id/public_id/
    // created_at/archived_at retention shape + uq/ck public_id are asserted by the CORE_TABLES
    // tests
    // above). Probe the story-specific columns + the (spec_artifact_id, question_id) dedup UNIQUE.
    assertColumnType("spec_clarification_acknowledgements", "spec_artifact_id", "text");
    assertColumnType("spec_clarification_acknowledgements", "question_id", "text");
    assertColumnType("spec_clarification_acknowledgements", "addressed", "boolean");
    assertColumnNullable("spec_clarification_acknowledgements", "spec_artifact_id", false);
    assertColumnNullable("spec_clarification_acknowledgements", "question_id", false);
    assertColumnNullable("spec_clarification_acknowledgements", "addressed", false);
    assertConstraintDefinitionContains(
        "ck_spec_clarification_acknowledgements_question_id_format", "A-Za-z0-9._-");

    // The (spec_artifact_id, question_id) UNIQUE is the dedup backstop the broker
    // pre-flight-probes.
    assertTrue(
        uniqueConstraintNames()
            .contains("uq_spec_clarification_acknowledgements_artifact_question"),
        "Missing dedup unique uq_spec_clarification_acknowledgements_artifact_question");

    String n = uniqueRowSuffix();
    String first = "sca_dedup1" + n;
    String second = "sca_dedup2" + n;
    jdbcTemplate.update(
        "insert into spec_clarification_acknowledgements (public_id, spec_artifact_id, question_id, addressed) "
            + "values (?, 'art_sweepkey', 'Q-DEDUP', true)",
        first);
    try {
      assertThrows(
          Exception.class,
          () ->
              jdbcTemplate.update(
                  "insert into spec_clarification_acknowledgements (public_id, spec_artifact_id, question_id, addressed) "
                      + "values (?, 'art_sweepkey', 'Q-DEDUP', false)",
                  second),
          "Expected uq_spec_clarification_acknowledgements_artifact_question violation on a duplicate (artifact, question)");
    } finally {
      jdbcTemplate.update(
          "delete from spec_clarification_acknowledgements where spec_artifact_id = 'art_sweepkey'");
    }
  }

  @Test
  void projectRunnerKindsSchemaCarriesExpectedColumnsConstraintsChecksAndForeignKey() {
    // Story 3e-4 / V26: per-step runner mapping. A lean mapping table — composite PK (project_id,
    // step), no public_id/created_at/archived_at (deliberately not a core table). Probe its
    // columns,
    // the two value-set CHECKs (drift-tested against the ProjectRunnerStep/RunnerKind registries by
    // RegistryContractTest), and the FK to projects.public_id (ON DELETE RESTRICT, like
    // credentials).
    assertColumnType("project_runner_kinds", "project_id", "text");
    assertColumnType("project_runner_kinds", "step", "text");
    assertColumnType("project_runner_kinds", "runner_kind", "text");
    assertColumnNullable("project_runner_kinds", "project_id", false);
    assertColumnNullable("project_runner_kinds", "step", false);
    assertColumnNullable("project_runner_kinds", "runner_kind", false);

    assertConstraintDefinitionContains("ck_project_runner_kinds_step", "spec");
    assertConstraintDefinitionContains("ck_project_runner_kinds_step", "implementationPlan");
    assertConstraintDefinitionContains("ck_project_runner_kinds_step", "prOutput");
    assertConstraintDefinitionContains("ck_project_runner_kinds_kind", "codex");
    assertConstraintDefinitionContains("ck_project_runner_kinds_kind", "claude");
    assertConstraintDefinitionContains("ck_project_runner_kinds_kind", "manual");
    assertConstraintDefinitionContains("fk_project_runner_kinds_projects", "project_id");

    // The composite (project_id, step) PRIMARY KEY enforces one runner kind per (project, step):
    // a duplicate (project, step) row is rejected; the same step on another project is allowed.
    String projectPid = seedProject();
    try {
      jdbcTemplate.update(
          "insert into project_runner_kinds (project_id, step, runner_kind) values (?, 'spec', 'codex')",
          projectPid);
      assertThrows(
          Exception.class,
          () ->
              jdbcTemplate.update(
                  "insert into project_runner_kinds (project_id, step, runner_kind) values (?, 'spec', 'manual')",
                  projectPid),
          "Expected a primary-key violation on a duplicate (project_id, step)");
      // A different step for the same project is fine.
      jdbcTemplate.update(
          "insert into project_runner_kinds (project_id, step, runner_kind) values (?, 'prOutput', 'manual')",
          projectPid);
      // An unknown step / kind trips the CHECKs.
      assertThrows(
          Exception.class,
          () ->
              jdbcTemplate.update(
                  "insert into project_runner_kinds (project_id, step, runner_kind) values (?, 'bogus', 'codex')",
                  projectPid),
          "Expected ck_project_runner_kinds_step violation for an unknown step");
      assertThrows(
          Exception.class,
          () ->
              jdbcTemplate.update(
                  "insert into project_runner_kinds (project_id, step, runner_kind) values (?, 'implementationPlan', 'bogus')",
                  projectPid),
          "Expected ck_project_runner_kinds_kind violation for an unknown runner kind");
    } finally {
      jdbcTemplate.update("delete from project_runner_kinds where project_id = ?", projectPid);
      jdbcTemplate.update("delete from projects where public_id = ?", projectPid);
    }
  }

  @Test
  void malformedMigrationFailsFastWithSyntaxError() {
    // Reuse the @ServiceConnection-managed Postgres against an isolated schema instead of
    // spinning up a second container per test run.
    Flyway brokenFlyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/broken-migration")
            .schemas("broken_test")
            .cleanDisabled(false)
            .load();
    try {
      brokenFlyway.clean();
      FlywayException thrown = assertThrows(FlywayException.class, brokenFlyway::migrate);
      String message = thrown.getMessage() == null ? "" : thrown.getMessage().toLowerCase();
      assertTrue(
          message.contains("syntax") || message.contains("error at or near"),
          () -> "Expected a syntax-error FlywayException but was: " + thrown.getMessage());
    } finally {
      try {
        brokenFlyway.clean();
      } catch (Exception ignored) {
        // Cleanup best-effort; broken_test schema may not exist if migrate aborted early.
      }
    }
  }

  private void assertColumnType(String tableName, String columnName, String expectedType) {
    List<String> rows =
        jdbcTemplate.queryForList(
            """
				select data_type
				from information_schema.columns
				where table_schema = 'public'
				  and table_name = ?
				  and column_name = ?
				""",
            String.class,
            tableName,
            columnName);
    assertEquals(1, rows.size(), () -> "Column not found: " + tableName + "." + columnName);
    assertEquals(
        expectedType,
        rows.get(0),
        () ->
            tableName
                + "."
                + columnName
                + " should use "
                + expectedType
                + " but was "
                + rows.get(0));
  }

  private void assertColumnNullable(String tableName, String columnName, boolean expectedNullable) {
    List<String> rows =
        jdbcTemplate.queryForList(
            """
				select is_nullable
				from information_schema.columns
				where table_schema = 'public'
				  and table_name = ?
				  and column_name = ?
				""",
            String.class,
            tableName,
            columnName);
    assertEquals(1, rows.size(), () -> "Column not found: " + tableName + "." + columnName);
    String expected = expectedNullable ? "YES" : "NO";
    assertEquals(
        expected, rows.get(0), () -> tableName + "." + columnName + " nullability mismatch");
  }

  private void assertConstraintDefinitionContains(String constraintName, String fragment) {
    List<String> defs =
        jdbcTemplate.queryForList(
            """
				select pg_get_constraintdef(oid)
				from pg_constraint
				where conname = ?
				""",
            String.class,
            constraintName);
    assertEquals(1, defs.size(), () -> "Constraint not found: " + constraintName);
    assertTrue(
        defs.get(0).contains(fragment),
        () ->
            "Constraint "
                + constraintName
                + " should contain '"
                + fragment
                + "' but was: "
                + defs.get(0));
  }

  private void assertIndexDefinitionContains(String indexName, String fragment) {
    List<String> defs =
        jdbcTemplate.queryForList(
            """
				select indexdef
				from pg_indexes
				where schemaname = 'public'
				  and indexname = ?
				""",
            String.class,
            indexName);
    assertEquals(1, defs.size(), () -> "Index not found: " + indexName);
    assertTrue(
        defs.get(0).contains(fragment),
        () -> "Index " + indexName + " should contain '" + fragment + "' but was: " + defs.get(0));
  }

  private void assertStateAccepted(String state) {
    String publicId = "run_test_" + Math.abs((state + System.nanoTime()).hashCode());
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", publicId, state);
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", publicId);
  }

  private void assertStateRejected(String state) {
    String publicId = "run_test_reject_" + Math.abs((state + System.nanoTime()).hashCode());
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "insert into workflow_runs (public_id, current_state) values (?, ?)",
                publicId,
                state),
        () -> "Expected CHECK violation for state " + state);
  }

  private void assertRejectionTaxonomyAccepted(String value) {
    // approvals requires a non-null artifact, so probe with workflow_events instead which permits
    // free-standing rows.
    String runPid = "run_tax_" + Math.abs((value + System.nanoTime()).hashCode());
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runPid);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPid);
    String evtPid = "evt_tax_" + Math.abs((value + System.nanoTime()).hashCode());
    jdbcTemplate.update(
        "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type, rejection_taxonomy) "
            + "values (?, ?, 'test', 'tester', 'system', ?)",
        evtPid,
        runId,
        value);
    jdbcTemplate.update("delete from workflow_events where public_id = ?", evtPid);
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", runPid);
  }

  private void assertRejectionTaxonomyRejected(String value) {
    String runPid = "run_taxrej_" + Math.abs((value + System.nanoTime()).hashCode());
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runPid);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPid);
    String evtPid = "evt_taxrej_" + Math.abs((value + System.nanoTime()).hashCode());
    try {
      assertThrows(
          Exception.class,
          () ->
              jdbcTemplate.update(
                  "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type, rejection_taxonomy) "
                      + "values (?, ?, 'test', 'tester', 'system', ?)",
                  evtPid,
                  runId,
                  value),
          () -> "Expected CHECK violation for rejection_taxonomy " + value);
    } finally {
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", runPid);
    }
  }

  private Set<String> uniqueConstraintNames() {
    return new HashSet<>(
        jdbcTemplate.queryForList(
            """
				select conname
				from pg_constraint
				where connamespace = 'public'::regnamespace
				  and contype = 'u'
				""",
            String.class));
  }

  private void assertColumnAbsent(String tableName, String columnName) {
    List<String> rows =
        jdbcTemplate.queryForList(
            """
				select column_name
				from information_schema.columns
				where table_schema = 'public'
				  and table_name = ?
				  and column_name = ?
				""",
            String.class,
            tableName,
            columnName);
    assertTrue(rows.isEmpty(), () -> "Column must not exist: " + tableName + "." + columnName);
  }

  private void assertProjectInsertAccepted(String status, String ticketKind, String repoKind) {
    String publicId = insertProjectRow(status, ticketKind, repoKind);
    jdbcTemplate.update("delete from projects where public_id = ?", publicId);
  }

  private void assertProjectInsertRejected(
      String expectedConstraint, String status, String ticketKind, String repoKind) {
    Throwable thrown =
        assertThrows(
            Exception.class,
            () -> insertProjectRow(status, ticketKind, repoKind),
            () ->
                "Expected "
                    + expectedConstraint
                    + " violation for projects ("
                    + status
                    + ", "
                    + ticketKind
                    + ", "
                    + repoKind
                    + ")");
    assertViolatesConstraint(thrown, expectedConstraint);
  }

  private void assertViolatesConstraint(Throwable thrown, String expectedConstraint) {
    StringBuilder messages = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      messages.append(t.getMessage()).append('\n');
    }
    String combined = messages.toString();
    assertTrue(
        combined.contains(expectedConstraint),
        () ->
            "Failure should cite constraint "
                + expectedConstraint
                + " (guards against a spurious unique/format collision passing the test) but was: "
                + combined);
  }

  private String insertProjectRow(String status, String ticketKind, String repoKind) {
    String n = uniqueRowSuffix();
    String publicId = "prj_test" + n;
    jdbcTemplate.update(
        "insert into projects (public_id, name, slug, status, ticket_source_kind, repo_host_kind) "
            + "values (?, ?, ?, ?, ?, ?)",
        publicId,
        "Test Project",
        "slug-" + n,
        status,
        ticketKind,
        repoKind);
    return publicId;
  }

  private String seedProject() {
    String n = uniqueRowSuffix();
    String publicId = "prj_seed" + n;
    jdbcTemplate.update(
        "insert into projects (public_id, name, slug, status, ticket_source_kind, repo_host_kind) "
            + "values (?, 'Seed Project', ?, 'active', 'linear', 'github')",
        publicId,
        "seed-" + n);
    return publicId;
  }

  private String insertCredentialRow(String projectPublicId, String connectorRole) {
    String n = uniqueRowSuffix();
    String publicId = "cred_test" + n;
    jdbcTemplate.update(
        "insert into project_credentials "
            + "(public_id, project_id, connector_role, ciphertext, key_id, algo) "
            + "values (?, ?, ?, ?, ?, ?)",
        publicId,
        projectPublicId,
        connectorRole,
        new byte[] {1, 2, 3},
        "key-1",
        "AES_GCM");
    return publicId;
  }

  private void assertProjectForeignKey(String constraintName) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
				select ccu.table_name as parent_table,
				       ccu.column_name as parent_column,
				       kcu.column_name as child_column,
				       rc.delete_rule
				from information_schema.table_constraints tc
				join information_schema.key_column_usage kcu
				  on tc.constraint_name = kcu.constraint_name
				 and tc.table_schema = kcu.table_schema
				join information_schema.referential_constraints rc
				  on tc.constraint_name = rc.constraint_name
				 and tc.table_schema = rc.constraint_schema
				join information_schema.constraint_column_usage ccu
				  on rc.unique_constraint_name = ccu.constraint_name
				 and rc.unique_constraint_schema = ccu.constraint_schema
				where tc.constraint_type = 'FOREIGN KEY'
				  and tc.table_schema = 'public'
				  and tc.constraint_name = ?
				""",
            constraintName);
    assertEquals(1, rows.size(), () -> "Expected exactly one FK named " + constraintName);
    Map<String, Object> row = rows.get(0);
    assertEquals(
        "projects",
        row.get("parent_table"),
        () -> constraintName + " must reference table projects");
    assertEquals(
        "public_id",
        row.get("parent_column"),
        () -> constraintName + " must reference projects.public_id");
    assertEquals(
        "project_id",
        row.get("child_column"),
        () -> constraintName + " must be on the project_id column");
    assertEquals(
        "RESTRICT", row.get("delete_rule"), () -> constraintName + " must be ON DELETE RESTRICT");
  }
}
