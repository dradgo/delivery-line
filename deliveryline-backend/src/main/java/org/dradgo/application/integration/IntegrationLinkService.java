package org.dradgo.application.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.integration.github.GitHubAdapter;
import org.dradgo.application.integration.github.GitHubAdapterException;
import org.dradgo.application.integration.github.GitHubBranch;
import org.dradgo.application.integration.github.GitHubPullRequest;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.NewIntegrationLink;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service that owns the lifecycle of {@code integration_links} rows for Linear-sourced
 * workflow runs (story 1.14 Task 5).
 *
 * <p>{@link #linkTicket(String, String, ActorContext, String)} is the single entry point for
 * creating a link. It reserves an {@code ilk_} id under an idempotency key, fetches the source
 * ticket through {@link TicketSourceAdapter}, takes a {@code SELECT … FOR UPDATE} on any existing
 * active link for the same {@code (linear, externalRef)}, and inserts a redacted row.
 *
 * <p>Only this service and the polling host bean may call {@link TicketSourceAdapter} directly —
 * CLI, REST, and persistence layers must call this service (mirrors the "only {@code RunnerBroker}
 * may call {@code RunnerAdapter.dispatch}" rule from story 1.13).
 */
@Service
public class IntegrationLinkService {

  private static final Logger log = LoggerFactory.getLogger(IntegrationLinkService.class);

  static final String LINEAR_INTEGRATION_TYPE = "linear";
  static final String GITHUB_PR_INTEGRATION_TYPE = "github_pr";
  static final String COMMAND_TYPE = "IntegrationLinkService.linkTicket";

  private final IntegrationLinkRecordPort integrationLinkRecordPort;
  private final TicketSourceAdapter linearAdapter;
  private final IdempotencyService idempotencyService;
  private final RedactionPolicyService redactionPolicyService;
  // Story 3.15 (Decision D3) — GitHubAdapter is @Profile(github-mock|github-real)-gated while this
  // service is an unconditional @Service; injecting it directly would red every @SpringBootTest
  // lacking the profile ([[unconditional-service-needs-profile-gate]]). Resolve getIfAvailable()
  // lazily at the github-only call site; absent at a real link attempt → a typed failure, never
  // NPE.
  private final ObjectProvider<GitHubAdapter> gitHubAdapterProvider;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final TransactionTemplate failureCompletionTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  public IntegrationLinkService(
      IntegrationLinkRecordPort integrationLinkRecordPort,
      TicketSourceAdapter linearAdapter,
      IdempotencyService idempotencyService,
      RedactionPolicyService redactionPolicyService,
      ObjectProvider<GitHubAdapter> gitHubAdapterProvider,
      WorkflowEventWritePort workflowEventWritePort,
      PlatformTransactionManager transactionManager) {
    this(
        integrationLinkRecordPort,
        linearAdapter,
        idempotencyService,
        redactionPolicyService,
        gitHubAdapterProvider,
        workflowEventWritePort,
        requiresNewTemplate(transactionManager));
  }

  public IntegrationLinkService(
      IntegrationLinkRecordPort integrationLinkRecordPort,
      TicketSourceAdapter linearAdapter,
      IdempotencyService idempotencyService,
      RedactionPolicyService redactionPolicyService,
      ObjectProvider<GitHubAdapter> gitHubAdapterProvider,
      WorkflowEventWritePort workflowEventWritePort,
      TransactionTemplate failureCompletionTemplate) {
    this.integrationLinkRecordPort =
        Objects.requireNonNull(integrationLinkRecordPort, "integrationLinkRecordPort");
    this.linearAdapter = Objects.requireNonNull(linearAdapter, "linearAdapter");
    this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
    this.gitHubAdapterProvider =
        Objects.requireNonNull(gitHubAdapterProvider, "gitHubAdapterProvider");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.failureCompletionTemplate =
        Objects.requireNonNull(failureCompletionTemplate, "failureCompletionTemplate");
  }

  /**
   * Reserve + insert (or replay) an {@code integration_links} row for a Linear ticket on a workflow
   * run. See class doc for invariants.
   */
  @Transactional
  public IntegrationLink linkTicket(
      String workflowRunPublicId,
      String linearTicketRef,
      ActorContext actor,
      String idempotencyKey) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    // Reject a blank ref up front — BEFORE the idempotency reservation — so a malformed input never
    // leaves a dangling RESERVED record (story 3.32 review). TicketRef.of(...) would otherwise
    // throw
    // only at the fetch call site, after checkAndReserve. Mirrors linkGitHubPr's non-blank guard.
    if (linearTicketRef == null || linearTicketRef.isBlank()) {
      throw new IllegalArgumentException("linearTicketReference must be non-blank");
    }
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");

    String priorCorrelationMdc = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, actor.correlationId());
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      String fingerprint =
          computeFingerprint(LINEAR_INTEGRATION_TYPE, linearTicketRef, workflowRunPublicId);
      log.info(
          "linkTicket entry workflowRunId={} actorIdentity={}",
          workflowRunPublicId,
          actor.actorIdentity());

      ReservationOutcome outcome =
          idempotencyService.checkAndReserve(
              idempotencyKey, COMMAND_TYPE, actor.actorIdentity(), fingerprint);

      if (outcome.decision() == IdempotencyService.ReservationDecision.REPLAY) {
        String priorRef = outcome.resultRef();
        if (priorRef == null) {
          // Prior attempt failed terminally (e.g., LINEAR_TICKET_NOT_FOUND with null resultRef
          // was completed). Replay the same failure rather than re-running the side effects.
          throw replayedTerminalFailure(idempotencyKey, linearTicketRef);
        }
        IntegrationLink existing =
            integrationLinkRecordPort
                .findByPublicId(priorRef)
                .orElseThrow(() -> replayedRecordMissing(idempotencyKey, priorRef));
        log.info("linkTicket replay resultRef={}", priorRef);
        return existing;
      }

      // RESERVED — proceed with the side-effecting body.
      Optional<IntegrationLink> activeLink =
          integrationLinkRecordPort.findActiveByTypeAndExternalRefForUpdate(
              LINEAR_INTEGRATION_TYPE, linearTicketRef);
      if (activeLink.isPresent()) {
        IntegrationLink existing = activeLink.get();
        if (existing.workflowRunPublicId().equals(workflowRunPublicId)) {
          completeInIndependentTransaction(
              idempotencyKey, existing.publicId(), IdempotencyRecordStatus.COMPLETED);
          log.info(
              "linkTicket idempotent_same_run workflowRunId={} existingPublicId={}",
              workflowRunPublicId,
              existing.publicId());
          return existing;
        }
        completeInIndependentTransaction(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
        log.warn(
            "linkTicket cross_run_conflict workflowRunId={} existingRunId={}",
            workflowRunPublicId,
            existing.workflowRunPublicId());
        throw crossRunConflict(linearTicketRef, existing);
      }

      String publicId = PublicIdPrefixes.INTEGRATION_LINK.next();
      Ticket ticket;
      try {
        Optional<Ticket> fetched =
            linearAdapter.fetchTicketByReference(TicketRef.of(linearTicketRef));
        if (fetched.isEmpty()) {
          completeInIndependentTransaction(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
          log.warn("linkTicket ticket_not_found workflowRunId={}", workflowRunPublicId);
          throw linearTicketNotFound(linearTicketRef);
        }
        ticket = fetched.get();
      } catch (TicketSourceAdapterException error) {
        completeInIndependentTransaction(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
        log.warn(
            "linkTicket adapter_failure workflowRunId={} category={}",
            workflowRunPublicId,
            error.failureCategory().value());
        throw adapterFailure(linearTicketRef, error);
      }

      Map<String, Object> rawMetadata = buildExternalMetadata(ticket);
      RedactionResult redacted =
          redactionPolicyService.redact(rawMetadata, DataClassification.SHAREABLE_REDACTED.value());
      byte[] metadataBytes = serializeRedactedMetadata(redacted.sanitizedJson());

      Instant now = Instant.now();
      IntegrationLink inserted =
          integrationLinkRecordPort.insert(
              new NewIntegrationLink(
                  publicId,
                  workflowRunPublicId,
                  LINEAR_INTEGRATION_TYPE,
                  linearTicketRef,
                  metadataBytes,
                  now,
                  now));
      idempotencyService.complete(
          idempotencyKey, inserted.publicId(), IdempotencyRecordStatus.COMPLETED);
      log.info(
          "linkTicket success workflowRunId={} integrationLinkPublicId={} effectiveClassification={}",
          workflowRunPublicId,
          inserted.publicId(),
          redacted.effectiveClassification().value());
      return inserted;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationMdc);
    }
  }

  private void completeInIndependentTransaction(
      String idempotencyKey, String resultRef, IdempotencyRecordStatus status) {
    failureCompletionTemplate.execute(
        ignored -> {
          idempotencyService.complete(idempotencyKey, resultRef, status);
          return null;
        });
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    Objects.requireNonNull(transactionManager, "transactionManager");
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  /**
   * Variant of {@link #linkTicket} that is invoked from within an already-open transaction (e.g.
   * {@code WorkflowCommandService.submit}) where the parent's idempotency layer already guarantees
   * exactly-once execution. Skips {@link IdempotencyService} entirely — the parent transaction owns
   * commit/rollback. Performs fetch, conflict detection (with pessimistic lock), redaction, and
   * insert in the caller's transaction.
   *
   * <p>Throws {@code LINEAR_TICKET_NOT_FOUND} when the adapter returns empty for the supplied ref,
   * {@code INTEGRATION_LINK_CONFLICT} when another active row exists for the same ticket but a
   * different run, and propagates {@link TicketSourceAdapterException} as a typed {@code
   * INTEGRATION_LINK_CONFLICT} carrying {@code failureCategory}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public IntegrationLink linkTicketWithinTransaction(
      String workflowRunPublicId, String linearTicketRef, ActorContext actor) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    // Up-front non-blank guard (story 3.32 review) — fail fast before the pessimistic-lock port
    // lookup / TicketRef.of(...), mirroring linkTicket + linkGitHubPr.
    if (linearTicketRef == null || linearTicketRef.isBlank()) {
      throw new IllegalArgumentException("linearTicketReference must be non-blank");
    }
    Objects.requireNonNull(actor, "actor");
    log.info(
        "linkTicketWithinTransaction entry workflowRunId={} actorIdentity={}",
        workflowRunPublicId,
        actor.actorIdentity());

    Optional<IntegrationLink> activeLink =
        integrationLinkRecordPort.findActiveByTypeAndExternalRefForUpdate(
            LINEAR_INTEGRATION_TYPE, linearTicketRef);
    if (activeLink.isPresent()) {
      IntegrationLink existing = activeLink.get();
      if (existing.workflowRunPublicId().equals(workflowRunPublicId)) {
        log.info(
            "linkTicketWithinTransaction idempotent_same_run workflowRunId={} existingPublicId={}",
            workflowRunPublicId,
            existing.publicId());
        return existing;
      }
      log.warn(
          "linkTicketWithinTransaction cross_run_conflict workflowRunId={} existingRunId={}",
          workflowRunPublicId,
          existing.workflowRunPublicId());
      throw crossRunConflict(linearTicketRef, existing);
    }

    String publicId = PublicIdPrefixes.INTEGRATION_LINK.next();
    Ticket ticket;
    try {
      Optional<Ticket> fetched =
          linearAdapter.fetchTicketByReference(TicketRef.of(linearTicketRef));
      if (fetched.isEmpty()) {
        log.warn(
            "linkTicketWithinTransaction ticket_not_found workflowRunId={}", workflowRunPublicId);
        throw linearTicketNotFound(linearTicketRef);
      }
      ticket = fetched.get();
    } catch (TicketSourceAdapterException error) {
      log.warn(
          "linkTicketWithinTransaction adapter_failure workflowRunId={} category={}",
          workflowRunPublicId,
          error.failureCategory().value());
      throw adapterFailure(linearTicketRef, error);
    }

    Map<String, Object> rawMetadata = buildExternalMetadata(ticket);
    RedactionResult redacted =
        redactionPolicyService.redact(rawMetadata, DataClassification.SHAREABLE_REDACTED.value());
    byte[] metadataBytes = serializeRedactedMetadata(redacted.sanitizedJson());

    Instant now = Instant.now();
    IntegrationLink inserted =
        integrationLinkRecordPort.insert(
            new NewIntegrationLink(
                publicId,
                workflowRunPublicId,
                LINEAR_INTEGRATION_TYPE,
                linearTicketRef,
                metadataBytes,
                now,
                now));
    log.info(
        "linkTicketWithinTransaction success workflowRunId={} integrationLinkPublicId={} effectiveClassification={}",
        workflowRunPublicId,
        inserted.publicId(),
        redacted.effectiveClassification().value());
    return inserted;
  }

  /**
   * Reserve + insert (or replay/supersede) an {@code integration_links} {@code github_pr} row for a
   * GitHub pull request on a workflow run (story 3.15 Task 1). The GitHub twin of {@link
   * #linkTicketWithinTransaction}: same lock → fetch → compat → conflict → redact → insert spine,
   * plus the AC9 supersede branch and an AC2 {@code integration.linked} event.
   *
   * <p>{@code prReference} MUST be the canonical GitHubAdapter ref ({@code owner/repo#number}) —
   * the authoritative {@code RepositoryPushOutcome.prRef()}, never the untrusted runner-reported
   * {@code PR-<n>} (Trap T1 / [[proutput-prref-validator-rejects-real-adapter]]). PR metadata
   * ({@code prNumber}/{@code prState}/{@code prUrl}/{@code repositoryFullName}) is resolved via
   * {@link GitHubAdapter#getPullRequestByRef(String)} (Decision D2), which also verifies existence
   * ({@code GITHUB_PR_NOT_FOUND}).
   *
   * <p>Transactional semantics (AC2): compat-check → cross-run conflict →
   * supersede-prior-different- PR → insert → append {@code integration.linked} — all in the
   * caller's/own transaction.
   *
   * @param idempotencyKey accepted for signature fidelity / a future direct caller (OQ-1); the
   *     broker-originated path relies on the same-run/same-ref no-op plus the V6 unique index, not
   *     {@link IdempotencyService}.
   */
  @Transactional
  public IntegrationLink linkGitHubPr(
      String workflowRunPublicId,
      String prReference,
      String repositoryRef,
      String branchName,
      String commitSha,
      ActorContext actor,
      String idempotencyKey) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    if (prReference == null || prReference.isBlank()) {
      throw new IllegalArgumentException("prReference must be non-blank");
    }
    Objects.requireNonNull(actor, "actor");

    String priorCorrelationMdc = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, actor.correlationId());
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "linkGitHubPr entry workflowRunId={} actorIdentity={} externalRef={}",
          workflowRunPublicId,
          actor.actorIdentity(),
          MdcKeys.sanitizeForLog(prReference));

      GitHubPullRequest pr = resolvePullRequest(workflowRunPublicId, prReference, "linkGitHubPr");

      // AC4 — defense-in-depth: the PR's repository must match the run's established repository
      // linkage (story 3.9 verifyRepositoryIsConsistent already blocks the workspace from being
      // prepared against a conflicting repo, so this can only fire on a code-path bug).
      if (repositoryRef != null
          && !repositoryRef.isBlank()
          && !repositoryRef.equals(pr.repoRef())) {
        log.warn(
            "linkGitHubPr repo_mismatch workflowRunId={} runRepoRef={} prRepoRef={}",
            workflowRunPublicId,
            MdcKeys.sanitizeForLog(repositoryRef),
            MdcKeys.sanitizeForLog(pr.repoRef()));
        throw repoMismatch(workflowRunPublicId, repositoryRef, pr.repoRef());
      }

      // AC2 — cross-run conflict (pessimistic lock + the V6 DB backstop). Same ref + same run is an
      // idempotent replay (AC9 same-PR no-op); same ref + different run is a hard conflict.
      Optional<IntegrationLink> activeByRef =
          integrationLinkRecordPort.findActiveByTypeAndExternalRefForUpdate(
              GITHUB_PR_INTEGRATION_TYPE, prReference);
      if (activeByRef.isPresent()) {
        IntegrationLink existing = activeByRef.get();
        if (!existing.workflowRunPublicId().equals(workflowRunPublicId)) {
          log.warn(
              "linkGitHubPr cross_run_conflict workflowRunId={} existingRunId={}",
              workflowRunPublicId,
              existing.workflowRunPublicId());
          throw crossRunGitHubConflict(prReference, existing);
        }
        log.warn(
            "linkGitHubPr idempotent_no_op workflowRunId={} existingPublicId={}",
            workflowRunPublicId,
            existing.publicId());
        return existing;
      }

      // AC9 — supersede a prior github_pr link for THIS run that carries a DIFFERENT PR reference
      // (the same-ref case already returned above). LINKED→SUPERSEDED is legal as of story 3.15
      // (Trap T2).
      Optional<IntegrationLink> priorForRun =
          integrationLinkRecordPort.findActiveByTypeAndWorkflowRunForUpdate(
              GITHUB_PR_INTEGRATION_TYPE, workflowRunPublicId);
      if (priorForRun.isPresent() && !priorForRun.get().externalRef().equals(prReference)) {
        IntegrationLink prior = priorForRun.get();
        integrationLinkRecordPort.updateSyncStatus(
            prior.publicId(), IntegrationSyncStatus.SUPERSEDED, null);
        log.info(
            "linkGitHubPr superseded_prior workflowRunId={} priorPublicId={}",
            workflowRunPublicId,
            prior.publicId());
      }

      String publicId = PublicIdPrefixes.INTEGRATION_LINK.next();
      Map<String, Object> rawMetadata = buildGitHubExternalMetadata(pr, branchName, commitSha);
      RedactionResult redacted =
          redactionPolicyService.redact(rawMetadata, DataClassification.SHAREABLE_REDACTED.value());
      byte[] metadataBytes = serializeRedactedMetadata(redacted.sanitizedJson());

      Instant now = Instant.now();
      IntegrationLink inserted =
          integrationLinkRecordPort.insert(
              new NewIntegrationLink(
                  publicId,
                  workflowRunPublicId,
                  GITHUB_PR_INTEGRATION_TYPE,
                  prReference,
                  metadataBytes,
                  now,
                  now));

      appendIntegrationLinkedEvent(
          workflowRunPublicId, inserted, pr, branchName, commitSha, actor, now);

      log.info(
          "linkGitHubPr success workflowRunId={} integrationLinkPublicId={} externalRef={} "
              + "prNumber={} prState={} effectiveClassification={}",
          workflowRunPublicId,
          inserted.publicId(),
          MdcKeys.sanitizeForLog(prReference),
          pr.number(),
          MdcKeys.sanitizeForLog(pr.state()),
          redacted.effectiveClassification().value());
      return inserted;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationMdc);
    }
  }

  /**
   * Refresh the active {@code github_pr} link for a run: re-query the PR via {@link
   * GitHubAdapter#getPullRequestByRef(String)}, rebuild + re-redact {@code external_metadata} with
   * the fresh {@code prState}, and persist alongside the {@code → synced} transition and a
   * refreshed {@code last_sync_at} (story 3.15 AC6). A vanished PR or a classified adapter failure
   * routes the link to {@code failed} via {@link #markFailed} (Trap T13) rather than throwing — the
   * caller (orchestration verifying a PR is mergeable before {@code Completed}) treats {@code
   * failed} as a non-mergeable signal.
   */
  @Transactional
  public IntegrationLink syncGitHubPr(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      IntegrationLink active =
          integrationLinkRecordPort
              .findActiveByTypeAndWorkflowRunForUpdate(
                  GITHUB_PR_INTEGRATION_TYPE, workflowRunPublicId)
              .orElseThrow(() -> noActiveGitHubLink(workflowRunPublicId));
      log.info(
          "syncGitHubPr entry workflowRunId={} integrationLinkPublicId={} externalRef={}",
          workflowRunPublicId,
          active.publicId(),
          MdcKeys.sanitizeForLog(active.externalRef()));

      GitHubAdapter adapter = gitHubAdapterProvider.getIfAvailable();
      if (adapter == null) {
        throw gitHubAdapterUnavailable(active.externalRef());
      }
      GitHubPullRequest pr;
      try {
        Optional<GitHubPullRequest> fetched = adapter.getPullRequestByRef(active.externalRef());
        if (fetched.isEmpty()) {
          log.warn(
              "syncGitHubPr pr_not_found workflowRunId={} integrationLinkPublicId={}",
              workflowRunPublicId,
              active.publicId());
          return markFailed(active.publicId(), IntegrationFailureCategory.GITHUB_PR_NOT_FOUND);
        }
        pr = fetched.get();
      } catch (GitHubAdapterException error) {
        log.warn(
            "syncGitHubPr adapter_failure workflowRunId={} integrationLinkPublicId={} category={}",
            workflowRunPublicId,
            active.publicId(),
            error.failureCategory().value());
        return markFailed(active.publicId(), error.failureCategory());
      }

      // A prior sync may have routed the link to FAILED on a transient adapter failure (rate-limit
      // /
      // 5xx). The fetch just succeeded, so recover FAILED → LINKED (the state machine's recovery
      // edge) before the → SYNCED write — otherwise updateExternalMetadataAndSync would throw
      // ILLEGAL_TRANSITION (FAILED → SYNCED is not allowed) and the link could never re-sync.
      if (active.syncStatus() == IntegrationSyncStatus.FAILED) {
        log.info(
            "syncGitHubPr recover_from_failed workflowRunId={} integrationLinkPublicId={}",
            workflowRunPublicId,
            active.publicId());
        integrationLinkRecordPort.updateSyncStatus(
            active.publicId(), IntegrationSyncStatus.LINKED, null);
      }

      String refreshedCommitSha = resolveHeadSha(adapter, pr);
      Map<String, Object> rawMetadata =
          buildGitHubExternalMetadata(pr, pr.sourceBranch(), refreshedCommitSha);
      RedactionResult redacted =
          redactionPolicyService.redact(rawMetadata, DataClassification.SHAREABLE_REDACTED.value());
      byte[] metadataBytes = serializeRedactedMetadata(redacted.sanitizedJson());

      IntegrationLink updated =
          integrationLinkRecordPort.updateExternalMetadataAndSync(
              active.publicId(), metadataBytes, IntegrationSyncStatus.SYNCED, Instant.now());
      log.info(
          "syncGitHubPr success workflowRunId={} integrationLinkPublicId={} externalRef={} "
              + "prState={}",
          workflowRunPublicId,
          updated.publicId(),
          MdcKeys.sanitizeForLog(active.externalRef()),
          MdcKeys.sanitizeForLog(pr.state()));
      return updated;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Reusable guard (story 3.15 AC5): assert an artifact's PR reference matches the active {@code
   * github_pr} link's canonical {@code external_ref} for the run — preventing approval of a {@code
   * prOutput} artifact whose PR reference has drifted (NFR19). Raises {@code
   * ARTIFACT_PR_LINK_MISMATCH} on mismatch. The comparison is <strong>format-exact</strong> against
   * the canonical {@code owner/repo#number} (no normalization): when the artifact still carries a
   * runner-reported {@code PR-<n>} or URL form (3.12 enrichment is best-effort and may have been
   * swallowed) the guard cannot prove equivalence and <strong>fails closed</strong> (OQ-2). The
   * production approval call-site is wired in story 3.20 ({@code acceptImplementation} does not yet
   * exist).
   */
  public void assertArtifactPrLinkMatches(String workflowRunPublicId, String artifactPrReference) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    if (artifactPrReference == null || artifactPrReference.isBlank()) {
      throw new IllegalArgumentException("artifactPrReference must be non-blank");
    }
    Optional<IntegrationLink> activeOpt =
        integrationLinkRecordPort.findActiveByTypeAndWorkflowRunForUpdate(
            GITHUB_PR_INTEGRATION_TYPE, workflowRunPublicId);
    if (activeOpt.isEmpty()) {
      // Fail closed (OQ-2): no durable linkage to prove the artifact's PR ref against.
      log.warn(
          "assertArtifactPrLinkMatches no_active_link workflowRunId={} artifactPrReference={}",
          workflowRunPublicId,
          MdcKeys.sanitizeForLog(artifactPrReference));
      throw artifactPrLinkMismatch(workflowRunPublicId, null, artifactPrReference);
    }
    IntegrationLink active = activeOpt.get();
    String canonicalLink = active.externalRef();
    // artifactPrReference is already validated non-blank above; compare from it so a (defensively
    // unexpected) null externalRef fails closed with the typed mismatch instead of an NPE.
    boolean matches = artifactPrReference.equals(canonicalLink);
    if (!matches) {
      log.warn(
          "assertArtifactPrLinkMatches mismatch workflowRunId={} linkExternalRef={} "
              + "artifactPrReference={}",
          workflowRunPublicId,
          MdcKeys.sanitizeForLog(canonicalLink),
          MdcKeys.sanitizeForLog(artifactPrReference));
      throw artifactPrLinkMismatch(workflowRunPublicId, canonicalLink, artifactPrReference);
    }
    log.info(
        "assertArtifactPrLinkMatches ok workflowRunId={} integrationLinkPublicId={}",
        workflowRunPublicId,
        active.publicId());
  }

  /**
   * Look up the currently-active integration link for an external reference. Active = {@code
   * archived_at IS NULL AND sync_status != 'superseded'}.
   */
  public Optional<IntegrationLink> findActiveLink(String integrationType, String externalRef) {
    return integrationLinkRecordPort.findActiveByTypeAndExternalRef(integrationType, externalRef);
  }

  /** Look up the currently-active integration link for a workflow run. */
  public Optional<IntegrationLink> findActiveLinkByWorkflowRun(String workflowRunPublicId) {
    return integrationLinkRecordPort.findActiveByWorkflowRun(workflowRunPublicId);
  }

  /**
   * Story 3.16 (AC3a) — the active {@code linear} ticket reference for a run, or empty when no
   * Linear ticket is linked. Resolved by the typed {@code (linear, run)} lookup rather than {@link
   * #findActiveLinkByWorkflowRun} (which returns whichever active link sorts first and so cannot
   * disambiguate a run that carries BOTH a {@code linear} and a {@code github_pr} link). Used by
   * completion-sync to find the ticket to post back to; absent ⇒ no Linear target ⇒ the sync
   * no-ops.
   */
  public Optional<IntegrationLink> findActiveLinearTicketLink(String workflowRunPublicId) {
    return integrationLinkRecordPort.findActiveByTypeAndWorkflowRunForUpdate(
        LINEAR_INTEGRATION_TYPE, workflowRunPublicId);
  }

  /**
   * Story 3.16 (AC3b) — the active {@code github_pr} link for a run, or empty when none (3.15's
   * broker linkage is best-effort, so a run can reach completion without a {@code github_pr} row).
   * Used by completion-sync to resolve the {@code {prUrl}} placeholder defensively. Typed by {@code
   * (github_pr, run)} — never the Linear variant.
   */
  public Optional<IntegrationLink> findActiveGitHubPrLink(String workflowRunPublicId) {
    return integrationLinkRecordPort.findActiveByTypeAndWorkflowRunForUpdate(
        GITHUB_PR_INTEGRATION_TYPE, workflowRunPublicId);
  }

  /**
   * Story 3b-5 — the active {@code github_pr} link's PR reference + state for the artifact-read
   * projection (prOutput review panel). NON-locking (a read path must not take the {@code
   * PESSIMISTIC_WRITE} lock {@link #findActiveGitHubPrLink} holds) and typed to {@code github_pr}
   * (never the Linear variant). {@code prState} is parsed from the link's {@code external_metadata}
   * (the {@code prState} key written by {@code buildGitHubExternalMetadata}); empty/malformed
   * metadata yields a {@code null} state. Empty Optional when the run has no active {@code github_pr}
   * link (⇒ the panel renders "No linked pull request").
   */
  @Transactional(readOnly = true)
  public Optional<GitHubPrLinkView> findActiveGitHubPrLinkView(String workflowRunPublicId) {
    return integrationLinkRecordPort
        .findActiveTicketSummaryByTypeAndWorkflowRun(
            GITHUB_PR_INTEGRATION_TYPE, workflowRunPublicId)
        .map(
            projection ->
                new GitHubPrLinkView(
                    projection.externalRef(), extractPrState(projection.externalMetadata())));
  }

  private String extractPrState(byte[] externalMetadata) {
    if (externalMetadata == null || externalMetadata.length == 0) {
      return null;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode state =
          objectMapper.readTree(externalMetadata).path("prState");
      return (state.isTextual() && !state.asText().isBlank()) ? state.asText() : null;
    } catch (java.io.IOException error) {
      log.warn("findActiveGitHubPrLinkView prState parse failed (treating as null)");
      return null;
    }
  }

  /**
   * Story 3b-5 — the github_pr link's externally-authoritative PR reference + state for the
   * artifact-read wire. {@code prState} is {@code null} when the link's metadata omits it.
   */
  public record GitHubPrLinkView(String prReference, String prState) {}

  /** Transition {@code sync_status} {@code linked → synced} and refresh {@code last_sync_at}. */
  @Transactional
  public IntegrationLink markSynced(String integrationLinkPublicId, Instant syncedAt) {
    Objects.requireNonNull(syncedAt, "syncedAt");
    return integrationLinkRecordPort.updateSyncStatus(
        integrationLinkPublicId, IntegrationSyncStatus.SYNCED, syncedAt);
  }

  /**
   * Transition the link to {@code stale}. Used by the polling loop when freshness thresholds are
   * exceeded (AC9).
   */
  @Transactional
  public IntegrationLink markStale(String integrationLinkPublicId) {
    return integrationLinkRecordPort.updateSyncStatus(
        integrationLinkPublicId, IntegrationSyncStatus.STALE, null);
  }

  /**
   * Transition the link to {@code failed} and emit a structured log line carrying the {@link
   * IntegrationFailureCategory}.
   */
  @Transactional
  public IntegrationLink markFailed(
      String integrationLinkPublicId, IntegrationFailureCategory category) {
    Objects.requireNonNull(category, "category");
    log.warn(
        "integration_link mark_failed publicId={} category={}",
        integrationLinkPublicId,
        category.value());
    return integrationLinkRecordPort.updateSyncStatus(
        integrationLinkPublicId, IntegrationSyncStatus.FAILED, null);
  }

  static String computeFingerprint(
      String integrationType, String externalRef, String workflowRunPublicId) {
    String canonical = integrationType + "|" + externalRef + "|" + workflowRunPublicId;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      // SHA-256 is mandatory in every Java distribution; defensive only.
      throw new IllegalStateException("SHA-256 not available", error);
    }
  }

  private static Map<String, Object> buildExternalMetadata(Ticket ticket) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("title", ticket.title());
    metadata.put("summary", ticket.summary());
    metadata.put("authorIdentity", ticket.authorIdentity());
    metadata.put("labels", ticket.labels());
    metadata.put("ticketCreatedAt", ticket.createdAt().toString());
    metadata.put("ticketUpdatedAt", ticket.updatedAt().toString());
    return metadata;
  }

  private byte[] serializeRedactedMetadata(JsonNode sanitizedJson) {
    if (sanitizedJson == null) {
      return "{}".getBytes(StandardCharsets.UTF_8);
    }
    try {
      return objectMapper.writeValueAsBytes(sanitizedJson);
    } catch (IOException error) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "redacted_metadata_serialization_failed");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Failed to serialize redacted external_metadata for integration_links",
          details);
    }
  }

  private static DomainException linearTicketNotFound(String externalRef) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("integrationType", LINEAR_INTEGRATION_TYPE);
    details.put("externalRef", externalRef);
    return new DomainException(
        DomainErrorCode.LINEAR_TICKET_NOT_FOUND,
        "Linear ticket not found: " + externalRef,
        details);
  }

  private static DomainException crossRunConflict(String externalRef, IntegrationLink existing) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("externalRef", externalRef);
    details.put("existingIntegrationLinkPublicId", existing.publicId());
    details.put("existingRunPublicId", existing.workflowRunPublicId());
    details.put("reason", "cross_run_active_linear_link_exists");
    return new DomainException(
        DomainErrorCode.INTEGRATION_LINK_CONFLICT,
        "Linear ticket "
            + externalRef
            + " is already linked to run "
            + existing.workflowRunPublicId(),
        details);
  }

  private static DomainException adapterFailure(
      String externalRef, TicketSourceAdapterException cause) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("externalRef", externalRef);
    details.put("failureCategory", cause.failureCategory().value());
    return new DomainException(
        DomainErrorCode.INTEGRATION_LINK_CONFLICT,
        "Linear adapter failure during linkTicket: " + cause.getMessage(),
        details);
  }

  private static DomainException replayedTerminalFailure(
      String idempotencyKey, String externalRef) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", idempotencyKey);
    details.put("externalRef", externalRef);
    details.put("reason", "prior_attempt_failed_terminally");
    return new DomainException(
        DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        "Prior linkTicket attempt for this key failed terminally; submit with a fresh idempotency key",
        details);
  }

  // =====================================================================
  // GitHub PR linkage helpers (story 3.15)
  // =====================================================================

  private GitHubPullRequest resolvePullRequest(String runId, String prReference, String op) {
    GitHubAdapter adapter = gitHubAdapterProvider.getIfAvailable();
    if (adapter == null) {
      throw gitHubAdapterUnavailable(prReference);
    }
    try {
      Optional<GitHubPullRequest> fetched = adapter.getPullRequestByRef(prReference);
      if (fetched.isEmpty()) {
        log.warn("{} pr_not_found workflowRunId={}", op, runId);
        throw gitHubPrNotFound(prReference);
      }
      return fetched.get();
    } catch (GitHubAdapterException error) {
      log.warn(
          "{} adapter_failure workflowRunId={} category={}",
          op,
          runId,
          error.failureCategory().value());
      throw gitHubAdapterFailure(prReference, error);
    }
  }

  private static String resolveHeadSha(GitHubAdapter adapter, GitHubPullRequest pr) {
    try {
      return adapter
          .getBranchByRef(pr.repoRef(), pr.sourceBranch())
          .map(GitHubBranch::headSha)
          .orElse(null);
    } catch (GitHubAdapterException error) {
      // Best-effort commitSha refresh; the prState refresh is the AC6 contract, not the SHA.
      return null;
    }
  }

  private static Map<String, Object> buildGitHubExternalMetadata(
      GitHubPullRequest pr, String branchName, String commitSha) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("repositoryFullName", pr.repoRef());
    metadata.put(
        "branch", (branchName == null || branchName.isBlank()) ? pr.sourceBranch() : branchName);
    // Omit (rather than store JSON null) when absent — mirrors appendIntegrationLinkedEvent's
    // guard.
    // commitSha is the only nullable field: it rides RepositoryPushOutcome.commitSha at link time
    // and resolveHeadSha (best-effort) at sync time, so a failed refresh must not overwrite a
    // previously-good SHA with null in the persisted reconstruction metadata (NFR17).
    if (commitSha != null && !commitSha.isBlank()) {
      metadata.put("commitSha", commitSha);
    }
    metadata.put("prNumber", pr.number());
    metadata.put("prState", pr.state());
    metadata.put("prUrl", pr.url());
    return metadata;
  }

  private void appendIntegrationLinkedEvent(
      String runId,
      IntegrationLink link,
      GitHubPullRequest pr,
      String branchName,
      String commitSha,
      ActorContext actor,
      Instant at) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.GITHUB_PR_REFERENCE, link.externalRef());
    details.put(WorkflowEventDetailKeys.REPOSITORY_FULL_NAME, pr.repoRef());
    details.put(
        WorkflowEventDetailKeys.BRANCH,
        (branchName == null || branchName.isBlank()) ? pr.sourceBranch() : branchName);
    if (commitSha != null && !commitSha.isBlank()) {
      details.put(WorkflowEventDetailKeys.COMMIT_SHA, commitSha);
    }
    details.put(WorkflowEventDetailKeys.PR_NUMBER, pr.number());
    details.put(WorkflowEventDetailKeys.PR_STATE, pr.state());
    if (actor.correlationId() != null && !actor.correlationId().isBlank()) {
      details.put(WorkflowEventDetailKeys.CORRELATION_ID, actor.correlationId());
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            runId,
            WorkflowEventType.INTEGRATION_LINKED,
            null,
            null,
            actor.actorIdentity(),
            actor.actorType(),
            "integration_linked",
            null,
            false,
            OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
            details));
  }

  private static DomainException crossRunGitHubConflict(
      String prReference, IntegrationLink existing) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("integrationType", GITHUB_PR_INTEGRATION_TYPE);
    details.put("externalRef", prReference);
    details.put("existingIntegrationLinkPublicId", existing.publicId());
    details.put("existingRunPublicId", existing.workflowRunPublicId());
    details.put("reason", "cross_run_active_github_pr_link_exists");
    return new DomainException(
        DomainErrorCode.INTEGRATION_LINK_CONFLICT,
        "GitHub PR " + prReference + " is already linked to run " + existing.workflowRunPublicId(),
        details);
  }

  private static DomainException repoMismatch(String runId, String runRepoRef, String prRepoRef) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("workflowRunId", runId);
    details.put("runRepositoryRef", runRepoRef);
    details.put("prRepositoryRef", prRepoRef);
    details.put("reason", "github_pr_repo_does_not_match_run_repository");
    return new DomainException(
        DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH,
        "GitHub PR repository " + prRepoRef + " does not match the run's repository " + runRepoRef,
        details);
  }

  private static DomainException gitHubPrNotFound(String prReference) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("integrationType", GITHUB_PR_INTEGRATION_TYPE);
    details.put("externalRef", prReference);
    details.put("failureCategory", IntegrationFailureCategory.GITHUB_PR_NOT_FOUND.value());
    details.put("reason", "github_pr_not_found");
    return new DomainException(
        DomainErrorCode.INTEGRATION_LINK_CONFLICT, "GitHub PR not found: " + prReference, details);
  }

  private static DomainException gitHubAdapterUnavailable(String prReference) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("integrationType", GITHUB_PR_INTEGRATION_TYPE);
    details.put("externalRef", prReference);
    details.put("reason", "github_adapter_unavailable");
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "GitHub adapter is not available (no github profile active) for " + prReference,
        details);
  }

  private static DomainException gitHubAdapterFailure(
      String prReference, GitHubAdapterException cause) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("integrationType", GITHUB_PR_INTEGRATION_TYPE);
    details.put("externalRef", prReference);
    details.put("failureCategory", cause.failureCategory().value());
    return new DomainException(
        DomainErrorCode.INTEGRATION_LINK_CONFLICT,
        "GitHub adapter failure during linkGitHubPr: " + cause.getMessage(),
        details);
  }

  private static DomainException noActiveGitHubLink(String runId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("workflowRunId", runId);
    details.put("integrationType", GITHUB_PR_INTEGRATION_TYPE);
    details.put("reason", "no_active_github_pr_link");
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "No active github_pr integration link for run " + runId,
        details);
  }

  private static DomainException artifactPrLinkMismatch(
      String runId, String linkExternalRef, String artifactPrReference) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("workflowRunId", runId);
    if (linkExternalRef != null) {
      details.put("linkExternalRef", linkExternalRef);
    }
    details.put("artifactPrReference", artifactPrReference);
    details.put(
        "reason",
        linkExternalRef == null ? "no_active_github_pr_link" : "artifact_pr_reference_drifted");
    return new DomainException(
        DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH,
        "Artifact PR reference "
            + artifactPrReference
            + " does not match the linked PR for run "
            + runId,
        details);
  }

  private static DomainException replayedRecordMissing(String idempotencyKey, String publicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", idempotencyKey);
    details.put("integrationLinkPublicId", publicId);
    details.put("reason", "integration_link_record_missing");
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "Idempotency replay references a missing integration_link row: " + publicId,
        details);
  }
}
