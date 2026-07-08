package org.dradgo.adapters.integration.ticketsource.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.jira.JiraProperties;
import org.dradgo.application.integration.ticketsource.CredentialBoundTicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.integration.ticketsource.CommentResult;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Production {@link TicketSourceAdapter} backed by the JIRA Cloud REST API v3 (the JIRA kind, story
 * 3i-1 / FR80). Activated under Spring profile {@code jira-real} (opt-in only; never in a default
 * profile group). Structurally mirrors {@code LinearRealAdapter}: marker idempotency, error
 * classification, credential-override interceptor, source-ticket URL build.
 *
 * <p><b>NOT {@code @Primary}</b> (Reconciliation R1): per-project resolution keys on {@link
 * #connectorKind()} via {@code ProjectConnectorResolver}, not {@code @Primary}. Marking this
 * adapter {@code @Primary} would collide with the {@code @Primary} {@code LinearRealAdapter} for
 * the single-injection {@code LinearPollingHost} when {@code linear-real}+{@code jira-real}
 * co-activate.
 *
 * <p><b>Idempotency</b> for {@link #postGovernedRunComment}: a fingerprint marker {@code <!--
 * deliveryline:run=<runPublicId> fp=<fingerprint> -->} is embedded in every comment so re-posting
 * the same fingerprint is a no-op ({@link CommentResult#SKIPPED_DUPLICATE}). {@link
 * #createSubticket} scans the parent for a {@code <!-- deliveryline:subticket key=<idempotencyKey>
 * child=<childRef> -->} marker and returns the existing child with {@code replay=true} on a hit.
 * The documented residual race is identical to the Linear adapter's: the parent-link marker comment
 * is posted <i>after</i> the sub-task is created, so a crash in that window can duplicate on replay
 * — the marker is the cross-instance backstop, not a transactional guarantee.
 *
 * <p><b>Failure classification</b> (AC6, no retry): 401/403 → {@link
 * IntegrationFailureCategory#LINK_FAILURE}; 429 / 5xx / network I/O → {@link
 * IntegrationFailureCategory#NETWORK_API_FAILURE}; 400/422 / malformed body → {@link
 * IntegrationFailureCategory#SYNC_FAILURE}; any other unexpected status → {@link
 * IntegrationFailureCategory#STATE_CONFLICT}. A {@code fetchTicketByReference} 404 is <i>not</i> an
 * error — it maps to {@link Optional#empty()}.
 *
 * <p>JIRA REST v3 comment/description bodies are Atlassian Document Format (ADF): comment payloads
 * are posted as a minimal ADF doc, and existing comments are scanned by extracting their ADF text
 * nodes and searching for the marker.
 */
@Component
@Profile("jira-real")
public class JiraRealAdapter implements CredentialBoundTicketSourceAdapter {

  private static final Logger log = LoggerFactory.getLogger(JiraRealAdapter.class);

  private static final String ISSUE_FIELDS =
      "summary,description,status,labels,reporter,created,updated";

  private static final String SUBTICKET_PROPERTY_KEY = "deliveryline.subticket";

  /** Per-page size when scanning existing comments for a marker. */
  private static final int IDEMPOTENCY_SCAN_PAGE_SIZE = 100;

  /** Hard cap on how many pages a marker scan will walk. */
  private static final int IDEMPOTENCY_SCAN_MAX_PAGES = 10;

  /**
   * Parses JIRA issue keys like {@code PROJ-123}. Group 1 = project key (uppercase letters, digits,
   * underscore); group 2 = issue number.
   */
  private static final Pattern TICKET_REF_PATTERN = Pattern.compile("^([A-Z][A-Z0-9_]*)-([0-9]+)$");

  /** JIRA timestamp shape, e.g. {@code 2024-01-02T03:04:05.678+0000} (offset without a colon). */
  private static final DateTimeFormatter JIRA_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);

  /** JQL {@code updated >} comparisons take {@code "yyyy-MM-dd HH:mm"} in the instance timezone. */
  private static final DateTimeFormatter JQL_UPDATED =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final RestClient jiraRestClient;
  private final JiraProperties properties;
  private final String projectPublicId;
  private final String credentialOverride;
  private final JiraOAuthTokenProvider tokenProvider;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Per-issue lock guarding the (scan existing comments + create) sequence so concurrent reposts of
   * the same fingerprint serialize within a single JVM. The intrinsic monitor is reentrant so the
   * nested parent-link comment post in {@link #createSubticket} is safe.
   */
  private final ConcurrentHashMap<String, CommentLockState> commentLocks;

  @Autowired
  public JiraRealAdapter(
      @Qualifier("jiraRestClient") RestClient jiraRestClient,
      JiraProperties properties,
      org.springframework.beans.factory.ObjectProvider<JiraOAuthTokenProvider> tokenProvider) {
    this(jiraRestClient, properties, tokenProvider.getIfAvailable());
  }

  public JiraRealAdapter(RestClient jiraRestClient, JiraProperties properties) {
    this(jiraRestClient, properties, (JiraOAuthTokenProvider) null);
  }

  public JiraRealAdapter(
      RestClient jiraRestClient, JiraProperties properties, JiraOAuthTokenProvider tokenProvider) {
    this(jiraRestClient, properties, null, null, tokenProvider, new ConcurrentHashMap<>());
  }

  private JiraRealAdapter(
      RestClient jiraRestClient,
      JiraProperties properties,
      String projectPublicId,
      String credentialOverride,
      JiraOAuthTokenProvider tokenProvider,
      ConcurrentHashMap<String, CommentLockState> commentLocks) {
    this.jiraRestClient = Objects.requireNonNull(jiraRestClient, "jiraRestClient");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.projectPublicId = projectPublicId;
    this.credentialOverride = credentialOverride;
    this.tokenProvider = tokenProvider;
    this.commentLocks = Objects.requireNonNull(commentLocks, "commentLocks");
  }

  @Override
  public TicketSourceAdapter withCredentialOverride(String credentialOverride) {
    return new JiraRealAdapter(
        jiraRestClient, properties, null, credentialOverride, tokenProvider, commentLocks);
  }

  @Override
  public TicketSourceAdapter withProjectCredential(
      String projectPublicId, String credentialOverride) {
    return new JiraRealAdapter(
        jiraRestClient,
        properties,
        projectPublicId,
        credentialOverride,
        tokenProvider,
        commentLocks);
  }

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.JIRA;
  }

  @Override
  public Optional<Ticket> fetchTicketByReference(TicketRef ref) {
    Objects.requireNonNull(ref, "ref");
    String ticketRef = ref.value();
    parseTicketRef(ticketRef); // fail fast on a malformed key (SYNC_FAILURE)
    long startedAt = System.nanoTime();
    String body;
    try {
      body =
          rawExecute(
              HttpMethod.GET,
              "/rest/api/3/issue/" + ticketRef + "?fields=" + ISSUE_FIELDS,
              null,
              credentialOverride);
    } catch (HttpClientErrorException.NotFound notFound) {
      log.info("jira_real fetch ticketRef={} resolution=not_found", ticketRef);
      return Optional.empty();
    } catch (RuntimeException failure) {
      throw classify("fetchTicketByReference", failure);
    }
    log.info("jira_real fetch ticketRef={} durationMs={}", ticketRef, elapsedMs(startedAt));
    return Optional.of(toTicket(parse(body, "fetchTicketByReference")));
  }

  @Override
  public List<Ticket> pollNewTickets(Instant since) {
    Objects.requireNonNull(since, "since");
    int batchSize = Math.max(1, properties.pollBatchSize());
    long startedAt = System.nanoTime();
    String jql = "updated > \"" + JQL_UPDATED.format(since) + "\" ORDER BY updated ASC";
    List<Ticket> collected = new ArrayList<>();
    int startAt = 0;
    int pages = 0;
    int total = Integer.MAX_VALUE;
    do {
      ObjectNode request = objectMapper.createObjectNode();
      request.put("jql", jql);
      request.put("startAt", startAt);
      request.put("maxResults", batchSize);
      ArrayNode fields = request.putArray("fields");
      for (String field : ISSUE_FIELDS.split(",")) {
        fields.add(field);
      }
      JsonNode response =
          parse(
              execute(
                  HttpMethod.POST,
                  "/rest/api/3/search",
                  writeJson(request, "pollNewTickets"),
                  "pollNewTickets",
                  credentialOverride),
              "pollNewTickets");
      JsonNode issues = response.path("issues");
      if (issues.isArray()) {
        for (JsonNode issue : issues) {
          Ticket ticket = toTicket(issue);
          if (ticket.updatedAt().isAfter(since)) {
            collected.add(ticket);
          }
        }
      }
      total = response.path("total").asInt(collected.size());
      startAt += batchSize;
      pages++;
    } while (startAt < total);
    collected.sort(Comparator.comparing(Ticket::updatedAt));
    log.info(
        "jira_real poll since={} returned={} tickets pages={} durationMs={}",
        since,
        collected.size(),
        pages,
        elapsedMs(startedAt));
    return collected;
  }

  @Override
  public CommentResult postGovernedRunComment(TicketRef ref, GovernedRunComment summary) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(summary, "summary");
    String ticketRef = ref.value();
    parseTicketRef(ticketRef);
    String marker = fingerprintMarker(summary);
    CommentLockState lockState = acquireCommentLock(ticketRef);
    synchronized (lockState.monitor()) {
      try {
        if (isAlreadyPosted(ticketRef, marker)) {
          log.info(
              "jira_real comment skipped ticketRef={} fingerprint={} reason=already_posted",
              safeLog(ticketRef),
              safeLog(summary.fingerprint()));
          return CommentResult.SKIPPED_DUPLICATE;
        }
        long startedAt = System.nanoTime();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("body", adfDoc(marker, summary.body()));
        execute(
            HttpMethod.POST,
            "/rest/api/3/issue/" + ticketRef + "/comment",
            writeJson(payload, "postGovernedRunComment"),
            "postGovernedRunComment",
            credentialOverride);
        log.info(
            "jira_real comment_posted ticketRef={} fingerprint={} durationMs={}",
            safeLog(ticketRef),
            safeLog(summary.fingerprint()),
            elapsedMs(startedAt));
        return CommentResult.POSTED;
      } finally {
        releaseCommentLock(ticketRef, lockState);
      }
    }
  }

  @Override
  public CreateSubticketResult createSubticket(TicketRef parentRef, SubticketDraft draft) {
    Objects.requireNonNull(parentRef, "parentRef");
    Objects.requireNonNull(draft, "draft");
    long startedAt = System.nanoTime();
    ParsedTicketRef parent = parseTicketRef(parentRef.value());
    CommentLockState lockState = acquireCommentLock(parentRef.value());
    synchronized (lockState.monitor()) {
      try {
        return createSubticketGuarded(parentRef, draft, parent, startedAt);
      } finally {
        releaseCommentLock(parentRef.value(), lockState);
      }
    }
  }

  private CreateSubticketResult createSubticketGuarded(
      TicketRef parentRef, SubticketDraft draft, ParsedTicketRef parent, long startedAt) {
    Optional<TicketRef> existing = findExistingSubticket(parentRef.value(), draft.idempotencyKey());
    if (existing.isPresent()) {
      return subticketReplay(parentRef, draft, existing.get(), startedAt, "parent-comment-marker");
    }
    Optional<TicketRef> existingByProperty =
        findExistingSubticketByProperty(parentRef.value(), draft.idempotencyKey());
    if (existingByProperty.isPresent()) {
      return subticketReplay(
          parentRef, draft, existingByProperty.get(), startedAt, "child-property");
    }

    ObjectNode fields = objectMapper.createObjectNode();
    fields.set("project", objectMapper.createObjectNode().put("key", parent.projectKey()));
    fields.set("parent", objectMapper.createObjectNode().put("key", parentRef.value()));
    fields.set("issuetype", objectMapper.createObjectNode().put("name", "Sub-task"));
    fields.put("summary", draft.title());
    fields.set("description", adfDoc(draft.description()));
    ObjectNode payload = objectMapper.createObjectNode();
    payload.set("fields", fields);
    ArrayNode properties = payload.putArray("properties");
    ObjectNode property = objectMapper.createObjectNode();
    property.put("key", SUBTICKET_PROPERTY_KEY);
    ObjectNode propertyValue = objectMapper.createObjectNode();
    propertyValue.put("idempotencyKey", draft.idempotencyKey());
    propertyValue.put("parentKey", parentRef.value());
    propertyValue.put("parentRunId", draft.parentRunId());
    propertyValue.put("ordinal", draft.ordinal());
    property.set("value", propertyValue);
    properties.add(property);

    JsonNode response =
        parse(
            execute(
                HttpMethod.POST,
                "/rest/api/3/issue",
                writeJson(payload, "createSubticket"),
                "createSubticket",
                credentialOverride),
            "createSubticket");
    TicketRef childRef = TicketRef.of(requireText(response, "key"));
    String marker = subticketMarker(draft.idempotencyKey(), childRef);
    String parentLinkBody =
        marker + System.lineSeparator() + "Created split child ticket " + childRef.value();
    postGovernedRunComment(
        parentRef,
        new GovernedRunComment(
            draft.parentRunId(),
            parentLinkFingerprint(draft),
            parentLinkBody,
            DataClassification.SHAREABLE_REDACTED));
    log.info(
        "jira_real subticket_created parentTicketRef={} childTicketRef={} workflowRunId={} subtaskOrdinal={} idempotencyKey={} durationMs={}",
        safeLog(parentRef.value()),
        safeLog(childRef.value()),
        safeLog(draft.parentRunId()),
        draft.ordinal(),
        safeLog(draft.idempotencyKey()),
        elapsedMs(startedAt));
    return new CreateSubticketResult(
        childRef,
        draft.idempotencyKey(),
        parentLinkFingerprint(draft),
        false,
        Map.of(
            "source",
            "jira",
            "parentKey",
            parentRef.value(),
            "jiraIssueId",
            response.path("id").asText("")));
  }

  private CreateSubticketResult subticketReplay(
      TicketRef parentRef,
      SubticketDraft draft,
      TicketRef childRef,
      long startedAt,
      String source) {
    log.info(
        "jira_real subticket_replay parentTicketRef={} childTicketRef={} workflowRunId={} subtaskOrdinal={} idempotencyKey={} source={} durationMs={}",
        safeLog(parentRef.value()),
        safeLog(childRef.value()),
        safeLog(draft.parentRunId()),
        draft.ordinal(),
        safeLog(draft.idempotencyKey()),
        source,
        elapsedMs(startedAt));
    return new CreateSubticketResult(
        childRef,
        draft.idempotencyKey(),
        parentLinkFingerprint(draft),
        true,
        Map.of("source", "jira", "parentKey", parentRef.value(), "replaySource", source));
  }

  @Override
  public Optional<String> buildSourceTicketUrl(TicketRef ref) {
    Objects.requireNonNull(ref, "ref");
    Matcher matcher = TICKET_REF_PATTERN.matcher(ref.value());
    String baseUrl = properties.baseUrl();
    if (!matcher.matches() || baseUrl == null || baseUrl.isBlank()) {
      // Unformable ref, or no base-url configured — snapshot no deep link (never a 404-prone one).
      log.debug(
          "jira_real build_source_ticket_url ticketRef={} present=false",
          MdcKeys.sanitizeForLog(ref.value()));
      return Optional.empty();
    }
    // Pure derivation — no network, no secret, never logged.
    String url = baseUrl + "/browse/" + ref.value();
    log.debug(
        "jira_real build_source_ticket_url ticketRef={} present=true",
        MdcKeys.sanitizeForLog(ref.value()));
    return Optional.of(url);
  }

  @Override
  public TicketSourceCapabilities getCapabilities() {
    return TicketSourceCapabilities.jiraDefaults();
  }

  @Override
  public ConnectivityResult verifyConnectivity(String credentialOverride) {
    long startedAt = System.nanoTime();
    try {
      execute(HttpMethod.GET, "/rest/api/3/myself", null, "verifyConnectivity", credentialOverride);
      String projectSearchBody =
          execute(
              HttpMethod.GET,
              "/rest/api/3/project/search?maxResults=1",
              null,
              "verifyConnectivity",
              credentialOverride);
      JsonNode projectSearch = parse(projectSearchBody, "verifyConnectivity");
      JsonNode values = projectSearch.path("values");
      if (!values.isArray() || values.isEmpty()) {
        log.warn("jira_real verify_connectivity resolution=no_reachable_projects");
        return new ConnectivityResult(true, false, "jira: no reachable projects visible");
      }
      log.info("jira_real verify_connectivity resolution=ok durationMs={}", elapsedMs(startedAt));
      return ConnectivityResult.ok("jira: authenticated");
    } catch (TicketSourceAdapterException failure) {
      return switch (failure.failureCategory()) {
        case LINK_FAILURE -> {
          log.warn("jira_real verify_connectivity resolution=unauthenticated");
          yield ConnectivityResult.unauthenticated("jira: authentication failed");
        }
        case NETWORK_API_FAILURE -> {
          log.warn("jira_real verify_connectivity resolution=unreachable");
          yield ConnectivityResult.unreachable("jira: host unreachable");
        }
        default -> {
          log.warn(
              "jira_real verify_connectivity resolution=unexpected category={}",
              failure.failureCategory().value());
          yield new ConnectivityResult(true, false, "jira: unexpected response");
        }
      };
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Marker idempotency
  // ---------------------------------------------------------------------------------------------

  private boolean isAlreadyPosted(String ticketRef, String marker) {
    int startAt = 0;
    int pages = 0;
    int total = Integer.MAX_VALUE;
    do {
      JsonNode comments = fetchCommentsPage(ticketRef, startAt);
      JsonNode nodes = comments.path("comments");
      if (!nodes.isArray() || !comments.has("total")) {
        throw new TicketSourceAdapterException(
            IntegrationFailureCategory.SYNC_FAILURE,
            "Malformed JIRA comments page for ticketRef=" + ticketRef);
      }
      if (nodes.isArray()) {
        for (JsonNode node : nodes) {
          if (extractAdfText(node.path("body")).contains(marker)) {
            return true;
          }
        }
      }
      total = comments.path("total").asInt(0);
      startAt += IDEMPOTENCY_SCAN_PAGE_SIZE;
      pages++;
    } while (startAt < total && pages < IDEMPOTENCY_SCAN_MAX_PAGES);
    if (startAt < total && pages >= IDEMPOTENCY_SCAN_MAX_PAGES) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "JIRA comment marker scan truncated before completion for ticketRef="
              + ticketRef
              + " (pages="
              + pages
              + ", maxPages="
              + IDEMPOTENCY_SCAN_MAX_PAGES
              + ")");
    }
    return false;
  }

  private Optional<TicketRef> findExistingSubticketByProperty(
      String parentRef, String idempotencyKey) {
    ObjectNode request = objectMapper.createObjectNode();
    request.put(
        "jql",
        "parent = "
            + parentRef
            + " AND issue.property["
            + SUBTICKET_PROPERTY_KEY
            + "].idempotencyKey = \""
            + escapeJqlString(idempotencyKey)
            + "\" ORDER BY created ASC");
    request.put("startAt", 0);
    request.put("maxResults", 1);
    request.putArray("fields").add("key");
    JsonNode response =
        parse(
            execute(
                HttpMethod.POST,
                "/rest/api/3/search",
                writeJson(request, "findExistingSubticketByProperty"),
                "findExistingSubticketByProperty",
                credentialOverride),
            "findExistingSubticketByProperty");
    JsonNode issues = response.path("issues");
    if (!issues.isArray() || issues.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(TicketRef.of(requireText(issues.get(0), "key")));
  }

  private Optional<TicketRef> findExistingSubticket(String parentRef, String idempotencyKey) {
    int startAt = 0;
    int pages = 0;
    int total = Integer.MAX_VALUE;
    do {
      JsonNode comments = fetchCommentsPage(parentRef, startAt);
      JsonNode nodes = comments.path("comments");
      if (nodes.isArray()) {
        for (JsonNode node : nodes) {
          Optional<TicketRef> child =
              parseSubticketMarker(extractAdfText(node.path("body")), idempotencyKey);
          if (child.isPresent()) {
            return child;
          }
        }
      }
      total = comments.path("total").asInt(0);
      startAt += IDEMPOTENCY_SCAN_PAGE_SIZE;
      pages++;
    } while (startAt < total && pages < IDEMPOTENCY_SCAN_MAX_PAGES);
    if (startAt < total) {
      // Fail closed on an idempotency-critical scan: a silent "not found" would create a DUPLICATE
      // sub-task, so surface a typed failure rather than returning empty on an incomplete scan.
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "JIRA sub-ticket marker scan truncated before completion for parentRef="
              + parentRef
              + " (pages="
              + pages
              + ", maxPages="
              + IDEMPOTENCY_SCAN_MAX_PAGES
              + ")");
    }
    return Optional.empty();
  }

  private JsonNode fetchCommentsPage(String ticketRef, int startAt) {
    return parse(
        execute(
            HttpMethod.GET,
            "/rest/api/3/issue/"
                + ticketRef
                + "/comment?startAt="
                + startAt
                + "&maxResults="
                + IDEMPOTENCY_SCAN_PAGE_SIZE,
            null,
            "listComments",
            credentialOverride),
        "listComments");
  }

  private static Optional<TicketRef> parseSubticketMarker(String body, String idempotencyKey) {
    String prefix = "<!-- deliveryline:subticket key=" + idempotencyKey + " child=";
    int start = body.indexOf(prefix);
    if (start < 0) {
      return Optional.empty();
    }
    int childStart = start + prefix.length();
    int end = body.indexOf(" -->", childStart);
    if (end < 0) {
      return Optional.empty();
    }
    String childRef = body.substring(childStart, end).trim();
    return childRef.isBlank() ? Optional.empty() : Optional.of(TicketRef.of(childRef));
  }

  private static String subticketMarker(String idempotencyKey, TicketRef childRef) {
    return "<!-- deliveryline:subticket key="
        + idempotencyKey
        + " child="
        + (childRef == null ? "pending" : childRef.value())
        + " -->";
  }

  private static String escapeJqlString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String parentLinkFingerprint(SubticketDraft draft) {
    return "subticket:" + draft.idempotencyKey();
  }

  private static String fingerprintMarker(GovernedRunComment summary) {
    return "<!-- deliveryline:run="
        + summary.runPublicId()
        + " fp="
        + summary.fingerprint()
        + " -->";
  }

  // ---------------------------------------------------------------------------------------------
  // ADF helpers
  // ---------------------------------------------------------------------------------------------

  /** Builds a minimal ADF document with the marker paragraph followed by the body paragraph. */
  private ObjectNode adfDoc(String marker, String body) {
    ObjectNode doc = objectMapper.createObjectNode();
    doc.put("type", "doc");
    doc.put("version", 1);
    ArrayNode content = doc.putArray("content");
    content.add(paragraph(marker));
    content.add(paragraph(body));
    return doc;
  }

  /** Builds a minimal single-paragraph ADF document (used for sub-task descriptions). */
  private ObjectNode adfDoc(String text) {
    ObjectNode doc = objectMapper.createObjectNode();
    doc.put("type", "doc");
    doc.put("version", 1);
    ArrayNode content = doc.putArray("content");
    content.add(paragraph(text));
    return doc;
  }

  private ObjectNode paragraph(String text) {
    ObjectNode paragraph = objectMapper.createObjectNode();
    paragraph.put("type", "paragraph");
    ArrayNode content = paragraph.putArray("content");
    ObjectNode textNode = objectMapper.createObjectNode();
    textNode.put("type", "text");
    textNode.put("text", text == null || text.isEmpty() ? " " : text);
    content.add(textNode);
    return paragraph;
  }

  /**
   * Extracts the concatenated plain text from an ADF node (walking every {@code text} node), or
   * returns a plain string body verbatim (JIRA REST v2 shape). Used both to map a JIRA description
   * to the neutral {@link Ticket#summary()} and to scan a comment body for a marker.
   */
  private static String extractAdfText(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.asText("");
    }
    StringBuilder builder = new StringBuilder();
    collectAdfText(node, builder);
    return builder.toString();
  }

  private static void collectAdfText(JsonNode node, StringBuilder builder) {
    if (node.isObject()) {
      JsonNode text = node.get("text");
      if (text != null && text.isTextual()) {
        if (builder.length() > 0) {
          builder.append(System.lineSeparator());
        }
        builder.append(text.asText(""));
      }
      JsonNode content = node.get("content");
      if (content != null && content.isArray()) {
        for (JsonNode child : content) {
          collectAdfText(child, builder);
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        collectAdfText(child, builder);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Mapping
  // ---------------------------------------------------------------------------------------------

  private Ticket toTicket(JsonNode issue) {
    String identifier = requireText(issue, "key");
    JsonNode fields = issue.path("fields");
    String title = requireText(fields, "summary");
    String summary = extractAdfText(fields.path("description"));
    JsonNode reporter = fields.path("reporter");
    String authorIdentity = reporter.path("emailAddress").asText("");
    if (authorIdentity.isBlank()) {
      authorIdentity = reporter.path("accountId").asText("");
    }
    if (authorIdentity.isBlank()) {
      authorIdentity = reporter.path("displayName").asText("unknown");
    }
    Instant createdAt = parseInstant(requireText(fields, "created"), "created");
    Instant updatedAt = parseInstant(requireText(fields, "updated"), "updated");
    Map<String, String> labels = extractLabels(fields.path("labels"));
    // Opaque source-status token (ADR 0007) — nullable when the issue omits status.
    String statusName = textOrNull(fields.path("status").path("name"));
    String statusId = textOrNull(fields.path("status").path("id"));
    return new Ticket(
        TicketRef.of(identifier),
        title,
        summary,
        authorIdentity,
        createdAt,
        updatedAt,
        labels,
        statusName,
        statusId);
  }

  private static Map<String, String> extractLabels(JsonNode labelsNode) {
    if (!labelsNode.isArray() || labelsNode.isEmpty()) {
      return Map.of();
    }
    Map<String, String> labels = new LinkedHashMap<>();
    for (JsonNode label : labelsNode) {
      String name = label.asText("");
      if (!name.isBlank()) {
        labels.put(name, "");
      }
    }
    return labels;
  }

  private static String textOrNull(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    String value = node.asText("");
    return value.isBlank() ? null : value;
  }

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "JIRA REST response missing required field: " + field);
    }
    return value.asText();
  }

  private static Instant parseInstant(String raw, String field) {
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException notIso) {
      try {
        return OffsetDateTime.parse(raw, JIRA_TIMESTAMP).toInstant();
      } catch (DateTimeParseException error) {
        throw new TicketSourceAdapterException(
            IntegrationFailureCategory.SYNC_FAILURE,
            "JIRA REST response has an unparseable " + field + ": " + raw,
            error);
      }
    }
  }

  private static ParsedTicketRef parseTicketRef(String ticketRef) {
    Matcher matcher = TICKET_REF_PATTERN.matcher(ticketRef);
    if (!matcher.matches()) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "JIRA issue key must match project-key/number shape (e.g. PROJ-123): " + ticketRef);
    }
    return new ParsedTicketRef(matcher.group(1), matcher.group(2));
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP + classification
  // ---------------------------------------------------------------------------------------------

  /**
   * Executes an HTTP call, mapping any transport/status failure to a typed exception (no retry).
   */
  private String execute(
      HttpMethod method, String uri, String jsonBody, String operation, String credentialOverride) {
    try {
      return rawExecute(method, uri, jsonBody, credentialOverride);
    } catch (RuntimeException failure) {
      throw classify(operation, failure);
    }
  }

  private String rawExecute(
      HttpMethod method, String uri, String jsonBody, String credentialOverride) {
    ResolvedCredential resolvedCredential = resolveCredential(credentialOverride);
    RestClient.RequestBodySpec spec =
        jiraRestClient
            .method(method)
            .uri(resolveUri(uri, resolvedCredential))
            .attributes(
                attrs -> {
                  if (resolvedCredential.accessToken() != null
                      && !resolvedCredential.accessToken().isBlank()) {
                    attrs.put(
                        JiraProperties.CREDENTIAL_OVERRIDE_ATTRIBUTE,
                        resolvedCredential.accessToken());
                  }
                });
    if (resolvedCredential.accessToken() != null && !resolvedCredential.accessToken().isBlank()) {
      spec = spec.headers(headers -> headers.setBearerAuth(resolvedCredential.accessToken()));
    }
    if (jsonBody != null) {
      spec = spec.body(jsonBody);
    }
    return spec.retrieve().body(String.class);
  }

  private ResolvedCredential resolveCredential(String credentialOverride) {
    if (tokenProvider != null && projectPublicId != null && credentialOverride != null) {
      Optional<JiraOAuthAccess> access = tokenProvider.refresh(projectPublicId, credentialOverride);
      if (access.isPresent()) {
        return new ResolvedCredential(access.get().accessToken(), access.get().apiBaseUrl());
      }
    }
    return new ResolvedCredential(credentialOverride, null);
  }

  private static String resolveUri(String uri, ResolvedCredential credential) {
    if (credential.apiBaseUrl() == null || credential.apiBaseUrl().isBlank()) {
      return uri;
    }
    return credential.apiBaseUrl() + uri;
  }

  private TicketSourceAdapterException classify(String operation, RuntimeException failure) {
    if (failure instanceof TicketSourceAdapterException typed) {
      return typed;
    }
    if (failure instanceof HttpClientErrorException.Unauthorized
        || failure instanceof HttpClientErrorException.Forbidden) {
      RestClientResponseException auth = (RestClientResponseException) failure;
      log.warn(
          "jira_real {} failed status={} category=link_failure", operation, auth.getStatusCode());
      return new TicketSourceAdapterException(
          IntegrationFailureCategory.LINK_FAILURE,
          "JIRA " + operation + " auth failed: " + auth.getStatusCode(),
          auth);
    }
    if (failure instanceof HttpClientErrorException.TooManyRequests rateLimit) {
      log.warn("jira_real {} failed status=429 category=network_api_failure", operation);
      return new TicketSourceAdapterException(
          IntegrationFailureCategory.NETWORK_API_FAILURE,
          "JIRA " + operation + " rate-limited (429)",
          rateLimit);
    }
    if (failure instanceof HttpServerErrorException server) {
      log.warn(
          "jira_real {} failed status={} category=network_api_failure",
          operation,
          server.getStatusCode());
      return new TicketSourceAdapterException(
          IntegrationFailureCategory.NETWORK_API_FAILURE,
          "JIRA " + operation + " server error: " + server.getStatusCode(),
          server);
    }
    if (failure instanceof ResourceAccessException io) {
      log.warn(
          "jira_real {} failed cause={} category=network_api_failure",
          operation,
          io.getMostSpecificCause().getClass().getSimpleName());
      return new TicketSourceAdapterException(
          IntegrationFailureCategory.NETWORK_API_FAILURE,
          "JIRA "
              + operation
              + " network failure: "
              + io.getMostSpecificCause().getClass().getSimpleName(),
          io);
    }
    if (failure instanceof HttpClientErrorException.BadRequest
        || failure instanceof HttpClientErrorException.UnprocessableEntity) {
      RestClientResponseException validation = (RestClientResponseException) failure;
      log.warn(
          "jira_real {} failed status={} category=sync_failure",
          operation,
          validation.getStatusCode());
      return new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "JIRA " + operation + " validation error: " + validation.getStatusCode(),
          validation);
    }
    if (failure instanceof RestClientResponseException other) {
      log.warn(
          "jira_real {} failed status={} category=state_conflict",
          operation,
          other.getStatusCode());
      return new TicketSourceAdapterException(
          IntegrationFailureCategory.STATE_CONFLICT,
          "JIRA " + operation + " unexpected status: " + other.getStatusCode(),
          other);
    }
    log.warn(
        "jira_real {} failed cause={} category=state_conflict",
        operation,
        failure.getClass().getSimpleName());
    return new TicketSourceAdapterException(
        IntegrationFailureCategory.STATE_CONFLICT,
        "JIRA " + operation + " unexpected failure: " + failure.getClass().getSimpleName(),
        failure);
  }

  private String writeJson(JsonNode payload, String operation) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (IOException error) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "Failed to serialize JSON payload for " + operation,
          error);
    }
  }

  private JsonNode parse(String responseBody, String operation) {
    if (responseBody == null || responseBody.isBlank()) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE, "JIRA " + operation + " returned empty body");
    }
    try {
      return objectMapper.readTree(responseBody);
    } catch (IOException error) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "JIRA " + operation + " returned non-JSON body",
          error);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Per-issue comment lock (mirrors LinearRealAdapter)
  // ---------------------------------------------------------------------------------------------

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

  private static String safeLog(String value) {
    return MdcKeys.sanitizeForLog(value);
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }

  private record ParsedTicketRef(String projectKey, String number) {}

  private record ResolvedCredential(String accessToken, String apiBaseUrl) {}

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
}
