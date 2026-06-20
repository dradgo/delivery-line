package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Stable wire form is the explicit {@code wireValue} (decoupled from the enum constant name).
 * Renaming an enum constant must keep its {@code wireValue} identical or it is a wire-breaking
 * change.
 */
public enum DomainErrorCode implements RegistryValue {
  ILLEGAL_TRANSITION("ILLEGAL_TRANSITION"),
  IDEMPOTENCY_KEY_CONFLICT("IDEMPOTENCY_KEY_CONFLICT"),
  MISSING_IDEMPOTENCY_KEY("MISSING_IDEMPOTENCY_KEY"),
  INVALID_IDEMPOTENCY_KEY("INVALID_IDEMPOTENCY_KEY"),
  STALE_IDEMPOTENCY_RESERVATION("STALE_IDEMPOTENCY_RESERVATION"),
  IDEMPOTENCY_RESERVATION_EXHAUSTED("IDEMPOTENCY_RESERVATION_EXHAUSTED"),
  IDEMPOTENCY_RECORD_LOST("IDEMPOTENCY_RECORD_LOST"),
  EXPORT_CLASSIFICATION_VIOLATION("EXPORT_CLASSIFICATION_VIOLATION"),
  APPROVAL_VERSION_MISMATCH("APPROVAL_VERSION_MISMATCH"),
  CLARIFICATION_ARTIFACT_VERSION_MISMATCH("CLARIFICATION_ARTIFACT_VERSION_MISMATCH"),
  CLARIFICATION_NOT_FOUND("CLARIFICATION_NOT_FOUND"),
  CLARIFICATION_TERMINAL_STATE("CLARIFICATION_TERMINAL_STATE"),
  ILLEGAL_CLARIFICATION_TRANSITION("ILLEGAL_CLARIFICATION_TRANSITION"),
  CONCURRENT_TRANSITION_CONFLICT("CONCURRENT_TRANSITION_CONFLICT"),
  RUNNER_TIMEOUT("RUNNER_TIMEOUT"),
  RUNNER_CONTRACT_VIOLATION("RUNNER_CONTRACT_VIOLATION"),
  LINEAR_TICKET_NOT_FOUND("LINEAR_TICKET_NOT_FOUND"),
  INTEGRATION_LINK_CONFLICT("INTEGRATION_LINK_CONFLICT"),
  ARTIFACT_PAYLOAD_UNAVAILABLE("ARTIFACT_PAYLOAD_UNAVAILABLE"),
  ARTIFACT_LINEAGE_ALREADY_EXISTS("ARTIFACT_LINEAGE_ALREADY_EXISTS"),
  ARTIFACT_RECORD_NOT_FOUND("ARTIFACT_RECORD_NOT_FOUND"),
  ARTIFACT_OPERATION_CONFLICT("ARTIFACT_OPERATION_CONFLICT"),
  ARTIFACT_INVALID_FILENAME("ARTIFACT_INVALID_FILENAME"),
  ARTIFACT_INVALID_STATE_TRANSITION("ARTIFACT_INVALID_STATE_TRANSITION"),
  ARTIFACT_CHECKSUM_MISMATCH("ARTIFACT_CHECKSUM_MISMATCH"),
  ARTIFACT_WORKFLOW_RUN_NOT_FOUND("ARTIFACT_WORKFLOW_RUN_NOT_FOUND"),
  ARTIFACT_RUNNER_EXECUTION_TIMED_OUT("ARTIFACT_RUNNER_EXECUTION_TIMED_OUT"),
  ARTIFACT_OPERATION_INTENT_CONFLICT("ARTIFACT_OPERATION_INTENT_CONFLICT"),
  WORKFLOW_RUN_TERMINAL("WORKFLOW_RUN_TERMINAL"),
  RUNNER_EXECUTION_NOT_FOUND("RUNNER_EXECUTION_NOT_FOUND"),
  INVALID_COMMAND_PAYLOAD("INVALID_COMMAND_PAYLOAD"),
  INVALID_TIME_RANGE("INVALID_TIME_RANGE"),
  HISTORY_TOO_LARGE("HISTORY_TOO_LARGE"),
  INTERNAL_ERROR("INTERNAL_ERROR"),
  UNKNOWN_REGISTRY_VALUE("UNKNOWN_REGISTRY_VALUE"),
  INVALID_ID_PREFIX("INVALID_ID_PREFIX"),
  RUN_NOT_FOUND("RUN_NOT_FOUND"),
  UNKNOWN_ACTOR_ROLE("UNKNOWN_ACTOR_ROLE"),
  DOCTOR_POSTGRES_UNREACHABLE("DOCTOR_POSTGRES_UNREACHABLE"),
  DOCTOR_FLYWAY_FAILED("DOCTOR_FLYWAY_FAILED"),
  DOCTOR_REST_BIND_UNAVAILABLE("DOCTOR_REST_BIND_UNAVAILABLE"),
  DOCTOR_DOCKER_MISSING("DOCTOR_DOCKER_MISSING"),
  DOCTOR_CONFIG_PERMISSIONS_UNSAFE("DOCTOR_CONFIG_PERMISSIONS_UNSAFE"),
  DOCTOR_UNSUPPORTED_ENVIRONMENT("DOCTOR_UNSUPPORTED_ENVIRONMENT"),
  DOCTOR_ARTIFACT_DIR_UNWRITABLE("DOCTOR_ARTIFACT_DIR_UNWRITABLE"),
  DOCTOR_RUNNER_SECRET_MISSING("DOCTOR_RUNNER_SECRET_MISSING"),
  DOCTOR_GITHUB_AUTH_FAILED("DOCTOR_GITHUB_AUTH_FAILED"),
  DOCTOR_GITHUB_TOKEN_MISSING("DOCTOR_GITHUB_TOKEN_MISSING"),
  // Story 3.9 (Decision D5) — three-sites codes (enum + ProblemDetailsCatalog + manifest).
  // LINEAR_GITHUB_REPO_MISMATCH guards prepareWorkspace (AC9); the two DOCTOR_GIT_* codes back the
  // git availability + bot-identity doctor probes (AC15).
  LINEAR_GITHUB_REPO_MISMATCH("LINEAR_GITHUB_REPO_MISMATCH"),
  DOCTOR_GIT_MISSING("DOCTOR_GIT_MISSING"),
  DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED("DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED"),
  RETRY_NOT_APPLICABLE("RETRY_NOT_APPLICABLE"),
  // Story 3a-1 (AC8 / Trap T5) — three-sites code (enum + ProblemDetailsCatalog + manifest).
  // Raised when a runner emits an artifact whose type does not match the dispatching stage's
  // expected type (e.g. a spec-stage / INVESTIGATION runner emits an implementationPlan). The
  // run is routed to Failed via the existing runner-contract-violation failure path; this code is
  // the typed surface for the REST / inspection layer.
  RUNNER_ARTIFACT_TYPE_MISMATCH("RUNNER_ARTIFACT_TYPE_MISMATCH"),
  // Story 3.7 (AC10 / Decision D7) — three-sites code (enum + ProblemDetailsCatalog + manifest).
  // Backs the doctor observability-memory probe: WARN (never FAIL) when the observability profile
  // is
  // active on a host with less than 8 GB total physical RAM, where the ELK stack may be unstable.
  DOCTOR_OBSERVABILITY_LOW_MEMORY("DOCTOR_OBSERVABILITY_LOW_MEMORY"),
  // Story 3.12 (AC3 / Trap T9) — three-sites code (enum + ProblemDetailsCatalog + manifest).
  // Raised when a pr-output runner reports branch/commitSha/prReference values that disagree with
  // the actual git/GitHub state captured by RepositoryWorkspaceService.captureAndPush. The run is
  // routed to Failed via the existing runner-contract-violation failure path; this code is the
  // typed surface for the REST / inspection layer.
  RUNNER_PR_REF_DRIFT("RUNNER_PR_REF_DRIFT"),
  // Story 3.12 (AC9 / Trap T9) — three-sites code (enum + ProblemDetailsCatalog + manifest).
  // Raised when an untrusted pr-output runner reports branch/commitSha/prReference strings that do
  // not match the documented format patterns (story 2.24). The artifact is rejected and the run is
  // routed to Failed via the existing runner-contract-violation failure path; this code is the
  // typed surface for the REST / inspection layer.
  RUNNER_OUTPUT_VALIDATION_FAILED("RUNNER_OUTPUT_VALIDATION_FAILED"),
  // Story 3.15 (AC5 / Trap T9) — three-sites code (enum + ProblemDetailsCatalog + manifest).
  // Raised when a prOutput artifact's PR reference has drifted from the canonical
  // integration_links.external_ref established by IntegrationLinkService.linkGitHubPr — preventing
  // approval of an artifact whose PR reference no longer matches the durable linkage (NFR19). The
  // guard (IntegrationLinkService.assertArtifactPrLinkMatches) is built + tested here; its
  // production approval call-site lands in story 3.20 (acceptImplementation does not yet exist).
  ARTIFACT_PR_LINK_MISMATCH("ARTIFACT_PR_LINK_MISMATCH"),
  // Story 3.16 (AC8 / Trap T4) — three-sites code (enum + ProblemDetailsCatalog + manifest).
  // Raised at startup by the Linear completion-sync template validator when, with completion-sync
  // enabled, the configured `deliveryline.workflow.linear-completion-sync.template` references an
  // unknown placeholder or omits a required one (Decision D4). Fails context boot so a malformed
  // pilot template never silently posts a broken merge-ready summary; BAD_REQUEST + non-retryable
  // (mirrors INVALID_COMMAND_PAYLOAD — it is a configuration defect, not a transient fault).
  INVALID_COMPLETION_TEMPLATE("INVALID_COMPLETION_TEMPLATE"),
  // Story 3.21 (AC7 / D6) — three-sites code (enum + ProblemDetailsCatalog + manifest). Raised by
  // TechnicalApprovalService.rejectImplementation when taggedFeedback is null (defense-in-depth for
  // AR34a: no rejection row ever persists without a developer taxonomy). The typed
  // @NotNull RejectionTaxonomy field already prevents this at the command boundary; the REST-layer
  // INVALID_REJECTION_TAXONOMY deserialization concern belongs to story 3.24. BAD_REQUEST +
  // non-retryable (mirrors INVALID_COMMAND_PAYLOAD — a malformed reviewer decision, not transient).
  MISSING_REJECTION_TAXONOMY("MISSING_REJECTION_TAXONOMY"),
  // Story 3.17a (AC4 / D5) — three-sites code (enum + ProblemDetailsCatalog + manifest). Raised by
  // RunnerExecutionQueue.enqueue when the count of queued rows is already at
  // deliveryline.runner.queue-max-depth; the row is NOT inserted and the error carries
  // details.currentDepth + details.maxDepth. SERVICE_UNAVAILABLE + retryable=true (transient
  // backpressure — mirrors RUNNER_TIMEOUT); the "run stays in its prior state" caller behavior is
  // realized at the dispatch call-site in story 3.17b (here the error simply propagates).
  RUNNER_QUEUE_FULL("RUNNER_QUEUE_FULL"),
  // Story 3.23 (AC5 / R4) — three-sites code (enum + ProblemDetailsCatalog + manifest). Raised at
  // the REST/CLI controller boundary by the accept-implementation (and, when it lands, the 3.24
  // reject-implementation) handler when the request body's reviewerRole is not exactly `developer`
  // (including blank — which would otherwise default to `product_reviewer` via
  // ApprovalReviewerRoleResolver and mask the mismatch). This is request-shape validation, not a
  // domain decision (ArchUnit-safe). BAD_REQUEST + non-retryable (mirrors INVALID_COMMAND_PAYLOAD —
  // a malformed reviewer decision, not a transient fault). SHARED with story 3.24: first-to-land
  // creates it; the other reuses both the code and the `reviewerRole == developer` validation
  // idiom.
  INVALID_REVIEWER_ROLE_FOR_ENDPOINT("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"),
  // Story 3.24 (AC4 / R4) — three-sites code (enum + ProblemDetailsCatalog + manifest).
  // Pre-reserved
  // by story 3.21's MISSING_REJECTION_TAXONOMY comment above. Raised at the REST/CLI controller
  // boundary by the reject-implementation handler when the request's taggedFeedback is a valid
  // RejectionTaxonomy value but NOT in the developer subset (e.g. a product value like
  // MISSING_SCOPE)
  // — surfaced via RejectionTaxonomy.isDeveloperValue(). An entirely-unknown enum string fails
  // Jackson deserialization first → INVALID_COMMAND_PAYLOAD (no extra code). This is request-shape
  // validation, not a domain decision (ArchUnit-safe); the service keeps its own defense-in-depth
  // subset guard (3.21, INVALID_COMMAND_PAYLOAD). BAD_REQUEST + non-retryable (mirrors
  // INVALID_COMMAND_PAYLOAD — a malformed reviewer decision, not a transient fault).
  INVALID_REJECTION_TAXONOMY("INVALID_REJECTION_TAXONOMY"),
  // Story 3c-2 (AC5) — three-sites codes (enum + ProblemDetailsCatalog + manifest), registered
  // ahead of their throw sites: PROJECT_NOT_FOUND / PROJECT_SLUG_CONFLICT land in the project
  // REST/service layer (3c-8); UNSUPPORTED_CONNECTOR_KIND in the ProjectConnectorResolver (3c-3).
  // The foundation gate round-trips every code through ProblemDetailsMapper, so no production throw
  // site is required here.
  PROJECT_NOT_FOUND("PROJECT_NOT_FOUND"),
  PROJECT_SLUG_CONFLICT("PROJECT_SLUG_CONFLICT"),
  UNSUPPORTED_CONNECTOR_KIND("UNSUPPORTED_CONNECTOR_KIND");

  private static final Map<String, DomainErrorCode> LOOKUP = RegistryParsers.index(values());

  private final String wireValue;

  DomainErrorCode(String wireValue) {
    this.wireValue = wireValue;
  }

  @Override
  public String value() {
    return wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  static DomainErrorCode fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static DomainErrorCode fromValue(String rawValue, String field) {
    return RegistryParsers.parse("DomainErrorCode", rawValue, field, LOOKUP);
  }
}
