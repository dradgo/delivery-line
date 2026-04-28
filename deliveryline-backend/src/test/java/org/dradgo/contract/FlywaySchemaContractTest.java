package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.dradgo.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FlywaySchemaContractTest {

	private static final List<String> CORE_TABLES = List.of(
		"workflow_runs",
		"workflow_events",
		"artifacts",
		"artifact_operations",
		"approvals",
		"runner_executions",
		"integration_links",
		"recovery_actions",
		"idempotency_records");

	private static final Map<String, String> EXPECTED_PUBLIC_ID_PREFIX = Map.ofEntries(
		Map.entry("workflow_runs", "run_"),
		Map.entry("workflow_events", "evt_"),
		Map.entry("artifacts", "art_"),
		Map.entry("artifact_operations", "op_"),
		Map.entry("approvals", "apr_"),
		Map.entry("runner_executions", "rex_"),
		Map.entry("integration_links", "ilk_"),
		Map.entry("recovery_actions", "rcv_"),
		Map.entry("idempotency_records", "idm_"));

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Flyway flyway;

	@Autowired
	private DataSource dataSource;

	@Test
	void startupCreatesExactlyTheExpectedCoreTables() {
		Set<String> actualUserTables = new HashSet<>(jdbcTemplate.queryForList(
			"""
				select table_name
				from information_schema.tables
				where table_schema = 'public'
				  and table_name <> 'flyway_schema_history'
				""",
			String.class));

		assertEquals(
			new HashSet<>(CORE_TABLES),
			actualUserTables,
			() -> "Public schema must contain exactly the V1 core tables (excluding flyway_schema_history). Found " + actualUserTables);
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
			List<String> defs = jdbcTemplate.queryForList(
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
				() -> "Constraint " + constraint + " should reference prefix '" + prefix + "' but was: " + defs.get(0));
		}
	}

	@Test
	void workflowStateAndMeasurementColumnsUseExpectedTypesAndChecks() {
		assertColumnType("workflow_runs", "current_state", "text");
		assertColumnType("workflow_runs", "version", "bigint");
		assertColumnNullable("workflow_runs", "current_state", false);
		assertColumnNullable("workflow_runs", "version", false);
		assertColumnType("workflow_events", "stage_duration_ms", "bigint");
		assertColumnType("workflow_events", "rejection_taxonomy", "text");
		assertColumnType("approvals", "rejection_taxonomy", "text");
		assertColumnType("runner_executions", "status", "text");
		assertColumnType("integration_links", "sync_status", "text");
		assertColumnType("recovery_actions", "action_type", "text");
		assertColumnType("idempotency_records", "status", "text");
		assertColumnType("idempotency_records", "expires_at", "timestamp with time zone");
		assertColumnNullable("idempotency_records", "expires_at", true);

		// Probe the state CHECK structurally rather than string-matching pg_get_constraintdef output.
		List<String> requiredStates = List.of(
			"Inbox", "Planned", "Investigating", "WaitingForSpecApproval", "Executing",
			"WaitingForReview", "Completed", "Failed", "Paused", "TakenOver", "Reconciled");
		for (String state : requiredStates) {
			assertStateAccepted(state);
		}
		assertStateRejected("NotARealState");

		List<String> requiredTaxonomy = List.of(
			"missing_scope", "unclear_specification", "misunderstood_implementation");
		for (String value : requiredTaxonomy) {
			assertRejectionTaxonomyAccepted(value);
		}
		assertRejectionTaxonomyRejected("not_a_real_taxonomy");
	}

	@Test
	void everyConstraintAndIndexNameFitsPostgresIdentifierLimit() {
		List<String> overlongConstraints = jdbcTemplate.queryForList(
			"""
				select conname
				from pg_constraint
				where connamespace = 'public'::regnamespace
				  and length(conname) > 63
				""",
			String.class);
		List<String> overlongIndexes = jdbcTemplate.queryForList(
			"""
				select indexname
				from pg_indexes
				where schemaname = 'public'
				  and length(indexname) > 63
				""",
			String.class);
		assertTrue(overlongConstraints.isEmpty(),
			() -> "Constraint names exceed 63 bytes: " + overlongConstraints);
		assertTrue(overlongIndexes.isEmpty(),
			() -> "Index names exceed 63 bytes: " + overlongIndexes);
	}

	@Test
	void foreignKeysReferenceExpectedTablesAndColumns() {
		List<Map<String, Object>> fks = jdbcTemplate.queryForList(
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

		// Sanity: every workflow_run_id FK must point to workflow_runs.id and be RESTRICT on delete.
		long workflowRunFks = fks.stream()
			.filter(row -> "workflow_run_id".equals(row.get("child_column")))
			.peek(row -> {
				assertEquals("workflow_runs", row.get("parent_table"),
					() -> "FK on " + row.get("child_table") + ".workflow_run_id must point to workflow_runs");
				assertEquals("id", row.get("parent_column"));
				assertEquals("RESTRICT", row.get("delete_rule"),
					() -> "Audit-critical FK on " + row.get("child_table") + ".workflow_run_id must be ON DELETE RESTRICT");
			})
			.count();
		assertEquals(7, workflowRunFks,
			() -> "Expected 7 workflow_run_id FKs (events, artifacts, artifact_operations, approvals, runner_executions, integration_links, recovery_actions). Found " + workflowRunFks);

		// recovery_actions soft event references: SET NULL.
		fks.stream()
			.filter(row -> "recovery_actions".equals(row.get("child_table"))
				&& (row.get("child_column").equals("triggering_event_id") || row.get("child_column").equals("resulting_event_id")))
			.forEach(row -> assertEquals("SET NULL", row.get("delete_rule"),
				() -> "Soft event FK on recovery_actions." + row.get("child_column") + " must be ON DELETE SET NULL"));

		// artifacts.parent_artifact_id soft self-FK: SET NULL.
		fks.stream()
			.filter(row -> "artifacts".equals(row.get("child_table"))
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
		Set<String> uniqueConstraintNames = new HashSet<>(jdbcTemplate.queryForList(
			"""
				select conname
				from pg_constraint
				where connamespace = 'public'::regnamespace
				  and contype = 'u'
				""",
			String.class));
		for (String table : CORE_TABLES) {
			assertTrue(uniqueConstraintNames.contains("uq_" + table + "_public_id"),
				() -> "Missing unique constraint uq_" + table + "_public_id. Found " + uniqueConstraintNames);
		}
		assertTrue(uniqueConstraintNames.contains("uq_approvals_idempotency_key"));
		assertTrue(uniqueConstraintNames.contains("uq_recovery_actions_idempotency_key"));
		assertTrue(uniqueConstraintNames.contains("uq_idempotency_records_key"));
		assertTrue(uniqueConstraintNames.contains("uq_artifact_operations_idem_key_op_type_artifact_id"));
		assertTrue(uniqueConstraintNames.contains("uq_artifacts_id_version"));
	}

	@Test
	void flywayMigrateIsReplaySafeAndChecksumStable() {
		Integer appliedBefore = jdbcTemplate.queryForObject(
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
		assertTrue(validateResult.validationSuccessful,
			() -> "Flyway validate detected drift / checksum mismatch: " + validateResult.errorDetails);

		Integer appliedAfter = jdbcTemplate.queryForObject(
			"""
				select count(*)
				from flyway_schema_history
				where success = true
				""",
			Integer.class);
		assertEquals(appliedBefore, appliedAfter);
	}

	@Test
	void malformedMigrationFailsFastWithSyntaxError() {
		// Reuse the @ServiceConnection-managed Postgres against an isolated schema instead of
		// spinning up a second container per test run.
		Flyway brokenFlyway = Flyway.configure()
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
		List<String> rows = jdbcTemplate.queryForList(
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
		assertEquals(expectedType, rows.get(0),
			() -> tableName + "." + columnName + " should use " + expectedType + " but was " + rows.get(0));
	}

	private void assertColumnNullable(String tableName, String columnName, boolean expectedNullable) {
		List<String> rows = jdbcTemplate.queryForList(
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
		assertEquals(expected, rows.get(0),
			() -> tableName + "." + columnName + " nullability mismatch");
	}

	private void assertStateAccepted(String state) {
		String publicId = "run_test_" + Math.abs((state + System.nanoTime()).hashCode());
		jdbcTemplate.update(
			"insert into workflow_runs (public_id, current_state) values (?, ?)",
			publicId, state);
		jdbcTemplate.update("delete from workflow_runs where public_id = ?", publicId);
	}

	private void assertStateRejected(String state) {
		String publicId = "run_test_reject_" + Math.abs((state + System.nanoTime()).hashCode());
		assertThrows(
			Exception.class,
			() -> jdbcTemplate.update(
				"insert into workflow_runs (public_id, current_state) values (?, ?)",
				publicId, state),
			() -> "Expected CHECK violation for state " + state);
	}

	private void assertRejectionTaxonomyAccepted(String value) {
		// approvals requires a non-null artifact, so probe with workflow_events instead which permits free-standing rows.
		String runPid = "run_tax_" + Math.abs((value + System.nanoTime()).hashCode());
		jdbcTemplate.update(
			"insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runPid);
		Long runId = jdbcTemplate.queryForObject(
			"select id from workflow_runs where public_id = ?", Long.class, runPid);
		String evtPid = "evt_tax_" + Math.abs((value + System.nanoTime()).hashCode());
		jdbcTemplate.update(
			"insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type, rejection_taxonomy) "
				+ "values (?, ?, 'test', 'tester', 'system', ?)",
			evtPid, runId, value);
		jdbcTemplate.update("delete from workflow_events where public_id = ?", evtPid);
		jdbcTemplate.update("delete from workflow_runs where public_id = ?", runPid);
	}

	private void assertRejectionTaxonomyRejected(String value) {
		String runPid = "run_taxrej_" + Math.abs((value + System.nanoTime()).hashCode());
		jdbcTemplate.update(
			"insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runPid);
		Long runId = jdbcTemplate.queryForObject(
			"select id from workflow_runs where public_id = ?", Long.class, runPid);
		String evtPid = "evt_taxrej_" + Math.abs((value + System.nanoTime()).hashCode());
		try {
			assertThrows(
				Exception.class,
				() -> jdbcTemplate.update(
					"insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type, rejection_taxonomy) "
						+ "values (?, ?, 'test', 'tester', 'system', ?)",
					evtPid, runId, value),
				() -> "Expected CHECK violation for rejection_taxonomy " + value);
		} finally {
			jdbcTemplate.update("delete from workflow_runs where public_id = ?", runPid);
		}
	}
}
