package org.dradgo.adapters.integration.linear;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.application.integration.linear.GovernedRunComment;
import org.dradgo.application.integration.linear.LinearAdapter;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearProperties;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Production {@link LinearAdapter} backed by the Linear GraphQL API. Activated under Spring profile
 * {@code linear-real} (opt-in only; never in any default profile group per AC3).
 *
 * <p>Idempotency contract for {@link #postGovernedRunComment}: a fingerprint marker {@code <!--
 * deliveryline:run=<runPublicId> fp=<fingerprint> -->} is embedded in every comment body so
 * re-posting the same fingerprint is a no-op (Linear's GraphQL API has no native comment-side
 * idempotency key — story 1.14 AC3).
 *
 * <p>Failure classification per AC6 maps exceptions to {@link IntegrationFailureCategory}:
 *
 * <ul>
 *   <li>401/403 → {@link IntegrationFailureCategory#LINK_FAILURE} (auth)
 *   <li>429 / 5xx / network I/O → {@link IntegrationFailureCategory#NETWORK_API_FAILURE}
 *   <li>GraphQL response with {@code data.issue == null} → {@link Optional#empty()} from {@link
 *       #fetchTicketByReference} (AC7 routes to {@code LINEAR_TICKET_NOT_FOUND} at the command
 *       layer)
 *   <li>GraphQL {@code errors[]} with code {@code INVALID_INPUT} → {@link
 *       IntegrationFailureCategory#SYNC_FAILURE}; {@code RATELIMITED} → NETWORK_API_FAILURE;
 *       otherwise {@link IntegrationFailureCategory#STATE_CONFLICT}
 * </ul>
 *
 * <p>Retry policy: this adapter does NOT retry. Surfaces typed failures for Epic 4 recovery to
 * decide retry policy (story 1.14 Dev Notes anti-pattern list).
 */
@Component
@Profile("linear-real")
public class LinearRealAdapter implements LinearAdapter {

  private static final Logger log = LoggerFactory.getLogger(LinearRealAdapter.class);

  private static final String FETCH_QUERY_RESOURCE =
      "graphql/linear/fetch-ticket-by-reference.graphql";
  private static final String POLL_QUERY_RESOURCE = "graphql/linear/poll-tickets-since.graphql";
  private static final String POST_COMMENT_QUERY_RESOURCE = "graphql/linear/post-comment.graphql";
  private static final String LIST_COMMENTS_QUERY_RESOURCE = "graphql/linear/list-comments.graphql";

  /** Per-page size when scanning existing comments for the fingerprint marker. */
  private static final int IDEMPOTENCY_SCAN_PAGE_SIZE = 100;

  /** Hard cap on how many pages the idempotency marker scan will walk. */
  private static final int IDEMPOTENCY_SCAN_MAX_PAGES = 10;

  /**
   * Hard cap on pages drained per {@link #pollNewTickets(Instant)} call. Hitting the cap is treated
   * as a sync failure so callers preserve their watermark instead of silently truncating the
   * observation window.
   */
  private static final int POLL_MAX_PAGES = 20;

  /**
   * Parses Linear human references like {@code LIN-123}. Group 1 = team key (uppercase letters,
   * digits, underscore — Linear team keys are typically 3–5 letters); group 2 = ticket number.
   */
  private static final Pattern TICKET_REF_PATTERN = Pattern.compile("^([A-Z][A-Z0-9_]*)-([0-9]+)$");

  private final RestClient linearRestClient;
  private final LinearProperties properties;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String fetchQuery;
  private final String pollQuery;
  private final String postCommentQuery;
  private final String listCommentsQuery;

  /**
   * Per-ticket lock guarding the (scan existing comments + create comment) sequence so concurrent
   * reposts of the same fingerprint serialize within a single JVM. Across multiple JVM instances
   * Linear's API has no native serialization primitive; the embedded fingerprint marker remains the
   * cross-instance backstop.
   */
  private final ConcurrentHashMap<String, CommentLockState> commentLocks =
      new ConcurrentHashMap<>();

  public LinearRealAdapter(
      @Qualifier("linearRestClient") RestClient linearRestClient, LinearProperties properties) {
    this.linearRestClient = Objects.requireNonNull(linearRestClient, "linearRestClient");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.fetchQuery = loadQuery(FETCH_QUERY_RESOURCE);
    this.pollQuery = loadQuery(POLL_QUERY_RESOURCE);
    this.postCommentQuery = loadQuery(POST_COMMENT_QUERY_RESOURCE);
    this.listCommentsQuery = loadQuery(LIST_COMMENTS_QUERY_RESOURCE);
  }

  @Override
  public Optional<LinearTicket> fetchTicketByReference(String ticketRef) {
    Objects.requireNonNull(ticketRef, "ticketRef");
    // Story 3a.4 — single-ticket resolution is authoritatively scoped by the reference's OWN team
    // key (parsed below into the fetch query's `team.key.eq`). Do NOT layer the poll-scope config
    // (properties.teamKey()/projectId()) onto this path — that scope is a poll-only concern.
    ParsedTicketRef parsed = parseTicketRef(ticketRef);
    Map<String, Object> variables =
        Map.of(
            "teamKey", parsed.teamKey(),
            "number", parsed.number());
    long startedAt = System.nanoTime();
    JsonNode response = executeGraphQL(fetchQuery, variables, "fetchTicketByReference");
    log.info("linear_real fetch ticketRef={} durationMs={}", ticketRef, elapsedMs(startedAt));
    JsonNode nodes = response.path("data").path("issues").path("nodes");
    if (!nodes.isArray() || nodes.isEmpty()) {
      log.info("linear_real fetch ticketRef={} resolution=not_found", ticketRef);
      return Optional.empty();
    }
    return Optional.of(toLinearTicket(nodes.get(0)));
  }

  @Override
  public List<LinearTicket> pollNewTickets(Instant since) {
    Objects.requireNonNull(since, "since");
    int batchSize = Math.max(1, properties.pollBatchSize());
    long startedAt = System.nanoTime();
    // Story 3a.4 — assemble the IssueFilter in Java and pass it as a whole variable. Conditional
    // team/project keys must be OMITTED when unconfigured (emitting `eq: null` would filter FOR
    // null); building the map here keeps absent scope byte-identical to the original poll.
    Map<String, Object> filter = buildPollFilter(since);
    List<LinearTicket> collected = new ArrayList<>();
    String afterCursor = null;
    int pages = 0;
    boolean hasNextPage = false;
    do {
      Map<String, Object> variables = new LinkedHashMap<>();
      variables.put("filter", filter);
      variables.put("first", batchSize);
      if (afterCursor != null) {
        variables.put("after", afterCursor);
      }
      JsonNode response = executeGraphQL(pollQuery, variables, "pollNewTickets");
      JsonNode issuesNode = response.path("data").path("issues");
      JsonNode nodes = issuesNode.path("nodes");
      if (nodes.isArray()) {
        for (JsonNode node : nodes) {
          collected.add(toLinearTicket(node));
        }
      }
      pages++;
      JsonNode pageInfo = issuesNode.path("pageInfo");
      hasNextPage = pageInfo.path("hasNextPage").asBoolean(false);
      String endCursor = pageInfo.path("endCursor").asText("");
      afterCursor = endCursor.isBlank() ? null : endCursor;
    } while (hasNextPage && afterCursor != null && pages < POLL_MAX_PAGES);
    if (hasNextPage && pages >= POLL_MAX_PAGES) {
      log.warn(
          "linear_real poll page_cap_hit since={} pages={} maxPages={} collected={} action=fail_closed",
          since,
          pages,
          POLL_MAX_PAGES,
          collected.size());
      throw new LinearAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "Linear poll exceeded page cap for since="
              + since
              + " (pages="
              + pages
              + ", maxPages="
              + POLL_MAX_PAGES
              + ")");
    }
    collected.sort(Comparator.comparing(LinearTicket::updatedAt));
    log.info(
        "linear_real poll since={} teamKey={} projectId={} returned={} tickets pages={} durationMs={}",
        since,
        scopeOrNone(properties.teamKey()),
        scopeOrNone(properties.projectId()),
        collected.size(),
        pages,
        elapsedMs(startedAt));
    return collected;
  }

  /**
   * Builds the poll {@code IssueFilter} map (story 3a.4). Always carries {@code updatedAt.gt}; adds
   * {@code team.key.eq} and/or {@code project.id.eq} only when the corresponding {@link
   * LinearProperties} scope field is non-blank. Conditional keys are OMITTED (never {@code eq:
   * null}) so an unconfigured poll is byte-identical to the original whole-workspace behavior.
   * Linear ANDs sibling {@code IssueFilter} keys, so team + project + updatedAt narrow together.
   */
  private Map<String, Object> buildPollFilter(Instant since) {
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("updatedAt", Map.of("gt", since.toString()));
    String teamKey = properties.teamKey();
    if (teamKey != null && !teamKey.isBlank()) {
      filter.put("team", Map.of("key", Map.of("eq", teamKey)));
    }
    String projectId = properties.projectId();
    if (projectId != null && !projectId.isBlank()) {
      filter.put("project", Map.of("id", Map.of("eq", projectId)));
    }
    return filter;
  }

  /** Non-secret scope identifier for logging; {@code "none"} when absent. */
  private static String scopeOrNone(String value) {
    return value == null || value.isBlank() ? "none" : value;
  }

  @Override
  public void postGovernedRunComment(String ticketRef, GovernedRunComment summary) {
    Objects.requireNonNull(ticketRef, "ticketRef");
    Objects.requireNonNull(summary, "summary");
    String marker = fingerprintMarker(summary);
    CommentLockState lockState = acquireCommentLock(ticketRef);
    synchronized (lockState.monitor()) {
      try {
        if (isAlreadyPosted(ticketRef, marker)) {
          log.info(
              "linear_real comment skipped ticketRef={} fingerprint={} reason=already_posted",
              ticketRef,
              summary.fingerprint());
          return;
        }
        String body = marker + System.lineSeparator() + summary.body();
        Map<String, Object> variables = Map.of("issueId", ticketRef, "body", body);
        long startedAt = System.nanoTime();
        JsonNode response = executeGraphQL(postCommentQuery, variables, "postGovernedRunComment");
        boolean success =
            response.path("data").path("commentCreate").path("success").asBoolean(false);
        log.info(
            "linear_real comment_posted ticketRef={} fingerprint={} success={} durationMs={}",
            ticketRef,
            summary.fingerprint(),
            success,
            elapsedMs(startedAt));
        if (!success) {
          throw new LinearAdapterException(
              IntegrationFailureCategory.SYNC_FAILURE,
              "Linear commentCreate returned success=false for ticketRef=" + ticketRef);
        }
      } finally {
        releaseCommentLock(ticketRef, lockState);
      }
    }
  }

  private CommentLockState acquireCommentLock(String ticketRef) {
    return commentLocks.compute(
        ticketRef,
        (key, existing) -> {
          CommentLockState state = existing == null ? new CommentLockState() : existing;
          state.incrementWaiters();
          return state;
        });
  }

  private void releaseCommentLock(String ticketRef, CommentLockState lockState) {
    commentLocks.computeIfPresent(
        ticketRef,
        (key, existing) -> {
          if (existing != lockState) {
            return existing;
          }
          return existing.decrementWaitersAndIsIdle() ? null : existing;
        });
  }

  private boolean isAlreadyPosted(String ticketRef, String marker) {
    String afterCursor = null;
    int pages = 0;
    boolean hasNextPage = false;
    do {
      Map<String, Object> variables = new LinkedHashMap<>();
      variables.put("issueId", ticketRef);
      variables.put("first", IDEMPOTENCY_SCAN_PAGE_SIZE);
      if (afterCursor != null) {
        variables.put("after", afterCursor);
      }
      JsonNode response = executeGraphQL(listCommentsQuery, variables, "listComments");
      JsonNode comments = response.path("data").path("issue").path("comments");
      JsonNode nodes = comments.path("nodes");
      if (nodes.isArray()) {
        for (JsonNode node : nodes) {
          String existingBody = node.path("body").asText("");
          if (existingBody.contains(marker)) {
            return true;
          }
        }
      }
      pages++;
      JsonNode pageInfo = comments.path("pageInfo");
      hasNextPage = pageInfo.path("hasNextPage").asBoolean(false);
      String endCursor = pageInfo.path("endCursor").asText("");
      afterCursor = endCursor.isBlank() ? null : endCursor;
    } while (hasNextPage && afterCursor != null && pages < IDEMPOTENCY_SCAN_MAX_PAGES);
    if (hasNextPage && pages >= IDEMPOTENCY_SCAN_MAX_PAGES) {
      // If we hit the scan cap without finding the marker, treat as "not posted" rather than
      // raise — duplicating a comment is recoverable; missing the post is worse for AC3
      // "best-effort write-back". The cap is high enough (10 pages × 100 = 1000 comments)
      // that this is an extreme outlier.
      log.warn(
          "linear_real listComments scan_cap_hit ticketRef={} pages={} maxPages={}",
          ticketRef,
          pages,
          IDEMPOTENCY_SCAN_MAX_PAGES);
    }
    return false;
  }

  private JsonNode executeGraphQL(String query, Map<String, Object> variables, String operation) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("query", query);
    payload.set("variables", objectMapper.valueToTree(variables));
    String body;
    try {
      body = objectMapper.writeValueAsString(payload);
    } catch (IOException error) {
      throw new LinearAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "Failed to serialize GraphQL payload for " + operation,
          error);
    }
    String responseBody;
    try {
      responseBody = linearRestClient.post().uri("").body(body).retrieve().body(String.class);
    } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden auth) {
      log.warn(
          "linear_real {} failed status={} category=link_failure", operation, auth.getStatusCode());
      throw new LinearAdapterException(
          IntegrationFailureCategory.LINK_FAILURE,
          "Linear " + operation + " auth failed: " + auth.getStatusCode(),
          auth);
    } catch (HttpClientErrorException.TooManyRequests rateLimit) {
      log.warn("linear_real {} failed status=429 category=network_api_failure", operation);
      throw new LinearAdapterException(
          IntegrationFailureCategory.NETWORK_API_FAILURE,
          "Linear " + operation + " rate-limited (429)",
          rateLimit);
    } catch (HttpServerErrorException server) {
      log.warn(
          "linear_real {} failed status={} category=network_api_failure",
          operation,
          server.getStatusCode());
      throw new LinearAdapterException(
          IntegrationFailureCategory.NETWORK_API_FAILURE,
          "Linear " + operation + " server error: " + server.getStatusCode(),
          server);
    } catch (ResourceAccessException io) {
      log.warn(
          "linear_real {} failed cause={} category=network_api_failure",
          operation,
          io.getMostSpecificCause().getClass().getSimpleName());
      throw new LinearAdapterException(
          IntegrationFailureCategory.NETWORK_API_FAILURE,
          "Linear "
              + operation
              + " network failure: "
              + io.getMostSpecificCause().getClass().getSimpleName(),
          io);
    } catch (RestClientResponseException other) {
      log.warn(
          "linear_real {} failed status={} category=state_conflict",
          operation,
          other.getStatusCode());
      throw new LinearAdapterException(
          IntegrationFailureCategory.STATE_CONFLICT,
          "Linear " + operation + " unexpected status: " + other.getStatusCode(),
          other);
    }
    if (responseBody == null || responseBody.isBlank()) {
      throw new LinearAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE, "Linear " + operation + " returned empty body");
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(responseBody);
    } catch (IOException error) {
      throw new LinearAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "Linear " + operation + " returned non-JSON body",
          error);
    }
    JsonNode errors = root.path("errors");
    if (errors.isArray() && !errors.isEmpty()) {
      String firstCode = errors.get(0).path("extensions").path("code").asText("");
      IntegrationFailureCategory category = mapGraphQLErrorCode(firstCode);
      log.warn(
          "linear_real {} graphql_error code={} category={}",
          operation,
          firstCode,
          category.value());
      throw new LinearAdapterException(
          category,
          "Linear " + operation + " returned GraphQL errors[0].extensions.code=" + firstCode);
    }
    return root;
  }

  private static IntegrationFailureCategory mapGraphQLErrorCode(String code) {
    return switch (code) {
      case "INVALID_INPUT" -> IntegrationFailureCategory.SYNC_FAILURE;
      case "RATELIMITED" -> IntegrationFailureCategory.NETWORK_API_FAILURE;
      case "AUTHENTICATION_ERROR" -> IntegrationFailureCategory.LINK_FAILURE;
      default -> IntegrationFailureCategory.STATE_CONFLICT;
    };
  }

  private LinearTicket toLinearTicket(JsonNode issue) {
    String identifier = requireText(issue, "identifier");
    String title = requireText(issue, "title");
    String summary = issue.path("description").asText("");
    JsonNode creator = issue.path("creator");
    String authorIdentity = creator.path("email").asText("");
    if (authorIdentity.isBlank()) {
      authorIdentity = creator.path("displayName").asText("unknown");
    }
    Instant createdAt = parseInstant(requireText(issue, "createdAt"), "createdAt");
    Instant updatedAt = parseInstant(requireText(issue, "updatedAt"), "updatedAt");
    Map<String, String> labels = extractLabels(issue.path("labels").path("nodes"));
    return new LinearTicket(
        identifier, title, summary, authorIdentity, createdAt, updatedAt, labels);
  }

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
      throw new LinearAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "Linear GraphQL response missing required field: " + field);
    }
    return value.asText();
  }

  private static Instant parseInstant(String raw, String field) {
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException error) {
      throw new LinearAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "Linear GraphQL response has non-ISO-8601 " + field + ": " + raw,
          error);
    }
  }

  private static Map<String, String> extractLabels(JsonNode labelsNodes) {
    if (!labelsNodes.isArray() || labelsNodes.isEmpty()) {
      return Map.of();
    }
    Map<String, String> labels = new LinkedHashMap<>();
    Iterator<JsonNode> it = labelsNodes.elements();
    while (it.hasNext()) {
      String name = it.next().path("name").asText("");
      if (!name.isBlank()) {
        labels.put(name, "");
      }
    }
    return labels;
  }

  private static String fingerprintMarker(GovernedRunComment summary) {
    return "<!-- deliveryline:run="
        + summary.runPublicId()
        + " fp="
        + summary.fingerprint()
        + " -->";
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }

  private static ParsedTicketRef parseTicketRef(String ticketRef) {
    Matcher matcher = TICKET_REF_PATTERN.matcher(ticketRef);
    if (!matcher.matches()) {
      throw new LinearAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "Linear ticket reference must match team-key/number shape (e.g. LIN-123): " + ticketRef);
    }
    String teamKey = matcher.group(1);
    long number;
    try {
      number = Long.parseLong(matcher.group(2));
    } catch (NumberFormatException error) {
      throw new LinearAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "Linear ticket reference carries a non-numeric ticket number: " + ticketRef,
          error);
    }
    return new ParsedTicketRef(teamKey, number);
  }

  private record ParsedTicketRef(String teamKey, long number) {}

  private static final class CommentLockState {

    private final Object monitor = new Object();
    private int waiters;

    private Object monitor() {
      return monitor;
    }

    private void incrementWaiters() {
      waiters++;
    }

    private boolean decrementWaitersAndIsIdle() {
      waiters--;
      return waiters == 0;
    }
  }

  private static String loadQuery(String resource) {
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing Linear GraphQL query resource: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to load Linear GraphQL query " + resource, error);
    }
  }
}
