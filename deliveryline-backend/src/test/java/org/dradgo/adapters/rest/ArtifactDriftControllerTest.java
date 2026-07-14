package org.dradgo.adapters.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.ArtifactRepairResult;
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
 * Story 4.16 (AC5/AC6/AC10) — {@code @WebMvcTest} conformance for {@code ArtifactDriftController}:
 * the 200 happy path and the typed Problem Details status mappings for the repair error codes.
 */
@WebMvcTest(controllers = ArtifactDriftController.class)
class ArtifactDriftControllerTest {

  private static final String PATH = "/api/v1/artifact-drift/adr_repair0001/repair";
  private static final String KEY = "idem-repair-conformance-01";

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
        "{\"role\":\"%s\",\"repairAction\":\"%s\",\"reasonText\":\"note\"}", role, action);
  }

  @Test
  void repairReturns200OnSuccess() throws Exception {
    when(artifactReconciliationService.repairArtifactDrift(any()))
        .thenReturn(
            new ArtifactRepairResult(
                "adr_repair0001", "mark_corrupted", "rcv_1", "evt_1", true, "corr-1", false));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "mark_corrupted")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.driftId").value("adr_repair0001"))
        .andExpect(jsonPath("$.repairAction").value("mark_corrupted"))
        .andExpect(jsonPath("$.resolved").value(true));
  }

  @Test
  void wrongRoleReturns400WithoutCallingService() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("developer", "mark_corrupted")))
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
                .content(body("workflow_owner", "mark_corrupted")))
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
                    "{\"role\":\"workflow_owner\",\"repairAction\":\"mark_corrupted\",\"bogus\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void driftNotFoundReturns404() throws Exception {
    when(artifactReconciliationService.repairArtifactDrift(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.DRIFT_NOT_FOUND, "no drift", Map.of("driftId", "adr_repair0001")));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "mark_corrupted")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DRIFT_NOT_FOUND"));
  }

  @Test
  void driftAlreadyResolvedReturns409() throws Exception {
    when(artifactReconciliationService.repairArtifactDrift(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.DRIFT_ALREADY_RESOLVED,
                "already resolved",
                Map.of("driftId", "adr_repair0001")));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "mark_corrupted")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DRIFT_ALREADY_RESOLVED"));
  }

  @Test
  void invalidRepairActionReturns400() throws Exception {
    when(artifactReconciliationService.repairArtifactDrift(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY,
                "not legal",
                Map.of("driftCategory", "checksum_mismatch")));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "mark_operation_failed")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY"));
  }

  @Test
  void idempotencyConflictReturns409() throws Exception {
    when(artifactReconciliationService.repairArtifactDrift(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, "conflict", Map.of("key", KEY)));

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", KEY)
                .content(body("workflow_owner", "mark_corrupted")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }
}
