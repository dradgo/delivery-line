package org.dradgo.adapters.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.dradgo.adapters.runner.EnabledIfDockerAvailable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Story 3.7 AC11 / Decision D9 / Trap T11 — heavy ELK round-trip test. Stands up real Elasticsearch
 * + Logstash containers wired to the committed {@code deliveryline.conf} pipeline and asserts the
 * end-to-end contract:
 *
 * <ul>
 *   <li>a JSON log sent over TCP/JSON :5044 is indexed in Elasticsearch (round-trip);
 *   <li>a {@code local-only}-classified document is DROPPED by the filter (AC4);
 *   <li>a document carrying a secret that slipped past source-side redaction is stripped by the
 *       pipeline's second-pass {@code gsub} before indexing (AC5 / double-redaction).
 * </ul>
 *
 * <p>Tagged {@code docker-runner-it} + {@link EnabledIfDockerAvailable}: it pulls real ELK images
 * and needs a live Docker engine, so it is excluded from the no-Docker PR / foundation tiers
 * (memory: springboot-testcontainers-test-must-be-IT) and runs only on the Docker-tier CI on Linux.
 * Do NOT claim this passes from a Windows-only run (Trap T11; memory: wsl-linux-ci-reproduction).
 */
@Tag("docker-runner-it")
@EnabledIfDockerAvailable
class ElkPipelineRoundTripIT {

  private static final String ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:8.15.3";
  private static final String LOGSTASH_IMAGE = "docker.elastic.co/logstash/logstash:8.15.3";
  private static final String INDEX_SEARCH = "/deliveryline-logs-*/_search";
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);

  private static Network network;
  private static GenericContainer<?> elasticsearch;
  private static GenericContainer<?> logstash;
  private static HttpClient http;

  @BeforeAll
  static void startStack() throws Exception {
    Path pipeline = locatePipeline();
    network = Network.newNetwork();

    elasticsearch =
        new GenericContainer<>(ES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("elasticsearch")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(
                Wait.forHttp("/_cluster/health")
                    .forStatusCodeMatching(code -> code >= 200 && code < 300)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    elasticsearch.start();

    logstash =
        new GenericContainer<>(LOGSTASH_IMAGE)
            .withNetwork(network)
            .withEnv("XPACK_MONITORING_ENABLED", "false")
            .withEnv("LS_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withCopyFileToContainer(
                MountableFile.forHostPath(pipeline),
                "/usr/share/logstash/pipeline/deliveryline.conf")
            .withExposedPorts(5044)
            .waitingFor(
                Wait.forLogMessage(".*Pipeline started.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    logstash.start();

    http = HttpClient.newHttpClient();
  }

  @AfterAll
  static void stopStack() {
    if (logstash != null) {
      logstash.stop();
    }
    if (elasticsearch != null) {
      elasticsearch.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  @Test
  void shippableJsonLogIsIndexedInElasticsearch() throws Exception {
    String marker = "roundtrip-" + System.nanoTime();
    sendJson(
        "{\"message\":\""
            + marker
            + "\",\"level\":\"INFO\",\"classification\":\"shareable-redacted\"}");

    assertThat(awaitHitCount("message:\"" + marker + "\"", 1)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void localOnlyClassifiedDocumentIsDropped() throws Exception {
    String dropped = "localonly-" + System.nanoTime();
    String kept = "kept-" + System.nanoTime();
    sendJson("{\"message\":\"" + dropped + "\",\"classification\":\"local-only\"}");
    sendJson("{\"message\":\"" + kept + "\",\"classification\":\"shareable-redacted\"}");

    // Once the later shippable doc is visible, the earlier local-only doc has had its chance — and
    // must be absent.
    awaitHitCount("message:\"" + kept + "\"", 1);
    assertThat(hitCount("message:\"" + dropped + "\"")).isZero();
  }

  @Test
  void secondPassRedactionStripsASourceMissedSecret() throws Exception {
    String marker = "redact-" + System.nanoTime();
    sendJson(
        "{\"message\":\""
            + marker
            + " ghp_1234567890abcdef1234567890abcdef1234\",\"classification\":\"shareable-redacted\"}");

    awaitHitCount("message:\"" + marker + "\"", 1);
    String body = search("message:\"" + marker + "\"");
    assertThat(body).contains("[REDACTED_GITHUB_TOKEN]");
    assertThat(body).doesNotContain("ghp_1234567890abcdef1234567890abcdef1234");
  }

  // ---- helpers ----

  private void sendJson(String json) throws Exception {
    try (Socket socket = new Socket(logstash.getHost(), logstash.getMappedPort(5044))) {
      OutputStream out = socket.getOutputStream();
      out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  private long awaitHitCount(String query, int atLeast) throws Exception {
    long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
    long count = 0;
    while (System.nanoTime() < deadline) {
      // refresh so freshly-indexed docs are searchable
      get("/deliveryline-logs-*/_refresh");
      count = hitCount(query);
      if (count >= atLeast) {
        return count;
      }
      Thread.sleep(1000L);
    }
    return count;
  }

  private long hitCount(String query) throws Exception {
    String body = search(query);
    // total may be rendered as {"value":N,...}; read the first integer after "value":
    int idx = body.indexOf("\"value\":");
    if (idx < 0) {
      return 0;
    }
    int start = idx + "\"value\":".length();
    int end = start;
    while (end < body.length() && Character.isDigit(body.charAt(end))) {
      end++;
    }
    return end > start ? Long.parseLong(body.substring(start, end)) : 0;
  }

  private String search(String query) throws Exception {
    return get(INDEX_SEARCH + "?q=" + encode(query));
  }

  private String get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(esBaseUrl() + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    return response.body();
  }

  private static String encode(String query) {
    return java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
  }

  private static String esBaseUrl() {
    return "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200);
  }

  private static Path locatePipeline() {
    String relative = "infra/observability/logstash/pipelines/deliveryline.conf";
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
      Path candidate = dir.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not locate " + relative);
  }
}
