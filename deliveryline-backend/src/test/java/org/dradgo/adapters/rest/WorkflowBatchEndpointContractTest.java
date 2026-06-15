package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.dradgo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3.18 (AC6/AC11) — REST contract for {@code POST /api/v1/workflows/batch}. House pattern:
 * {@code @SpringBootTest} RANDOM_PORT + a real {@link HttpClient} (NOT MockMvc). Asserts the 200 +
 * camelCase body shape, per-ticket outcomes, and idempotent replay over HTTP.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
class WorkflowBatchEndpointContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private Environment environment;

  @Test
  void batchSubmitReturns200WithPerTicketOutcomesInCamelCase() throws Exception {
    String idempotencyKey = "idem-batch-" + uniqueSuffix();
    HttpResponse<String> response =
        post(
            """
            {
              "linearTicketReferences": ["LIN-101", "LIN-102"],
              "actorIdentity": "alex",
              "actorType": "HUMAN"
            }
            """,
            idempotencyKey);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("batchId").asText()).startsWith("bat_");
    assertThat(body.path("total").asInt()).isEqualTo(2);
    assertThat(body.path("queuedCount").asInt() + body.path("rejectedCount").asInt()).isEqualTo(2);
    assertThat(body.path("submittedAt").asText()).isNotBlank();
    assertThat(body.path("actorIdentity").asText()).isEqualTo("alex");
    JsonNode tickets = body.path("tickets");
    assertThat(tickets.isArray()).isTrue();
    assertThat(tickets).hasSize(2);
    assertThat(tickets.get(0).path("ticketRef").asText()).isEqualTo("LIN-101");
    assertThat(tickets.get(0).path("queueResult").asText()).isIn("queued", "rejected");
  }

  @Test
  void batchSubmitReplayReturnsIdenticalBody() throws Exception {
    String idempotencyKey = "idem-batch-" + uniqueSuffix();
    String requestBody =
        """
        {
          "linearTicketReferences": ["LIN-101", "LIN-103"],
          "actorIdentity": "alex",
          "actorType": "HUMAN"
        }
        """;

    HttpResponse<String> first = post(requestBody, idempotencyKey);
    HttpResponse<String> second = post(requestBody, idempotencyKey);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
  }

  @Test
  void missingIdempotencyKeyReturns400() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/api/v1/workflows/batch"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {
                      "linearTicketReferences": ["LIN-101"],
                      "actorIdentity": "alex",
                      "actorType": "HUMAN"
                    }
                    """))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
  }

  private HttpResponse<String> post(String body, String idempotencyKey)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/api/v1/workflows/batch"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
  }

  private static String uniqueSuffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
