package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.dradgo.adapters.integration.github.GitHubMockAdapter;
import org.dradgo.adapters.integration.github.GitHubMockScenarioRegistry;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.github.GitHubAdapter;
import org.dradgo.application.integration.github.GitHubPullRequest;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.NewIntegrationLink;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Foundation contract (story 3.15 AC8) — widens the foundation gate (story 1.23) to assert {@link
 * IntegrationLinkService#linkGitHubPr} works end-to-end against the {@code github-mock} adapter: it
 * resolves the PR via the real {@link GitHubMockAdapter}, writes a {@code github_pr} {@code
 * integration_links} row through the SPI, and appends an {@code integration.linked} workflow event
 * — with REAL redaction (NFR17 reconstruction fields survive the {@code shareable-redacted} pass).
 *
 * <p>Driven as a POJO with the production {@link GitHubMockAdapter} + a real {@link
 * RedactionPolicyService}; the persistence SPI and event port are mocked so the contract stays in
 * the no-Docker foundation tier (the real V6 conflict index + supersede round-trip live in {@code
 * IntegrationLinkGitHubPrIT}).
 */
@Tag("foundation-gate")
class IntegrationLinkGitHubPrFoundationContract {

  private static final String RUN_ID = "run_foundation01";
  private static final ActorContext SYSTEM =
      new ActorContext("system", ActorType.SYSTEM, "corr-foundation");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void linkGitHubPrWritesGithubRowAndEmitsIntegrationLinkedEventAgainstMockAdapter()
      throws Exception {
    GitHubAdapter mockAdapter = new GitHubMockAdapter(new GitHubMockScenarioRegistry());
    IntegrationLinkRecordPort port = mock(IntegrationLinkRecordPort.class);
    WorkflowEventWritePort eventPort = mock(WorkflowEventWritePort.class);
    IntegrationLinkService service = service(port, mockAdapter, eventPort);

    // The mock seeds PR-101 against repo GH-101 (github-feature-low-risk fixture).
    String prRef = GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK;
    String repoRef = GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK;
    GitHubPullRequest seeded = mockAdapter.getPullRequestByRef(prRef).orElseThrow();

    when(port.findActiveByTypeAndExternalRefForUpdate("github_pr", prRef))
        .thenReturn(Optional.empty());
    when(port.findActiveByTypeAndWorkflowRunForUpdate("github_pr", RUN_ID))
        .thenReturn(Optional.empty());
    when(port.insert(any(NewIntegrationLink.class)))
        .thenAnswer(invocation -> linkFrom(invocation.getArgument(0)));

    IntegrationLink linked =
        service.linkGitHubPr(
            RUN_ID,
            prRef,
            repoRef,
            seeded.sourceBranch(),
            "deadbeefcafe0001",
            SYSTEM,
            "linkGitHubPr:" + RUN_ID + ":" + prRef);

    assertEquals("github_pr", linked.integrationType(), tag("integration_type must be github_pr"));
    assertEquals(prRef, linked.externalRef(), tag("external_ref must be the canonical PR ref"));

    // AC1/NFR17 — the persisted (real-redacted) metadata carries every reconstruction field.
    ArgumentCaptor<NewIntegrationLink> insert = ArgumentCaptor.forClass(NewIntegrationLink.class);
    verify(port).insert(insert.capture());
    JsonNode metadata = MAPPER.readTree(insert.getValue().externalMetadata());
    assertEquals(repoRef, metadata.path("repositoryFullName").asText(), tag("repositoryFullName"));
    assertEquals(seeded.sourceBranch(), metadata.path("branch").asText(), tag("branch"));
    assertEquals("deadbeefcafe0001", metadata.path("commitSha").asText(), tag("commitSha"));
    assertEquals(seeded.number(), metadata.path("prNumber").asInt(), tag("prNumber"));
    assertEquals(seeded.state(), metadata.path("prState").asText(), tag("prState"));
    assertTrue(metadata.path("prUrl").isTextual(), tag("prUrl present for reconstruction"));

    // AC2 — the integration.linked event is appended within the link.
    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventPort).append(event.capture());
    assertEquals(
        WorkflowEventType.INTEGRATION_LINKED,
        event.getValue().eventType(),
        tag("integration.linked event"));
    assertEquals(prRef, event.getValue().details().get("githubPrReference"), tag("event PR ref"));
    assertNotNull(event.getValue().details().get("prState"), tag("event prState"));
  }

  private static IntegrationLinkService service(
      IntegrationLinkRecordPort port, GitHubAdapter adapter, WorkflowEventWritePort eventPort) {
    return new IntegrationLinkService(
        port,
        mock(TicketSourceAdapter.class),
        mock(IdempotencyService.class),
        new RedactionPolicyService(new DataClassificationService()),
        gitHubAdapterProvider(adapter),
        eventPort,
        callthroughTemplate());
  }

  private static IntegrationLink linkFrom(NewIntegrationLink request) {
    Instant now = Instant.parse("2026-06-10T10:00:00Z");
    return new IntegrationLink(
        request.publicId(),
        request.workflowRunPublicId(),
        request.integrationType(),
        request.externalRef(),
        IntegrationSyncStatus.LINKED,
        now,
        now,
        null);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<GitHubAdapter> gitHubAdapterProvider(GitHubAdapter adapter) {
    ObjectProvider<GitHubAdapter> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(adapter);
    return provider;
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              org.springframework.transaction.support.TransactionCallback<?> callback =
                  invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    return template;
  }

  private static String tag(String detail) {
    return FoundationGateAssertions.tagged("3.15", "linkGitHubPr github-mock e2e: " + detail);
  }
}
