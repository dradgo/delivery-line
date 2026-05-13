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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * Production {@link LinearAdapter} backed by the Linear GraphQL API. Activated under Spring
 * profile {@code linear-real} (opt-in only; never in any default profile group per AC3).
 *
 * <p>Idempotency contract for {@link #postGovernedRunComment}: a fingerprint marker
 * {@code <!-- deliveryline:run=<runPublicId> fp=<fingerprint> -->} is embedded in every comment
 * body so re-posting the same fingerprint is a no-op (Linear's GraphQL API has no native
 * comment-side idempotency key — story 1.14 AC3).
 *
 * <p>Failure classification per AC6 maps exceptions to {@link IntegrationFailureCategory}:
 * <ul>
 *   <li>401/403 → {@link IntegrationFailureCategory#LINK_FAILURE} (auth)</li>
 *   <li>429 / 5xx / network I/O → {@link IntegrationFailureCategory#NETWORK_API_FAILURE}</li>
 *   <li>GraphQL response with {@code data.issue == null} → {@link Optional#empty()} from
 *       {@link #fetchTicketByReference} (AC7 routes to {@code LINEAR_TICKET_NOT_FOUND}
 *       at the command layer)</li>
 *   <li>GraphQL {@code errors[]} with code {@code INVALID_INPUT} →
 *       {@link IntegrationFailureCategory#SYNC_FAILURE}; {@code RATELIMITED} → NETWORK_API_FAILURE;
 *       otherwise {@link IntegrationFailureCategory#STATE_CONFLICT}</li>
 * </ul>
 *
 * <p>Retry policy: this adapter does NOT retry. Surfaces typed failures for Epic 4 recovery to
 * decide retry policy (story 1.14 Dev Notes anti-pattern list).
 */
@Component
@Profile("linear-real")
public class LinearRealAdapter implements LinearAdapter {

	private static final Logger log = LoggerFactory.getLogger(LinearRealAdapter.class);

	private static final String FETCH_QUERY_RESOURCE = "graphql/linear/fetch-ticket-by-reference.graphql";
	private static final String POLL_QUERY_RESOURCE = "graphql/linear/poll-tickets-since.graphql";
	private static final String POST_COMMENT_QUERY_RESOURCE = "graphql/linear/post-comment.graphql";
	private static final String LIST_COMMENTS_QUERY_RESOURCE = "graphql/linear/list-comments.graphql";

	/** Number of existing comments to scan for the fingerprint marker during idempotency check. */
	private static final int IDEMPOTENCY_SCAN_DEPTH = 100;

	private final RestClient linearRestClient;
	private final LinearProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final String fetchQuery;
	private final String pollQuery;
	private final String postCommentQuery;
	private final String listCommentsQuery;

	public LinearRealAdapter(
		@Qualifier("linearRestClient") RestClient linearRestClient,
		LinearProperties properties
	) {
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
		Map<String, Object> variables = Map.of("identifier", ticketRef);
		long startedAt = System.nanoTime();
		JsonNode response = executeGraphQL(fetchQuery, variables, "fetchTicketByReference");
		log.info("linear_real fetch ticketRef={} durationMs={}", ticketRef, elapsedMs(startedAt));
		JsonNode issue = response.path("data").path("issue");
		if (issue.isMissingNode() || issue.isNull()) {
			log.info("linear_real fetch ticketRef={} resolution=not_found", ticketRef);
			return Optional.empty();
		}
		return Optional.of(toLinearTicket(issue));
	}

	@Override
	public List<LinearTicket> pollNewTickets(Instant since) {
		Objects.requireNonNull(since, "since");
		int batchSize = Math.max(1, properties.pollBatchSize());
		Map<String, Object> variables = Map.of("since", since.toString(), "first", batchSize);
		long startedAt = System.nanoTime();
		JsonNode response = executeGraphQL(pollQuery, variables, "pollNewTickets");
		JsonNode nodes = response.path("data").path("issues").path("nodes");
		List<LinearTicket> tickets = new ArrayList<>();
		if (nodes.isArray()) {
			for (JsonNode node : nodes) {
				tickets.add(toLinearTicket(node));
			}
		}
		log.info("linear_real poll since={} returned={} tickets durationMs={}",
			since, tickets.size(), elapsedMs(startedAt));
		return tickets;
	}

	@Override
	public void postGovernedRunComment(String ticketRef, GovernedRunComment summary) {
		Objects.requireNonNull(ticketRef, "ticketRef");
		Objects.requireNonNull(summary, "summary");
		String marker = fingerprintMarker(summary);
		if (isAlreadyPosted(ticketRef, marker)) {
			log.info("linear_real comment skipped ticketRef={} fingerprint={} reason=already_posted",
				ticketRef, summary.fingerprint());
			return;
		}
		String body = marker + System.lineSeparator() + summary.body();
		Map<String, Object> variables = Map.of("issueId", ticketRef, "body", body);
		long startedAt = System.nanoTime();
		JsonNode response = executeGraphQL(postCommentQuery, variables, "postGovernedRunComment");
		boolean success = response.path("data").path("commentCreate").path("success").asBoolean(false);
		log.info("linear_real comment_posted ticketRef={} fingerprint={} success={} durationMs={}",
			ticketRef, summary.fingerprint(), success, elapsedMs(startedAt));
		if (!success) {
			throw new LinearAdapterException(
				IntegrationFailureCategory.SYNC_FAILURE,
				"Linear commentCreate returned success=false for ticketRef=" + ticketRef);
		}
	}

	private boolean isAlreadyPosted(String ticketRef, String marker) {
		Map<String, Object> variables = Map.of("issueId", ticketRef, "first", IDEMPOTENCY_SCAN_DEPTH);
		try {
			JsonNode response = executeGraphQL(listCommentsQuery, variables, "listComments");
			JsonNode nodes = response.path("data").path("issue").path("comments").path("nodes");
			if (!nodes.isArray()) {
				return false;
			}
			for (JsonNode node : nodes) {
				String existingBody = node.path("body").asText("");
				if (existingBody.contains(marker)) {
					return true;
				}
			}
			return false;
		} catch (LinearAdapterException error) {
			// A failure to list comments is itself a posting failure — surface it rather than
			// risking a duplicate write.
			throw error;
		}
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
				"Failed to serialize GraphQL payload for " + operation, error);
		}
		String responseBody;
		try {
			responseBody = linearRestClient.post()
				.uri("")
				.body(body)
				.retrieve()
				.body(String.class);
		} catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden auth) {
			log.warn("linear_real {} failed status={} category=link_failure", operation, auth.getStatusCode());
			throw new LinearAdapterException(
				IntegrationFailureCategory.LINK_FAILURE,
				"Linear " + operation + " auth failed: " + auth.getStatusCode(), auth);
		} catch (HttpClientErrorException.TooManyRequests rateLimit) {
			log.warn("linear_real {} failed status=429 category=network_api_failure", operation);
			throw new LinearAdapterException(
				IntegrationFailureCategory.NETWORK_API_FAILURE,
				"Linear " + operation + " rate-limited (429)", rateLimit);
		} catch (HttpServerErrorException server) {
			log.warn("linear_real {} failed status={} category=network_api_failure", operation, server.getStatusCode());
			throw new LinearAdapterException(
				IntegrationFailureCategory.NETWORK_API_FAILURE,
				"Linear " + operation + " server error: " + server.getStatusCode(), server);
		} catch (ResourceAccessException io) {
			log.warn("linear_real {} failed cause={} category=network_api_failure",
				operation, io.getMostSpecificCause().getClass().getSimpleName());
			throw new LinearAdapterException(
				IntegrationFailureCategory.NETWORK_API_FAILURE,
				"Linear " + operation + " network failure: " + io.getMostSpecificCause().getClass().getSimpleName(),
				io);
		} catch (RestClientResponseException other) {
			log.warn("linear_real {} failed status={} category=state_conflict", operation, other.getStatusCode());
			throw new LinearAdapterException(
				IntegrationFailureCategory.STATE_CONFLICT,
				"Linear " + operation + " unexpected status: " + other.getStatusCode(), other);
		}
		if (responseBody == null || responseBody.isBlank()) {
			throw new LinearAdapterException(
				IntegrationFailureCategory.SYNC_FAILURE,
				"Linear " + operation + " returned empty body");
		}
		JsonNode root;
		try {
			root = objectMapper.readTree(responseBody);
		} catch (IOException error) {
			throw new LinearAdapterException(
				IntegrationFailureCategory.SYNC_FAILURE,
				"Linear " + operation + " returned non-JSON body", error);
		}
		JsonNode errors = root.path("errors");
		if (errors.isArray() && !errors.isEmpty()) {
			String firstCode = errors.get(0).path("extensions").path("code").asText("");
			IntegrationFailureCategory category = mapGraphQLErrorCode(firstCode);
			log.warn("linear_real {} graphql_error code={} category={}", operation, firstCode, category.value());
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
		return new LinearTicket(identifier, title, summary, authorIdentity, createdAt, updatedAt, labels);
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
				"Linear GraphQL response has non-ISO-8601 " + field + ": " + raw, error);
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
		return "<!-- deliveryline:run=" + summary.runPublicId() + " fp=" + summary.fingerprint() + " -->";
	}

	private static long elapsedMs(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}

	private static String loadQuery(String resource) {
		try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException("Missing Linear GraphQL query resource: " + resource);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new IllegalStateException("Failed to load Linear GraphQL query " + resource, error);
		}
	}
}
