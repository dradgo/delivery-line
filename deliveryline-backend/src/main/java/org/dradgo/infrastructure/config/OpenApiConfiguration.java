package org.dradgo.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import java.math.BigDecimal;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi document metadata for the DeliveryLine REST surface (story 6.9 Task 4).
 *
 * <p>Pins a stable title/version and a single explicit localhost server so the generated {@code
 * /v3/api-docs} document — and the committed {@code openapi.json} snapshot the CI drift gate diffs
 * — is deterministic (no request-derived server URL, no build timestamp). Story 2.6 generates its
 * typed TypeScript client from this document.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

  private static final String DEFAULT_HOST = "127.0.0.1";
  private static final String LOCALHOST_HOST = "localhost";

  @Bean
  OpenAPI deliveryLineOpenApi(
      RestBindingProperties properties,
      @Value("${server.address:127.0.0.1}") String serverAddress,
      @Value("${server.port:8080}") int serverPort) {
    return new OpenAPI()
        .info(
            new Info()
                .title("DeliveryLine API")
                .version("v1")
                .description(
                    "Governed local-first agent delivery workflow API. Phase 1 is localhost-bound "
                        + "(loopback-only) and unauthenticated; the embedded server refuses to "
                        + "bind a non-loopback address without an explicit unsafe override."))
        .servers(
            List.of(
                new Server()
                    .url(serverUrl(properties.bindAddress(), serverAddress, serverPort))
                    .description("Localhost (loopback-only, Phase 1)")));
  }

  @Bean
  OpenApiCustomizer deliveryLineOpenApiCustomizer() {
    return openApi -> {
      if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
        return;
      }
      @SuppressWarnings("rawtypes")
      Schema problemDetails = openApi.getComponents().getSchemas().get("ProblemDetailsResponse");
      @SuppressWarnings("rawtypes")
      Schema workflowEvent = openApi.getComponents().getSchemas().get("WorkflowEvent");
      patchProblemDetailsSchema(problemDetails);
      patchWorkflowEventSchema(workflowEvent);
    };
  }

  private static String serverUrl(
      String bindAddressOverride, String serverAddress, int serverPort) {
    String effectiveHost = firstNonBlank(bindAddressOverride, serverAddress, DEFAULT_HOST);
    String renderedHost =
        isWildcardAddress(effectiveHost) ? LOCALHOST_HOST : toUriAuthorityHost(effectiveHost);
    int renderedPort = serverPort > 0 ? serverPort : 8080;
    return "http://" + renderedHost + ":" + renderedPort;
  }

  private static boolean isWildcardAddress(String host) {
    return "0.0.0.0".equals(host) || "::".equals(host) || "0:0:0:0:0:0:0:0".equals(host);
  }

  private static String toUriAuthorityHost(String host) {
    return host.contains(":") && !(host.startsWith("[") && host.endsWith("]"))
        ? "[" + host + "]"
        : host;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return DEFAULT_HOST;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void patchProblemDetailsSchema(Schema schema) {
    if (schema == null || schema.getProperties() == null) {
      return;
    }
    ComposedSchema detailsSchema = new ComposedSchema();
    detailsSchema.setAnyOf(
        List.of(
            new ObjectSchema().additionalProperties(new Schema<>()),
            new ArraySchema().items(new ObjectSchema())));
    detailsSchema.setNullable(true);
    detailsSchema.setDescription(
        "Domain-specific extension payload. Validation errors emit an array of field-error "
            + "objects; other domain errors typically emit an object.");
    schema.getProperties().put("details", detailsSchema);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void patchWorkflowEventSchema(Schema schema) {
    if (schema == null || schema.getProperties() == null) {
      return;
    }
    ObjectSchema detailsSchema = new ObjectSchema();
    detailsSchema.setDescription(
        "Open detail map; server-only keys (e.g. idempotencyKey) are stripped. Known keys include "
            + "linearTicketReference, artifactId, artifactVersion, contextVersion, correlationId, "
            + "failedStage, triggeringEventId, recoveryActionId, recoveryRetriedEventId, "
            + "compensationFailed, artifactVariant, and feedback.");
    detailsSchema.additionalProperties(new Schema<>());
    detailsSchema.setNot(new ObjectSchema().required(List.of("idempotencyKey")));
    detailsSchema.addProperty("linearTicketReference", new StringSchema().minLength(1));
    detailsSchema.addProperty("artifactId", new StringSchema().minLength(1));
    detailsSchema.addProperty(
        "artifactVersion", new IntegerSchema().minimum(BigDecimal.ONE).format("int32"));
    detailsSchema.addProperty(
        "contextVersion", new IntegerSchema().minimum(BigDecimal.ONE).format("int32"));
    detailsSchema.addProperty("correlationId", new StringSchema().minLength(1));
    detailsSchema.addProperty("failedStage", new StringSchema().minLength(1));
    detailsSchema.addProperty(
        "triggeringEventId", new StringSchema().pattern("^evt_[A-Za-z0-9_]{4,}$"));
    detailsSchema.addProperty("reason", new StringSchema());
    detailsSchema.addProperty("errorCode", new StringSchema().minLength(1));
    detailsSchema.addProperty("errorClass", new StringSchema().minLength(1));
    detailsSchema.addProperty(
        "recoveryActionId", new StringSchema().pattern("^rcv_[A-Za-z0-9_]{4,}$"));
    detailsSchema.addProperty(
        "recoveryRetriedEventId", new StringSchema().pattern("^evt_[A-Za-z0-9_]{4,}$"));
    detailsSchema.addProperty("compensationFailed", new BooleanSchema());
    detailsSchema.addProperty(
        "artifactVariant",
        new StringSchema()._enum(List.of("spec", "implementationPlan", "prOutput")));
    detailsSchema.addProperty("feedback", new StringSchema());
    schema.getProperties().put("details", detailsSchema);
  }
}
