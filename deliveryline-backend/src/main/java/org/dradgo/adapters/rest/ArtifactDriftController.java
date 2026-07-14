package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.ArtifactRepairResult;
import org.dradgo.application.artifact.RepairArtifactDriftCommand;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 4.16 (AC5 / Reconciliation 11) — the NON-workflow-scoped controller for operator-driven
 * artifact-drift repair. Mirrors {@code IntegrationConflictController}'s single-service shell +
 * {@code WorkflowController}'s mutating-endpoint preamble idiom (Idempotency-Key + X-Actor-Identity
 * headers, a workflow_owner role gate, sanitized logs). Stays thin per the ArchUnit thin-controller
 * rule: it calls {@link ArtifactReconciliationService#repairArtifactDrift} only — never a {@code
 * *Port} directly — and lets the global {@code ProblemDetailsMapper} advice map every {@code
 * DomainException} to Problem Details.
 */
@RestController
@Validated
@RequestMapping("/api/v1/artifact-drift")
@Tag(
    name = "Artifact Drift",
    description = "Operator-driven repair of detected artifact DB/file drift (story 4.16).")
public class ArtifactDriftController {

  private static final Logger log = LoggerFactory.getLogger(ArtifactDriftController.class);

  private static final String WORKFLOW_OWNER_ROLE = "workflow_owner";

  private final ArtifactReconciliationService artifactReconciliationService;
  private final LocalActorIdentityResolver localActorIdentityResolver;

  public ArtifactDriftController(
      ArtifactReconciliationService artifactReconciliationService,
      LocalActorIdentityResolver localActorIdentityResolver) {
    this.artifactReconciliationService = artifactReconciliationService;
    this.localActorIdentityResolver = localActorIdentityResolver;
  }

  @PostMapping(
      value = "/{driftId}/repair",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "repairArtifactDrift",
      summary = "Apply an operator-driven repair to a detected artifact drift (story 4.16)",
      description =
          "Operator (workflow_owner) recovery action that resolves a detected artifact_drift_detected"
              + " row through an explicit, auditable repair (NFR19: no silent overwrite). Records a"
              + " recovery_actions row + artifact.driftRepaired event and, for the resolving"
              + " repairs, sets the drift's resolved_at/resolved_by_action_id. Idempotent under"
              + " Idempotency-Key. A re_verify_checksum that still mismatches leaves the drift open"
              + " (resolved=false).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Repair applied (or idempotent replay)."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD,"
                + " INVALID_REVIEWER_ROLE_FOR_ENDPOINT, INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY,"
                + " MISSING_REPAIR_REQUIRED_FIELD.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "DRIFT_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "DRIFT_ALREADY_RESOLVED or IDEMPOTENCY_KEY_CONFLICT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ArtifactRepairResponse repairArtifactDrift(
      @PathVariable String driftId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody ArtifactRepairRequest request) {
    rejectMultiValuedHeader(httpRequest, "Idempotency-Key");
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedHeader(httpRequest, "X-Actor-Identity");
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    requireWorkflowOwnerRole("artifact-repair", request.role());
    log.info(
        "REST repairArtifactDrift received driftId={} actorIdentity={} repairAction={} reasonLength={}",
        MdcKeys.sanitizeForLog(driftId),
        MdcKeys.sanitizeForLog(actorIdentity),
        MdcKeys.sanitizeForLog(request.repairAction()),
        request.reasonText() == null ? 0 : request.reasonText().length());
    ActorContext actor = new ActorContext(actorIdentity, ActorType.HUMAN, correlationId);
    ArtifactRepairResult result =
        artifactReconciliationService.repairArtifactDrift(
            new RepairArtifactDriftCommand(
                driftId,
                request.repairAction(),
                request.reasonText(),
                request.completionEvidence(),
                request.backupSource(),
                actor,
                idempotencyKey));
    ArtifactRepairResponse response = ArtifactRepairResponse.from(result);
    log.info(
        "REST repairArtifactDrift success driftId={} repairAction={} recoveryActionId={} resolved={}"
            + " replayed={}",
        MdcKeys.sanitizeForLog(driftId),
        response.repairAction(),
        response.recoveryActionId(),
        response.resolved(),
        response.replayed());
    return response;
  }

  // ------------------------------------------------------------------------------------------------
  // Mutating-endpoint preamble helpers. Duplicated (minimal set) from WorkflowController rather
  // than
  // extracted (OQ-5) to keep the blast radius to this new controller only; identical semantics.
  // ------------------------------------------------------------------------------------------------

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

  private static void requireWorkflowOwnerRole(String action, String role) {
    String trimmed = role == null ? null : role.trim();
    if (!WORKFLOW_OWNER_ROLE.equals(trimmed)) {
      log.warn(
          "REST {} rejected: role must be 'workflow_owner' actualRole={}",
          action,
          MdcKeys.sanitizeForLog(role));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("field", "role");
      details.put("expected", WORKFLOW_OWNER_ROLE);
      details.put("actual", role);
      throw new DomainException(
          DomainErrorCode.INVALID_REVIEWER_ROLE_FOR_ENDPOINT,
          "Role must be 'workflow_owner' for this endpoint",
          details);
    }
  }

  private static void rejectMultiValuedHeader(HttpServletRequest httpRequest, String headerName) {
    if (httpRequest == null) {
      return;
    }
    Enumeration<String> headers = httpRequest.getHeaders(headerName);
    if (headers == null) {
      return;
    }
    List<String> values = Collections.list(headers);
    if (values.size() > 1) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("header", headerName);
      details.put("valueCount", values.size());
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Multiple " + headerName + " headers supplied; exactly one is allowed",
          details);
    }
    if (!values.isEmpty() && values.get(0) != null && values.get(0).indexOf(',') >= 0) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("header", headerName);
      details.put("reason", "comma_folded_multi_value");
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Comma-folded multi-value "
              + headerName
              + " header detected; exactly one value is allowed",
          details);
    }
  }
}
