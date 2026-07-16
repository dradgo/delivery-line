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
import org.dradgo.application.artifact.LineageReconciliationResult;
import org.dradgo.application.artifact.ReconcileLineageCommand;
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
 * Story 4.16a (AC9 / Reconciliation 9) — the NON-run-scoped controller for operator-driven artifact
 * LINEAGE recovery (Model A: the operator acts directly on an ambiguous artifact id — there is no
 * persisted conflict to look up). A SECOND controller under the top-level {@code /api/v1/artifacts}
 * prefix established by {@code ArtifactCompareController} (distinct full path — {@code
 * /{artifactId}/reconcile-lineage}). Mirrors {@code ArtifactDriftController}'s mutating-endpoint
 * preamble idiom (Idempotency-Key + X-Actor-Identity headers, a workflow_owner role gate, sanitized
 * logs). Stays thin per the ArchUnit thin-controller rule: it calls {@link
 * ArtifactReconciliationService#reconcileLineage} only — never a {@code *Port} directly — and lets
 * the global {@code ProblemDetailsMapper} advice map every {@code DomainException} to Problem
 * Details.
 */
@RestController
@Validated
@RequestMapping("/api/v1/artifacts")
@Tag(
    name = "Artifact Lineage",
    description = "Operator-driven lineage recovery for ambiguous artifact history (story 4.16a).")
public class ArtifactLineageController {

  private static final Logger log = LoggerFactory.getLogger(ArtifactLineageController.class);

  private static final String WORKFLOW_OWNER_ROLE = "workflow_owner";

  private final ArtifactReconciliationService artifactReconciliationService;
  private final LocalActorIdentityResolver localActorIdentityResolver;

  public ArtifactLineageController(
      ArtifactReconciliationService artifactReconciliationService,
      LocalActorIdentityResolver localActorIdentityResolver) {
    this.artifactReconciliationService = artifactReconciliationService;
    this.localActorIdentityResolver = localActorIdentityResolver;
  }

  @PostMapping(
      value = "/{artifactId}/reconcile-lineage",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "reconcileArtifactLineage",
      summary = "Apply an operator-driven lineage-recovery action to an ambiguous artifact (4.16a)",
      description =
          "Operator (workflow_owner) recovery action that resolves ambiguous artifact lineage through"
              + " an explicit, auditable decision (NFR19: no silent overwrite). One of"
              + " reattach_to_existing_lineage (re-parents an orphan onto a chosen leaf),"
              + " terminate_ambiguous_lineage (flips the lineage terminal so replay cannot revive"
              + " it), or create_explicit_fork (starts a fresh lineage_recovery branch). Records a"
              + " recovery_actions row + artifact.lineageReconciled event. Idempotent under"
              + " Idempotency-Key. The transient ARTIFACT_OPERATION_INTENT_CONFLICT /"
              + " ARTIFACT_LINEAGE_ALREADY_EXISTS (409) keeps firing on re-ingest until the operator"
              + " picks one of these actions.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Reconcile applied (or idempotent replay)."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD,"
                + " INVALID_REVIEWER_ROLE_FOR_ENDPOINT, INVALID_LINEAGE_RECOVERY_ACTION,"
                + " MISSING_LINEAGE_RECOVERY_FIELD, ARTIFACT_LINEAGE_MISMATCH.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "ARTIFACT_RECORD_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description =
            "ARTIFACT_INVALID_STATE_TRANSITION, IDEMPOTENCY_KEY_CONFLICT, or WORKFLOW_RUN_TERMINAL.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ArtifactLineageReconcileResponse reconcileArtifactLineage(
      @PathVariable String artifactId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody ArtifactLineageReconcileRequest request) {
    rejectMultiValuedHeader(httpRequest, "Idempotency-Key");
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedHeader(httpRequest, "X-Actor-Identity");
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    requireWorkflowOwnerRole("reconcile-lineage", request.role());
    log.info(
        "REST reconcileArtifactLineage received artifactId={} actorIdentity={} lineageAction={}"
            + " reasonLength={}",
        MdcKeys.sanitizeForLog(artifactId),
        MdcKeys.sanitizeForLog(actorIdentity),
        MdcKeys.sanitizeForLog(request.lineageAction()),
        request.reasonText() == null ? 0 : request.reasonText().length());
    ActorContext actor = new ActorContext(actorIdentity, ActorType.HUMAN, correlationId);
    LineageReconciliationResult result =
        artifactReconciliationService.reconcileLineage(
            new ReconcileLineageCommand(
                artifactId,
                request.lineageAction(),
                request.chosenParentArtifactId(),
                request.reasonText(),
                actor,
                idempotencyKey));
    ArtifactLineageReconcileResponse response = ArtifactLineageReconcileResponse.from(result);
    log.info(
        "REST reconcileArtifactLineage success artifactId={} lineageAction={} recoveryActionId={}"
            + " lineageReferenceArtifactId={} replayed={}",
        MdcKeys.sanitizeForLog(artifactId),
        response.lineageAction(),
        response.recoveryActionId(),
        response.lineageReferenceArtifactId(),
        response.replayed());
    return response;
  }

  // ------------------------------------------------------------------------------------------------
  // Mutating-endpoint preamble helpers. Duplicated (minimal set) from ArtifactDriftController /
  // WorkflowController rather than extracted (OQ-6) to keep the blast radius to this new controller
  // only; identical semantics.
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
