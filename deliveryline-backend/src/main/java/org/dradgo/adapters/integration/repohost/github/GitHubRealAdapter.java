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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.integration.repohost.Branch;
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

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.GITHUB;
  }

  @Override
  public RepositoryHostCapabilities getCapabilities() {
    return RepositoryHostCapabilities.githubDefaults();
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
    String url = requireText(json, "html_url", "pullRequest");
    Instant createdAt = parseInstant(requireText(json, "created_at", "pullRequest"));
    return new PullRequest(
        PullRequestRef.of(repoRef + "#" + number),
        RepositoryRef.of(repoRef),
        number,
        sourceBranch,
        state,
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
