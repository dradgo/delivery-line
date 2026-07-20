package org.dradgo.adapters.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.WorkflowRunEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.workflow.spi.CiPollRow;
import org.dradgo.application.workflow.spi.CiRunView;
import org.dradgo.application.workflow.spi.CiStatusPort;
import org.dradgo.application.workflow.spi.FailureClassificationRecord;
import org.dradgo.application.workflow.spi.WorkflowRunArchivePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunFailureClassificationPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.application.workflow.spi.WorkflowRunStatePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkflowRunPersistenceAdapter
    implements WorkflowRunReadPort,
        WorkflowRunCreatePort,
        WorkflowRunStatePort,
        WorkflowRunArchivePort,
        WorkflowRunRejectionLoopPort,
        WorkflowRunFailureClassificationPort,
        CiStatusPort {

  private static final Logger log = LoggerFactory.getLogger(WorkflowRunPersistenceAdapter.class);
  private static final String INCREMENT_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set spec_rejection_loop_count = spec_rejection_loop_count + 1
       where public_id = :publicId
       returning spec_rejection_loop_count
      """;
  private static final String INCREMENT_IMPLEMENTATION_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set implementation_rejection_loop_count = implementation_rejection_loop_count + 1
       where public_id = :publicId
       returning implementation_rejection_loop_count
      """;
  // Story 3f-4 — the split-proposal re-propose loop twin (drives a DISTINCT dispatch idempotency
  // key per attempt + the shared escalation marker).
  private static final String INCREMENT_SPLIT_PROPOSAL_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set split_proposal_loop_count = split_proposal_loop_count + 1
       where public_id = :publicId
       returning split_proposal_loop_count
      """;
  // Story 3h-1 — the build-validation auto-fix loop twin (distinct dispatch idempotency key per
  // attempt + the cap check; shares the escalation marker with the other loops).
  private static final String INCREMENT_BUILD_FIX_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set build_fix_loop_count = build_fix_loop_count + 1
       where public_id = :publicId
       returning build_fix_loop_count
      """;
  // Story 3h-2 — the operator-driven lint fix loop twin (distinct dispatch idempotency key per
  // attempt + the cap check for the visibility-only escalation; shares the escalation marker).
  private static final String INCREMENT_LINT_FIX_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set lint_fix_loop_count = lint_fix_loop_count + 1
       where public_id = :publicId
       returning lint_fix_loop_count
      """;
  // Story 3h-5 (AC2/AC6) — the CI-investigation fix loop twin (distinct dispatch idempotency key
  // per
  // attempt + the cap check for the visibility-only escalation; shares the escalation marker).
  private static final String INCREMENT_CI_FIX_LOOP_COUNT_SQL =
      """
      update workflow_runs
         set ci_fix_loop_count = ci_fix_loop_count + 1
       where public_id = :publicId
       returning ci_fix_loop_count
      """;
  // Story 4.9 (AC4/AC9) — the failure-classification triple is read/written ONLY through these
  // targeted raw-SQL statements (never a WorkflowRunEntity save — no @DynamicUpdate + full-row
  // clobber risk, exactly like the ci_* columns below). The FOR UPDATE read captures the PRIOR
  // classification inside the caller's classify prep transaction so a concurrent classify cannot
  // interleave between the prior-read and the overwrite.
  private static final String READ_FAILURE_CLASSIFICATION_SQL =
      """
      select failure_classification, failure_classified_at, failure_classified_by
        from workflow_runs
       where public_id = :publicId
      """;
  private static final String READ_FAILURE_CLASSIFICATION_FOR_UPDATE_SQL =
      READ_FAILURE_CLASSIFICATION_SQL + " for update";
  private static final String APPLY_FAILURE_CLASSIFICATION_SQL =
      """
      update workflow_runs
         set failure_classification = :taxonomyValue,
             failure_classified_at = :classifiedAt,
             failure_classified_by = :classifiedBy
       where public_id = :publicId
      """;

  // Story 3h-5 (AC2, Decision 6) — the ci_* columns are written ONLY through these targeted raw-SQL
  // UPDATEs (never a WorkflowRunEntity save — no @DynamicUpdate + optimistic-version clobber risk).
  private static final String MARK_CI_POLL_PENDING_SQL =
      """
      update workflow_runs
         set ci_status = 'pending',
             ci_head_sha = :headSha,
             ci_poll_attempts = 0,
             ci_last_polled_at = null
       where public_id = :publicId
      """;
  private static final String RECORD_CI_POLL_ATTEMPT_SQL =
      """
      update workflow_runs
         set ci_poll_attempts = ci_poll_attempts + 1,
             ci_last_polled_at = now()
       where public_id = :publicId
       returning ci_poll_attempts
      """;
  private static final String RECORD_CI_STATUS_SQL =
      """
      update workflow_runs
         set ci_status = :status,
             ci_last_polled_at = now()
       where public_id = :publicId
      """;
  private static final String READ_CI_CURRENT_STATE_SQL =
      "select current_state from workflow_runs where public_id = :publicId";
  private static final String READ_CI_VIEW_SQL =
      """
      select ci_status, ci_head_sha, ci_fix_loop_count
        from workflow_runs
       where public_id = :publicId
      """;
  // Keyset-paginated by id (the raw monotonic PK) — the sweep advances past a batch across ticks
  // instead of re-selecting the same oldest pending window every tick (bare-LIMIT starvation). The
  // partial index ix_workflow_runs_ci_pending (V41) backs the ci_status='pending' predicate.
  private static final String FIND_RUNS_AWAITING_CI_STATUS_SQL =
      """
      select public_id as workflow_run_id,
             ci_head_sha as ci_head_sha,
             id as run_seq
        from workflow_runs
       where ci_status = 'pending'
         and archived_at is null
         and id > :afterSeq
       order by id asc
       limit :batchLimit
      """;
  // Sweep-wide advisory lock key, transaction-scoped (auto-released on commit). 0x43495354 ==
  // "CIST"
  // — a fresh key distinct from ICON (0x49434F4E) / RDEP (0x52444550) / RC (0x5243) so the sweeps
  // never contend on the same key.
  private static final long CI_SWEEP_ADVISORY_LOCK_KEY = 0x43495354L;
  // Per-run CI-investigation lock classifier. 0x4349 == "CI" — the two-int
  // pg_advisory_xact_lock(classifier, hashtext(runId)) form serializes the red-CI CAS + re-dispatch
  // on the SAME run (against a concurrent REVIEW harvest / operator accept) while different runs
  // never contend. Distinct from the RC reconcile classifier (0x5243).
  private static final int CI_RUN_LOCK_CLASSIFIER = 0x4349;
  private static final String ACQUIRE_CI_SWEEP_LOCK_SQL = "select pg_advisory_xact_lock(:lockKey)";
  private static final String LOCK_RUN_FOR_CI_SQL =
      "select pg_advisory_xact_lock(:classifier, hashtext(:runId))";

  private static final String MARK_ESCALATION_SQL =
      // RETURNING an int literal (not the boolean escalation_marker_set column) so the int row
      // mapper below reads cleanly — a returned row means this call just-flipped the marker, zero
      // rows means it was already set. Reading the boolean column via getInt throws "Bad value for
      // type int : t" on Postgres (story 3.21 — first real-DB exercise of this escalation path).
      """
      update workflow_runs
         set escalation_marker_set = true
       where public_id = :publicId
         and escalation_marker_set = false
       returning 1
      """;

  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowRunEntityMapper workflowRunEntityMapper;
  private final NamedParameterJdbcTemplate jdbcTemplate;

  public WorkflowRunPersistenceAdapter(
      WorkflowRunRepository workflowRunRepository,
      WorkflowRunEntityMapper workflowRunEntityMapper,
      NamedParameterJdbcTemplate jdbcTemplate) {
    this.workflowRunRepository = workflowRunRepository;
    this.workflowRunEntityMapper = workflowRunEntityMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<WorkflowRunSnapshot> findByPublicId(String publicId) {
    return workflowRunRepository.findByPublicId(publicId).map(workflowRunEntityMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkflowRunSnapshot> findByParentRunId(String parentRunId) {
    return workflowRunRepository.findByParentRunIdOrderByCreatedAtDescIdDesc(parentRunId).stream()
        .map(workflowRunEntityMapper::toSnapshot)
        .toList();
  }

  // Story 3f-8 (AC1) — the reconciliation-sweep discovery query. Read-only; the sweep then
  // re-invokes
  // the idempotent 3f-7 rollup (its own REQUIRES_NEW tx) per returned parent. Limit clamped to >=
  // 1.
  @Override
  @Transactional(readOnly = true)
  public List<WorkflowRunSnapshot> findStrandedSplitParents(int limit) {
    Pageable page = PageRequest.of(0, Math.max(1, limit));
    return workflowRunRepository
        .findStrandedSplitParents(
            WorkflowState.SPLIT.value(), WorkflowState.COMPLETED.value(), page)
        .stream()
        .map(workflowRunEntityMapper::toSnapshot)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkflowRunSnapshot> listRuns(
      WorkflowState stateFilter, boolean includeArchived, int limit, String projectId) {
    Pageable page = PageRequest.of(0, limit);
    List<WorkflowRunEntity> entities =
        workflowRunRepository.listRunsFiltered(
            stateFilter == null ? null : stateFilter.value(), includeArchived, projectId, page);
    return entities.stream().map(workflowRunEntityMapper::toSnapshot).toList();
  }

  // Story 3d-8 (FR67, AC1/AC4) — the run-archive write seam. Mirrors
  // IntegrationLinkPersistenceAdapter.markArchived: load-by-public-id semantics (RUN_NOT_FOUND when
  // absent) over a bulk marker update that leaves current_state + version untouched. The governed
  // workflow.archived / workflow.unarchived event append is the caller's (WorkflowArchiveService)
  // concern and shares this transaction (REQUIRED propagation).
  @Override
  @Transactional
  public void markArchived(String workflowRunPublicId, java.time.Instant archivedAt) {
    java.util.Objects.requireNonNull(archivedAt, "archivedAt");
    int updated =
        workflowRunRepository.archiveIfNotArchived(
            workflowRunPublicId,
            java.time.OffsetDateTime.ofInstant(archivedAt, java.time.ZoneOffset.UTC));
    requireSingleArchiveWrite(workflowRunPublicId, updated);
    log.info("workflow_run archived publicId={} archivedAt={}", workflowRunPublicId, archivedAt);
  }

  @Override
  @Transactional
  public void clearArchived(String workflowRunPublicId) {
    int updated = workflowRunRepository.clearArchivedIfArchived(workflowRunPublicId);
    requireSingleArchiveWrite(workflowRunPublicId, updated);
    log.info("workflow_run unarchived publicId={}", workflowRunPublicId);
  }

  // A conditional archive/clear that affects 0 rows is either a genuinely-missing run
  // (RUN_NOT_FOUND)
  // or a run whose marker was concurrently flipped between the caller's snapshot read and this
  // write
  // (a lost race → ARCHIVE_NOT_APPLICABLE — the same code the caller's precondition check throws).
  private void requireSingleArchiveWrite(String workflowRunPublicId, int updated) {
    if (updated == 1) {
      return;
    }
    if (!workflowRunRepository.existsByPublicId(workflowRunPublicId)) {
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    throw new DomainException(
        DomainErrorCode.ARCHIVE_NOT_APPLICABLE,
        "Workflow run archive state changed concurrently: " + workflowRunPublicId,
        Map.of("runId", workflowRunPublicId, "reason", "concurrent_modification"));
  }

  @Override
  public WorkflowRunSnapshot create(
      String publicId, WorkflowState initialState, String projectId, String parentRunId) {
    if (parentRunId != null) {
      log.info("creating child run {} under parent {}", publicId, parentRunId);
    }
    return workflowRunEntityMapper.toSnapshot(
        workflowRunRepository.saveAndFlush(
            workflowRunEntityMapper.toNewEntity(publicId, initialState, projectId, parentRunId)));
  }

  @Override
  public void updateCurrentState(String publicId, WorkflowState targetState, Long expectedVersion) {
    if (expectedVersion == null) {
      throw new OptimisticLockingFailureException(
          "Workflow run state update is missing an optimistic-lock version for " + publicId);
    }
    int updated =
        workflowRunRepository.updateCurrentState(publicId, targetState.value(), expectedVersion);
    if (updated != 1) {
      if (!workflowRunRepository.existsByPublicId(publicId)) {
        throw new DomainException(
            DomainErrorCode.RUN_NOT_FOUND,
            "Workflow run not found: " + publicId,
            Map.of("runId", publicId));
      }
      throw new OptimisticLockingFailureException(
          "Workflow run state update lost optimistic lock for " + publicId);
    }
  }

  @Override
  public int incrementAndReadLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadLoopCount workflowRunNotFound publicId={}",
          workflowRunPublicId,
          workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadLoopCount publicId={} newLoopCount={}", workflowRunPublicId, newCount);
    return newCount;
  }

  @Override
  public int incrementAndReadImplementationLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_IMPLEMENTATION_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadImplementationLoopCount workflowRunNotFound publicId={}",
          workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadImplementationLoopCount publicId={} newLoopCount={}",
        workflowRunPublicId,
        newCount);
    return newCount;
  }

  @Override
  public int incrementAndReadSplitProposalLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_SPLIT_PROPOSAL_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadSplitProposalLoopCount workflowRunNotFound publicId={}",
          workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadSplitProposalLoopCount publicId={} newLoopCount={}",
        workflowRunPublicId,
        newCount);
    return newCount;
  }

  @Override
  public int incrementAndReadBuildFixLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_BUILD_FIX_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadBuildFixLoopCount workflowRunNotFound publicId={}", workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadBuildFixLoopCount publicId={} newLoopCount={}",
        workflowRunPublicId,
        newCount);
    return newCount;
  }

  @Override
  public int incrementAndReadLintFixLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_LINT_FIX_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadLintFixLoopCount workflowRunNotFound publicId={}", workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadLintFixLoopCount publicId={} newLoopCount={}",
        workflowRunPublicId,
        newCount);
    return newCount;
  }

  @Override
  public int incrementAndReadCiFixLoopCount(String workflowRunPublicId) {
    Integer newCount =
        jdbcTemplate.query(
            INCREMENT_CI_FIX_LOOP_COUNT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (newCount == null) {
      log.warn(
          "incrementAndReadCiFixLoopCount workflowRunNotFound publicId={}", workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug(
        "incrementAndReadCiFixLoopCount publicId={} newLoopCount={}",
        workflowRunPublicId,
        newCount);
    return newCount;
  }

  // ---------------------------------------------------------------------------------------------
  // WorkflowRunFailureClassificationPort (story 4.9) — targeted raw-SQL reads/writes only; the
  // triple is deliberately unmapped on WorkflowRunEntity/WorkflowRunSnapshot (R7).
  // ---------------------------------------------------------------------------------------------

  @Override
  public Optional<FailureClassificationRecord> findClassification(String workflowRunPublicId) {
    return readClassification(
        READ_FAILURE_CLASSIFICATION_SQL, workflowRunPublicId, "findClassification");
  }

  @Override
  public Optional<FailureClassificationRecord> applyClassification(
      String workflowRunPublicId,
      String taxonomyValue,
      java.time.OffsetDateTime classifiedAt,
      String classifiedBy) {
    Optional<FailureClassificationRecord> prior =
        readClassification(
            READ_FAILURE_CLASSIFICATION_FOR_UPDATE_SQL, workflowRunPublicId, "applyClassification");
    MapSqlParameterSource params =
        params(workflowRunPublicId)
            .addValue("taxonomyValue", taxonomyValue)
            .addValue("classifiedAt", classifiedAt)
            .addValue("classifiedBy", classifiedBy);
    jdbcTemplate.update(APPLY_FAILURE_CLASSIFICATION_SQL, params);
    log.debug(
        "applyClassification publicId={} taxonomyValue={} priorPresent={}",
        workflowRunPublicId,
        taxonomyValue,
        prior.isPresent());
    return prior;
  }

  private Optional<FailureClassificationRecord> readClassification(
      String sql, String workflowRunPublicId, String operation) {
    List<Optional<FailureClassificationRecord>> rows =
        jdbcTemplate.query(
            sql,
            params(workflowRunPublicId),
            (rs, rowNum) -> {
              String rawValue = rs.getString("failure_classification");
              if (rawValue == null) {
                return Optional.<FailureClassificationRecord>empty();
              }
              return Optional.of(
                  new FailureClassificationRecord(
                      PersistedRegistryValues.workflowRunFailureClassification(rawValue),
                      rs.getObject("failure_classified_at", java.time.OffsetDateTime.class),
                      rs.getString("failure_classified_by")));
            });
    if (rows.isEmpty()) {
      log.warn("{} workflowRunNotFound publicId={}", operation, workflowRunPublicId);
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    return rows.get(0);
  }

  // ---------------------------------------------------------------------------------------------
  // CiStatusPort (story 3h-5, Decision 6) — targeted raw-SQL writes only; never a WorkflowRunEntity
  // save (no @DynamicUpdate + optimistic-version clobber risk).
  // ---------------------------------------------------------------------------------------------

  @Override
  public void markCiPollPending(String workflowRunPublicId, String ciHeadSha) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("publicId", workflowRunPublicId)
            .addValue("headSha", ciHeadSha);
    int updated = jdbcTemplate.update(MARK_CI_POLL_PENDING_SQL, params);
    if (updated == 0) {
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.info("ci poll pending workflowRunId={} ciHeadSha={}", workflowRunPublicId, ciHeadSha);
  }

  @Override
  public int recordCiPollAttempt(String workflowRunPublicId) {
    Integer attempts =
        jdbcTemplate.query(
            RECORD_CI_POLL_ATTEMPT_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (attempts == null) {
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug("recordCiPollAttempt publicId={} attempts={}", workflowRunPublicId, attempts);
    return attempts;
  }

  @Override
  public void recordCiStatus(String workflowRunPublicId, String status) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("publicId", workflowRunPublicId)
            .addValue("status", status);
    int updated = jdbcTemplate.update(RECORD_CI_STATUS_SQL, params);
    if (updated == 0) {
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    log.debug("recordCiStatus publicId={} ciStatus={}", workflowRunPublicId, status);
  }

  @Override
  public List<CiPollRow> findRunsAwaitingCiStatus(long afterSeq, int batchLimit) {
    if (batchLimit <= 0) {
      throw new IllegalArgumentException("batchLimit must be positive");
    }
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("afterSeq", afterSeq)
            .addValue("batchLimit", batchLimit);
    List<CiPollRow> rows =
        jdbcTemplate.query(
            FIND_RUNS_AWAITING_CI_STATUS_SQL,
            params,
            (rs, rowNum) ->
                new CiPollRow(
                    rs.getString("workflow_run_id"),
                    rs.getString("ci_head_sha"),
                    rs.getLong("run_seq")));
    log.debug(
        "findRunsAwaitingCiStatus returned={} batchLimit={} afterSeq={}",
        rows.size(),
        batchLimit,
        afterSeq);
    return rows;
  }

  @Override
  public Optional<CiRunView> readCiView(String workflowRunPublicId) {
    List<CiRunView> views =
        jdbcTemplate.query(
            READ_CI_VIEW_SQL,
            params(workflowRunPublicId),
            (rs, rowNum) ->
                new CiRunView(
                    rs.getString("ci_status"),
                    rs.getString("ci_head_sha"),
                    rs.getInt("ci_fix_loop_count")));
    return views.isEmpty() ? Optional.empty() : Optional.of(views.get(0));
  }

  @Override
  public Optional<String> readCurrentState(String workflowRunPublicId) {
    List<String> states =
        jdbcTemplate.query(
            READ_CI_CURRENT_STATE_SQL,
            params(workflowRunPublicId),
            (rs, rowNum) -> rs.getString("current_state"));
    return states.isEmpty() ? Optional.empty() : Optional.ofNullable(states.get(0));
  }

  @Override
  public void acquireCiSweepLock() {
    // Joins the caller's (sweep) transaction so the lock is held for the whole phase-1 scan and
    // released on that transaction's commit. NOT @Transactional — a REQUIRES_NEW would release it
    // at
    // once.
    jdbcTemplate.queryForObject(
        ACQUIRE_CI_SWEEP_LOCK_SQL,
        new MapSqlParameterSource("lockKey", CI_SWEEP_ADVISORY_LOCK_KEY),
        Object.class);
    log.debug("ci-investigation SWEEP advisory lock acquired");
  }

  @Override
  public void lockRunForCiInvestigation(String workflowRunPublicId) {
    // Joins the caller's per-run REQUIRES_NEW transaction; released on that transaction's commit.
    jdbcTemplate.queryForObject(
        LOCK_RUN_FOR_CI_SQL,
        new MapSqlParameterSource()
            .addValue("classifier", CI_RUN_LOCK_CLASSIFIER)
            .addValue("runId", workflowRunPublicId),
        Object.class);
    log.debug(
        "ci-investigation per-run advisory lock acquired workflowRunId={}", workflowRunPublicId);
  }

  @Override
  public int markEscalationOnce(String workflowRunPublicId) {
    Integer flipped =
        jdbcTemplate.query(
            MARK_ESCALATION_SQL,
            params(workflowRunPublicId),
            rs -> rs.next() ? rs.getInt(1) : null);
    if (flipped == null && !workflowRunRepository.existsByPublicId(workflowRunPublicId)) {
      throw new DomainException(
          DomainErrorCode.RUN_NOT_FOUND,
          "Workflow run not found: " + workflowRunPublicId,
          Map.of("runId", workflowRunPublicId));
    }
    int rows = flipped == null ? 0 : 1;
    log.debug(
        "markEscalationOnce publicId={} rowsAffected={} (1=just-flipped, 0=already-set)",
        workflowRunPublicId,
        rows);
    return rows;
  }

  @Override
  public boolean isEscalationMarkerSet(String workflowRunPublicId) {
    return workflowRunRepository
        .findByPublicId(workflowRunPublicId)
        .map(WorkflowRunEntity::isEscalationMarkerSet)
        .orElse(false);
  }

  private static MapSqlParameterSource params(String workflowRunPublicId) {
    return new MapSqlParameterSource("publicId", workflowRunPublicId);
  }
}
