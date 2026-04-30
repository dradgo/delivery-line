package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.rest.ProblemDetailsCatalog;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.AllowedAction;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.DomainRegistry;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerSchemaVersion;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RegistryContractTest {

	// Postgres normalizes `IN (...)` to `= ANY (ARRAY[...])` in pg_get_constraintdef output, so we
	// scope the value-literal extraction to whichever shape appears in the CHECK definition.
	private static final Pattern VALUES_CLAUSE = Pattern.compile(
		"(?i)(?:IN \\(([^)]+)\\)|ARRAY\\[([^\\]]+)\\])");
	private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']+)'");
	private static final Pattern PUBLIC_ID_REGEX = Pattern.compile("'\\^([a-z]+_)\\[A-Za-z0-9_-\\]\\{4,64\\}\\$'");
	private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^[a-z]+(?:\\.[a-z][A-Za-z0-9]*)+$");
	private static final String PROBLEM_TYPE_URI_PREFIX = "https://deliveryline.local/problems/";

	private static final String API_PLACEHOLDER_RESOURCE =
		"contracts/openapi/registry-api-schema-placeholders.json";
	private static final String FRONTEND_ALLOWED_ACTIONS_RESOURCE =
		"contracts/frontend/allowed-actions.placeholder.json";
	private static final String EVENT_TYPES_RESOURCE =
		"contracts/events/workflow-event-types.fixture.json";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void registryCatalogExposesTheAuthoritativeFoundationValueSets() {
		assertEquals(registryValues(WorkflowState.values()), DomainRegistry.workflowStates());
		assertEquals(registryValues(ActorType.values()), DomainRegistry.actorTypes());
		assertEquals(registryValues(AllowedAction.values()), DomainRegistry.allowedActions());
		assertEquals(registryValues(DomainErrorCode.values()), DomainRegistry.domainErrorCodes());
		assertEquals(registryValues(RunnerExecutionStatus.values()), DomainRegistry.runnerExecutionStatuses());
		assertEquals(registryValues(ArtifactOperationStatus.values()), DomainRegistry.artifactOperationStatuses());
		assertEquals(registryValues(ArtifactStatus.values()), DomainRegistry.artifactStatuses());
		assertEquals(registryValues(ArtifactType.values()), DomainRegistry.artifactTypes());
		assertEquals(registryValues(DataClassification.values()), DomainRegistry.dataClassifications());
		assertEquals(registryValues(FailureCategory.values()), DomainRegistry.failureCategories());
		assertEquals(registryValues(IntegrationSyncStatus.values()), DomainRegistry.integrationSyncStatuses());
		assertEquals(registryValues(RunnerSchemaVersion.values()), DomainRegistry.runnerSchemaVersions());
		assertEquals(registryValues(WorkflowEventType.values()), DomainRegistry.workflowEventTypes());
		assertEquals(PublicIdPrefixes.prefixMap(), DomainRegistry.publicIdPrefixes());
	}

	@Test
	void workflowStatesStayAlignedWithSqlChecksAndApiManifest() throws IOException {
		Set<String> expectedStates = DomainRegistry.workflowStates();
		assertFalse(expectedStates.isEmpty(), "WorkflowState registry must not be empty");

		assertEquals(expectedStates, extractConstraintValues("ck_workflow_runs_current_state"));
		assertEquals(expectedStates, extractConstraintValues("ck_workflow_events_prior_state"));
		assertEquals(expectedStates, extractConstraintValues("ck_workflow_events_resulting_state"));
		assertEquals(expectedStates, readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "workflowStates"));
	}

	@Test
	void actorTypesAndStatusRegistriesStayAlignedWithSqlChecksAndApiManifest() throws IOException {
		assertFalse(DomainRegistry.actorTypes().isEmpty());
		assertEquals(DomainRegistry.actorTypes(), extractConstraintValues("ck_workflow_events_actor_type"));
		assertEquals(DomainRegistry.actorTypes(), extractConstraintValues("ck_approvals_actor_type"));
		assertEquals(DomainRegistry.actorTypes(), extractConstraintValues("ck_recovery_actions_actor_type"));
		assertEquals(DomainRegistry.actorTypes(), readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "actorTypes"));

		assertEquals(DomainRegistry.artifactStatuses(), extractConstraintValues("ck_artifacts_status"));
		assertEquals(DomainRegistry.artifactStatuses(),
			readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "artifactStatuses"));
		assertEquals(DomainRegistry.artifactOperationStatuses(),
			extractConstraintValues("ck_artifact_operations_status"));
		assertEquals(DomainRegistry.artifactOperationStatuses(),
			readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "artifactOperationStatuses"));
		assertEquals(DomainRegistry.runnerExecutionStatuses(),
			extractConstraintValues("ck_runner_executions_status"));
		assertEquals(DomainRegistry.runnerExecutionStatuses(),
			readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "runnerExecutionStatuses"));
		assertEquals(DomainRegistry.integrationSyncStatuses(),
			extractConstraintValues("ck_integration_links_sync_status"));
		assertEquals(DomainRegistry.integrationSyncStatuses(),
			readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "integrationSyncStatuses"));
	}

	@Test
	void registriesWithoutSqlChecksStayAlignedWithApiPlaceholder() throws IOException {
		// V1 schema does NOT enforce CHECKs for these — drift is asserted only against the API
		// placeholder. When V1.x adds CHECKs (deferred schema-tightening story), add SQL assertions.
		assertEquals(DomainRegistry.artifactTypes(),
			readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "artifactTypes"));
		assertEquals(DomainRegistry.dataClassifications(),
			readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "dataClassifications"));
		assertEquals(DomainRegistry.runnerSchemaVersions(),
			readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "runnerSchemaVersions"));
	}

	@Test
	void failureCategoryRegistryRemainsCataloguedEvenWithoutSqlOrApiChecks() {
		// FailureCategory still has no SQL CHECK (workflow_events.failure_category is just length>0)
		// and is not yet in the API manifest. Story 1.5 does consume it in the workflow transition
		// path, but the drift gate is still registry-vs-itself until a later story tightens schema/API.
		assertFalse(DomainRegistry.failureCategories().isEmpty(),
			"FailureCategory registry must not be empty even without external consumers");
	}

	@Test
	void publicIdPrefixesStayAlignedWithSqlChecksAndParsingHelpers() throws IOException {
		assertEquals(PublicIdPrefixes.prefixMap(), extractPublicIdPrefixesFromSql());
		assertEquals(PublicIdPrefixes.prefixMap(),
			readObjectNonEmpty(API_PLACEHOLDER_RESOURCE, "publicIdPrefixes"));

		// Happy path — well-formed public_id with prefix + 4+ char suffix
		assertEquals(PublicIdPrefixes.WORKFLOW_RUN, PublicIdPrefixes.fromPublicId("run_demo123"));
		assertEquals(PublicIdPrefixes.WORKFLOW_EVENT, PublicIdPrefixes.fromPublicId("evt_demo123"));

		// Unknown prefix
		DomainException unknownPrefix = assertThrows(DomainException.class,
			() -> PublicIdPrefixes.fromPublicId("zzz_demo123"));
		assertEquals(DomainErrorCode.INVALID_ID_PREFIX, unknownPrefix.errorCode());
		assertEquals("PublicIdPrefixes", unknownPrefix.details().get("registry"));
		assertEquals("zzz_demo123", unknownPrefix.details().get("value"));
		assertEquals("unknown_or_mismatched_prefix", unknownPrefix.details().get("reason"));

		// Mismatched-prefix path via require()
		DomainException mismatchedPrefix = assertThrows(DomainException.class,
			() -> PublicIdPrefixes.require("evt_demo123", PublicIdPrefixes.WORKFLOW_RUN));
		assertEquals(DomainErrorCode.INVALID_ID_PREFIX, mismatchedPrefix.errorCode());
		assertEquals("run_", mismatchedPrefix.details().get("expectedPrefix"));

		// Null / empty distinguished from unknown
		DomainException nullId = assertThrows(DomainException.class,
			() -> PublicIdPrefixes.fromPublicId(null));
		assertEquals("null_value", nullId.details().get("reason"));
		DomainException emptyId = assertThrows(DomainException.class,
			() -> PublicIdPrefixes.fromPublicId(""));
		assertEquals("empty_value", emptyId.details().get("reason"));

		// Malformed suffix — recognized prefix but suffix < 4 chars or contains illegal chars
		DomainException tooShort = assertThrows(DomainException.class,
			() -> PublicIdPrefixes.fromPublicId("run_a"));
		assertEquals("malformed_suffix", tooShort.details().get("reason"));
		DomainException sqlInjection = assertThrows(DomainException.class,
			() -> PublicIdPrefixes.fromPublicId("run_'; DROP TABLE users--"));
		assertEquals("malformed_suffix", sqlInjection.details().get("reason"));
		DomainException onlyPrefix = assertThrows(DomainException.class,
			() -> PublicIdPrefixes.fromPublicId("run_"));
		assertEquals("malformed_suffix", onlyPrefix.details().get("reason"));
	}

	@Test
	void publicIdPrefixSqlRegexExactlyMatchesJavaSuffixPattern() {
		// Stronger drift gate: assert the SQL CHECK uses the same suffix pattern as Java's validator.
		// Anything more lenient/stricter on either side breaks the V1↔Java contract.
		for (PublicIdPrefixes prefix : PublicIdPrefixes.values()) {
			List<String> defs = jdbcTemplate.queryForList(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = ?",
				String.class, prefix.constraintName());
			assertEquals(1, defs.size(),
				() -> "Constraint not found: " + prefix.constraintName());
			Matcher matcher = PUBLIC_ID_REGEX.matcher(defs.get(0));
			assertTrue(matcher.find(),
				() -> "Constraint " + prefix.constraintName()
					+ " must use regex '^<prefix>_[A-Za-z0-9_-]{4,64}$' but was: " + defs.get(0));
			assertEquals(prefix.prefix(), matcher.group(1),
				() -> "SQL prefix in " + prefix.constraintName() + " must equal " + prefix.prefix());
		}
	}

	@Test
	void workflowEventTypesUseDotSeparatedLowerCamelAndStayAlignedWithFixture() throws IOException {
		Set<String> eventTypes = registryValues(WorkflowEventType.values());
		assertFalse(eventTypes.isEmpty(), "WorkflowEventType registry must not be empty");
		assertEquals(eventTypes, readArrayNonEmpty(EVENT_TYPES_RESOURCE, "workflowEventTypes"));
		for (String eventType : eventTypes) {
			assertTrue(
				EVENT_TYPE_PATTERN.matcher(eventType).matches(),
				() -> "Workflow event type must use dot-separated lowerCamel namespaces: " + eventType);
		}
	}

	@Test
	void allowedActionsStayAlignedWithFrontendPlaceholder() throws IOException {
		assertEquals(
			DomainRegistry.allowedActions(),
			readArrayNonEmpty(FRONTEND_ALLOWED_ACTIONS_RESOURCE, "allowedActions"));
	}

	@Test
	void domainErrorCodesStayAlignedWithProblemTypeOwnershipManifest() throws IOException {
		Set<String> codes = DomainRegistry.domainErrorCodes();
		Map<String, String> uris = readObjectNonEmpty(API_PLACEHOLDER_RESOURCE, "problemTypeUris");
		assertEquals(codes, uris.keySet());
		assertEquals(codes, ProblemDetailsCatalog.metadataByCode().keySet().stream()
			.map(DomainErrorCode::value)
			.collect(Collectors.toCollection(LinkedHashSet::new)));

		Set<String> uniqueUris = new LinkedHashSet<>(uris.values());
		assertEquals(uris.size(), uniqueUris.size(),
			"Every error code must map to a unique problem type URI");

		for (Map.Entry<String, String> entry : uris.entrySet()) {
			assertEquals(
				entry.getValue(),
				ProblemDetailsCatalog.metadataFor(DomainErrorCode.valueOf(entry.getKey())).typeUri(),
				() -> "ProblemDetailsCatalog must own the same type URI as the placeholder manifest for " + entry.getKey());
			assertTrue(entry.getValue().startsWith(PROBLEM_TYPE_URI_PREFIX),
				() -> "Problem type URI must start with " + PROBLEM_TYPE_URI_PREFIX
					+ " for " + entry.getKey());
			String suffix = entry.getValue().substring(PROBLEM_TYPE_URI_PREFIX.length());
			assertFalse(suffix.isEmpty(),
				() -> "Problem type URI for " + entry.getKey() + " must have a non-empty path suffix");
		}

		// wireValue contract: each code's wireValue equals its name (one-time alignment).
		// Future renames must keep wireValue stable; only enum constant identifier may change.
		for (DomainErrorCode code : DomainErrorCode.values()) {
			assertEquals(code.name(), code.wireValue(),
				() -> "DomainErrorCode " + code + " wireValue must match enum constant for V1");
		}
	}

	@Test
	void everyCurrentPersistenceBoundaryUsesUniformFailFastRegistryParsing() {
		Map<String, Function<String, ?>> registryBoundaries = new LinkedHashMap<>();
		registryBoundaries.put("workflow_runs.current_state", PersistedRegistryValues::workflowRunState);
		registryBoundaries.put("workflow_events.event_type", PersistedRegistryValues::workflowEventType);
		registryBoundaries.put("workflow_events.prior_state", PersistedRegistryValues::workflowEventPriorState);
		registryBoundaries.put("workflow_events.resulting_state", PersistedRegistryValues::workflowEventResultingState);
		registryBoundaries.put("workflow_events.actor_type", PersistedRegistryValues::workflowEventActorType);
		registryBoundaries.put("workflow_events.failure_category", PersistedRegistryValues::workflowEventFailureCategory);
		registryBoundaries.put("artifacts.artifact_type", PersistedRegistryValues::artifactType);
		registryBoundaries.put("artifacts.classification", PersistedRegistryValues::artifactClassification);
		registryBoundaries.put("artifacts.status", PersistedRegistryValues::artifactStatus);
		registryBoundaries.put("artifact_operations.status", PersistedRegistryValues::artifactOperationStatus);
		registryBoundaries.put("approvals.actor_type", PersistedRegistryValues::approvalActorType);
		registryBoundaries.put("runner_executions.status", PersistedRegistryValues::runnerExecutionStatus);
		registryBoundaries.put("integration_links.sync_status", PersistedRegistryValues::integrationSyncStatus);
		registryBoundaries.put("recovery_actions.actor_type", PersistedRegistryValues::recoveryActorType);

		List<DomainException> thrown = new ArrayList<>();
		for (Map.Entry<String, Function<String, ?>> entry : registryBoundaries.entrySet()) {
			DomainException error = assertThrows(
				DomainException.class,
				() -> entry.getValue().apply("__unknown__"),
				() -> "Expected fail-fast registry parsing for " + entry.getKey());
			thrown.add(error);
			assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, error.errorCode());
			assertEquals("__unknown__", error.details().get("value"));
			assertEquals(entry.getKey(), error.details().get("field"));
			assertTrue(error.details().containsKey("registry"));
		}
		assertEquals(registryBoundaries.size(), thrown.size());
	}

	@Test
	void persistenceBoundariesRejectEmptyStringAndCaseMismatchedInputs() {
		// Lock down the documented contract: empty string is unknown, not silently null.
		DomainException emptyState = assertThrows(DomainException.class,
			() -> PersistedRegistryValues.workflowRunState(""));
		assertEquals("", emptyState.details().get("value"));
		assertEquals("workflow_runs.current_state", emptyState.details().get("field"));

		// Case-mismatch: V1 stores 'Inbox' (PascalCase). Lowercase must fail.
		DomainException lowercaseState = assertThrows(DomainException.class,
			() -> PersistedRegistryValues.workflowRunState("inbox"));
		assertEquals("inbox", lowercaseState.details().get("value"));

		// Whitespace must fail (no implicit trim).
		DomainException paddedState = assertThrows(DomainException.class,
			() -> PersistedRegistryValues.workflowRunState(" Inbox"));
		assertEquals(" Inbox", paddedState.details().get("value"));

		// Actor types are lowercase — uppercase must fail.
		DomainException uppercaseActor = assertThrows(DomainException.class,
			() -> PersistedRegistryValues.workflowEventActorType("HUMAN"));
		assertEquals("HUMAN", uppercaseActor.details().get("value"));
	}

	@Test
	void nonNullablePersistenceBoundariesRejectNullWithExplicitReason() {
		// workflowRunState is non-nullable; null must fail fast with reason="null_value".
		DomainException nullState = assertThrows(DomainException.class,
			() -> PersistedRegistryValues.workflowRunState(null));
		assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, nullState.errorCode());
		assertEquals("null_value", nullState.details().get("reason"));
		assertEquals("workflow_runs.current_state", nullState.details().get("field"));
	}

	@Test
	void nullableStateParsingPreservesNullButRejectsUnknownAndEmpty() {
		// Null is tolerated for nullable columns (workflow_events.{prior_state,resulting_state}).
		assertNull(PersistedRegistryValues.workflowEventPriorState(null));
		assertNull(PersistedRegistryValues.workflowEventResultingState(null));
		assertNull(PersistedRegistryValues.workflowEventFailureCategory(null));

		// Valid value passes through.
		assertInstanceOf(WorkflowState.class, PersistedRegistryValues.workflowEventResultingState("Inbox"));
		assertInstanceOf(FailureCategory.class,
			PersistedRegistryValues.workflowEventFailureCategory("runner_timeout"));

		// Unknown still fails fast even on the nullable path.
		DomainException unknown = assertThrows(DomainException.class,
			() -> PersistedRegistryValues.workflowEventPriorState("__bogus__"));
		assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, unknown.errorCode());
		assertEquals("workflow_events.prior_state", unknown.details().get("field"));

		// Empty string is unknown, not null — the nullable path only short-circuits true Java null.
		DomainException emptyOnNullable = assertThrows(DomainException.class,
			() -> PersistedRegistryValues.workflowEventPriorState(""));
		assertEquals("", emptyOnNullable.details().get("value"));

		DomainException emptyFailureCategory = assertThrows(DomainException.class,
			() -> PersistedRegistryValues.workflowEventFailureCategory(""));
		assertEquals("", emptyFailureCategory.details().get("value"));
	}

	@Test
	void domainExceptionConstructorsHandleNullDetailsAndCauses() {
		// null details map must NOT NPE.
		DomainException noDetails = new DomainException(
			DomainErrorCode.UNKNOWN_REGISTRY_VALUE, "test", (Map<String, Object>) null);
		assertNotNull(noDetails.details());
		assertTrue(noDetails.details().isEmpty());

		// Cause-bearing constructor preserves the chain.
		Throwable cause = new IllegalStateException("root");
		DomainException withCause = new DomainException(
			DomainErrorCode.UNKNOWN_REGISTRY_VALUE, "wrapped", cause);
		assertEquals(cause, withCause.getCause());
	}

	private Set<String> extractConstraintValues(String constraintName) {
		List<String> defs = jdbcTemplate.queryForList(
			"select pg_get_constraintdef(oid) from pg_constraint where conname = ?",
			String.class, constraintName);
		assertEquals(1, defs.size(), () -> "Constraint not found: " + constraintName);
		String definition = defs.get(0);

		Matcher valuesMatcher = VALUES_CLAUSE.matcher(definition);
		assertTrue(valuesMatcher.find(),
			() -> "Constraint " + constraintName
				+ " must contain an `IN (...)` or `ARRAY[...]` clause but was: " + definition);
		String valuesClause = valuesMatcher.group(1) != null ? valuesMatcher.group(1) : valuesMatcher.group(2);

		Matcher matcher = QUOTED_LITERAL.matcher(valuesClause);
		Set<String> values = new LinkedHashSet<>();
		while (matcher.find()) {
			values.add(matcher.group(1));
		}
		return values;
	}

	private Map<String, String> extractPublicIdPrefixesFromSql() {
		Map<String, String> prefixes = new LinkedHashMap<>();
		for (PublicIdPrefixes prefix : PublicIdPrefixes.values()) {
			List<String> defs = jdbcTemplate.queryForList(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = ?",
				String.class, prefix.constraintName());
			assertEquals(1, defs.size(),
				() -> "Constraint not found: " + prefix.constraintName());
			Matcher matcher = PUBLIC_ID_REGEX.matcher(defs.get(0));
			assertTrue(matcher.find(),
				() -> "Could not extract public_id regex from " + defs.get(0));
			prefixes.put(prefix.alias(), matcher.group(1));
		}
		return prefixes;
	}

	private Set<String> readArrayNonEmpty(String resourcePath, String fieldName) throws IOException {
		JsonNode arrayNode = readResource(resourcePath).path(fieldName);
		if (!arrayNode.isArray()) {
			throw new IllegalStateException(resourcePath + " must contain an array field named " + fieldName);
		}
		Set<String> values = new LinkedHashSet<>();
		List<String> raw = new ArrayList<>();
		for (JsonNode node : arrayNode) {
			raw.add(node.asText());
			values.add(node.asText());
		}
		assertFalse(values.isEmpty(),
			() -> resourcePath + "#" + fieldName + " must declare at least one value");
		assertEquals(raw.size(), values.size(),
			() -> resourcePath + "#" + fieldName + " contains duplicate entries: " + raw);
		return values;
	}

	private Map<String, String> readObjectNonEmpty(String resourcePath, String fieldName) throws IOException {
		JsonNode objectNode = readResource(resourcePath).path(fieldName);
		if (!objectNode.isObject()) {
			throw new IllegalStateException(resourcePath + " must contain an object field named " + fieldName);
		}
		Map<String, String> values = new LinkedHashMap<>();
		objectNode.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
		assertFalse(values.isEmpty(),
			() -> resourcePath + "#" + fieldName + " must declare at least one entry");
		return values;
	}

	private JsonNode readResource(String resourcePath) throws IOException {
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new IllegalStateException("Missing contract resource " + resourcePath);
			}
			return objectMapper.readTree(stream);
		}
	}

	private Set<String> registryValues(Enum<?>[] values) {
		return java.util.Arrays.stream(values)
			.map(value -> ((org.dradgo.domain.registry.RegistryValue) value).value())
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}
}
