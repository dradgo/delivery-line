package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.audit.AuditQueryService;
import org.dradgo.application.audit.AuditQueryService.AuditEventRow;
import org.dradgo.application.audit.AuditQueryService.AuditQueryFilter;
import org.dradgo.application.audit.AuditQueryService.AuditQueryResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.3 (AC1/AC3/AC7/AC8) — real-Postgres coverage for {@code AuditQueryService}. The keyset
 * walk (same-tick {@code created_at} tie-broken by {@code id}), the cross-run by-ticket join over
 * {@code integration_links} (including a superseded link to an earlier run), JSONB {@code
 * details->>} extraction, and the {@code reason} redaction only exercise on real PG. Named {@code
 * *IT} so Failsafe runs it. Not {@code @Transactional} — seeds via auto-committed JDBC and cleans
 * in {@code @AfterEach} so the read tx sees committed rows.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class AuditQueryServiceIT {

  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

  @Autowired private AuditQueryService auditQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs where project_id is null");
  }

  private static AuditQueryFilter filter(int limit) {
    return new AuditQueryFilter(List.of(), null, null, null, limit, null);
  }

  @Test
  void byRunReturnsRunEventsNewestFirstWithMappedFields() {
    String run = seedRun("Failed");
    seedEvent(
        run,
        "workflow.stateChanged",
        "Inbox",
        "Planned",
        "alex",
        "human",
        null,
        null,
        "{}",
        NOW.minusMinutes(3));
    seedEvent(
        run,
        "runner.failed",
        "Executing",
        "Failed",
        "system",
        "system",
        "orphan",
        "runner orphaned",
        "{}",
        NOW.minusMinutes(1));

    AuditQueryResult result = auditQueryService.queryByRun(run, filter(50));

    assertThat(result.totalCount()).isEqualTo(2);
    assertThat(result.events()).hasSize(2);
    AuditEventRow newest = result.events().get(0);
    assertThat(newest.eventType()).isEqualTo("runner.failed");
    assertThat(newest.workflowRunId()).isEqualTo(run);
    assertThat(newest.resultingState().value()).isEqualTo("Failed");
    assertThat(newest.failureCategory().value()).isEqualTo("orphan");
    assertThat(result.nextCursor()).isNull();
  }

  @Test
  void byTicketJoinsAllRunsIncludingSupersededLinkToEarlierRun() {
    String ticket = "LIN-777";
    String firstRun = seedRun("Failed");
    seedLink(firstRun, ticket, "superseded");
    seedEvent(
        firstRun,
        "workflow.stateChanged",
        "Inbox",
        "Planned",
        "system",
        "system",
        null,
        null,
        "{}",
        NOW.minusMinutes(10));

    String retryRun = seedRun("Completed");
    seedLink(retryRun, ticket, "linked");
    seedEvent(
        retryRun,
        "workflow.stateChanged",
        "Executing",
        "Completed",
        "system",
        "system",
        null,
        null,
        "{}",
        NOW.minusMinutes(2));

    AuditQueryResult result = auditQueryService.queryByTicket(ticket, filter(50));

    assertThat(result.totalCount()).isEqualTo(2);
    assertThat(result.events().stream().map(AuditEventRow::workflowRunId))
        .containsExactlyInAnyOrder(firstRun, retryRun);
  }

  @Test
  void eventTypeAndActorAndTimeFiltersNarrowResults() {
    String run = seedRun("Failed");
    seedEvent(
        run,
        "workflow.stateChanged",
        "Inbox",
        "Planned",
        "alex",
        "human",
        null,
        null,
        "{}",
        NOW.minusMinutes(5));
    seedEvent(
        run,
        "runner.failed",
        "Executing",
        "Failed",
        "system",
        "system",
        "orphan",
        null,
        "{}",
        NOW.minusMinutes(3));
    seedEvent(
        run,
        "runner.failed",
        "Executing",
        "Failed",
        "alex",
        "human",
        "orphan",
        null,
        "{}",
        NOW.minusMinutes(1));

    AuditQueryResult byType =
        auditQueryService.queryByRun(
            run, new AuditQueryFilter(List.of("runner.failed"), null, null, null, 50, null));
    assertThat(byType.totalCount()).isEqualTo(2);

    AuditQueryResult byActor =
        auditQueryService.queryByRun(
            run, new AuditQueryFilter(List.of(), "alex", null, null, 50, null));
    assertThat(byActor.totalCount()).isEqualTo(2);

    AuditQueryResult byWindow =
        auditQueryService.queryByRun(
            run,
            new AuditQueryFilter(
                List.of(), null, NOW.minusMinutes(4), NOW.minusMinutes(2), 50, null));
    assertThat(byWindow.totalCount()).isEqualTo(1);
  }

  @Test
  void sinceAfterUntilRaisesInvalidTimeRange() {
    String run = seedRun("Failed");
    assertThatThrownBy(
            () ->
                auditQueryService.queryByRun(
                    run, new AuditQueryFilter(List.of(), null, NOW, NOW.minusMinutes(5), 50, null)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_TIME_RANGE);
  }

  @Test
  void cursorWalkReproducesStableOrderAcrossSameTickPages() {
    String run = seedRun("Failed");
    // Seven events sharing the SAME created_at → ordering must fall back to the id tiebreaker
    // (created_at DESC, id DESC). A correct keyset cursor reproduces the single-scan order exactly.
    OffsetDateTime sameTick = NOW.minusMinutes(1);
    for (int i = 0; i < 7; i++) {
      seedEvent(
          run,
          "workflow.stateChanged",
          "Inbox",
          "Planned",
          "system",
          "system",
          null,
          null,
          "{}",
          sameTick);
    }

    List<String> reference =
        auditQueryService.queryByRun(run, filter(50)).events().stream()
            .map(AuditEventRow::eventId)
            .toList();
    assertThat(reference).hasSize(7);

    List<String> paged = pageAllEventIds(run, 3);
    assertThat(paged)
        .as("cursor walk must return the same events in the same order as a single-page scan")
        .containsExactlyElementsOf(reference);
    assertThat(new HashSet<>(paged)).as("no event duplicated across pages").hasSize(7);
  }

  @Test
  void redactsSecretBearingReasonAndStripsControlChars() {
    String run = seedRun("Failed");
    String secretReason = "leaked ghp_ABCDEFGHIJKLMNOPQRSTUV then\nnewline";
    seedEvent(
        run,
        "runner.failed",
        "Executing",
        "Failed",
        "system",
        "system",
        "orphan",
        secretReason,
        "{}",
        NOW.minusMinutes(1));

    AuditQueryResult result = auditQueryService.queryByRun(run, filter(50));
    String reason = result.events().get(0).reason();

    assertThat(reason).doesNotContain("ghp_ABCDEFGHIJKLMNOPQRSTUV");
    assertThat(reason).doesNotContain("\n");
  }

  @Test
  void extractsCorrelationIdAndArtifactIdFromRedactedDetails() {
    String run = seedRun("Failed");
    String details =
        "{\"correlationId\":\"cor_123\",\"artifactId\":\"art_456\",\"idempotencyKey\":\"idm_secret\"}";
    seedEvent(
        run,
        "workflow.stateChanged",
        "Inbox",
        "Planned",
        "system",
        "system",
        null,
        null,
        details,
        NOW.minusMinutes(1));

    AuditEventRow row = auditQueryService.queryByRun(run, filter(50)).events().get(0);

    assertThat(row.correlationId()).isEqualTo("cor_123");
    assertThat(row.linkedArtifactId()).isEqualTo("art_456");
  }

  @Test
  void unknownRunThrowsRunNotFound() {
    String missing = PublicIdPrefixes.WORKFLOW_RUN.next();
    assertThatThrownBy(() -> auditQueryService.queryByRun(missing, filter(50)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.RUN_NOT_FOUND);
  }

  @Test
  void unknownTicketReturnsEmptyResultNotError() {
    AuditQueryResult result = auditQueryService.queryByTicket("LIN-does-not-exist", filter(50));
    assertThat(result.totalCount()).isZero();
    assertThat(result.events()).isEmpty();
    assertThat(result.nextCursor()).isNull();
  }

  @Test
  void twoHundredEventQueryCompletesWellUnderFiveSeconds(CapturedOutput output) {
    String run = seedRun("Failed");
    for (int i = 0; i < 200; i++) {
      seedEvent(
          run,
          "workflow.stateChanged",
          "Inbox",
          "Planned",
          "system",
          "system",
          null,
          null,
          "{}",
          NOW.minusSeconds(i + 1));
    }

    long start = System.nanoTime();
    AuditQueryResult page = auditQueryService.queryByRun(run, filter(200));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    assertThat(page.events()).hasSize(200);
    assertThat(page.totalCount()).isEqualTo(200);
    assertThat(elapsedMs).as("200-event query must return in <5s (AC8)").isLessThan(5_000L);
    assertThat(output.getOut()).contains("audit query success");
  }

  private List<String> pageAllEventIds(String run, int pageSize) {
    List<String> all = new ArrayList<>();
    String cursor = null;
    int guard = 0;
    do {
      AuditQueryResult page =
          auditQueryService.queryByRun(
              run, new AuditQueryFilter(List.of(), null, null, null, pageSize, cursor));
      page.events().forEach(e -> all.add(e.eventId()));
      cursor = page.nextCursor();
      if (++guard > 100) {
        throw new IllegalStateException("cursor did not terminate");
      }
    } while (cursor != null);
    return all;
  }

  private String seedRun(String state) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", runId, state);
    return runId;
  }

  private void seedLink(String run, String externalRef, String syncStatus) {
    jdbcTemplate.update(
        """
        insert into integration_links
          (public_id, workflow_run_id, integration_type, external_ref, sync_status)
        values (?, (select id from workflow_runs where public_id = ?), 'linear', ?, ?)
        """,
        PublicIdPrefixes.INTEGRATION_LINK.next(),
        run,
        externalRef,
        syncStatus);
  }

  private void seedEvent(
      String run,
      String eventType,
      String priorState,
      String resultingState,
      String actorIdentity,
      String actorType,
      String failureCategory,
      String reason,
      String detailsJson,
      OffsetDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, prior_state, resulting_state,
           actor_identity, actor_type, failure_category, reason, details, created_at)
        values (?, (select id from workflow_runs where public_id = ?), ?, ?, ?, ?, ?, ?, ?,
                ?::jsonb, ?)
        """,
        PublicIdPrefixes.WORKFLOW_EVENT.next(),
        run,
        eventType,
        priorState,
        resultingState,
        actorIdentity,
        actorType,
        failureCategory,
        reason,
        detailsJson,
        createdAt);
  }
}
