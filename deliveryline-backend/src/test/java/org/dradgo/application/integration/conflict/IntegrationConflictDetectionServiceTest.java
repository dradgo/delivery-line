package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictScanPort;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictWritePort;
import org.dradgo.application.integration.conflict.spi.IntegrationLinkScanRow;
import org.dradgo.application.integration.conflict.spi.NewIntegrationConflict;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Story 4.17 (AC2/AC5/AC8/AC10) — unit coverage of the classification matrix, dedup, and rate-limit
 * back-off with all ports mocked. Real-PG behaviour (dedup index, advisory lock, replay) is covered
 * by {@code IntegrationConflictDetectionIT}.
 */
class IntegrationConflictDetectionServiceTest {

  private static final int BATCH_LIMIT = 100;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private IntegrationConflictScanPort scanPort;
  private IntegrationConflictWritePort writePort;
  private WorkflowEventWritePort eventPort;
  private TicketSourceAdapter linearAdapter;
  private RepositoryHostAdapter gitHubAdapter;
  private MeterRegistry meterRegistry;
  private ConflictAutoPauseHandler conflictAutoPauseHandler;
  private IntegrationConflictDetectionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    scanPort = org.mockito.Mockito.mock(IntegrationConflictScanPort.class);
    writePort = org.mockito.Mockito.mock(IntegrationConflictWritePort.class);
    eventPort = org.mockito.Mockito.mock(WorkflowEventWritePort.class);
    linearAdapter = org.mockito.Mockito.mock(TicketSourceAdapter.class);
    gitHubAdapter = org.mockito.Mockito.mock(RepositoryHostAdapter.class);
    meterRegistry = new SimpleMeterRegistry();
    conflictAutoPauseHandler = org.mockito.Mockito.mock(ConflictAutoPauseHandler.class);

    ObjectProvider<RepositoryHostAdapter> gitHubProvider =
        org.mockito.Mockito.mock(ObjectProvider.class);
    when(gitHubProvider.getIfAvailable()).thenReturn(gitHubAdapter);

    PlatformTransactionManager txManager =
        org.mockito.Mockito.mock(PlatformTransactionManager.class);
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    // Insert succeeds (new row) unless a test overrides it.
    when(writePort.insertIfAbsent(any())).thenReturn(true);
    // Both scans empty unless a test overrides.
    when(scanPort.scanActiveLinksByType(any(), anyInt(), anyLong())).thenReturn(List.of());

    service =
        new IntegrationConflictDetectionService(
            scanPort,
            writePort,
            eventPort,
            linearAdapter,
            gitHubProvider,
            IntegrationConflictDetectionProperties.defaults(),
            meterRegistry,
            txManager,
            conflictAutoPauseHandler);
  }

  @Test
  void mergedWhileOpenIsExternalStateAdvanced() {
    stubGitHub(row("ilk_gh1", "run_a", "octo/repo#1", Map.of("prState", "open")));
    when(gitHubAdapter.getPullRequestByRef(any()))
        .thenReturn(Optional.of(pr("octo/repo#1", "octo/repo", "feature/x", "closed", true)));

    SweepResult result = service.sweep();

    assertThat(result.conflictsDetected()).isEqualTo(1);
    assertThat(capturedConflict().conflictCategory())
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value());
    assertThat(conflictDetectedEvents()).isEqualTo(1);
    assertThat(counterCount("external_state_advanced", "github")).isEqualTo(1.0);
    // Story 4.18 (AC4) — a NEW conflict fires the auto-pause hook with the run + category (the
    // handler itself decides whether the category is configured for auto-pause).
    verify(conflictAutoPauseHandler)
        .maybeAutoPause(
            eq("run_a"), any(), eq(IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED), any());
  }

  @Test
  void reopenedAfterCloseIsExternalStateReverted() {
    stubGitHub(row("ilk_gh2", "run_b", "octo/repo#2", Map.of("prState", "closed")));
    when(gitHubAdapter.getPullRequestByRef(any()))
        .thenReturn(Optional.of(pr("octo/repo#2", "octo/repo", "feature/x", "open", false)));

    service.sweep();

    assertThat(capturedConflict().conflictCategory())
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_REVERTED.value());
  }

  @Test
  void absentPullRequestIsExternalResourceRemoved() {
    stubGitHub(row("ilk_gh3", "run_c", "octo/repo#3", Map.of("prState", "open")));
    when(gitHubAdapter.getPullRequestByRef(any())).thenReturn(Optional.empty());

    service.sweep();

    assertThat(capturedConflict().conflictCategory())
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED.value());
  }

  @Test
  void branchDriftIsMetadataDrift() {
    stubGitHub(
        row(
            "ilk_gh4",
            "run_d",
            "octo/repo#4",
            Map.of("prState", "open", "branch", "feature/original")));
    when(gitHubAdapter.getPullRequestByRef(any()))
        .thenReturn(Optional.of(pr("octo/repo#4", "octo/repo", "feature/renamed", "open", false)));

    service.sweep();

    assertThat(capturedConflict().conflictCategory())
        .isEqualTo(IntegrationConflictCategory.METADATA_DRIFT.value());
  }

  @Test
  void permissionDeniedIsLinkBroken() {
    stubGitHub(row("ilk_gh5", "run_e", "octo/repo#5", Map.of("prState", "open")));
    when(gitHubAdapter.getPullRequestByRef(any()))
        .thenThrow(
            new RepositoryHostAdapterException(
                IntegrationFailureCategory.GITHUB_PERMISSION_DENIED, "403"));

    service.sweep();

    assertThat(capturedConflict().conflictCategory())
        .isEqualTo(IntegrationConflictCategory.LINK_BROKEN.value());
  }

  @Test
  void rateLimitBacksOffAndSkipsRemainingGitHubLinks() {
    stubGitHub(
        row("ilk_gh6", "run_f", "octo/repo#6", Map.of("prState", "open")),
        row("ilk_gh7", "run_g", "octo/repo#7", Map.of("prState", "open")));
    when(gitHubAdapter.getPullRequestByRef(any()))
        .thenThrow(
            new RepositoryHostAdapterException(
                IntegrationFailureCategory.GITHUB_RATE_LIMITED, "429"));

    SweepResult result = service.sweep();

    assertThat(result.rateLimited()).isTrue();
    assertThat(result.conflictsDetected()).isZero();
    // Only the FIRST github link was queried before the back-off short-circuited the rest.
    verify(gitHubAdapter, times(1)).getPullRequestByRef(any());
    verify(writePort, never()).insertIfAbsent(any());
  }

  @Test
  void transientNetworkFailureBacksOffWithoutConflict() {
    // Reconciliation 8 / review D4: a GitHub network failure backs off the integration's remaining
    // links for the tick (symmetric with Linear), not just a per-link skip.
    stubGitHub(
        row("ilk_gh8", "run_h", "octo/repo#8", Map.of("prState", "open")),
        row("ilk_gh8b", "run_h2", "octo/repo#8b", Map.of("prState", "open")));
    when(gitHubAdapter.getPullRequestByRef(any()))
        .thenThrow(
            new RepositoryHostAdapterException(
                IntegrationFailureCategory.GITHUB_NETWORK_FAILURE, "timeout"));

    SweepResult result = service.sweep();

    assertThat(result.rateLimited()).isTrue();
    assertThat(result.conflictsDetected()).isZero();
    // Backed off after the FIRST link — the second github link was not queried.
    verify(gitHubAdapter, times(1)).getPullRequestByRef(any());
    verify(writePort, never()).insertIfAbsent(any());
    verify(eventPort, never()).append(any());
  }

  @Test
  void dedupSkipCountsButEmitsNoEvent() {
    stubGitHub(row("ilk_gh9", "run_i", "octo/repo#9", Map.of("prState", "open")));
    when(gitHubAdapter.getPullRequestByRef(any())).thenReturn(Optional.empty());
    when(writePort.insertIfAbsent(any())).thenReturn(false); // standing conflict already present

    SweepResult result = service.sweep();

    assertThat(result.conflictsDetected()).isZero();
    assertThat(result.skippedDuplicate()).isEqualTo(1);
    verify(eventPort, never()).append(any());
    assertThat(counterCount("external_resource_removed", "github")).isZero();
    // Story 4.18 (AC4) — a dedup-skipped (already-standing) conflict does NOT re-fire the
    // auto-pause
    // hook: exactly-once per (link, category), no per-tick pause spam.
    verify(conflictAutoPauseHandler, never()).maybeAutoPause(any(), any(), any(), any());
  }

  @Test
  void absentLinearTicketIsExternalResourceRemoved() {
    stubLinear(row("ilk_lin1", "run_j", "LIN-1", Map.of()));
    when(linearAdapter.fetchTicketByReference(any())).thenReturn(Optional.empty());

    service.sweep();

    NewIntegrationConflict conflict = capturedConflict();
    assertThat(conflict.conflictCategory())
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED.value());
    assertThat(conflict.integrationLinkPublicId()).isEqualTo("ilk_lin1");
  }

  @Test
  void linearLinkFailureIsLinkBroken() {
    stubLinear(row("ilk_lin2", "run_k", "LIN-2", Map.of()));
    when(linearAdapter.fetchTicketByReference(any()))
        .thenThrow(
            new TicketSourceAdapterException(IntegrationFailureCategory.LINK_FAILURE, "gone"));

    service.sweep();

    assertThat(capturedConflict().conflictCategory())
        .isEqualTo(IntegrationConflictCategory.LINK_BROKEN.value());
  }

  @Test
  void presentLinearTicketWithFirstSeenStatusSnapshotsBaselineAndEmitsNoConflict() {
    stubLinear(row("ilk_lin3", "run_l", "LIN-3", Map.of()));
    when(linearAdapter.fetchTicketByReference(any()))
        .thenReturn(Optional.of(ticket("LIN-3", "In Progress", "status-42")));

    SweepResult result = service.sweep();

    assertThat(result.conflictsDetected()).isZero();
    verify(writePort, never()).insertIfAbsent(any());
    // Best-effort first-seen baseline snapshot written for a FUTURE story (OQ-1 provisional).
    verify(scanPort, times(1)).snapshotExternalMetadataBaseline(eq("ilk_lin3"), any());
  }

  @Test
  void closedWithoutMergeWhileOpenIsExternalStateAdvanced() {
    // Review D3: a PR closed WITHOUT merge externally while the cached baseline was still open is a
    // real drift (external reached a terminal state ahead of the internal run).
    stubGitHub(row("ilk_gh10", "run_m", "octo/repo#10", Map.of("prState", "open")));
    when(gitHubAdapter.getPullRequestByRef(any()))
        .thenReturn(Optional.of(pr("octo/repo#10", "octo/repo", "feature/x", "closed", false)));

    service.sweep();

    assertThat(capturedConflict().conflictCategory())
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value());
  }

  @Test
  void firstSeenGitHubLinkWithoutBaselineSnapshotsPrStateAndEmitsNoConflict() {
    // Review D3: no cached prState baseline → snapshot the fresh state first-seen (like Linear)
    // rather than silently missing a later merge; no conflict this tick.
    stubGitHub(row("ilk_gh11", "run_n", "octo/repo#11", Map.of()));
    when(gitHubAdapter.getPullRequestByRef(any()))
        .thenReturn(Optional.of(pr("octo/repo#11", "octo/repo", "feature/x", "open", false)));

    SweepResult result = service.sweep();

    assertThat(result.conflictsDetected()).isZero();
    verify(writePort, never()).insertIfAbsent(any());
    verify(scanPort, times(1)).snapshotExternalMetadataBaseline(eq("ilk_gh11"), any());
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private void stubGitHub(IntegrationLinkScanRow... rows) {
    when(scanPort.scanActiveLinksByType(eq("github_pr"), anyInt(), anyLong()))
        .thenReturn(List.of(rows));
  }

  private void stubLinear(IntegrationLinkScanRow... rows) {
    when(scanPort.scanActiveLinksByType(eq("linear"), anyInt(), anyLong()))
        .thenReturn(List.of(rows));
  }

  private NewIntegrationConflict capturedConflict() {
    ArgumentCaptor<NewIntegrationConflict> captor =
        ArgumentCaptor.forClass(NewIntegrationConflict.class);
    verify(writePort).insertIfAbsent(captor.capture());
    return captor.getValue();
  }

  private long conflictDetectedEvents() {
    ArgumentCaptor<WorkflowEventRecord> captor = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventPort, org.mockito.Mockito.atLeast(0)).append(captor.capture());
    return captor.getAllValues().stream()
        .filter(e -> e.eventType() == WorkflowEventType.INTEGRATION_CONFLICT_DETECTED)
        .count();
  }

  private double counterCount(String category, String integration) {
    var counter =
        meterRegistry
            .find("deliveryline.integration.conflict.detected")
            .tag("category", category)
            .tag("integration", integration)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static final java.util.concurrent.atomic.AtomicLong SEQ =
      new java.util.concurrent.atomic.AtomicLong(1);

  private static IntegrationLinkScanRow row(
      String linkId, String runId, String externalRef, Map<String, Object> cachedMetadata) {
    return new IntegrationLinkScanRow(
        linkId,
        runId,
        externalRef.startsWith("LIN") ? "linear" : "github_pr",
        externalRef,
        toBytes(cachedMetadata),
        "prj_test1",
        "WaitingForReview",
        SEQ.getAndIncrement());
  }

  private static byte[] toBytes(Map<String, Object> metadata) {
    try {
      return MAPPER.writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PullRequest pr(
      String prRef, String repoRef, String branch, String state, boolean merged) {
    return new PullRequest(
        PullRequestRef.of(prRef),
        RepositoryRef.of(repoRef),
        1,
        branch,
        state,
        merged,
        "https://github.com/" + repoRef + "/pull/1",
        Instant.parse("2026-05-01T10:00:00Z"));
  }

  private static Ticket ticket(String ref, String status, String statusId) {
    return new Ticket(
        TicketRef.of(ref),
        "title",
        "summary",
        "author@example.com",
        Instant.parse("2026-05-01T10:00:00Z"),
        Instant.parse("2026-05-02T10:00:00Z"),
        Map.of(),
        status,
        statusId);
  }
}
