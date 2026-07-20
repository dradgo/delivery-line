package org.dradgo.adapters.integration.ticketsource.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.project.ProjectCredentialService;
import org.dradgo.domain.registry.ConnectorRole;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Refreshes encrypted JIRA OAuth grants into one-use access tokens without logging secret material.
 *
 * <p>Self-wired as a {@code @Component} under the {@code jira-real} profile (mirroring {@code
 * JiraRealAdapter}) rather than defined as an {@code @Bean} in {@code JiraConfiguration}: the
 * {@code LAYERED_BOUNDARIES} ArchUnit rule forbids the {@code infrastructure} layer from
 * referencing an {@code adapters} type, so the wiring lives with the adapter it belongs to.
 */
@Component
@Profile("jira-real")
public class JiraOAuthTokenProvider {

  private static final String API_BASE_URL = "https://api.atlassian.com/ex/jira/";
  private static final String OAUTH_BASE_URL = "https://auth.atlassian.com";

  private final RestClient oauthClient;
  private final BiConsumer<String, String> rotatedGrantStore;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Spring wiring constructor: builds the Atlassian OAuth token client and persists any rotated
   * refresh-token grant back through {@link ProjectCredentialService} (looked up lazily so the
   * provider still loads when no credential store is present, e.g. in slice tests).
   */
  @Autowired
  public JiraOAuthTokenProvider(
      ObjectProvider<ProjectCredentialService> credentialServiceProvider) {
    this(
        RestClient.builder().baseUrl(OAUTH_BASE_URL).build(),
        (projectPublicId, plaintextGrant) -> {
          ProjectCredentialService credentialService = credentialServiceProvider.getIfAvailable();
          if (credentialService != null) {
            credentialService.setCredential(
                projectPublicId, ConnectorRole.TICKET_SOURCE, plaintextGrant);
          }
        });
  }

  public JiraOAuthTokenProvider(
      RestClient oauthClient, BiConsumer<String, String> rotatedGrantStore) {
    this.oauthClient = Objects.requireNonNull(oauthClient, "oauthClient");
    this.rotatedGrantStore = Objects.requireNonNull(rotatedGrantStore, "rotatedGrantStore");
  }

  Optional<JiraOAuthAccess> refresh(String projectPublicId, String plaintextGrant) {
    if (projectPublicId == null || projectPublicId.isBlank() || plaintextGrant == null) {
      return Optional.empty();
    }
    String trimmed = plaintextGrant.strip();
    if (!trimmed.startsWith("{")) {
      return Optional.empty();
    }
    JiraOAuthGrant grant = parseGrant(trimmed);
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("grant_type", "refresh_token");
    payload.put("client_id", grant.clientId());
    payload.put("client_secret", grant.clientSecret());
    payload.put("refresh_token", grant.refreshToken());
    JsonNode response;
    try {
      String body =
          oauthClient
              .method(HttpMethod.POST)
              .uri("/oauth/token")
              .contentType(MediaType.APPLICATION_JSON)
              .body(writeJson(payload))
              .retrieve()
              .body(String.class);
      response = parseJson(body, "JIRA OAuth token response");
    } catch (RestClientResponseException error) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.LINK_FAILURE,
          "JIRA OAuth refresh failed: " + error.getStatusCode(),
          error);
    } catch (RuntimeException error) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.NETWORK_API_FAILURE,
          "JIRA OAuth refresh failed: " + error.getClass().getSimpleName(),
          error);
    }
    String accessToken = requiredText(response, "access_token");
    String refreshedToken = textOrNull(response.path("refresh_token"));
    if (refreshedToken != null && !refreshedToken.equals(grant.refreshToken())) {
      rotatedGrantStore.accept(
          projectPublicId, grant.withRefreshToken(refreshedToken).toJson(objectMapper));
    }
    return Optional.of(new JiraOAuthAccess(accessToken, API_BASE_URL + grant.cloudId()));
  }

  private JiraOAuthGrant parseGrant(String plaintextGrant) {
    JsonNode node = parseJson(plaintextGrant, "JIRA OAuth grant");
    if (!node.isObject()) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE, "JIRA OAuth grant must be a JSON object");
    }
    return new JiraOAuthGrant(
        firstText(node, "cloudId", "cloudid", "cloud_id"),
        firstText(node, "refreshToken", "refresh_token"),
        firstText(node, "clientId", "client_id"),
        firstText(node, "clientSecret", "client_secret"));
  }

  private JsonNode parseJson(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE, label + " was empty");
    }
    try {
      return objectMapper.readTree(value);
    } catch (IOException error) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE, label + " was not valid JSON", error);
    }
  }

  private String writeJson(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (IOException error) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE, "Failed to serialize JIRA OAuth request", error);
    }
  }

  private static String firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      String value = textOrNull(node.path(field));
      if (value != null) {
        return value;
      }
    }
    throw new TicketSourceAdapterException(
        IntegrationFailureCategory.SYNC_FAILURE,
        "JIRA OAuth grant missing required field: " + fields[0]);
  }

  private static String requiredText(JsonNode node, String field) {
    String value = textOrNull(node.path(field));
    if (value == null) {
      throw new TicketSourceAdapterException(
          IntegrationFailureCategory.SYNC_FAILURE,
          "JIRA OAuth response missing required field: " + field);
    }
    return value;
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String value = node.asText("");
    return value.isBlank() ? null : value;
  }

  private record JiraOAuthGrant(
      String cloudId, String refreshToken, String clientId, String clientSecret) {
    private JiraOAuthGrant withRefreshToken(String newRefreshToken) {
      return new JiraOAuthGrant(cloudId, newRefreshToken, clientId, clientSecret);
    }

    private String toJson(ObjectMapper mapper) {
      ObjectNode node = mapper.createObjectNode();
      node.put("cloudId", cloudId);
      node.put("refreshToken", refreshToken);
      node.put("clientId", clientId);
      node.put("clientSecret", clientSecret);
      try {
        return mapper.writeValueAsString(node);
      } catch (IOException error) {
        throw new TicketSourceAdapterException(
            IntegrationFailureCategory.SYNC_FAILURE, "Failed to serialize JIRA OAuth grant", error);
      }
    }
  }
}
