package org.dradgo.adapters.integration.repohost.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.CiCheck;
import org.dradgo.domain.integration.repohost.CiConclusion;
import org.dradgo.domain.integration.repohost.CiStatus;
import org.dradgo.domain.integration.repohost.CommentResult;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Production {@link RepositoryHostAdapter} (GitHub kind) backed by the GitHub REST API v3 (story
 * 3.14). Activated under Spring profile {@code github-real} (opt-in only; never in any default
 * profile group, mirroring {@code linear-real}). GitHub twin of {@code LinearRealAdapter};
 * extracted to the vendor-neutral port by story 3.33.
 *
 * <p><strong>Reference formats accepted.</strong> A {@code repoRef} is {@code "owner/repo"}; a
 * {@code prRef} is {@code "owner/repo#number"}. Parsing is private to the adapter — refs are opaque
 * {@link RepositoryRef}/{@link PullRequestRef} tokens on the {@link RepositoryHostAdapter} port.
 *
 * <p><strong>Authentication (AC2).</strong> The PAT is read from {@code GITHUB_TOKEN} via {@link
 * GitHubProperties} and set as {@code Authorization: Bearer <token>} at request time inside the
 * {@code gitHubRestClient} interceptor ({@code GitHubConfiguration}) — never logged, never embedded
 * in a URL, never persisted.
 *
 * <p><strong>Redaction-on-egress (AC3/AC6, Decision D3).</strong> Every write method passes its
 * {@code title}/{@code body} through {@link RedactionPolicyService} (classification {@code
 * shareable-redacted}) before the request is sent — redact-and-send, never refuse.
 *
 * <p><strong>Idempotent PR creation (AC4).</strong> {@link #createPullRequest} first searches for
 * an open PR for the same {@code (owner:branch, base)} and returns it instead of stacking a
 * duplicate. The base is the supplied {@code targetBranch} (story 3.33 OQ-2); a blank target falls
 * back to the repository default branch (legacy behavior preserved byte-for-byte).
 *
 * <p><strong>Rate-limit awareness (AC5).</strong> {@code X-RateLimit-Remaining}/{@code
 * X-RateLimit-Reset} are inspected on every response: WARN below {@code rate-limit-warn-threshold},
 * raise {@link IntegrationFailureCategory#GITHUB_RATE_LIMITED} (carrying {@code resetAtSeconds})
 * when exhausted. Classification only — retry/pause orchestration is a separate concern.
 *
 * <p><strong>Failure classification (AC7).</strong> HTTP outcomes map to the existing GitHub {@link
 * IntegrationFailureCategory} values via {@link #classify}; everything throws {@link
 * RepositoryHostAdapterException}, never a generic unclassified error. This adapter does NOT retry.
 */
@Component
@Primary
@Profile("github-real")
public class GitHubRealAdapter implements RepositoryHostAdapter {

  private static final Logger log = LoggerFactory.getLogger(GitHubRealAdapter.class);

  private static final String REDACTION_CLASSIFICATION =
      DataClassification.SHAREABLE_REDACTED.value();

  private static final String HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
  private static final String HEADER_RATE_LIMIT_RESET = "X-RateLimit-Reset";
  private static final long RATE_LIMIT_RESET_UNKNOWN = -1L;

  /** {@code owner/repo} — owner and repo are GitHub name segments (no slash, no {@code #}). */
  private static final Pattern REPO_REF_PATTERN =
      Pattern.compile("^([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}[A-Za-z0-9])?)/([A-Za-z0-9._-]+)$");

  /** {@code owner/repo#number}. */
  private static final Pattern PR_REF_PATTERN =
      Pattern.compile(
          "^([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}[A-Za-z0-9])?)/([A-Za-z0-9._-]+)#([0-9]+)$");

  private final RestClient gitHubRestClient;
  private final GitHubProperties properties;
  private final RedactionPolicyService redactionPolicyService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public GitHubRealAdapter(
      @Qualifier("gitHubRestClient") RestClient gitHubRestClient,
      GitHubProperties properties,
      RedactionPolicyService redactionPolicyService) {
    this.gitHubRestClient = Objects.requireNonNull(gitHubRestClient, "gitHubRestClient");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
  }

  // ---------------------------------------------------------------------------------------------
  // Read methods (AC3) — genuine absence is Optional.empty() (404); other failures classify.
  // ---------------------------------------------------------------------------------------------

  @Override
  public Optional<Repository> getRepositoryByRef(RepositoryRef ref) {
    Objects.requireNonNull(ref, "ref");
    String repoRef = ref.value();
    ParsedRepoRef repo = parseRepoRef(repoRef);
    long startedAt = System.nanoTime();
    Optional<JsonNode> body =
        getOrEmptyOnNotFound(
            repoUri(repo), "getRepositoryByRef", IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND);
    if (body.isEmpty()) {
      log.info("github_real get_repository repoRef={} resolution=not_found", repoRef);
      return Optional.empty();
    }
    log.info(
        "github_real get_repository repoRef={} resolution=hit durationMs={}",
        repoRef,
        elapsedMs(startedAt));
    return Optional.of(toRepository(repoRef, body.get()));
  }

  @Override
  public Optional<PullRequest> getPullRequestByRef(PullRequestRef ref) {
    Objects.requireNonNull(ref, "ref");
    String prRef = ref.value();
    ParsedPrRef pr = parsePrRef(prRef);
    long startedAt = System.nanoTime();
    Optional<JsonNode> body =
        getOrEmptyOnNotFound(
            repoUri(pr, "pulls", String.valueOf(pr.number())),
            "getPullRequestByRef",
            IntegrationFailureCategory.GITHUB_PR_NOT_FOUND);
    if (body.isEmpty()) {
      log.info("github_real get_pull_request prRef={} resolution=not_found", prRef);
      return Optional.empty();
    }
    log.info(
        "github_real get_pull_request prRef={} resolution=hit durationMs={}",
        prRef,
        elapsedMs(startedAt));
    return Optional.of(toPullRequest(pr.repoRef(), body.get()));
  }

  @Override
  public Optional<Branch> getBranchByRef(RepositoryRef repoRefValue, String branchName) {
    Objects.requireNonNull(repoRefValue, "repo");
    Objects.requireNonNull(branchName, "branchName");
    String repoRef = repoRefValue.value();
    ParsedRepoRef repo = parseRepoRef(repoRef);
    long startedAt = System.nanoTime();
    Optional<JsonNode> body =
        getOrEmptyOnNotFound(
            repoUri(repo, "branches", branchName),
            "getBranchByRef",
            IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND);
    if (body.isEmpty()) {
      log.info(
          "github_real get_branch repoRef={} branch={} resolution=not_found", repoRef, branchName);
      return Optional.empty();
    }
    log.info(
        "github_real get_branch repoRef={} branch={} resolution=hit durationMs={}",
        repoRef,
        branchName,
        elapsedMs(startedAt));
    return Optional.of(toBranch(repoRef, body.get()));
  }

  // ---------------------------------------------------------------------------------------------
  // Write methods (AC3/AC4/AC6) — redaction-on-egress + idempotent PR creation.
  // ---------------------------------------------------------------------------------------------

  @Override
  public PullRequest createPullRequest(
      RepositoryRef repoRefValue, String branch, String targetBranch, String title, String body) {
    Objects.requireNonNull(repoRefValue, "repo");
    Objects.requireNonNull(branch, "branch");
    String repoRef = repoRefValue.value();
    ParsedRepoRef repo = parseRepoRef(repoRef);
    long startedAt = System.nanoTime();

    // Existence check (also resolves the default branch for the blank-targetBranch back-compat
    // path). A 404 here classifies as GITHUB_REPO_NOT_FOUND (story 3.13 AC5 / 3.33 R1).
    JsonNode repoJson =
        getOrEmptyOnNotFound(
                repoUri(repo),
                "createPullRequest",
                IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND)
            .orElseThrow(
                () ->
                    new RepositoryHostAdapterException(
                        IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND,
                        "GitHub createPullRequest: repository not found for repoRef=" + repoRef));
    // OQ-2: honor the explicit target branch; a blank target falls back to the repository default
    // branch so the legacy (story 3.14) behavior is preserved byte-for-byte.
    String baseBranch =
        (targetBranch == null || targetBranch.isBlank())
            ? requireText(repoJson, "default_branch", "createPullRequest")
            : targetBranch;

    // AC4 idempotency probe: an open PR for the same (head, base) is reused, never duplicated.
    Optional<PullRequest> existing = findOpenPullRequest(repo, branch, baseBranch, repoRef);
    if (existing.isPresent()) {
      log.warn(
          "github_real create_pull_request repoRef={} branch={} resolution=idempotent_existing prRef={}",
          repoRef,
          branch,
          existing.get().prRef().value());
      return existing.get();
    }

    String redactedTitle = redact(title);
    String redactedBody = redact(body);
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("title", redactedTitle);
    payload.put("head", branch);
    payload.put("base", baseBranch);
    payload.put("body", redactedBody);
    payload.put("draft", true);

    JsonNode created =
        send(
            "POST",
            repoUri(repo, "pulls"),
            payload,
            "createPullRequest",
            IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND);
    PullRequest pullRequest = toPullRequest(repoRef, created);
    log.info(
        "github_real create_pull_request repoRef={} branch={} resolution=created prRef={} number={} durationMs={}",
        repoRef,
        branch,
        pullRequest.prRef().value(),
        pullRequest.number(),
        elapsedMs(startedAt));
    return pullRequest;
  }

  @Override
  public PullRequest updatePullRequest(PullRequestRef ref, String body) {
    Objects.requireNonNull(ref, "ref");
    String prRef = ref.value();
    ParsedPrRef pr = parsePrRef(prRef);
    long startedAt = System.nanoTime();
    String redactedBody = redact(body);
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("body", redactedBody);
    JsonNode updated =
        send(
            "PATCH",
            repoUri(pr, "pulls", String.valueOf(pr.number())),
            payload,
            "updatePullRequest",
            IntegrationFailureCategory.GITHUB_PR_NOT_FOUND);
    log.info(
        "github_real update_pull_request prRef={} resolution=updated durationMs={}",
        prRef,
        elapsedMs(startedAt));
    return toPullRequest(pr.repoRef(), updated);
  }

  @Override
  public CommentResult commentOnPullRequest(PullRequestRef ref, String body) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(body, "body");
    String prRef = ref.value();
    ParsedPrRef pr = parsePrRef(prRef);
    long startedAt = System.nanoTime();
    String redactedBody = redact(body);
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("body", redactedBody);
    // PR comments are issue comments in GitHub's model (issues/{number}/comments). Real GitHub
    // does not dedupe comments; the adapter's idempotency story is the PR, not comments (AC4) — so
    // this always reports POSTED (the SKIPPED_DUPLICATE asymmetry is the mock's, story 3.33 R3).
    send(
        "POST",
        repoUri(pr, "issues", String.valueOf(pr.number()), "comments"),
        payload,
        "commentOnPullRequest",
        IntegrationFailureCategory.GITHUB_PR_NOT_FOUND);
    log.info(
        "github_real comment_on_pull_request prRef={} resolution=posted durationMs={}",
        prRef,
        elapsedMs(startedAt));
    return CommentResult.POSTED;
  }

  // ---------------------------------------------------------------------------------------------
  // CI status read (story 3h-5, AC1/AC5, Decision 7) — check-runs for a pushed commit + failure
  // annotations. Reuses getOrEmptyOnNotFound + inspectRateLimit + classify verbatim; NEVER retries
  // (the scheduled CiStatusPollingService owns the retry budget).
  // ---------------------------------------------------------------------------------------------

  /** GitHub completed-conclusion values that mean the check failed (drives the FAILURE verdict). */
  private static final Set<String> FAILING_CONCLUSIONS = Set.of("failure", "timed_out");

  /**
   * {@code action_required} means the check awaits a MANUAL operator action (a required
   * deployment/environment approval, a GitHub App requesting authorization) — NOT a code defect.
   * Story 3h-5 (3rd review, Decision 2 — CI is informational/non-blocking): mapping it to FAILURE
   * would drive the bounded auto-fix loop the agent can never satisfy until the cap escalates, so
   * it is treated as inconclusive → NEUTRAL and WARNed distinctly so the manual gate stays visible.
   */
  private static final Set<String> MANUAL_ACTION_CONCLUSIONS = Set.of("action_required");

  /** Completed conclusions that are inconclusive (no CI value) when nothing failed → NEUTRAL. */
  private static final Set<String> INCONCLUSIVE_CONCLUSIONS = Set.of("cancelled", "stale");

  /**
   * Completed conclusions that PASS (contribute neither a failure nor an inconclusive verdict). Any
   * completed conclusion outside {@link #FAILING_CONCLUSIONS}, {@link #INCONCLUSIVE_CONCLUSIONS},
   * and this set is UNKNOWN — treated conservatively as inconclusive (never silently green) and
   * WARNed, so a new failing-type conclusion GitHub introduces later surfaces instead of counting
   * as success.
   */
  private static final Set<String> SUCCESS_CONCLUSIONS = Set.of("success", "skipped", "neutral");

  /**
   * Page budget for the {@code check-runs} list (per_page=100 → up to 1000 checks, GitHub's
   * documented per-ref ceiling). Story 3h-5 review: a single un-paginated page silently computed
   * the verdict over a partial set when {@code total_count > 100}, so failing/pending checks beyond
   * the first page were missed (false SUCCESS). We now walk pages until every reported check is
   * read.
   */
  private static final int MAX_CHECK_RUNS_PAGES = 10;

  /** Bound the composed failure body — first N failure annotations, ≤ this many bytes. */
  private static final int MAX_FAILURE_ANNOTATIONS = 50;

  private static final int MAX_FAILURE_TEXT_BYTES = 64 * 1024;

  private static final String TRUNCATION_SUFFIX = "\n…(truncated)";

  @Override
  public CiStatus readCheckRuns(RepositoryRef repoRefValue, String ref) {
    Objects.requireNonNull(repoRefValue, "repo");
    Objects.requireNonNull(ref, "ref");
    if (ref.isBlank()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub readCheckRuns: ref (commit SHA) must be non-blank");
    }
    ParsedRepoRef repo = parseRepoRef(repoRefValue.value());
    long startedAt = System.nanoTime();

    // GET /repos/{owner}/{repo}/commits/{ref}/check-runs?filter=latest&per_page=100
    // The `ref` is the pushed commit SHA (RepositoryPushOutcome.commitSha()). filter=latest returns
    // only the most recent run per (app, name). per_page max is 100; with more than 1000 check
    // suites on a ref only the 1000 most recent are returned (documented GitHub ceiling).
    // Paginate the check-runs list: page 1 keeps the original query (no `page` param) so existing
    // callers/fixtures are byte-identical; only total_count>100 walks further pages. filter=latest
    // returns one run per (app, name).
    List<JsonNode> checkRuns = new ArrayList<>();
    int totalCount = 0;
    for (int page = 1; page <= MAX_CHECK_RUNS_PAGES; page++) {
      String query =
          page == 1 ? "filter=latest&per_page=100" : "filter=latest&per_page=100&page=" + page;
      URI checkRunsUri =
          UriComponentsBuilder.fromPath("")
              .pathSegment("repos", repo.owner(), repo.name(), "commits", ref, "check-runs")
              .query(query)
              .build()
              .encode()
              .toUri();
      JsonNode body =
          getOrEmptyOnNotFound(
                  checkRunsUri, "readCheckRuns", IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND)
              .orElseThrow(
                  () ->
                      new RepositoryHostAdapterException(
                          IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND,
                          "GitHub readCheckRuns: repository/ref not found for repoRef="
                              + repoRefValue.value()));
      if (page == 1) {
        totalCount = body.path("total_count").asInt(0);
      }
      JsonNode pageRuns = body.path("check_runs");
      if (!pageRuns.isArray() || pageRuns.isEmpty()) {
        break;
      }
      pageRuns.forEach(checkRuns::add);
      if (checkRuns.size() >= totalCount) {
        break;
      }
    }
    if (totalCount == 0 || checkRuns.isEmpty()) {
      // No CI configured / registered on this ref — never loop, stop polling.
      log.info(
          "github_real read_check_runs repoRef={} ref={} conclusion=neutral reason=no_checks "
              + "durationMs={}",
          repoRefValue.value(),
          ref,
          elapsedMs(startedAt));
      return new CiStatus(CiConclusion.NEUTRAL, ref, List.of());
    }
    boolean paginationTruncated = checkRuns.size() < totalCount;
    if (paginationTruncated) {
      // Ran out of the page budget before collecting every reported check — WARN (no silent
      // truncation) and compute the verdict over what we have. A still-pending or failed check
      // among the collected set keeps the run polling / loops as usual; but if the collected set is
      // ALL green we must NOT report a terminal SUCCESS, because an unread check beyond the budget
      // may have failed and we cannot see it — fall through to PENDING to force another poll.
      log.warn(
          "github_real read_check_runs repoRef={} ref={} reason=check_runs_pagination_truncated "
              + "collected={} totalCount={}",
          repoRefValue.value(),
          ref,
          checkRuns.size(),
          totalCount);
    }

    boolean anyPending = false;
    boolean anyFailure = false;
    boolean anyInconclusive = false;
    List<JsonNode> failedRuns = new ArrayList<>();
    for (JsonNode run : checkRuns) {
      String status = run.path("status").asText("");
      if (!"completed".equals(status)) {
        anyPending = true;
        continue;
      }
      String conclusion = run.path("conclusion").asText("");
      if (FAILING_CONCLUSIONS.contains(conclusion)) {
        anyFailure = true;
        failedRuns.add(run);
      } else if (SUCCESS_CONCLUSIONS.contains(conclusion)) {
        // Passing — contributes neither a failure nor an inconclusive verdict.
        continue;
      } else if (MANUAL_ACTION_CONCLUSIONS.contains(conclusion)) {
        // Awaiting a manual operator action — non-blocking, never a fixable failure. Treat as
        // inconclusive (NEUTRAL) and WARN distinctly so the manual gate is visible in logs.
        anyInconclusive = true;
        log.warn(
            "github_real read_check_runs repoRef={} ref={} reason=action_required_non_blocking "
                + "check={}",
            repoRefValue.value(),
            ref,
            run.path("name").asText(""));
      } else {
        // cancelled/stale (known inconclusive) OR an unrecognized/future conclusion. Treat as
        // inconclusive (never silently green); WARN on a value we do not recognize so a new
        // failing-type conclusion GitHub adds later surfaces instead of counting as success.
        anyInconclusive = true;
        if (!INCONCLUSIVE_CONCLUSIONS.contains(conclusion)) {
          log.warn(
              "github_real read_check_runs repoRef={} ref={} reason=unknown_conclusion "
                  + "conclusion={} check={}",
              repoRefValue.value(),
              ref,
              conclusion,
              run.path("name").asText(""));
        }
      }
    }

    // Precedence (story 3h-5 Task 3): a still-running check keeps the whole verdict PENDING even if
    // a sibling already failed — we wait for completion before declaring FAILURE (avoids a
    // premature
    // re-dispatch that a later-passing check would race).
    if (anyPending) {
      log.info(
          "github_real read_check_runs repoRef={} ref={} conclusion=pending durationMs={}",
          repoRefValue.value(),
          ref,
          elapsedMs(startedAt));
      return new CiStatus(CiConclusion.PENDING, ref, List.of());
    }
    if (anyFailure) {
      List<CiCheck> checks = new ArrayList<>();
      for (JsonNode run : failedRuns) {
        checks.add(toFailedCiCheck(repo, run));
      }
      log.info(
          "github_real read_check_runs repoRef={} ref={} conclusion=failure failedChecks={} "
              + "durationMs={}",
          repoRefValue.value(),
          ref,
          checks.size(),
          elapsedMs(startedAt));
      return new CiStatus(CiConclusion.FAILURE, ref, checks);
    }
    if (anyInconclusive) {
      log.info(
          "github_real read_check_runs repoRef={} ref={} conclusion=neutral reason=cancelled_stale "
              + "durationMs={}",
          repoRefValue.value(),
          ref,
          elapsedMs(startedAt));
      return new CiStatus(CiConclusion.NEUTRAL, ref, List.of());
    }
    if (paginationTruncated) {
      // All collected checks passed, but the page budget was exhausted — an unread check could
      // still be failing. Report PENDING (not a terminal SUCCESS) so the sweep polls again rather
      // than dropping a possibly-red ref as green.
      log.warn(
          "github_real read_check_runs repoRef={} ref={} conclusion=pending "
              + "reason=all_collected_green_but_truncated durationMs={}",
          repoRefValue.value(),
          ref,
          elapsedMs(startedAt));
      return new CiStatus(CiConclusion.PENDING, ref, List.of());
    }
    log.info(
        "github_real read_check_runs repoRef={} ref={} conclusion=success durationMs={}",
        repoRefValue.value(),
        ref,
        elapsedMs(startedAt));
    return new CiStatus(CiConclusion.SUCCESS, ref, List.of());
  }

  /**
   * Compose a bounded, redaction-bound failure body for a failed check run from its {@code output}
   * ({@code title}/{@code summary}/{@code text}) plus its {@code failure}-level annotations. The
   * annotation fetch happens only for failed runs. Never logs the composed bytes — only their
   * length. The composed text is redaction-policed downstream when it lands as a CI
   * runner_executions raw output (story 3h-5 AC5).
   */
  private CiCheck toFailedCiCheck(ParsedRepoRef repo, JsonNode run) {
    String name = run.path("name").asText("");
    String conclusion = run.path("conclusion").asText("");
    String detailsUrl = optText(run, "details_url");
    JsonNode output = run.path("output");
    String title = optText(output, "title");
    String summary = optText(output, "summary");
    String text = optText(output, "text");
    long checkRunId = run.path("id").asLong(-1L);

    StringBuilder body = new StringBuilder();
    appendIfPresent(body, "check", name);
    appendIfPresent(body, "conclusion", conclusion);
    appendIfPresent(body, "title", title);
    appendIfPresent(body, "summary", summary);
    appendIfPresent(body, "text", text);

    if (checkRunId >= 0) {
      List<String> failureAnnotations = fetchFailureAnnotations(repo, checkRunId);
      if (!failureAnnotations.isEmpty()) {
        body.append("annotations:\n");
        for (String annotation : failureAnnotations) {
          body.append("  ").append(annotation).append('\n');
        }
      }
    }

    String failureText = boundBytes(body.toString());
    log.info(
        "github_real read_check_runs failed_check repoRef={}/{} check={} failureTextBytes={}",
        repo.owner(),
        repo.name(),
        name,
        failureText.getBytes(StandardCharsets.UTF_8).length);
    return new CiCheck(
        name.isBlank() ? "unnamed-check" : name, conclusion, detailsUrl, summary, failureText);
  }

  /**
   * GET /repos/{owner}/{repo}/check-runs/{id}/annotations, keeping only {@code annotation_level ==
   * "failure"} and rendering each as {@code path:start_line — message}. Bounded to the first {@link
   * #MAX_FAILURE_ANNOTATIONS}. A read failure here degrades to an empty list (the check
   * title/summary/text already carry a usable body) — the sweep's retry budget covers the run as a
   * whole.
   */
  private List<String> fetchFailureAnnotations(ParsedRepoRef repo, long checkRunId) {
    URI annotationsUri =
        UriComponentsBuilder.fromPath("")
            .pathSegment(
                "repos",
                repo.owner(),
                repo.name(),
                "check-runs",
                String.valueOf(checkRunId),
                "annotations")
            .query("per_page=100")
            .build()
            .encode()
            .toUri();
    Optional<JsonNode> body =
        getOrEmptyOnNotFound(
            annotationsUri,
            "readCheckRunAnnotations",
            IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND);
    if (body.isEmpty() || !body.get().isArray()) {
      return List.of();
    }
    List<String> failures = new ArrayList<>();
    for (JsonNode annotation : body.get()) {
      if (!"failure".equals(annotation.path("annotation_level").asText(""))) {
        continue;
      }
      String path = optText(annotation, "path");
      long startLine = annotation.path("start_line").asLong(-1L);
      String message = optText(annotation, "message");
      StringBuilder rendered = new StringBuilder();
      rendered.append(path.isBlank() ? "(unknown)" : path);
      if (startLine >= 0) {
        rendered.append(':').append(startLine);
      }
      if (!message.isBlank()) {
        rendered.append(" — ").append(message);
      }
      failures.add(rendered.toString());
      if (failures.size() >= MAX_FAILURE_ANNOTATIONS) {
        break;
      }
    }
    return failures;
  }

  private static void appendIfPresent(StringBuilder body, String label, String value) {
    if (value != null && !value.isBlank()) {
      body.append(label).append(": ").append(value).append('\n');
    }
  }

  private static String optText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? "" : value.asText("");
  }

  /**
   * Truncate to at most {@link #MAX_FAILURE_TEXT_BYTES} UTF-8 bytes INCLUDING the truncation suffix
   * (whole-char safe). Reserves the suffix's byte budget before trimming so the returned string
   * never exceeds the cap.
   */
  private static String boundBytes(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_FAILURE_TEXT_BYTES) {
      return value;
    }
    // Trim by characters until under the byte budget (UTF-8 chars are ≤ 4 bytes; the loop
    // converges). Reserve the suffix bytes so value + suffix stays within MAX_FAILURE_TEXT_BYTES.
    int budget =
        Math.max(
            0, MAX_FAILURE_TEXT_BYTES - TRUNCATION_SUFFIX.getBytes(StandardCharsets.UTF_8).length);
    String truncated = value;
    while (truncated.getBytes(StandardCharsets.UTF_8).length > budget && !truncated.isEmpty()) {
      truncated = truncated.substring(0, truncated.length() - Math.max(1, truncated.length() / 16));
    }
    return truncated + TRUNCATION_SUFFIX;
  }

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.GITHUB;
  }

  @Override
  public RepositoryHostCapabilities getCapabilities() {
    return RepositoryHostCapabilities.githubDefaults();
  }

  /**
   * Story 3c-8 (AC3 / R1) — a single authenticated GET probe: the repository's metadata when {@code
   * repo} is supplied (so {@code reachable} answers "is this repo reachable"), else {@code /user}
   * (host reachability + credential validity only). Every failure folds into a secret-free {@link
   * ConnectivityResult} — the probe never throws across the port. A 404 on a repo probe means the
   * credentials authenticated but that repository is absent.
   *
   * <p>When {@code credentialOverride} is non-blank it is attached as a per-request attribute the
   * {@code gitHubRestClient} interceptor prefers over the host-env PAT (so the project-scoped
   * stored token actually authenticates the probe); otherwise the host-env PAT is used (AC3
   * fallback). The override is never logged.
   */
  @Override
  public ConnectivityResult verifyConnectivity(RepositoryRef repo, String credentialOverride) {
    long startedAt = System.nanoTime();
    String repoRefLabel = repo == null ? "<whoami>" : repo.value();
    ParsedRepoRef parsed = null;
    if (repo != null) {
      try {
        parsed = parseRepoRef(repo.value());
      } catch (RepositoryHostAdapterException malformed) {
        log.warn("github_real verify_connectivity repoRef={} resolution=invalid_ref", repoRefLabel);
        return new ConnectivityResult(false, false, "github: invalid repository reference");
      }
    }
    try {
      RestClient.RequestHeadersSpec<?> request =
          (parsed == null
                  ? gitHubRestClient.get().uri("/user")
                  : gitHubRestClient
                      .get()
                      .uri("/repos/{owner}/{repo}", parsed.owner(), parsed.name()))
              .attributes(
                  attrs -> {
                    if (credentialOverride != null && !credentialOverride.isBlank()) {
                      attrs.put(GitHubProperties.CREDENTIAL_OVERRIDE_ATTRIBUTE, credentialOverride);
                    }
                  });
      request.retrieve().toBodilessEntity();
      log.info(
          "github_real verify_connectivity repoRef={} resolution=ok durationMs={}",
          repoRefLabel,
          elapsedMs(startedAt));
      return ConnectivityResult.ok(
          parsed == null
              ? "github: authenticated"
              : "github: repository reachable + authenticated");
    } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden auth) {
      log.warn(
          "github_real verify_connectivity repoRef={} status={} resolution=unauthenticated",
          repoRefLabel,
          auth.getStatusCode());
      return ConnectivityResult.unauthenticated("github: authentication failed");
    } catch (HttpClientErrorException.NotFound notFound) {
      // Host + credentials are fine; the specific repository is absent.
      log.warn(
          "github_real verify_connectivity repoRef={} resolution=repo_not_found", repoRefLabel);
      return new ConnectivityResult(false, true, "github: repository not found");
    } catch (HttpClientErrorException.TooManyRequests rateLimited) {
      // 429 — the host answered and the credential was NOT rejected; we simply cannot complete the
      // probe right now. Report a non-misleading "could not verify" rather than an auth failure.
      log.warn(
          "github_real verify_connectivity repoRef={} status=429 resolution=rate_limited",
          repoRefLabel);
      return new ConnectivityResult(
          true, false, "github: rate limited — could not verify, retry later");
    } catch (HttpServerErrorException server) {
      // 5xx — the host answered with a server error; not an auth verdict. Same "could not verify".
      log.warn(
          "github_real verify_connectivity repoRef={} status={} resolution=server_error",
          repoRefLabel,
          server.getStatusCode());
      return new ConnectivityResult(
          true, false, "github: server error — could not verify, retry later");
    } catch (ResourceAccessException io) {
      log.warn(
          "github_real verify_connectivity repoRef={} resolution=unreachable cause={}",
          repoRefLabel,
          io.getMostSpecificCause().getClass().getSimpleName());
      log.debug("github_real verify_connectivity network fault repoRef={}", repoRefLabel, io);
      return ConnectivityResult.unreachable("github: host unreachable");
    } catch (RestClientException other) {
      log.warn(
          "github_real verify_connectivity repoRef={} resolution=unexpected cause={}",
          repoRefLabel,
          other.getClass().getSimpleName());
      return new ConnectivityResult(true, false, "github: unexpected response");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP plumbing — rate-limit inspection + classification ladder (AC5/AC7, Task 4).
  // ---------------------------------------------------------------------------------------------

  /**
   * GET that returns {@link Optional#empty()} on a genuine 404 (read-method absence is the port
   * contract) and classifies every other failure. Rate-limit headers are inspected on the success
   * path.
   */
  private Optional<JsonNode> getOrEmptyOnNotFound(
      URI uri, String operation, IntegrationFailureCategory notFoundCategory) {
    try {
      ResponseEntity<String> response =
          gitHubRestClient.get().uri(uri).retrieve().toEntity(String.class);
      inspectRateLimit(response.getHeaders(), operation);
      return Optional.of(parseJson(response.getBody(), operation));
    } catch (HttpClientErrorException.NotFound notFound) {
      return Optional.empty();
    } catch (RuntimeException error) {
      throw classify(error, operation, notFoundCategory);
    }
  }

  /** Mutating call (POST/PATCH) — 404 classifies via {@code notFoundCategory}; never swallowed. */
  private JsonNode send(
      String method,
      URI uri,
      JsonNode jsonBody,
      String operation,
      IntegrationFailureCategory notFoundCategory) {
    String serialized;
    try {
      serialized = objectMapper.writeValueAsString(jsonBody);
    } catch (IOException error) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub " + operation + " failed to serialize request body",
          error);
    }
    try {
      ResponseEntity<String> response =
          gitHubRestClient
              .method(HttpMethod.valueOf(method))
              .uri(uri)
              .contentType(MediaType.APPLICATION_JSON)
              .body(serialized)
              .retrieve()
              .toEntity(String.class);
      inspectRateLimit(response.getHeaders(), operation);
      return parseJson(response.getBody(), operation);
    } catch (RuntimeException error) {
      throw classify(error, operation, notFoundCategory);
    }
  }

  private Optional<PullRequest> findOpenPullRequest(
      ParsedRepoRef repo, String headBranch, String baseBranch, String repoRef) {
    URI uri =
        UriComponentsBuilder.fromPath("")
            .pathSegment("repos", repo.owner(), repo.name(), "pulls")
            .query(
                "head="
                    + encodeQueryValue(repo.owner() + ":" + headBranch)
                    + "&base="
                    + encodeQueryValue(baseBranch)
                    + "&state=open")
            .build(true)
            .toUri();
    JsonNode array =
        getOrEmptyOnNotFound(
                uri, "createPullRequest", IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND)
            .orElseGet(objectMapper::createArrayNode);
    if (!array.isArray() || array.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(toPullRequest(repoRef, array.get(0)));
  }

  /**
   * Inspects GitHub's rate-limit headers (AC5). WARN below the configured threshold; raise {@link
   * IntegrationFailureCategory#GITHUB_RATE_LIMITED} when remaining has reached zero. {@code
   * resetAtSeconds} is surfaced in the WARN log, exception message, and exception details.
   */
  private void inspectRateLimit(HttpHeaders headers, String operation) {
    if (headers == null) {
      return;
    }
    String remainingRaw = headers.getFirst(HEADER_RATE_LIMIT_REMAINING);
    if (remainingRaw == null || remainingRaw.isBlank()) {
      return;
    }
    int remaining;
    try {
      remaining = Integer.parseInt(remainingRaw.trim());
    } catch (NumberFormatException malformed) {
      return;
    }
    if (remaining <= 0) {
      long resetAtSeconds = parseResetAtSeconds(headers);
      log.warn(
          "github_real {} rate_limited remaining=0 resetAtSeconds={}", operation, resetAtSeconds);
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_RATE_LIMITED,
          "GitHub " + operation + " rate limit exhausted (resetAtSeconds=" + resetAtSeconds + ")",
          rateLimitDetails(resetAtSeconds));
    }
    if (remaining < properties.rateLimitWarnThreshold()) {
      log.warn(
          "github_real {} rate_limit_low remaining={} threshold={} resetAtSeconds={}",
          operation,
          remaining,
          properties.rateLimitWarnThreshold(),
          parseResetAtSeconds(headers));
    }
  }

  /**
   * Maps an HTTP failure onto the existing GitHub {@link IntegrationFailureCategory} values (AC7) —
   * never a generic unclassified error. Mirrors {@code LinearRealAdapter}'s catch-cascade order.
   */
  private RepositoryHostAdapterException classify(
      RuntimeException error, String operation, IntegrationFailureCategory notFoundCategory) {
    if (error instanceof RepositoryHostAdapterException already) {
      // A rate-limit raise from inspectRateLimit (or a nested classified failure) is already typed.
      return already;
    }
    if (error instanceof HttpClientErrorException.Unauthorized unauthorized) {
      return warnAndBuild(
          operation, unauthorized, IntegrationFailureCategory.GITHUB_AUTH_FAILED, "auth failed");
    }
    if (error instanceof HttpClientErrorException.Forbidden forbidden) {
      if (rateLimitExhausted(forbidden.getResponseHeaders())) {
        long resetAtSeconds = parseResetAtSeconds(forbidden.getResponseHeaders());
        log.warn(
            "github_real {} rate_limited status=403 resetAtSeconds={}", operation, resetAtSeconds);
        return new RepositoryHostAdapterException(
            IntegrationFailureCategory.GITHUB_RATE_LIMITED,
            "GitHub " + operation + " rate limited (403, resetAtSeconds=" + resetAtSeconds + ")",
            forbidden,
            rateLimitDetails(resetAtSeconds));
      }
      return warnAndBuild(
          operation,
          forbidden,
          IntegrationFailureCategory.GITHUB_PERMISSION_DENIED,
          "permission denied");
    }
    if (error instanceof HttpClientErrorException.TooManyRequests tooMany) {
      long resetAtSeconds = parseResetAtSeconds(tooMany.getResponseHeaders());
      log.warn(
          "github_real {} rate_limited status=429 resetAtSeconds={}", operation, resetAtSeconds);
      return new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_RATE_LIMITED,
          "GitHub " + operation + " rate limited (429, resetAtSeconds=" + resetAtSeconds + ")",
          tooMany,
          rateLimitDetails(resetAtSeconds));
    }
    if (error instanceof HttpClientErrorException.NotFound notFound) {
      return warnAndBuild(operation, notFound, notFoundCategory, "not found");
    }
    if (error instanceof HttpClientErrorException.UnsupportedMediaType unsupported) {
      return warnAndBuild(
          operation,
          unsupported,
          IntegrationFailureCategory.GITHUB_API_VERSION_INCOMPATIBLE,
          "api version incompatible");
    }
    if (error instanceof HttpClientErrorException.UnprocessableEntity unprocessable) {
      if (!"createPullRequest".equals(operation)) {
        return warnAndBuild(
            operation,
            unprocessable,
            IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
            "unprocessable response");
      }
      // 422 on PR create is GitHub's protected-branch / validation signal (AC7).
      return warnAndBuild(
          operation,
          unprocessable,
          IntegrationFailureCategory.GITHUB_BRANCH_PROTECTED,
          "branch protected / unprocessable");
    }
    if (error instanceof HttpServerErrorException server) {
      return warnAndBuild(
          operation, server, IntegrationFailureCategory.GITHUB_NETWORK_FAILURE, "server error");
    }
    if (error instanceof ResourceAccessException io) {
      log.warn(
          "github_real {} failed cause={} category=github_network_failure",
          operation,
          io.getMostSpecificCause().getClass().getSimpleName());
      return new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub "
              + operation
              + " network failure: "
              + io.getMostSpecificCause().getClass().getSimpleName(),
          io);
    }
    if (error instanceof RestClientResponseException other) {
      // Any other unexpected HTTP status — classified (never generic) as a network/API failure.
      return warnAndBuild(
          operation, other, IntegrationFailureCategory.GITHUB_NETWORK_FAILURE, "unexpected status");
    }
    // Non-HTTP runtime failure (e.g. malformed JSON parse) — surface as a network/API failure.
    log.warn(
        "github_real {} failed cause={} category=github_network_failure",
        operation,
        error.getClass().getSimpleName());
    return new RepositoryHostAdapterException(
        IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
        "GitHub " + operation + " failed: " + error.getClass().getSimpleName(),
        error);
  }

  private RepositoryHostAdapterException warnAndBuild(
      String operation,
      RestClientResponseException error,
      IntegrationFailureCategory category,
      String reason) {
    log.warn(
        "github_real {} failed status={} category={}",
        operation,
        error.getStatusCode(),
        category.value());
    return new RepositoryHostAdapterException(
        category, "GitHub " + operation + " " + reason + ": " + error.getStatusCode(), error);
  }

  private static boolean rateLimitExhausted(HttpHeaders headers) {
    if (headers == null) {
      return false;
    }
    String remainingRaw = headers.getFirst(HEADER_RATE_LIMIT_REMAINING);
    if (remainingRaw == null || remainingRaw.isBlank()) {
      return false;
    }
    try {
      return Integer.parseInt(remainingRaw.trim()) <= 0;
    } catch (NumberFormatException malformed) {
      return false;
    }
  }

  private static long parseResetAtSeconds(HttpHeaders headers) {
    if (headers == null) {
      return RATE_LIMIT_RESET_UNKNOWN;
    }
    String resetRaw = headers.getFirst(HEADER_RATE_LIMIT_RESET);
    if (resetRaw == null || resetRaw.isBlank()) {
      return RATE_LIMIT_RESET_UNKNOWN;
    }
    try {
      return Long.parseLong(resetRaw.trim());
    } catch (NumberFormatException malformed) {
      return RATE_LIMIT_RESET_UNKNOWN;
    }
  }

  private static Map<String, String> rateLimitDetails(long resetAtSeconds) {
    return Map.of("resetAtSeconds", String.valueOf(resetAtSeconds));
  }

  private static String encodeQueryValue(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static URI repoUri(ParsedRepoRef repo, String... extraSegments) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromPath("").pathSegment("repos", repo.owner(), repo.name());
    for (String segment : extraSegments) {
      builder.pathSegment(segment);
    }
    return builder.build().encode().toUri();
  }

  private static URI repoUri(ParsedPrRef pr, String... extraSegments) {
    return repoUri(new ParsedRepoRef(pr.owner(), pr.name()), extraSegments);
  }

  // ---------------------------------------------------------------------------------------------
  // JSON → domain mapping.
  // ---------------------------------------------------------------------------------------------

  private Repository toRepository(String repoRef, JsonNode json) {
    return new Repository(
        RepositoryRef.of(repoRef),
        requireText(json, "full_name", "getRepositoryByRef"),
        requireText(json, "default_branch", "getRepositoryByRef"),
        requireText(json, "html_url", "getRepositoryByRef"));
  }

  private PullRequest toPullRequest(String repoRef, JsonNode json) {
    int number = json.path("number").asInt(-1);
    if (number < 0) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub PR response missing numeric 'number' for repoRef=" + repoRef);
    }
    String sourceBranch = requireText(json.path("head"), "ref", "pullRequest");
    String state = requireText(json, "state", "pullRequest");
    // GitHub's REST `state` collapses merged and closed to "closed"; the separate `merged` boolean
    // is the only way to tell an externally-merged PR from a merely-closed one (story 4.17). Absent
    // on some list-shaped payloads → default false (an unmerged closed/open PR).
    boolean merged = json.path("merged").asBoolean(false);
    String url = requireText(json, "html_url", "pullRequest");
    Instant createdAt = parseInstant(requireText(json, "created_at", "pullRequest"));
    return new PullRequest(
        PullRequestRef.of(repoRef + "#" + number),
        RepositoryRef.of(repoRef),
        number,
        sourceBranch,
        state,
        merged,
        url,
        createdAt);
  }

  private Branch toBranch(String repoRef, JsonNode json) {
    String name = requireText(json, "name", "getBranchByRef");
    String headSha = requireText(json.path("commit"), "sha", "getBranchByRef");
    return new Branch(RepositoryRef.of(repoRef), name, headSha);
  }

  private JsonNode parseJson(String body, String operation) {
    if (body == null || body.isBlank()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub " + operation + " returned an empty body");
    }
    try {
      return objectMapper.readTree(body);
    } catch (IOException error) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub " + operation + " returned non-JSON body",
          error);
    }
  }

  private String redact(String payload) {
    if (payload == null) {
      return null;
    }
    return redactionPolicyService.redact(payload, REDACTION_CLASSIFICATION).sanitizedText();
  }

  private static String requireText(JsonNode node, String field, String operation) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub " + operation + " response missing required field: " + field);
    }
    return value.asText();
  }

  private static Instant parseInstant(String raw) {
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException error) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub response has non-ISO-8601 created_at: " + raw,
          error);
    }
  }

  private static ParsedRepoRef parseRepoRef(String repoRef) {
    Matcher matcher = REPO_REF_PATTERN.matcher(repoRef);
    if (!matcher.matches()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND,
          "GitHub repository reference must be owner/repo: " + repoRef);
    }
    return new ParsedRepoRef(matcher.group(1), matcher.group(2));
  }

  private static ParsedPrRef parsePrRef(String prRef) {
    Matcher matcher = PR_REF_PATTERN.matcher(prRef);
    if (!matcher.matches()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_PR_NOT_FOUND,
          "GitHub pull-request reference must be owner/repo#number: " + prRef);
    }
    try {
      return new ParsedPrRef(
          matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
    } catch (NumberFormatException outOfRange) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_PR_NOT_FOUND,
          "GitHub pull-request reference number is out of range: " + prRef,
          outOfRange);
    }
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }

  private record ParsedRepoRef(String owner, String name) {
    String repoRef() {
      return owner + "/" + name;
    }
  }

  private record ParsedPrRef(String owner, String name, int number) {
    String repoRef() {
      return owner + "/" + name;
    }
  }
}
