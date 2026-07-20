package org.dradgo.adapters.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.LineageReconciliationResult;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.16a (AC9/AC10) — {@code @WebMvcTest} conformance for {@code ArtifactLineageController}:
 * the 200 happy path and the typed Problem Details status mappings for the lineage-recovery error
 * codes.
 */
@WebMvcTest(controllers = ArtifactLineageController.class)
class ArtifactLineageControllerTest {

  private static final String PATH = "/api/v1/artifacts/art_lineage0001/reconcile-lineage";
  private static final String KEY = "idem-lineage-conformance-01";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ArtifactReconciliationService artifactReconciliationService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;

  @BeforeEach
  void stubActorResolver() {
    LocalActorIdentityResolver real = new LocalActorIdentityResolver("local-operator");
    when(localActorIdentityResolver.resolve(any()))
        .thenAnswer(invocation -> real.resolve(invocation.getArgument(0)));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              real.requireSafe(invocation.getArgument(0));
              return null;
            })
        .when(localActorIdentityResolver)
        .requireSafe(any());
  }

  private static String body(String role, String action) {
    return String.format(
        "{\"role\":\"%s\",\"lineageAction\":\"%s\",\"reasonText\":\"note\"}", role, action);
  }

  @Test
  void reconcileReturns200OnSuccess() throws Exception {
    when(artifactReconciliationService.reconcileLineage(any()))
        .thenReturn(
            new LineageReconciliationResult(
                "art_lineage0001",
                "create_explicit_fork",
                "rcv_1",
                "evt_1",
                "art_forkhead001",
                "corr-1",
                false));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "create_explicit_fork")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetArtifactId").value("art_lineage0001"))
        .andExpect(jsonPath("$.lineageAction").value("create_explicit_fork"))
        .andExpect(jsonPath("$.lineageReferenceArtifactId").value("art_forkhead001"));
  }

  @Test
  void wrongRoleReturns400WithoutCallingService() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("developer", "terminate_ambiguous_lineage")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"));
  }

  @Test
  void blankIdempotencyKeyReturns400() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "   ")
                .content(body("workflow_owner", "terminate_ambiguous_lineage")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void unknownJsonFieldReturns400() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(
                    "{\"role\":\"workflow_owner\",\"lineageAction\":\"terminate_ambiguous_lineage\",\"bogus\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void artifactNotFoundReturns404() throws Exception {
    when(artifactReconciliationService.reconcileLineage(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                "no artifact",
                Map.of("artifactId", "art_lineage0001")));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "terminate_ambiguous_lineage")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ARTIFACT_RECORD_NOT_FOUND"));
  }

  @Test
  void invalidLineageActionReturns400() throws Exception {
    when(artifactReconciliationService.reconcileLineage(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_LINEAGE_RECOVERY_ACTION,
                "unknown action",
                Map.of("provided", "nope")));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "nope")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_LINEAGE_RECOVERY_ACTION"));
  }

  @Test
  void missingLineageFieldReturns400() throws Exception {
    when(artifactReconciliationService.reconcileLineage(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MISSING_LINEAGE_RECOVERY_FIELD,
                "needs parent",
                Map.of("field", "chosenParentArtifactId")));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "reattach_to_existing_lineage")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_LINEAGE_RECOVERY_FIELD"));
  }

  @Test
  void invalidStateTransitionReturns409() throws Exception {
    when(artifactReconciliationService.reconcileLineage(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
                "cycle",
                Map.of("reason", "lineage_cycle")));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "reattach_to_existing_lineage")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ARTIFACT_INVALID_STATE_TRANSITION"));
  }

  @Test
  void idempotencyConflictReturns409() throws Exception {
    when(artifactReconciliationService.reconcileLineage(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, "conflict", Map.of("key", KEY)));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "terminate_ambiguous_lineage")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }
}
