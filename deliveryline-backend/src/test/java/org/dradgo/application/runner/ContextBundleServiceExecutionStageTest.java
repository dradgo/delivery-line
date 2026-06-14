package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.runner.workspace.RepoManifestRef;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.spi.RepoTreeEntry;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3.10 — execution-stage (sub-stage-aware + repo-aware) {@link ContextBundleService#create}
 * composition. Covers AC1 (plan sub-stage), AC2 (pr-output sub-stage + repo context + branch),
 * AC4/AC9(d) (redaction), AC9(e) (schema rejection), AC9(g) (no-repo byte-minimal), and the
 * subStage==null legacy byte-identical baseline (Decision D1).
 */
class ContextBundleServiceExecutionStageTest {

  private static final String RUN_ID = "run_exec12345678";
  private static final String REX_ID = "rex_exec12345678";
  private static final String ART_SPEC_ID = "art_spec00000001";
  private static final String ART_PLAN_ID = "art_plan00000001";
  private static final String BRANCH = "deliveryline/DL-310/stage-exec1234";
  private static final ExecutionConstraints CONSTRAINTS =
      new ExecutionConstraints(java.time.Duration.ofSeconds(1800), false);
  private static final ActorContext ACTOR =
      new ActorContext("system-broker", ActorType.SYSTEM, "corr-exec-1");

  private final ObjectMapper objectMapper = new ObjectMapper();

  // ---------------------------------------------------------------------------------------------
  // AC1 — implementation-plan sub-stage
  // ---------------------------------------------------------------------------------------------

  @Test
  void implementationPlanSubStage_carriesApprovedSpecPmDecisionsAndIncorporatedClarifications()
      throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export pipeline", "Plan it."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(Optional.of(availableArtifact(ART_SPEC_ID, ArtifactType.SPEC, "spec/v1.json")));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "prOutput"))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(
            List.of(approval("apr_specapprove1", true), approval("apr_specreject01", false)));
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID))
        .thenReturn(
            List.of(
                clarification("clr_incorporated1", Clarification.STATUS_INCORPORATED),
                clarification("clr_open0000000001", Clarification.STATUS_OPEN)));
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            clarificationReadPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.IMPLEMENTATION_PLAN,
            null,
            null);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    assertEquals(RunnerStage.EXECUTION, bundle.stage());
    assertEquals(
        ART_SPEC_ID, parsed.get("approvedSpecificationReference").get("artifactId").asText());
    // No approved-plan reference field on the plan sub-stage (AC1).
    assertFalse(parsed.has("approvedImplementationPlanReference"));
    // PM decisions (spec.approval + spec.rejection) + incorporated clarification ride
    // priorFeedbackReferences via distinct kinds; the open clarification is excluded.
    List<String> kinds = parsed.get("priorFeedbackReferences").findValuesAsText("kind");
    assertEquals(List.of("spec.approval", "spec.rejection", "clarification.incorporated"), kinds);
    assertTrue(
        parsed
            .get("priorFeedbackReferences")
            .findValuesAsText("referenceId")
            .contains("clr_incorporated1"));
    // No repo context on a no-workspace dispatch.
    assertFalse(parsed.has("repositoryWorkspaceRef"));
    assertFalse(parsed.has("repositoryBranchRef"));
  }

  // Story 3.21 (OQ-2) — a regenerated implementation-plan (after a prior plan rejection) must see
  // the prior implementationPlan.rejection technical feedback so it can address it ("feedback flows
  // back into the rebuild loop", FR17). Before 3.21 this rejection feedback was threaded only into
  // the PR_OUTPUT sub-stage.
  @Test
  void implementationPlanSubStage_carriesPriorImplementationPlanRejectionFeedback()
      throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export pipeline", "Re-plan it."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(Optional.of(availableArtifact(ART_SPEC_ID, ArtifactType.SPEC, "spec/v1.json")));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "prOutput"))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of(approval("apr_specapprove1", true)));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(List.of(approval("apr_planreject01", false)));
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            clarificationReadPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.IMPLEMENTATION_PLAN,
            null,
            null);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    List<String> kinds = parsed.get("priorFeedbackReferences").findValuesAsText("kind");
    assertEquals(List.of("spec.approval", "implementationPlan.rejection"), kinds);
    assertTrue(
        parsed
            .get("priorFeedbackReferences")
            .findValuesAsText("referenceId")
            .contains("apr_planreject01"));
  }

  // ---------------------------------------------------------------------------------------------
  // AC2 — pr-output sub-stage + repo context + branch
  // ---------------------------------------------------------------------------------------------

  @Test
  void prOutputSubStage_carriesApprovedPlanTechnicalFeedbackRepoContextAndBranch()
      throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export pipeline", "Open the PR."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(Optional.of(availableArtifact(ART_SPEC_ID, ArtifactType.SPEC, "spec/v1.json")));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(
            Optional.of(
                availableArtifact(ART_PLAN_ID, ArtifactType.IMPLEMENTATION_PLAN, "plan/v1.json")));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "prOutput"))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of(approval("apr_specapprove1", true)));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(List.of(approval("apr_planreject01", false)));
    // The approved-plan reference is resolved from the approval lineage (same source
    // deriveExecutionSubStage keys PR_OUTPUT on), then the exact artifact by its lineage id.
    when(approvalReadPort.findLatestApprovedForArtifactLineage(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.of(planApproval("apr_planapprove1")));
    when(artifactRecordPort.findByPublicId(ART_PLAN_ID))
        .thenReturn(
            Optional.of(
                availableArtifact(ART_PLAN_ID, ArtifactType.IMPLEMENTATION_PLAN, "plan/v1.json")));
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            clarificationReadPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.PR_OUTPUT,
            summary(),
            BRANCH);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    assertEquals(
        ART_PLAN_ID, parsed.get("approvedImplementationPlanReference").get("artifactId").asText());
    List<String> kinds = parsed.get("priorFeedbackReferences").findValuesAsText("kind");
    assertTrue(kinds.contains("spec.approval"));
    assertTrue(kinds.contains("implementationPlan.rejection"));
    // Repo context + branch present (workspace supplied).
    assertEquals("/workspace/repo", parsed.get("repositoryWorkspaceRef").asText());
    assertEquals("README.md", parsed.get("repositoryReadmeRef").asText());
    assertEquals("config:GH-101@1", parsed.get("ticketRepositoryMappingVersion").asText());
    assertEquals(BRANCH, parsed.get("repositoryBranchRef").asText());
    assertTrue(parsed.get("repositoryTreeSummary").isArray());
  }

  // ---------------------------------------------------------------------------------------------
  // AC9(g) — no-repo execution bundle stays byte-minimal (repo fields + branch absent)
  // ---------------------------------------------------------------------------------------------

  @Test
  void prOutputSubStage_withoutWorkspace_omitsRepoFieldsAndBranch() throws Exception {
    ContextBundleService service = serviceWithMinimalRun();

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.PR_OUTPUT,
            null,
            null);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    assertFalse(parsed.has("repositoryWorkspaceRef"));
    assertFalse(parsed.has("repositoryTreeSummary"));
    assertFalse(parsed.has("repositoryReadmeRef"));
    assertFalse(parsed.has("packageManifestRefs"));
    assertFalse(parsed.has("repositoryBranchRef"));
    // PR-output still declares the (null) approved-plan slot.
    assertTrue(parsed.get("approvedImplementationPlanReference").isNull());
  }

  // ---------------------------------------------------------------------------------------------
  // AC5 — versioning across re-dispatch: each call stamps the supplied monotonic version
  // ---------------------------------------------------------------------------------------------

  @Test
  void executionBundleStampsTheSuppliedContextBundleVersionAcrossReDispatch() {
    ContextBundleService service = serviceWithMinimalRun();

    ContextBundle v1 =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.IMPLEMENTATION_PLAN,
            null,
            null);
    ContextBundle v2 =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            "rex_exec99999999",
            2,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.IMPLEMENTATION_PLAN,
            null,
            null);

    assertEquals(1, v1.contextBundleVersion());
    assertEquals(2, v2.contextBundleVersion());
  }

  // ---------------------------------------------------------------------------------------------
  // Decision D1 — subStage==null preserves today's exact (legacy) composition
  // ---------------------------------------------------------------------------------------------

  @Test
  void nullSubStage_isByteIdenticalToTheLegacySevenArgOverload() throws Exception {
    ContextBundleService service = serviceWithMinimalRun();

    ContextBundle viaSevenArg =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR);
    ContextBundle viaTenArgNull =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            null,
            null,
            null);

    assertArrayEquals(viaSevenArg.redactedPayload(), viaTenArgNull.redactedPayload());
    JsonNode parsed = objectMapper.readTree(viaTenArgNull.redactedPayload());
    // Legacy shape: no sub-stage-specific fields.
    assertFalse(parsed.has("approvedImplementationPlanReference"));
    assertFalse(parsed.has("repositoryBranchRef"));
  }

  // ---------------------------------------------------------------------------------------------
  // AC9(e) — schema rejection of a malformed implementation-stage bundle (oversized via redaction)
  // ---------------------------------------------------------------------------------------------

  @Test
  void oversizedImplementationStageBundleIsRejectedAtValidatorBoundary() {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export", "Plan."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec")).thenReturn(List.of());
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(
            invocation -> {
              JsonNode input = invocation.getArgument(0);
              com.fasterxml.jackson.databind.node.ObjectNode inflated = input.deepCopy();
              ((com.fasterxml.jackson.databind.node.ObjectNode) inflated.get("ticketSummary"))
                  .put("summary", "x".repeat(3000));
              return redactionPassthrough(inflated);
            });

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            clarificationReadPort,
            redactionPolicyService,
            new RunnerContractValidator());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.create(
                    RUN_ID,
                    RunnerStage.EXECUTION,
                    REX_ID,
                    1,
                    CONSTRAINTS,
                    DataClassification.SHAREABLE_REDACTED,
                    ACTOR,
                    ExecutionSubStage.IMPLEMENTATION_PLAN,
                    null,
                    null));
    assertEquals(DomainErrorCode.RUNNER_CONTRACT_VIOLATION, error.errorCode());
  }

  // ---------------------------------------------------------------------------------------------
  // AC4 / AC9(d) — a token embedded in a tree-summary path is redacted from the persisted bundle
  // ---------------------------------------------------------------------------------------------

  @Test
  void repoTreeSummaryTokenIsRedactedFromPersistedBundle() throws Exception {
    String leakToken = "ghp_1234567890abcdef1234567890abcdef1234";
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export", "Plan."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec")).thenReturn(List.of());
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());

    // REAL redaction policy — proves the single redact pass walks the embedded tree-summary leaves.
    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            clarificationReadPort,
            new RedactionPolicyService(
                new org.dradgo.application.security.DataClassificationService()),
            new RunnerContractValidator());

    RepositoryContextSummary leakySummary =
        new RepositoryContextSummary(
            "/workspace/repo",
            List.of(new RepoTreeEntry(leakToken + ".txt", RepoTreeEntry.Type.FILE)),
            "README.md",
            List.of(),
            "config:GH-101@1");

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.IMPLEMENTATION_PLAN,
            leakySummary,
            BRANCH);

    String serialized = new String(bundle.redactedPayload(), StandardCharsets.UTF_8);
    assertFalse(serialized.contains(leakToken), "embedded tree-path token must be redacted");
  }

  // ---------------------------------------------------------------------------------------------
  // Logging task — the sub-stage + repo-context summary line is emitted with no secret/host path
  // ---------------------------------------------------------------------------------------------

  @Test
  void create_logsSubStageAndRepoContextFieldsWithoutLeak() {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export", "PR."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec")).thenReturn(List.of());
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(List.of());
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            clarificationReadPort,
            redactionPolicyService,
            new RunnerContractValidator());

    Logger logger = (Logger) LoggerFactory.getLogger(ContextBundleService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      service.create(
          RUN_ID,
          RunnerStage.EXECUTION,
          REX_ID,
          1,
          CONSTRAINTS,
          DataClassification.SHAREABLE_REDACTED,
          ACTOR,
          ExecutionSubStage.PR_OUTPUT,
          summary(),
          BRANCH);

      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getLevel() == Level.INFO
                          && e.getFormattedMessage().contains("create context-bundle ok")
                          && e.getFormattedMessage().contains("subStage=PR_OUTPUT")
                          && e.getFormattedMessage().contains("repoContextPresent=true")),
          "expected the sub-stage + repo-context summary INFO line");
      assertTrue(
          appender.list.stream().allMatch(e -> !e.getFormattedMessage().contains("ghp_")),
          "no token may be serialized into a log line");
    } finally {
      logger.detachAppender(appender);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Decision D4 — deriveExecutionSubStage selects PR_OUTPUT iff an approved plan exists
  // ---------------------------------------------------------------------------------------------

  @Test
  void deriveExecutionSubStage_isPrOutputWhenPlanApproved_elseImplementationPlan() {
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ContextBundleService service =
        new ContextBundleService(
            mock(TicketSummaryProvider.class),
            mock(ArtifactRecordPort.class),
            approvalReadPort,
            mock(ClarificationReadPort.class),
            mock(RedactionPolicyService.class),
            new RunnerContractValidator());

    when(approvalReadPort.findLatestApprovedForArtifactLineage(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.of(planApproval("apr_planapprove1")));
    assertEquals(ExecutionSubStage.PR_OUTPUT, service.deriveExecutionSubStage(RUN_ID));

    when(approvalReadPort.findLatestApprovedForArtifactLineage(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    assertEquals(ExecutionSubStage.IMPLEMENTATION_PLAN, service.deriveExecutionSubStage(RUN_ID));
  }

  // ---------------------------------------------------------------------------------------------
  // OQ-3 — repo context is workspace-gated, NOT sub-stage-gated: the plan sub-stage with a prepared
  // workspace still carries repo context + the deterministic branch (but never the plan reference).
  // ---------------------------------------------------------------------------------------------

  @Test
  void implementationPlanSubStage_withWorkspace_carriesRepoContextAndBranchButNoPlanRef()
      throws Exception {
    ContextBundleService service = serviceWithMinimalRun();

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.IMPLEMENTATION_PLAN,
            summary(),
            BRANCH);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    assertEquals("/workspace/repo", parsed.get("repositoryWorkspaceRef").asText());
    assertEquals(BRANCH, parsed.get("repositoryBranchRef").asText());
    // The approved-plan reference stays pr-output-only even with a workspace present (AC1).
    assertFalse(parsed.has("approvedImplementationPlanReference"));
  }

  // ---------------------------------------------------------------------------------------------
  // Decision D3 — priorFeedbackReferences ordering is deterministic across a mixed feedback set:
  // spec decisions (in list order), then implementation-plan rejections, then incorporated
  // clarifications (each in list order).
  // ---------------------------------------------------------------------------------------------

  @Test
  void prOutputSubStage_mixedFeedback_isOrderedDeterministically() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export", "PR."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(
            List.of(approval("apr_specapprove1", true), approval("apr_specreject01", false)));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(
            List.of(approval("apr_planreject01", false), approval("apr_planreject02", false)));
    when(approvalReadPort.findLatestApprovedForArtifactLineage(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID))
        .thenReturn(
            List.of(
                clarification("clr_incorporated1", Clarification.STATUS_INCORPORATED),
                clarification("clr_incorporated2", Clarification.STATUS_INCORPORATED)));
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            clarificationReadPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.PR_OUTPUT,
            null,
            null);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    List<String> kinds = parsed.get("priorFeedbackReferences").findValuesAsText("kind");
    assertEquals(
        List.of(
            "spec.approval",
            "spec.rejection",
            "implementationPlan.rejection",
            "implementationPlan.rejection",
            "clarification.incorporated",
            "clarification.incorporated"),
        kinds);
  }

  // ---------------------------------------------------------------------------------------------
  // Review fix (D1) — PR_OUTPUT sources the approved-plan reference from the approval lineage (the
  // same source deriveExecutionSubStage keys on), so an approved-but-not-yet-`available` plan is
  // STILL referenced (marked referenceAvailable=false) rather than silently dropped to null.
  // ---------------------------------------------------------------------------------------------

  @Test
  void prOutputSubStage_approvedPlanNotYetAvailable_stillEmitsReferenceMarkedUnavailable()
      throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export", "PR."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec")).thenReturn(List.of());
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(List.of());
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    // Plan approved, but its artifact has not yet reached `available` (it can stay `pending`).
    when(approvalReadPort.findLatestApprovedForArtifactLineage(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.of(planApproval("apr_planapprove1")));
    when(artifactRecordPort.findByPublicId(ART_PLAN_ID))
        .thenReturn(Optional.of(pendingArtifact(ART_PLAN_ID, ArtifactType.IMPLEMENTATION_PLAN)));
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            clarificationReadPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR,
            ExecutionSubStage.PR_OUTPUT,
            null,
            null);

    JsonNode planRef =
        objectMapper.readTree(bundle.redactedPayload()).get("approvedImplementationPlanReference");
    assertFalse(
        planRef.isNull(), "an approved plan must be referenced even before it is available");
    assertEquals(ART_PLAN_ID, planRef.get("artifactId").asText());
    assertFalse(planRef.get("referenceAvailable").asBoolean());
    assertEquals("artifact_status_pending", planRef.get("unavailableReason").asText());
  }

  // =============================================================================================
  // helpers
  // =============================================================================================

  private ContextBundleService serviceWithMinimalRun() {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export", "Minimal."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec")).thenReturn(List.of());
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(List.of());
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    return new ContextBundleService(
        ticketProvider,
        artifactRecordPort,
        approvalReadPort,
        clarificationReadPort,
        redactionPolicyService,
        new RunnerContractValidator());
  }

  private static RepositoryContextSummary summary() {
    return new RepositoryContextSummary(
        "/workspace/repo",
        List.of(
            new RepoTreeEntry("README.md", RepoTreeEntry.Type.FILE),
            new RepoTreeEntry("src", RepoTreeEntry.Type.DIR)),
        "README.md",
        List.of(new RepoManifestRef("pom.xml", "pom.xml")),
        "config:GH-101@1");
  }

  private static ApprovalSnapshot approval(String publicId, boolean approved) {
    return new ApprovalSnapshot(
        publicId,
        RUN_ID,
        ART_SPEC_ID,
        1,
        1,
        "alex@example.com",
        ActorType.HUMAN,
        "product_owner",
        approved ? ApprovalSnapshot.DECISION_APPROVED : ApprovalSnapshot.DECISION_REJECTED,
        approved ? null : "needs work",
        approved ? null : "missing_scope",
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static ApprovalSnapshot planApproval(String publicId) {
    return new ApprovalSnapshot(
        publicId,
        RUN_ID,
        ART_PLAN_ID,
        1,
        1,
        "alex@example.com",
        ActorType.HUMAN,
        "product_owner",
        ApprovalSnapshot.DECISION_APPROVED,
        null,
        null,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static ArtifactRecordSnapshot pendingArtifact(String publicId, ArtifactType type) {
    return new ArtifactRecordSnapshot(
        publicId,
        RUN_ID,
        type,
        1,
        null,
        DataClassification.SHAREABLE_REDACTED,
        null,
        null,
        null,
        null,
        null,
        ArtifactStatus.PENDING,
        null,
        false);
  }

  private static Clarification clarification(String publicId, String status) {
    boolean open = Clarification.STATUS_OPEN.equals(status);
    return new Clarification(
        publicId,
        RUN_ID,
        ART_SPEC_ID,
        1,
        "Q1",
        "What is the retention window?",
        status,
        open ? null : "30 days",
        open ? null : "alex@example.com",
        open ? null : ActorType.HUMAN,
        open ? null : OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static RedactionResult redactionPassthrough(JsonNode input) {
    return new RedactionResult(
        null,
        input,
        DataClassification.SHAREABLE_REDACTED,
        DataClassification.SHAREABLE_REDACTED,
        false,
        Set.of());
  }

  private static ArtifactRecordSnapshot availableArtifact(
      String publicId, ArtifactType type, String storageRef) {
    return new ArtifactRecordSnapshot(
        publicId,
        RUN_ID,
        type,
        1,
        null,
        DataClassification.SHAREABLE_REDACTED,
        storageRef,
        "SHA-256",
        "a".repeat(64),
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false);
  }
}
