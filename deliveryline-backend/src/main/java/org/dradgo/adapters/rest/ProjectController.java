package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.CreateProjectCommand;
import org.dradgo.application.project.ProjectConnectivityService;
import org.dradgo.application.project.ProjectCredentialService;
import org.dradgo.application.project.ProjectManagementService;
import org.dradgo.application.project.UpdateProjectCommand;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorRole;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3c-8 — the first product surface over the {@code Project} aggregate. A governed,
 * localhost-only (inherited from {@code RestBindingGuard}, AC7), idempotent, Problem-Details REST
 * contract that never returns a stored secret (the credential store is write-only end-to-end).
 *
 * <p>Idempotency uses the generic {@code IdempotencyService} directly (R6 — no {@code
 * WorkflowCommandFingerprintFactory} edit): {@code POST /projects} (optional key) and {@code PUT
 * …/credentials/{role}} (required key) compute their own canonical fingerprint and run
 * reserve&rarr;execute&rarr;complete here. The credential plaintext is excluded from the
 * fingerprint so a same-key replay is idempotent without the secret entering the idempotency table.
 */
@RestController
@Validated
@RequestMapping("/api/v1/projects")
@Tag(
    name = "Projects",
    description = "Manage projects: CRUD, write-only credentials, connectivity.")
public class ProjectController {

  private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

  private static final String COMMAND_CREATE_PROJECT = "create-project";
  private static final String COMMAND_SET_CREDENTIAL = "set-project-credential";

  private final ProjectManagementService projectManagementService;
  private final ProjectConnectivityService projectConnectivityService;
  private final ProjectCredentialService projectCredentialService;
  private final IdempotencyService idempotencyService;
  private final LocalActorIdentityResolver localActorIdentityResolver;

  public ProjectController(
      ProjectManagementService projectManagementService,
      ProjectConnectivityService projectConnectivityService,
      ProjectCredentialService projectCredentialService,
      IdempotencyService idempotencyService,
      LocalActorIdentityResolver localActorIdentityResolver) {
    this.projectManagementService = projectManagementService;
    this.projectConnectivityService = projectConnectivityService;
    this.projectCredentialService = projectCredentialService;
    this.idempotencyService = idempotencyService;
    this.localActorIdentityResolver = localActorIdentityResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "listProjects", summary = "List projects")
  @ApiResponse(responseCode = "200", description = "Projects (direct array, no envelope).")
  public List<ProjectResponse> listProjects() {
    return projectManagementService.listProjects().stream().map(this::toResponse).toList();
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "createProject", summary = "Create a project")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Project created."),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_COMMAND_PAYLOAD or UNKNOWN_REGISTRY_VALUE (bad connector kind).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "PROJECT_SLUG_CONFLICT or IDEMPOTENCY_KEY_CONFLICT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ResponseEntity<ProjectResponse> createProject(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody CreateProjectRequest request) {
    String actorIdentity = resolveActor(actorIdentityHeader);
    CreateProjectCommand command =
        new CreateProjectCommand(
            request.name(),
            request.slug(),
            request.repositoryUrl(),
            request.ticketSourceKind(),
            request.repoHostKind(),
            request.openspecEnabled(),
            request.runnerKind(),
            request.stepRunnerKinds(),
            request.buildStageEnabled(),
            request.buildCommand(),
            request.lintStageEnabled(),
            request.lintCommands(),
            request.pushMode(),
            defaultTrue(request.autoCreatePullRequest()),
            idempotencyKey,
            actorIdentity);
    log.info(
        "REST create project received slug={} ticketSourceKind={} repoHostKind={} actorIdentity={}",
        MdcKeys.sanitizeForLog(request.slug()),
        MdcKeys.sanitizeForLog(request.ticketSourceKind()),
        MdcKeys.sanitizeForLog(request.repoHostKind()),
        MdcKeys.sanitizeForLog(actorIdentity));

    // Reject duplicated / comma-folded Idempotency-Key headers regardless of whether a single key
    // was supplied — a malformed multi-header set must not silently bypass idempotency on the
    // optional-key create path.
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    Project project;
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      // AC5 / Open Decision #5 default: create is idempotency-OPTIONAL (like submit-workflow).
      project = projectManagementService.createProject(command);
    } else {
      String fingerprint = createFingerprint(command);
      ReservationOutcome outcome =
          idempotencyService.checkAndReserve(
              idempotencyKey, COMMAND_CREATE_PROJECT, actorIdentity, fingerprint);
      if (outcome.decision() == ReservationDecision.REPLAY) {
        log.info(
            "REST create project idempotent replay idempotencyKey={} projectId={}",
            MdcKeys.sanitizeForLog(idempotencyKey),
            MdcKeys.sanitizeForLog(outcome.resultRef()));
        project = projectManagementService.getProject(outcome.resultRef());
      } else {
        project =
            executeWithCompletion(
                idempotencyKey, () -> projectManagementService.createProject(command));
      }
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(project));
  }

  @GetMapping(value = "/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "getProject", summary = "Get a project")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Project."),
    @ApiResponse(
        responseCode = "404",
        description = "PROJECT_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ProjectResponse getProject(@PathVariable String projectId) {
    log.info("REST get project received projectId={}", MdcKeys.sanitizeForLog(projectId));
    return toResponse(projectManagementService.getProject(projectId));
  }

  @PutMapping(
      value = "/{projectId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "updateProject", summary = "Edit a project's mutable configuration")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Project updated."),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_COMMAND_PAYLOAD or UNKNOWN_REGISTRY_VALUE (bad connector kind).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "PROJECT_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "PROJECT_SLUG_CONFLICT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ProjectResponse updateProject(
      @PathVariable String projectId,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      @Valid @RequestBody UpdateProjectRequest request) {
    String actorIdentity = resolveActor(actorIdentityHeader);
    log.info(
        "REST update project received projectId={} actorIdentity={}",
        MdcKeys.sanitizeForLog(projectId),
        MdcKeys.sanitizeForLog(actorIdentity));
    UpdateProjectCommand command =
        new UpdateProjectCommand(
            request.name(),
            request.repositoryUrl(),
            request.ticketSourceKind(),
            request.repoHostKind(),
            request.openspecEnabled(),
            request.runnerKind(),
            request.reviewerModelKind(),
            request.stepRunnerKinds(),
            request.buildStageEnabled(),
            request.buildCommand(),
            request.lintStageEnabled(),
            request.lintCommands(),
            request.pushMode(),
            defaultTrue(request.autoCreatePullRequest()),
            actorIdentity);
    return toResponse(projectManagementService.updateProject(projectId, command));
  }

  @PostMapping(value = "/{projectId}/disable", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "disableProject",
      summary = "Soft-disable a project (the default project cannot be disabled)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Project disabled."),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_COMMAND_PAYLOAD (the default project cannot be disabled).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "PROJECT_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ProjectResponse disableProject(@PathVariable String projectId) {
    log.info("REST disable project received projectId={}", MdcKeys.sanitizeForLog(projectId));
    return toResponse(projectManagementService.disableProject(projectId));
  }

  @PostMapping(value = "/{projectId}/enable", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "enableProject",
      summary = "Re-enable a disabled project (status := ACTIVE)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Project enabled."),
    @ApiResponse(
        responseCode = "404",
        description = "PROJECT_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ProjectResponse enableProject(@PathVariable String projectId) {
    log.info("REST enable project received projectId={}", MdcKeys.sanitizeForLog(projectId));
    return toResponse(projectManagementService.enableProject(projectId));
  }

  @PutMapping(
      value = "/{projectId}/credentials/{role}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "setProjectCredential",
      summary = "Set or rotate a connector credential (write-only — never returned)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Credential configured (non-secret id only)."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, or UNKNOWN_REGISTRY_VALUE (bad role).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "PROJECT_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "IDEMPOTENCY_KEY_CONFLICT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "CREDENTIAL_MASTER_KEY_UNCONFIGURED (no master key configured).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public SetCredentialResponse setProjectCredential(
      @PathVariable String projectId,
      @Parameter(description = "Connector role (underscored wire form).", example = "ticket_source")
          @PathVariable
          String role,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody SetCredentialRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    String actorIdentity = resolveActor(actorIdentityHeader);
    ConnectorRole connectorRole = ConnectorRole.fromValue(role, "credentials.role");
    // Secret-hostile: the plaintext is never logged. Only the project / role / non-secret cred id.
    log.info(
        "REST set credential received projectId={} role={} actorIdentity={}",
        MdcKeys.sanitizeForLog(projectId),
        connectorRole.value(),
        MdcKeys.sanitizeForLog(actorIdentity));

    String fingerprint = credentialFingerprint(projectId, connectorRole, actorIdentity);
    ReservationOutcome outcome =
        idempotencyService.checkAndReserve(
            idempotencyKey, COMMAND_SET_CREDENTIAL, actorIdentity, fingerprint);
    if (outcome.decision() == ReservationDecision.REPLAY) {
      log.info(
          "REST set credential idempotent replay projectId={} role={} credentialId={}",
          MdcKeys.sanitizeForLog(projectId),
          connectorRole.value(),
          MdcKeys.sanitizeForLog(outcome.resultRef()));
      return SetCredentialResponse.configured(connectorRole.value(), outcome.resultRef());
    }
    String credentialId;
    try {
      credentialId =
          projectCredentialService.setCredential(projectId, connectorRole, request.secret());
      idempotencyService.complete(idempotencyKey, credentialId, IdempotencyRecordStatus.COMPLETED);
    } catch (IllegalStateException masterKeyAbsent) {
      // 3c-4 cipher.encrypt throws IllegalStateException from requireMasterKey when no master key
      // is
      // configured (the startup guard stays dormant until the first credential row exists). Map it
      // to the already-registered 503 rather than the opaque generic 500 — secret-hostile (no
      // message/cause echoed). Scoped to the credential-set path; the only ISE the encrypt path can
      // throw is the master-key one.
      idempotencyService.complete(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
      throw masterKeyUnconfigured(projectId, connectorRole);
    } catch (RuntimeException failure) {
      // A CredentialCipherException (cipher fault) bubbles to the ProblemDetailsMapper handler as a
      // secret-hostile 500; a DomainException (e.g. PROJECT_NOT_FOUND from the FK) renders
      // normally.
      idempotencyService.complete(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
      throw failure;
    }
    log.info(
        "REST set credential success projectId={} role={} credentialId={}",
        MdcKeys.sanitizeForLog(projectId),
        connectorRole.value(),
        credentialId);
    return SetCredentialResponse.configured(connectorRole.value(), credentialId);
  }

  @PostMapping(value = "/{projectId}/test-connection", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "testProjectConnection",
      summary = "Run capability-aware connectivity probes (per-check results, HTTP 200)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Per-check connectivity results."),
    @ApiResponse(
        responseCode = "400",
        description = "UNSUPPORTED_CONNECTOR_KIND (a project kind has no registered adapter).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "PROJECT_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public TestConnectionResponse testProjectConnection(@PathVariable String projectId) {
    log.info("REST test connection received projectId={}", MdcKeys.sanitizeForLog(projectId));
    return TestConnectionResponse.from(projectConnectivityService.testConnection(projectId));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ProjectResponse toResponse(Project project) {
    boolean ticketConfigured =
        projectCredentialService.isConfigured(project.publicId(), ConnectorRole.TICKET_SOURCE);
    boolean repoConfigured =
        projectCredentialService.isConfigured(project.publicId(), ConnectorRole.REPO_HOST);
    // Story 3d-2 — the per-project advisory-reviewer credential (ConnectorRole.REVIEWER). A
    // REVIEW
    // dispatch (advisory reviewer + the 3f-4 split-proposal) resolves THIS secret with no host-key
    // fallback, so its presence must be surfaced for the UI to set it. Appended last so the
    // existing ticket_source/repo_host array indices are unchanged.
    boolean reviewerConfigured =
        projectCredentialService.isConfigured(project.publicId(), ConnectorRole.REVIEWER);
    List<CredentialPresenceResponse> credentials =
        List.of(
            CredentialPresenceResponse.of(ConnectorRole.TICKET_SOURCE.value(), ticketConfigured),
            CredentialPresenceResponse.of(ConnectorRole.REPO_HOST.value(), repoConfigured),
            CredentialPresenceResponse.of(ConnectorRole.REVIEWER.value(), reviewerConfigured));
    return ProjectResponse.from(
        project, credentials, ProjectManagementService.allowedActionsFor(project));
  }

  private String resolveActor(String actorIdentityHeader) {
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    return localActorIdentityResolver.resolve(actorIdentityHeader);
  }

  private Project executeWithCompletion(
      String idempotencyKey, java.util.function.Supplier<Project> action) {
    try {
      Project created = action.get();
      idempotencyService.complete(
          idempotencyKey, created.publicId(), IdempotencyRecordStatus.COMPLETED);
      return created;
    } catch (RuntimeException failure) {
      idempotencyService.complete(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
      throw failure;
    }
  }

  private static boolean defaultTrue(Boolean value) {
    return value == null || value;
  }

  private static String createFingerprint(CreateProjectCommand command) {
    String canonical =
        String.join(
            "|",
            COMMAND_CREATE_PROJECT,
            nullSafe(command.actorIdentity()),
            nullSafe(command.name()),
            nullSafe(command.slug()),
            nullSafe(command.repositoryUrl()),
            nullSafe(command.ticketSourceKind()),
            nullSafe(command.repoHostKind()),
            Boolean.toString(command.openspecEnabled()),
            nullSafe(command.runnerKind()),
            canonicalStepRunnerKinds(command.stepRunnerKinds()),
            // Story 3h-1 (AC2) — both build-config fields MUST be part of the create fingerprint,
            // else two creates differing only in build config would collide as idempotent replays.
            Boolean.toString(command.buildStageEnabled()),
            nullSafe(command.buildCommand()),
            // Story 3h-2 (AC2) — both lint-config fields MUST be part of the create fingerprint,
            // else two creates differing only in lint config would collide as idempotent replays.
            Boolean.toString(command.lintStageEnabled()),
            canonicalLintCommands(command.lintCommands()),
            // Story 3h-4 (AC1) — both delivery-config fields MUST be part of the create
            // fingerprint,
            // else two creates differing only in delivery config would collide as idempotent
            // replays. pushMode is the raw wire string (null ⇒ "" canonicalizes to the AUTO
            // default).
            nullSafe(command.pushMode()),
            Boolean.toString(command.autoCreatePullRequest()));
    return sha256Hex(canonical);
  }

  /**
   * Story 3h-2 — deterministic canonical form of the lint command list for the create fingerprint:
   * the commands joined in order (order is semantically meaningful — fail-fast runs first-to-last),
   * newline-delimited. Two same-key creates with the same lint commands fingerprint identically
   * (and replay); a different list (or order) is a distinct create.
   */
  private static String canonicalLintCommands(java.util.List<String> lintCommands) {
    if (lintCommands == null || lintCommands.isEmpty()) {
      return "";
    }
    return lintCommands.stream()
        .map(ProjectController::nullSafe)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  /**
   * Story 3e-4 — deterministic canonical form of the per-step map for the create fingerprint: the
   * (step=kind) pairs sorted by step key, comma-joined. Two same-key creates with the same per-step
   * mapping fingerprint identically (and replay); a different mapping is a distinct create.
   */
  private static String canonicalStepRunnerKinds(Map<String, String> stepRunnerKinds) {
    if (stepRunnerKinds == null || stepRunnerKinds.isEmpty()) {
      return "";
    }
    return stepRunnerKinds.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> nullSafe(entry.getKey()) + "=" + nullSafe(entry.getValue()))
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static String credentialFingerprint(
      String projectPublicId, ConnectorRole role, String actorIdentity) {
    // The plaintext secret is DELIBERATELY excluded (R5/R6) — only project + role + actor + a
    // constant marker participate, so a same-key replay is idempotent without the secret entering
    // the idempotency table or any fingerprint log.
    String canonical =
        String.join(
            "|",
            COMMAND_SET_CREDENTIAL,
            nullSafe(actorIdentity),
            nullSafe(projectPublicId),
            role.value());
    return sha256Hex(canonical);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  private static String sha256Hex(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 digest is unavailable", error);
    }
  }

  private static DomainException masterKeyUnconfigured(String projectId, ConnectorRole role) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("projectId", projectId);
    details.put("role", role.value());
    return new DomainException(
        DomainErrorCode.CREDENTIAL_MASTER_KEY_UNCONFIGURED,
        "Credential master key is not configured",
        details);
  }

  /**
   * Story 2.13 P10 mirror — collapse a null/blank Idempotency-Key back to MISSING_IDEMPOTENCY_KEY.
   */
  private static void requireNonBlankIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("header", "Idempotency-Key");
      throw new DomainException(
          DomainErrorCode.MISSING_IDEMPOTENCY_KEY,
          "Missing required header: Idempotency-Key",
          details);
    }
  }

  /** Story 2.13 P8 mirror — reject duplicated / comma-folded Idempotency-Key headers. */
  private static void rejectMultiValuedIdempotencyKeyHeader(HttpServletRequest httpRequest) {
    if (httpRequest == null) {
      return;
    }
    Enumeration<String> headers = httpRequest.getHeaders("Idempotency-Key");
    if (headers == null) {
      return;
    }
    List<String> values = Collections.list(headers);
    if (values.size() > 1) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("header", "Idempotency-Key");
      details.put("valueCount", values.size());
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Multiple Idempotency-Key headers supplied; exactly one is allowed",
          details);
    }
    if (!values.isEmpty() && values.get(0) != null && values.get(0).indexOf(',') >= 0) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("header", "Idempotency-Key");
      details.put("reason", "comma_folded_multi_value");
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Comma-folded multi-value Idempotency-Key header detected; exactly one value is allowed",
          details);
    }
  }
}
