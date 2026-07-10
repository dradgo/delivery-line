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
  // Story 3i-1 (AC6) — three-sites codes (enum + ProblemDetailsCatalog + problemTypeUris
  // placeholder; ProblemDetailsCoverageFoundationContract auto-covers). Back the doctor jira-auth
  // probe under the jira-real profile, mirroring the DOCTOR_GITHUB_* pair.
  DOCTOR_JIRA_AUTH_FAILED("DOCTOR_JIRA_AUTH_FAILED"),
  DOCTOR_JIRA_TOKEN_MISSING("DOCTOR_JIRA_TOKEN_MISSING"),
  // Story 3i-3 (AC6) — three-sites codes (enum + ProblemDetailsCatalog + problemTypeUris
  // placeholder; ProblemDetailsCoverageFoundationContract auto-covers). Back the doctor
  // bitbucket-auth probe under the bitbucket-real profile, mirroring the DOCTOR_GITHUB_* /
  // DOCTOR_JIRA_* pairs.
  DOCTOR_BITBUCKET_AUTH_FAILED("DOCTOR_BITBUCKET_AUTH_FAILED"),
  DOCTOR_BITBUCKET_TOKEN_MISSING("DOCTOR_BITBUCKET_TOKEN_MISSING"),
  // Story 3.9 (Decision D5) — three-sites codes (enum + ProblemDetailsCatalog + manifest).
  // LINEAR_GITHUB_REPO_MISMATCH guards prepareWorkspace (AC9); the two DOCTOR_GIT_* codes back the
  // git availability + bot-identity doctor probes (AC15).
  LINEAR_GITHUB_REPO_MISMATCH("LINEAR_GITHUB_REPO_MISMATCH"),
  DOCTOR_GIT_MISSING("DOCTOR_GIT_MISSING"),
  DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED("DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED"),
  RETRY_NOT_APPLICABLE("RETRY_NOT_APPLICABLE"),
  // Story 3d-4 (AC6 / R5) — three-sites code (enum + ProblemDetailsCatalog + manifest). The
  // wrong-state gate for the manual-artifact submission + manual-bundle retrieval endpoints: raised
  // (409) when the run is not in WaitingForManualExecution (no parked awaiting_manual runner
  // execution). It plays the role the epic wording assigns to a generic ACTION_NOT_ALLOWED — which
  // is deliberately NOT added (this codebase expresses wrong-state via state-specific codes, the
  // RETRY_NOT_APPLICABLE precedent). RBAC stays audit-only; this is the only allowed-action gate.
  MANUAL_EXECUTION_NOT_APPLICABLE("MANUAL_EXECUTION_NOT_APPLICABLE"),
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
  UNSUPPORTED_CONNECTOR_KIND("UNSUPPORTED_CONNECTOR_KIND"),
  // Story 3i-2 (AC3) — three-sites code (enum + ProblemDetailsCatalog + problemTypeUris
  // placeholder; ProblemDetailsCoverageFoundationContract auto-covers). Raised by
  // TicketQueryService
  // when the project's ticket source has no registered adapter in this context OR reports
  // supportsTicketQuery=false. Distinct from UNSUPPORTED_CONNECTOR_KIND, which means "no adapter is
  // registered for that connector kind at all" — here the connector may well exist and simply
  // cannot
  // be browsed. NOT_FOUND + non-retryable, so the intake UI cleanly hides the surface by catching
  // it.
  TICKET_QUERY_NOT_SUPPORTED("TICKET_QUERY_NOT_SUPPORTED"),
  // Story 3i-2 code-review — three-sites codes (enum + ProblemDetailsCatalog + problemTypeUris
  // placeholder). The browse is the FIRST synchronous REST call into a ticket-source adapter, so it
  // is the first place a TicketSourceAdapterException can reach an HTTP response. Left uncaught it
  // renders as an opaque 500 INTERNAL_ERROR + retryable=false, which mislabels the two conditions
  // operators actually hit: an expired token (diagnosable, not retryable) and a transient
  // 429/5xx/timeout (retryable). TicketQueryService translates the exception's
  // IntegrationFailureCategory into one of these two codes, honoring the contract that
  // TicketSourceAdapterException's own javadoc states: "the application service is responsible for
  // converting this to the appropriate DomainException".
  //
  // NETWORK_API_FAILURE — the source was unreachable or answered transiently. 503 + RETRYABLE.
  TICKET_QUERY_SOURCE_UNAVAILABLE("TICKET_QUERY_SOURCE_UNAVAILABLE"),
  // Every other category (LINK_FAILURE auth/permission, SYNC_FAILURE malformed response,
  // STATE_CONFLICT) — the source answered, but not usefully. 502 + non-retryable: retrying an
  // expired credential or an unparseable payload cannot help.
  TICKET_QUERY_SOURCE_FAILED("TICKET_QUERY_SOURCE_FAILED"),
  // Story 3c-4 (AC3) — three-sites code (enum + ProblemDetailsCatalog + manifest). Thrown by
  // CredentialMasterKeyGuard at startup (as a typed DomainException, precedent
  // DOCTOR_REST_BIND_UNAVAILABLE) when the credential master key is missing AND at least one
  // project_credentials row exists. SERVICE_UNAVAILABLE + non-retryable (an infrastructure-missing
  // fail-fast, mirroring DOCTOR_GITHUB_TOKEN_MISSING / DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED). The
  // cipher's decrypt faults (tamper/wrong-key/bad-algo) do NOT get a code — they throw
  // CredentialCipherException; the REST/Problem-Details mapping of credential failures is 3c-8.
  CREDENTIAL_MASTER_KEY_UNCONFIGURED("CREDENTIAL_MASTER_KEY_UNCONFIGURED"),
  // Story 3c-10 (AC3) — three-sites code (enum + ProblemDetailsCatalog + manifest). Raised (WARN,
  // never FAIL) by the doctor `projects` probe when an ACTIVE project is misconfigured: a blank
  // repository URL, a connector role's credential `missing`, or a ticket-source/repo-host kind the
  // ProjectConnectorResolver cannot resolve. A misconfigured project is advisory, not a boot
  // blocker — SERVICE_UNAVAILABLE + non-retryable, mirroring the other WARN-advisory DOCTOR_* codes
  // (DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED / DOCTOR_OBSERVABILITY_LOW_MEMORY). The specific failing
  // sub-check rides in `details`/`reason`, not in distinct codes.
  DOCTOR_PROJECT_CONFIG_INCOMPLETE("DOCTOR_PROJECT_CONFIG_INCOMPLETE"),
  // Story 3d-1 (AC6) — three-sites code (enum + ProblemDetailsCatalog + manifest), registered AHEAD
  // of its throw site: the throw lands in 3d-2's ProjectConnectorResolver reviewer-resolution path
  // when a reviewer verdict is requested for a project with no reviewer model bound. The foundation
  // gate (ProblemDetailsCoverageFoundationContract) round-trips every registered code, so
  // registration alone passes the gate — no production throw site is required in 3d-1.
  // SERVICE_UNAVAILABLE + non-retryable mirrors the other "config-absent advisory" codes.
  REVIEWER_MODEL_NOT_CONFIGURED("REVIEWER_MODEL_NOT_CONFIGURED"),
  // Story 3d-8 (FR67, AC9 / R3) — three-sites code (enum + ProblemDetailsCatalog + manifest).
  // Mirrors RETRY_NOT_APPLICABLE: there is NO generic ACTION_NOT_ALLOWED guard — command services
  // enforce their own state preconditions. Raised by WorkflowArchiveService when archiving an
  // already-archived run, or un-archiving a run that is not archived. CONFLICT (409) +
  // non-retryable
  // (a precondition mismatch on the run's archive marker, not a transient fault).
  ARCHIVE_NOT_APPLICABLE("ARCHIVE_NOT_APPLICABLE"),
  // Story 3e-2 (review D1) — three-sites code (enum + ProblemDetailsCatalog + manifest). Mirrors
  // RETRY_NOT_APPLICABLE / ARCHIVE_NOT_APPLICABLE: there is NO generic ACTION_NOT_ALLOWED guard —
  // command services enforce their own state preconditions. Raised by
  // WorkflowCommandService.regenerateSpecWithClarifications when a spec rebuild is requested for a
  // run that has ZERO `accepted` clarifications to incorporate (the regenerate action is surfaced
  // unconditionally at WaitingForSpecApproval, so the executor is the gate). CONFLICT (409) +
  // non-retryable (a precondition mismatch, not a transient fault).
  REGENERATE_NOT_APPLICABLE("REGENERATE_NOT_APPLICABLE"),
  // Story 3f-3 (AC4) — three-sites code (enum + ProblemDetailsCatalog + manifest). Raised by
  // RunDependencyService when declaring a run dependency edge would introduce a cycle in the
  // run-dependency DAG (a recursive-CTE reachability probe detects that the prerequisite already
  // depends, transitively, on the dependent). CONFLICT (409) + non-retryable: a malformed
  // declaration, not a transient fault (mirrors ILLEGAL_TRANSITION). Carries runId, dependsOnRunId,
  // and reason=cycle_detected.
  RUN_DEPENDENCY_CYCLE("RUN_DEPENDENCY_CYCLE"),
  // Story 3f-7 (AC5) — three-sites code (enum + ProblemDetailsCatalog + manifest). Raised by
  // SplitProposalService.request() at the TOP of the method (before any reviewer-bound check or
  // LLM dispatch) when the run's split depth (distance from the lineage root, walking parentRunId;
  // root = depth 0) is already >= deliveryline.complex-ticket-flow.max-split-depth and the request
  // does NOT carry the allowDeepSplit override. A recursive split deeper than the configured cap is
  // a malformed request, not a transient fault; mirror RUN_DEPENDENCY_CYCLE's CONFLICT (409) +
  // non-retryable mapping. Carries runId, currentDepth, maxDepth, and reason=depth_limit_exceeded.
  SPLIT_DEPTH_LIMIT_EXCEEDED("SPLIT_DEPTH_LIMIT_EXCEEDED"),
  // Story 4.3 (AC2 / Reconciliation 9) — three-sites code (enum + ProblemDetailsCatalog +
  // manifest).
  // Raised by AuditQueryService when an audit-history filter value is malformed: an unknown
  // --event-type token (not in the WorkflowEventType registry), an undecodable --cursor (bad
  // base64url / wrong shape / unparseable keyset), or a mutually-exclusive scope at the service
  // seam.
  // BAD_REQUEST (400) + non-retryable (a malformed query, not a transient fault — mirrors
  // INVALID_COMMAND_PAYLOAD). The since>until case reuses the existing INVALID_TIME_RANGE, and the
  // CLI --ticket/--run XOR reuses INVALID_COMMAND_PAYLOAD (the parseBatchTickets precedent).
  INVALID_AUDIT_FILTER("INVALID_AUDIT_FILTER"),
  // Story 4.5 (AC3 / Reconciliation 6) — three-sites code (enum + ProblemDetailsCatalog +
  // manifest).
  // Mirrors RETRY_NOT_APPLICABLE / MANUAL_EXECUTION_NOT_APPLICABLE / ARCHIVE_NOT_APPLICABLE: there
  // is
  // NO generic ACTION_NOT_ALLOWED guard — command services enforce their own state preconditions.
  // Raised by RecoveryService.resume when the run is not in Paused (details.currentState), OR when
  // the run IS Paused but no WORKFLOW_STATE_CHANGED → Paused event exists to derive the prior
  // executing state / link the audit trail. CONFLICT (409) + non-retryable (a precondition mismatch
  // on the run's state, not a transient fault).
  RESUME_NOT_APPLICABLE("RESUME_NOT_APPLICABLE"),
  RECONCILE_NOT_APPLICABLE("RECONCILE_NOT_APPLICABLE"),
  MISSING_RECONCILIATION_DECISION("MISSING_RECONCILIATION_DECISION"),
  INVALID_RECONCILIATION_DECISION("INVALID_RECONCILIATION_DECISION"),
  CONFLICT_NOT_FOUND("CONFLICT_NOT_FOUND"),
  CONFLICT_ALREADY_RESOLVED("CONFLICT_ALREADY_RESOLVED");

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
