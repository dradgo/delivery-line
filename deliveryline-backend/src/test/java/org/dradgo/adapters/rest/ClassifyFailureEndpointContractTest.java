package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.ClassifyFailureResult;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.14 AC9 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/classify-failure}. The FIFTH+LAST recovery sibling,
 * wired to the RICH {@link RecoveryService#classifyFailure} (Reconciliation 2) and mapping the
 * {@link ClassifyFailureResponse} (Reconciliation 6 — the ONLY sibling whose response carries NO
 * workflow-state field). Covers: happy-path first classify (200 + echoed {@code taxonomyValue} +
 * {@code priorTaxonomyValue} omitted + {@code recoveryActionId ^rcv_} + {@code replayed=false},
 * capturing the FIVE positional args with {@code taxonomyValue} SECOND before {@code
 * idempotencyKey}); re-classification (non-null {@code priorTaxonomyValue} differing from the new
 * value); idempotent replay ({@code replayed=true}); omitted {@code reasonText} → 200 (proving
 * {@code reasonText} is GENUINELY OPTIONAL — the divergence from reconcile/rerun/pause, captured as
 * {@code null}); {@code MISSING_TAXONOMY_VALUE} → 400 (blank {@code taxonomyValue} reaching the
 * service — proving it is NOT {@code @NotBlank}, the defining trap); {@code INVALID_TAXONOMY_VALUE}
 * → 400 ({@code details.provided}); {@code DEPRECATED_TAXONOMY_VALUE} → 400 ({@code
 * details.replacementValue}); {@code CLASSIFY_NOT_APPLICABLE} → 409 ({@code details.currentState});
 * {@code RUN_NOT_FOUND} → 404; {@code IDEMPOTENCY_KEY_CONFLICT} → 409; missing/blank {@code
 * Idempotency-Key} → 400; multi-valued header → 400; {@code role != workflow_owner} → 400 {@code
 * INVALID_REVIEWER_ROLE_FOR_ENDPOINT}; blank {@code role} → 400 {@code INVALID_COMMAND_PAYLOAD}
 * (bean-validation fires first); unknown body field → 400; omitted {@code X-Actor-Identity} →
 * captured {@code local-operator}; multi-valued/unsafe {@code X-Actor-Identity} → 400.
 *
 * <p>Uses {@code @WebMvcTest} + {@code @MockitoBean RecoveryService} so the test runs without
 * Testcontainers. A {@link ListAppender} pins the INFO entry/success lines (free-form {@code
 * reasonText} never logged verbatim; {@code taxonomyValue}/{@code priorTaxonomyValue} values ARE
 * logged — bounded governed registry values) and the WARN role-rejection line. The
 * {@code @BeforeAll}/{@code @AfterAll} identity-holder guard keeps the shared {@code
 * RedactionLayoutHolder} wired so a reused Surefire fork does not mask CapturedOutput-based sibling
 * tests (webmvctest-redaction-holder trap).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ClassifyFailureEndpointContractTest {

  private static final String RUN_ID = "run_classify_endpoint_a";
  private static final String RECOVERY_ID = "rcv_classify_endpoint_a";
  private static final String CLASSIFIED_EVENT_ID = "evt_classify_endpoint_a";
  private static final String IDEMPOTENCY_KEY = "idem-classify-endpoint-aaaaaa";
  private static final String TAXONOMY = "agent_execution_failure";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  @MockitoBean private RecoveryService recoveryService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;
  @MockitoBean private org.dradgo.application.workflow.RunDependencyService runDependencyService;
  @MockitoBean private org.dradgo.application.workflow.SplitProposalService splitProposalService;
  @MockitoBean private org.dradgo.application.workflow.SplitCommitService splitCommitService;

  private static RedactionPolicyService priorRedactionService;

  private ListAppender<ILoggingEvent> appender;
  private Logger controllerLogger;

  @BeforeAll
  static void wireRedactionHolder() {
    priorRedactionService = RedactionLayoutHolder.currentForTesting();
    RedactionLayoutHolder.setRedactionService(
        new RedactionPolicyService(new DataClassificationService()));
  }

  @AfterAll
  static void unwireRedactionHolder() {
    if (priorRedactionService == null) {
      RedactionLayoutHolder.clearForTesting();
    } else {
      RedactionLayoutHolder.setRedactionService(priorRedactionService);
    }
  }

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

    appender = new ListAppender<>();
    appender.start();
    controllerLogger = (Logger) LoggerFactory.getLogger(WorkflowController.class);
    controllerLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    controllerLogger.detachAppender(appender);
    appender.stop();
    MDC.remove(MdcKeys.CORRELATION_ID);
  }

  private static ClassifyFailureResult freshResult(String priorTaxonomyValue, boolean replayed) {
    return new ClassifyFailureResult(
        RECOVERY_ID, CLASSIFIED_EVENT_ID, TAXONOMY, priorTaxonomyValue, null, replayed);
  }

  @Test
  void happyPathFirstClassifyReturns200AndCapturesFivePositionalArgs() throws Exception {
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(freshResult(null, false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "taxonomyValue": "agent_execution_failure",
                      "reasonText": "The runner produced a partial diff and gave up."
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.taxonomyValue").value(TAXONOMY))
        // priorTaxonomyValue is null on a first classify → NOT_REQUIRED field omitted from the
        // wire.
        .andExpect(jsonPath("$.priorTaxonomyValue").doesNotExist())
        .andExpect(jsonPath("$.recoveryActionId").value(RECOVERY_ID))
        .andExpect(jsonPath("$.recoveryActionId").value(org.hamcrest.Matchers.startsWith("rcv_")))
        .andExpect(jsonPath("$.classifiedEventId").value(CLASSIFIED_EVENT_ID))
        .andExpect(jsonPath("$.replayed").value(false))
        // Reconciliation 6 — classify is the ODD sibling: NO workflow-state field on the response.
        .andExpect(jsonPath("$.currentState").doesNotExist());

    // Reconciliation 7 — FIVE positional args, taxonomyValue SECOND (before idempotencyKey),
    // reasonText LAST.
    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .classifyFailure(
            eq(RUN_ID),
            eq(TAXONOMY),
            eq(IDEMPOTENCY_KEY),
            actorCaptor.capture(),
            eq("The runner produced a partial diff and gave up."));
    ActorContext actor = actorCaptor.getValue();
    assertThat(actor.actorIdentity()).isEqualTo("alex");
    assertThat(actor.actorType()).isEqualTo(ActorType.HUMAN);

    // Logging-instrumentation task: INFO received + success lines carry run/actor + taxonomy
    // telemetry; the free-form reasonText never appears verbatim (length only).
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST classifyFailure received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex")
                    && line.contains("taxonomyValue=" + TAXONOMY))
        .anyMatch(
            line ->
                line.contains("REST classifyFailure success")
                    && line.contains("taxonomyValue=" + TAXONOMY)
                    && line.contains("recoveryActionId=" + RECOVERY_ID)
                    && line.contains("replayed=false"));
    assertThat(infoLines()).noneMatch(line -> line.contains("partial diff and gave up"));
  }

  @Test
  void reClassificationReturnsPriorTaxonomyValueDifferingFromNewValue() throws Exception {
    // epic AC8 — re-classification overwrites the column and echoes the value it replaced.
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(freshResult("specification_gap", false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taxonomyValue").value(TAXONOMY))
        .andExpect(jsonPath("$.priorTaxonomyValue").value("specification_gap"))
        .andExpect(jsonPath("$.replayed").value(false));

    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST classifyFailure success")
                    && line.contains("priorTaxonomyValue=specification_gap"));
  }

  @Test
  void idempotentReplayReturnsReplayedTrue() throws Exception {
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(freshResult(null, true));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(true))
        .andExpect(jsonPath("$.taxonomyValue").value(TAXONOMY));
  }

  @Test
  void omittedReasonTextReturns200ProvingReasonIsGenuinelyOptional() throws Exception {
    // ⚠️ The DEFINING divergence from reconcile/rerun/pause (the RESUME posture): reasonText
    // carries
    // NO @NotBlank AND is genuinely optional (Reconciliation 5). A body omitting reasonText passes
    // bean-validation, reaches the service, and returns 200 — the service stores a null reason (NO
    // MISSING_REASON_TEXT on this path). Captured as null (the 5th positional arg).
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(freshResult(null, false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "taxonomyValue": "agent_execution_failure"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taxonomyValue").value(TAXONOMY));

    verify(recoveryService)
        .classifyFailure(eq(RUN_ID), eq(TAXONOMY), eq(IDEMPOTENCY_KEY), any(), eq((String) null));
  }

  @Test
  void blankTaxonomyValueSurfacesTypedMissingTaxonomyValueProvingNoNotBlank() throws Exception {
    // ⚠️ The defining trap (Reconciliation 4, the reconcile resolutionDecision treatment):
    // taxonomyValue carries NO @NotBlank — a blank value passes bean-validation and reaches the
    // service, which surfaces the DISTINCT typed MISSING_TAXONOMY_VALUE (400). An @NotBlank here
    // would preempt it as INVALID_COMMAND_PAYLOAD, masking the typed code.
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MISSING_TAXONOMY_VALUE, "Missing taxonomy value", Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "taxonomyValue": "   "
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_TAXONOMY_VALUE"))
        .andExpect(jsonPath("$.retryable").value(false));

    // Proves the blank taxonomy passed the boundary and reached the service (the 2nd positional
    // arg).
    verify(recoveryService)
        .classifyFailure(eq(RUN_ID), eq("   "), eq(IDEMPOTENCY_KEY), any(), any());
  }

  @Test
  void invalidTaxonomyValueSurfaces400WithProvidedDetail() throws Exception {
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_TAXONOMY_VALUE,
                "Invalid taxonomy value",
                Map.of("provided", "not_a_real_value")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "taxonomyValue": "not_a_real_value"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TAXONOMY_VALUE"))
        .andExpect(jsonPath("$.details.provided").value("not_a_real_value"));
  }

  @Test
  void deprecatedTaxonomyValueSurfaces400WithReplacementHint() throws Exception {
    // epic AC4 — a value marked deprecated is rejected on the write path with the remediation hint.
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.DEPRECATED_TAXONOMY_VALUE,
                "Deprecated taxonomy value",
                Map.of("provided", "legacy_value", "replacementValue", "agent_execution_failure")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "taxonomyValue": "legacy_value"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DEPRECATED_TAXONOMY_VALUE"))
        .andExpect(jsonPath("$.details.replacementValue").value("agent_execution_failure"));
  }

  @Test
  void wrongStateSurfacesClassifyNotApplicable409WithCurrentStateDetail() throws Exception {
    // Reconciliation 6/8 — the FAILED-only gate throws the dedicated CLASSIFY_NOT_APPLICABLE (409)
    // for any non-Failed source. ACTION_NOT_ALLOWED does NOT exist. details.currentState is
    // present.
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.CLASSIFY_NOT_APPLICABLE,
                "Classify is not applicable from state Executing",
                Map.of("runId", RUN_ID, "currentState", "Executing")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLASSIFY_NOT_APPLICABLE"))
        .andExpect(jsonPath("$.details.currentState").value("Executing"));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void missingIdempotencyKeyHeaderMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void blankIdempotencyKeyHeaderMapsToMissingIdempotencyKey() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "   ")
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void multiValuedIdempotencyKeyHeaderRejectedAsInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("Idempotency-Key", "idem-classify-endpoint-bbbbbb")
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("Idempotency-Key"));
  }

  @Test
  void blankRoleRejectedAsInvalidCommandPayloadByBeanValidation() throws Exception {
    // role carries @NotBlank (mirrors resume/reconcile/rerun/pause), so a blank value is caught by
    // bean validation as INVALID_COMMAND_PAYLOAD BEFORE requireWorkflowOwnerRole runs — no WARN.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "   ",
                      "taxonomyValue": "agent_execution_failure"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details[*].field", org.hamcrest.Matchers.hasItem("role")));
  }

  @Test
  void nonWorkflowOwnerRoleRejectedAsInvalidReviewerRoleForEndpoint() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "developer",
                      "taxonomyValue": "agent_execution_failure"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"))
        .andExpect(jsonPath("$.details.field").value("role"))
        .andExpect(jsonPath("$.details.expected").value("workflow_owner"))
        .andExpect(jsonPath("$.details.actual").value("developer"));

    assertThat(warnLines()).anyMatch(line -> line.contains("role must be 'workflow_owner'"));
  }

  @Test
  void unknownBodyFieldRejectedAsInvalidCommandPayload() throws Exception {
    // @JsonIgnoreProperties(ignoreUnknown = false) — a mis-shaped body fails fast via the Jackson
    // deserialization advice as INVALID_COMMAND_PAYLOAD; the rich service is never reached.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "taxonomyValue": "agent_execution_failure",
                      "bogus": "unexpected"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(freshResult(null, false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk());

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .classifyFailure(
            eq(RUN_ID), eq(TAXONOMY), eq(IDEMPOTENCY_KEY), actorCaptor.capture(), any());
    assertThat(actorCaptor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void multiValuedActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .header("X-Actor-Identity", "mallory")
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"));

    org.mockito.Mockito.verifyNoInteractions(recoveryService);
  }

  @Test
  void unsafeActorIdentityHeaderValueRejectedAsInvalidCommandPayload() throws Exception {
    String oversizeActor = "a".repeat(129);
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", oversizeActor)
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"));

    org.mockito.Mockito.verifyNoInteractions(recoveryService);
  }

  private List<String> infoLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private List<String> warnLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private String workflowOwnerBody() {
    return """
        {
          "role": "workflow_owner",
          "taxonomyValue": "agent_execution_failure",
          "reasonText": "Classifying the failure for cross-run analysis."
        }
        """;
  }
}
