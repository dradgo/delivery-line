package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.dradgo.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 2.28 (AC3 + AC4) — wired DispatcherServlet collision + cache-header coverage for the
 * embedded SPA. One boot exercises the real interception path ({@code NoResourceFoundException} →
 * {@link ProblemDetailsMapper} → {@link SpaFallbackController}) plus the {@code
 * StaticResourceCacheConfig} resource handler / interceptor, against the test bundle stub at {@code
 * src/test/resources/static/} (the real Vite bundle is never on the test classpath).
 *
 * <ul>
 *   <li>AC3 — a non-API browser route serves {@code index.html}; an API 404, a missing asset, the
 *       OpenAPI doc, and the actuator are NOT masked by the SPA fallback (the API-path-collision
 *       tests referenced by story 2.5 AC5 + story 6.9 AC9);
 *   <li>AC4 — {@code /assets/**} carry {@code max-age=31536000, immutable}; the shell ({@code /}
 *       and {@code /index.html}) carries {@code no-store}.
 * </ul>
 *
 * <p>Named {@code *ContractTest} → Failsafe tier (Docker/Testcontainers), excluded from the Windows
 * Surefire tier by the pom's file-pattern exclusion (matches the sibling REST contract tests).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
@Tag("contract")
class SpaServingContractTest {

  private static final String ROOT_MOUNT_NODE = "<div id=\"root\">";

  @Autowired private Environment environment;

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path, String accept)
      throws IOException, InterruptedException {
    String port = environment.getProperty("local.server.port");
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    if (accept != null) {
      builder.header("Accept", accept);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  // --- AC3: SPA fallback fires for genuine browser navigations -------------------------------

  @Test
  void browserRouteIsServedTheAppShell() throws Exception {
    HttpResponse<String> response = get("/workflows/run_abc", "text/html");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(response.body()).contains(ROOT_MOUNT_NODE);
  }

  // --- AC3: collisions — these MUST NOT be masked by the SPA fallback ------------------------

  @Test
  void apiNotFoundReturnsProblemDetailsNotTheShell() throws Exception {
    // Well-formed but absent run id → governed RUN_NOT_FOUND 404 Problem Details, never index.html.
    HttpResponse<String> response =
        get("/api/v1/workflows/run_doesnotexist0001", "application/json");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    assertThat(response.body()).doesNotContain(ROOT_MOUNT_NODE);
  }

  @Test
  void missingAssetReturns404NotTheShell() throws Exception {
    // Trailing dot segment looks like a (missing) asset, not a route → JSON 404, never the shell.
    HttpResponse<String> response = get("/assets/missing.js", "text/html");

    assertThat(response.statusCode()).isEqualTo(404);
    // Pin the contract: a JSON ProblemDetail, not the shell and not a Whitelabel HTML error page.
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    assertThat(response.body()).doesNotContain(ROOT_MOUNT_NODE);
  }

  @Test
  void openApiDocIsServedNotTheShell() throws Exception {
    HttpResponse<String> response = get("/v3/api-docs", "application/json");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"openapi\"").doesNotContain(ROOT_MOUNT_NODE);
  }

  @Test
  void actuatorHealthIsServedNotTheShell() throws Exception {
    HttpResponse<String> response = get("/actuator/health", "application/json");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\"").doesNotContain(ROOT_MOUNT_NODE);
  }

  // --- AC4: cache headers ---------------------------------------------------------------------

  @Test
  void contentHashedAssetsCarryAnImmutableLongCache() throws Exception {
    HttpResponse<String> response = get("/assets/app-test12345.js", null);

    assertThat(response.statusCode()).isEqualTo(200);
    String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
    assertThat(cacheControl).contains("max-age=31536000").contains("immutable");
  }

  @Test
  void welcomePageShellCarriesNoStore() throws Exception {
    HttpResponse<String> response = get("/", "text/html");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(response.body()).contains(ROOT_MOUNT_NODE);
  }

  @Test
  void indexHtmlCarriesNoStore() throws Exception {
    HttpResponse<String> response = get("/index.html", "text/html");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
  }
}
