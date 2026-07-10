package org.dradgo.adapters.integration.repohost.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.bitbucket.BitbucketProperties;
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
 * Production {@link RepositoryHostAdapter} (Bitbucket kind) backed by the Bitbucket Cloud REST API
 * v2 (story 3i-3 / FR82). Activated under Spring profile {@code bitbucket-real} (opt-in only; never
 * in any default profile group, mirroring {@code github-real}). Bitbucket twin of {@code
 * GitHubRealAdapter}; the second real repository host promoted onto the vendor-neutral port of
 * story 3.33 (ADR 0008).
 *
 * <p><strong>Reference formats accepted.</strong> A {@code repoRef} is {@code "workspace/repo"}; a
 * {@code prRef} is {@code "workspace/repo#id"}. Parsing is private to the adapter — refs are opaque
 * {@link RepositoryRef}/{@link PullRequestRef} tokens on the {@link RepositoryHostAdapter} port.
 *
 * <p><strong>Authentication.</strong> The single Bitbucket secret is read from {@code
 * BITBUCKET_TOKEN} via {@link BitbucketProperties} and applied at request time inside the {@code
 * bitbucketRestClient} interceptor ({@code BitbucketConfiguration}) — a {@code
 * workspace:app_password} pair as HTTP Basic, a bare access token as Bearer — never logged, never
 * embedded in a URL, never persisted.
 *
 * <p><strong>Redaction-on-egress.</strong> Every write method passes its {@code title}/{@code body}
 * through {@link RedactionPolicyService} (classification {@code shareable-redacted}) before the
 * request is sent — redact-and-send, never refuse.
 *
 * <p><strong>Idempotent PR creation.</strong> {@link #createPullRequest} first searches for an open
 * PR for the same {@code (source branch, destination branch)} and returns it instead of stacking a
 * duplicate. The destination is the supplied {@code targetBranch}; a blank target falls back to the
 * repository's main branch.
 *
 * <p><strong>Failure classification.</strong> HTTP outcomes map to the Bitbucket {@link
 * IntegrationFailureCategory} values via {@link #classify}; everything throws {@link
 * RepositoryHostAdapterException}, never a generic unclassified error. This adapter does NOT retry.
 * Bitbucket Cloud has no draft-PR concept, so — unlike the GitHub adapter — no {@code draft} flag
 * is sent on PR create (see {@link RepositoryHostCapabilities#bitbucketDefaults()}).
 */
@Component
@Primary
@Profile("bitbucket-real")
public class BitbucketRealAdapter implements RepositoryHostAdapter {

  private static final Logger log = LoggerFactory.getLogger(BitbucketRealAdapter.class);

  private static final String REDACTION_CLASSIFICATION =
      DataClassification.SHAREABLE_REDACTED.value();

  /**
   * {@code workspace/repo} — workspace and repo are Bitbucket name segments (no slash/{@code #}).
   */
  private static final Pattern REPO_REF_PATTERN =
      Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)/([A-Za-z0-9._-]+)$");

  /** {@code workspace/repo#id}. */
  private static final Pattern PR_REF_PATTERN =
      Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)/([A-Za-z0-9._-]+)#([0-9]+)$");

  private final RestClient bitbucketRestClient;
  private final BitbucketProperties properties;
  private final RedactionPolicyService redactionPolicyService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public BitbucketRealAdapter(
      @Qualifier("bitbucketRestClient") RestClient bitbucketRestClient,
      BitbucketProperties properties,
      RedactionPolicyService redactionPolicyService) {
    this.bitbucketRestClient = Objects.requireNonNull(bitbucketRestClient, "bitbucketRestClient");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
  }

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.BITBUCKET;
  }

  @Override
  public RepositoryHostCapabilities getCapabilities() {
    return RepositoryHostCapabilities.bitbucketDefaults();
  }

  // ---------------------------------------------------------------------------------------------
  // Read methods — genuine absence is Optional.empty() (404); other failures classify.
  // ---------------------------------------------------------------------------------------------

  @Override
  public Optional<Repository> getRepositoryByRef(RepositoryRef ref) {
    Objects.requireNonNull(ref, "ref");
    String repoRef = ref.value();
    ParsedRepoRef repo = parseRepoRef(repoRef);
    long startedAt = System.nanoTime();
    Optional<JsonNode> body =
        getOrEmptyOnNotFound(
            repoUri(repo),
            "getRepositoryByRef",
            IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND);
    if (body.isEmpty()) {
      log.info("bitbucket_real get_repository repoRef={} resolution=not_found", repoRef);
      return Optional.empty();
    }
    log.info(
        "bitbucket_real get_repository repoRef={} resolution=hit durationMs={}",
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
            repoUri(pr, "pullrequests", String.valueOf(pr.number())),
            "getPullRequestByRef",
            IntegrationFailureCategory.BITBUCKET_PR_NOT_FOUND);
    if (body.isEmpty()) {
      log.info("bitbucket_real get_pull_request prRef={} resolution=not_found", prRef);
      return Optional.empty();
    }
    log.info(
        "bitbucket_real get_pull_request prRef={} resolution=hit durationMs={}",
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
            repoUri(repo, "refs", "branches", branchName),
            "getBranchByRef",
            IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND);
    if (body.isEmpty()) {
      log.info(
          "bitbucket_real get_branch repoRef={} branch={} resolution=not_found",
          repoRef,
          branchName);
      return Optional.empty();
    }
    log.info(
        "bitbucket_real get_branch repoRef={} branch={} resolution=hit durationMs={}",
        repoRef,
        branchName,
        elapsedMs(startedAt));
    return Optional.of(toBranch(repoRef, body.get()));
  }

  // ---------------------------------------------------------------------------------------------
  // Write methods — redaction-on-egress + idempotent PR creation.
  // ---------------------------------------------------------------------------------------------

  @Override
  public PullRequest createPullRequest(
      RepositoryRef repoRefValue, String branch, String targetBranch, String title, String body) {
    Objects.requireNonNull(repoRefValue, "repo");
    Objects.requireNonNull(branch, "branch");
    // Bitbucket requires a PR title; guard here so a null never serializes to "title":null and
    // gets bounced back as a 400 that the classifier would misread as BITBUCKET_BRANCH_PROTECTED.
    Objects.requireNonNull(title, "title");
    String repoRef = repoRefValue.value();
    ParsedRepoRef repo = parseRepoRef(repoRef);
    long startedAt = System.nanoTime();

    // Existence check (also resolves the main branch for the blank-targetBranch back-compat path).
    // A 404 here classifies as BITBUCKET_REPO_NOT_FOUND.
    JsonNode repoJson =
        getOrEmptyOnNotFound(
                repoUri(repo),
                "createPullRequest",
                IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND)
            .orElseThrow(
                () ->
                    new RepositoryHostAdapterException(
                        IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND,
                        "Bitbucket createPullRequest: repository not found for repoRef="
                            + repoRef));
    // Honor the explicit destination branch; a blank target falls back to the repository main
    // branch so the legacy behavior is preserved.
    String baseBranch =
        (targetBranch == null || targetBranch.isBlank())
            ? requireText(repoJson.path("mainbranch"), "name", "createPullRequest")
            : targetBranch;

    // Idempotency probe: an open PR for the same (source, destination) is reused, never duplicated.
    Optional<PullRequest> existing = findOpenPullRequest(repo, branch, baseBranch, repoRef);
    if (existing.isPresent()) {
      log.warn(
          "bitbucket_real create_pull_request repoRef={} branch={} resolution=idempotent_existing prRef={}",
          repoRef,
          branch,
          existing.get().prRef().value());
      return existing.get();
    }

    String redactedTitle = redact(title);
    String redactedBody = redact(body);
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("title", redactedTitle);
    payload.set("source", branchNode(branch));
    payload.set("destination", branchNode(baseBranch));
    // Bitbucket Cloud has no draft-PR concept (see bitbucketDefaults()) — no draft flag is sent.
    payload.put("description", redactedBody == null ? "" : redactedBody);

    JsonNode created =
        send(
            "POST",
            repoUri(repo, "pullrequests"),
            payload,
            "createPullRequest",
            IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND);
    PullRequest pullRequest = toPullRequest(repoRef, created);
    log.info(
        "bitbucket_real create_pull_request repoRef={} branch={} resolution=created prRef={} number={} durationMs={}",
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
    payload.put("description", redactedBody == null ? "" : redactedBody);
    JsonNode updated =
        send(
            "PUT",
            repoUri(pr, "pullrequests", String.valueOf(pr.number())),
            payload,
            "updatePullRequest",
            IntegrationFailureCategory.BITBUCKET_PR_NOT_FOUND);
    log.info(
        "bitbucket_real update_pull_request prRef={} resolution=updated durationMs={}",
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
    ObjectNode content = objectMapper.createObjectNode();
    content.put("raw", redactedBody == null ? "" : redactedBody);
    ObjectNode payload = objectMapper.createObjectNode();
    payload.set("content", content);
    // Bitbucket has no server-side comment dedup; the adapter's idempotency story is the PR, not
    // comments — so this always reports POSTED (the SKIPPED_DUPLICATE asymmetry is the mock's).
    send(
        "POST",
        repoUri(pr, "pullrequests", String.valueOf(pr.number()), "comments"),
        payload,
        "commentOnPullRequest",
        IntegrationFailureCategory.BITBUCKET_PR_NOT_FOUND);
    log.info(
        "bitbucket_real comment_on_pull_request prRef={} resolution=posted durationMs={}",
        prRef,
        elapsedMs(startedAt));
    return CommentResult.POSTED;
  }

  /**
   * Story 3c-8 — a single authenticated GET probe: the repository's metadata when {@code repo} is
   * supplied (so {@code reachable} answers "is this repo reachable"), else {@code /user} (host
   * reachability + credential validity only). Every failure folds into a secret-free {@link
   * ConnectivityResult} — the probe never throws across the port. A 404 on a repo probe means the
   * credentials authenticated but that repository is absent.
   *
   * <p>When {@code credentialOverride} is non-blank it is attached as a per-request attribute the
   * {@code bitbucketRestClient} interceptor prefers over the host-env secret; otherwise the
   * host-env secret is used. The override is never logged.
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
        log.warn(
            "bitbucket_real verify_connectivity repoRef={} resolution=invalid_ref", repoRefLabel);
        return new ConnectivityResult(false, false, "bitbucket: invalid repository reference");
      }
    }
    try {
      RestClient.RequestHeadersSpec<?> request =
          (parsed == null
                  ? bitbucketRestClient.get().uri("/2.0/user")
                  : bitbucketRestClient
                      .get()
                      .uri(
                          "/2.0/repositories/{workspace}/{repo}",
                          parsed.workspace(),
                          parsed.name()))
              .attributes(
                  attrs -> {
                    if (credentialOverride != null && !credentialOverride.isBlank()) {
                      attrs.put(
                          BitbucketProperties.CREDENTIAL_OVERRIDE_ATTRIBUTE, credentialOverride);
                    }
                  });
      request.retrieve().toBodilessEntity();
      log.info(
          "bitbucket_real verify_connectivity repoRef={} resolution=ok durationMs={}",
          repoRefLabel,
          elapsedMs(startedAt));
      return ConnectivityResult.ok(
          parsed == null
              ? "bitbucket: authenticated"
              : "bitbucket: repository reachable + authenticated");
    } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden auth) {
      log.warn(
          "bitbucket_real verify_connectivity repoRef={} status={} resolution=unauthenticated",
          repoRefLabel,
          auth.getStatusCode());
      return ConnectivityResult.unauthenticated("bitbucket: authentication failed");
    } catch (HttpClientErrorException.NotFound notFound) {
      // Host + credentials are fine; the specific repository is absent.
      log.warn(
          "bitbucket_real verify_connectivity repoRef={} resolution=repo_not_found", repoRefLabel);
      return new ConnectivityResult(false, true, "bitbucket: repository not found");
    } catch (HttpClientErrorException.TooManyRequests rateLimited) {
      // 429 — the host answered and the credential was NOT rejected; we simply cannot complete the
      // probe right now. Report a non-misleading "could not verify" rather than an auth failure.
      log.warn(
          "bitbucket_real verify_connectivity repoRef={} status=429 resolution=rate_limited",
          repoRefLabel);
      return new ConnectivityResult(
          true, false, "bitbucket: rate limited — could not verify, retry later");
    } catch (HttpServerErrorException server) {
      // 5xx — the host answered with a server error; not an auth verdict. Same "could not verify".
      log.warn(
          "bitbucket_real verify_connectivity repoRef={} status={} resolution=server_error",
          repoRefLabel,
          server.getStatusCode());
      return new ConnectivityResult(
          true, false, "bitbucket: server error — could not verify, retry later");
    } catch (ResourceAccessException io) {
      log.warn(
          "bitbucket_real verify_connectivity repoRef={} resolution=unreachable cause={}",
          repoRefLabel,
          io.getMostSpecificCause().getClass().getSimpleName());
      log.debug("bitbucket_real verify_connectivity network fault repoRef={}", repoRefLabel, io);
      return ConnectivityResult.unreachable("bitbucket: host unreachable");
    } catch (RestClientException other) {
      log.warn(
          "bitbucket_real verify_connectivity repoRef={} resolution=unexpected cause={}",
          repoRefLabel,
          other.getClass().getSimpleName());
      return new ConnectivityResult(true, false, "bitbucket: unexpected response");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP plumbing — classification ladder.
  // ---------------------------------------------------------------------------------------------

  private Optional<JsonNode> getOrEmptyOnNotFound(
      URI uri, String operation, IntegrationFailureCategory notFoundCategory) {
    try {
      ResponseEntity<String> response =
          bitbucketRestClient.get().uri(uri).retrieve().toEntity(String.class);
      return Optional.of(parseJson(response.getBody(), operation));
    } catch (HttpClientErrorException.NotFound notFound) {
      return Optional.empty();
    } catch (RuntimeException error) {
      throw classify(error, operation, notFoundCategory);
    }
  }

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
          IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
          "Bitbucket " + operation + " failed to serialize request body",
          error);
    }
    try {
      ResponseEntity<String> response =
          bitbucketRestClient
              .method(HttpMethod.valueOf(method))
              .uri(uri)
              .contentType(MediaType.APPLICATION_JSON)
              .body(serialized)
              .retrieve()
              .toEntity(String.class);
      return parseJson(response.getBody(), operation);
    } catch (RuntimeException error) {
      throw classify(error, operation, notFoundCategory);
    }
  }

  private Optional<PullRequest> findOpenPullRequest(
      ParsedRepoRef repo, String headBranch, String baseBranch, String repoRef) {
    // Bitbucket's PR list supports a BBQL `q` filter; scope to the exact (source, destination) open
    // PR so the idempotency probe reuses rather than stacking a duplicate.
    // Escape the branch names for the quoted BBQL string literals — a `"` (legal in a git ref)
    // would
    // otherwise break the query, yielding a 400 misclassified as BITBUCKET_BRANCH_PROTECTED.
    String query =
        "source.branch.name=\""
            + bbqlQuote(headBranch)
            + "\" AND destination.branch.name=\""
            + bbqlQuote(baseBranch)
            + "\"";
    URI uri =
        UriComponentsBuilder.fromPath("")
            .pathSegment("2.0", "repositories", repo.workspace(), repo.name(), "pullrequests")
            .queryParam("q", query)
            .queryParam("state", "OPEN")
            .build()
            .encode()
            .toUri();
    JsonNode page =
        getOrEmptyOnNotFound(
                uri, "createPullRequest", IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND)
            .orElse(null);
    if (page == null) {
      return Optional.empty();
    }
    JsonNode values = page.path("values");
    if (!values.isArray() || values.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(toPullRequest(repoRef, values.get(0)));
  }

  /**
   * Maps an HTTP failure onto the Bitbucket {@link IntegrationFailureCategory} values — never a
   * generic unclassified error. Bitbucket Cloud returns 429 for rate limiting and 400 for PR-create
   * validation (e.g. a protected destination branch), which differs from GitHub's 403/422.
   */
  private RepositoryHostAdapterException classify(
      RuntimeException error, String operation, IntegrationFailureCategory notFoundCategory) {
    if (error instanceof RepositoryHostAdapterException already) {
      return already;
    }
    if (error instanceof HttpClientErrorException.Unauthorized unauthorized) {
      return warnAndBuild(
          operation, unauthorized, IntegrationFailureCategory.BITBUCKET_AUTH_FAILED, "auth failed");
    }
    if (error instanceof HttpClientErrorException.Forbidden forbidden) {
      return warnAndBuild(
          operation,
          forbidden,
          IntegrationFailureCategory.BITBUCKET_PERMISSION_DENIED,
          "permission denied");
    }
    if (error instanceof HttpClientErrorException.TooManyRequests tooMany) {
      log.warn("bitbucket_real {} rate_limited status=429", operation);
      return new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_RATE_LIMITED,
          "Bitbucket " + operation + " rate limited (429)",
          tooMany);
    }
    if (error instanceof HttpClientErrorException.NotFound notFound) {
      return warnAndBuild(operation, notFound, notFoundCategory, "not found");
    }
    if (error instanceof HttpClientErrorException.BadRequest badRequest) {
      if ("createPullRequest".equals(operation)) {
        // 400 on PR create is Bitbucket's protected-branch / validation signal.
        return warnAndBuild(
            operation,
            badRequest,
            IntegrationFailureCategory.BITBUCKET_BRANCH_PROTECTED,
            "branch protected / invalid request");
      }
      return warnAndBuild(
          operation,
          badRequest,
          IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
          "bad request");
    }
    if (error instanceof HttpServerErrorException server) {
      return warnAndBuild(
          operation, server, IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE, "server error");
    }
    if (error instanceof ResourceAccessException io) {
      log.warn(
          "bitbucket_real {} failed cause={} category=bitbucket_network_failure",
          operation,
          io.getMostSpecificCause().getClass().getSimpleName());
      return new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
          "Bitbucket "
              + operation
              + " network failure: "
              + io.getMostSpecificCause().getClass().getSimpleName(),
          io);
    }
    if (error instanceof RestClientResponseException other) {
      return warnAndBuild(
          operation,
          other,
          IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
          "unexpected status");
    }
    log.warn(
        "bitbucket_real {} failed cause={} category=bitbucket_network_failure",
        operation,
        error.getClass().getSimpleName());
    return new RepositoryHostAdapterException(
        IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
        "Bitbucket " + operation + " failed: " + error.getClass().getSimpleName(),
        error);
  }

  private RepositoryHostAdapterException warnAndBuild(
      String operation,
      RestClientResponseException error,
      IntegrationFailureCategory category,
      String reason) {
    log.warn(
        "bitbucket_real {} failed status={} category={}",
        operation,
        error.getStatusCode(),
        category.value());
    return new RepositoryHostAdapterException(
        category, "Bitbucket " + operation + " " + reason + ": " + error.getStatusCode(), error);
  }

  private ObjectNode branchNode(String branchName) {
    ObjectNode branch = objectMapper.createObjectNode();
    branch.put("name", branchName);
    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.set("branch", branch);
    return wrapper;
  }

  private static URI repoUri(ParsedRepoRef repo, String... extraSegments) {
    // The base URL is the host only (mirrors the GitHub adapter); the Bitbucket Cloud REST v2 API
    // version segment is carried explicitly in the path so an absolute-path URI does not clobber a
    // versioned base path.
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromPath("")
            .pathSegment("2.0", "repositories", repo.workspace(), repo.name());
    for (String segment : extraSegments) {
      builder.pathSegment(segment);
    }
    return builder.build().encode().toUri();
  }

  private static URI repoUri(ParsedPrRef pr, String... extraSegments) {
    return repoUri(new ParsedRepoRef(pr.workspace(), pr.name()), extraSegments);
  }

  // ---------------------------------------------------------------------------------------------
  // JSON → domain mapping.
  // ---------------------------------------------------------------------------------------------

  private Repository toRepository(String repoRef, JsonNode json) {
    return new Repository(
        RepositoryRef.of(repoRef),
        requireText(json, "full_name", "getRepositoryByRef"),
        requireText(json.path("mainbranch"), "name", "getRepositoryByRef"),
        requireText(json.path("links").path("html"), "href", "getRepositoryByRef"));
  }

  private PullRequest toPullRequest(String repoRef, JsonNode json) {
    int number = json.path("id").asInt(-1);
    if (number < 0) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
          "Bitbucket PR response missing numeric 'id' for repoRef=" + repoRef);
    }
    String sourceBranch = requireText(json.path("source").path("branch"), "name", "pullRequest");
    // Bitbucket PR `state` is OPEN/MERGED/DECLINED/SUPERSEDED; normalize to lower-case and derive
    // the merged boolean the domain needs (story 4.17) from the MERGED terminal state.
    String rawState = requireText(json, "state", "pullRequest");
    boolean merged = "MERGED".equalsIgnoreCase(rawState);
    String state = rawState.toLowerCase(java.util.Locale.ROOT);
    String url = requireText(json.path("links").path("html"), "href", "pullRequest");
    Instant createdAt = parseInstant(requireText(json, "created_on", "pullRequest"));
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
    String headSha = requireText(json.path("target"), "hash", "getBranchByRef");
    return new Branch(RepositoryRef.of(repoRef), name, headSha);
  }

  private JsonNode parseJson(String body, String operation) {
    if (body == null || body.isBlank()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
          "Bitbucket " + operation + " returned an empty body");
    }
    try {
      return objectMapper.readTree(body);
    } catch (IOException error) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
          "Bitbucket " + operation + " returned non-JSON body",
          error);
    }
  }

  private String redact(String payload) {
    if (payload == null) {
      return null;
    }
    return redactionPolicyService.redact(payload, REDACTION_CLASSIFICATION).sanitizedText();
  }

  /**
   * Backslash-escapes {@code \} and {@code "} so a branch name is safe inside a BBQL string
   * literal.
   */
  private static String bbqlQuote(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String requireText(JsonNode node, String field, String operation) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
          "Bitbucket " + operation + " response missing required field: " + field);
    }
    return value.asText();
  }

  /**
   * Parses a Bitbucket timestamp. Bitbucket Cloud returns offset-form ISO-8601 (e.g. {@code
   * 2026-01-01T00:00:00+00:00}), which {@link Instant#parse} rejects — parse via {@link
   * OffsetDateTime} first, falling back to {@link Instant#parse} for the {@code Z}-suffixed form.
   */
  private static Instant parseInstant(String raw) {
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (DateTimeParseException offsetFailure) {
      try {
        return Instant.parse(raw);
      } catch (DateTimeParseException instantFailure) {
        throw new RepositoryHostAdapterException(
            IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
            "Bitbucket response has non-ISO-8601 created_on: " + raw,
            instantFailure);
      }
    }
  }

  private static ParsedRepoRef parseRepoRef(String repoRef) {
    Matcher matcher = REPO_REF_PATTERN.matcher(repoRef);
    if (!matcher.matches()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND,
          "Bitbucket repository reference must be workspace/repo: " + repoRef);
    }
    return new ParsedRepoRef(matcher.group(1), matcher.group(2));
  }

  private static ParsedPrRef parsePrRef(String prRef) {
    Matcher matcher = PR_REF_PATTERN.matcher(prRef);
    if (!matcher.matches()) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_PR_NOT_FOUND,
          "Bitbucket pull-request reference must be workspace/repo#id: " + prRef);
    }
    try {
      return new ParsedPrRef(
          matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
    } catch (NumberFormatException outOfRange) {
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.BITBUCKET_PR_NOT_FOUND,
          "Bitbucket pull-request reference id is out of range: " + prRef,
          outOfRange);
    }
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }

  private record ParsedRepoRef(String workspace, String name) {
    String repoRef() {
      return workspace + "/" + name;
    }
  }

  private record ParsedPrRef(String workspace, String name, int number) {
    String repoRef() {
      return workspace + "/" + name;
    }
  }
}
