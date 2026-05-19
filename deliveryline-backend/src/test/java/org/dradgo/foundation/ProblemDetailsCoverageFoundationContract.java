package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.dradgo.adapters.rest.ProblemDetailsCatalog;
import org.dradgo.adapters.rest.ProblemDetailsCatalog.ProblemDetailsMetadata;
import org.dradgo.adapters.rest.ProblemDetailsMapper;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Foundation contract #7 — {@link ProblemDetailsCatalog} maps every {@link DomainErrorCode} to a
 * stable {@link ProblemDetailsMetadata} entry, AND {@link ProblemDetailsMapper#handleDomainException}
 * surfaces that entry on the wire as a {@link ProblemDetail} whose {@code code} property equals the
 * enum value.
 *
 * <p>Two layers of defense:
 *
 * <ol>
 *   <li><strong>Catalog metadata coverage.</strong> Existing assertion — every code has a non-null
 *       {@link ProblemDetailsMetadata} with a sane status, title, and {@code https://} type URI.
 *       The catalog's static initializer already throws {@link IllegalStateException} on missing
 *       entries; the parameterized test produces a per-code diff so triage is per-code, not "one
 *       static init exception".
 *   <li><strong>Runtime mapper round-trip.</strong> Story 1.23 review patch P12. For every {@link
 *       DomainErrorCode} value, construct a {@link DomainException} carrying that code, invoke
 *       {@link ProblemDetailsMapper#handleDomainException} directly with a {@link
 *       MockHttpServletRequest}, and assert the returned {@link ProblemDetail}'s {@code code}
 *       property equals {@link DomainErrorCode#value()}. This is the runtime invariant Epic 2/3/4
 *       UI clients rely on; the static catalog alone cannot prove it.
 * </ol>
 */
@Tag("foundation-gate")
class ProblemDetailsCoverageFoundationContract {

  @Test
  void everyDomainErrorCodeHasProblemDetailsMetadata() {
    List<String> violations = new ArrayList<>();
    for (DomainErrorCode code : DomainErrorCode.values()) {
      ProblemDetailsMetadata metadata;
      try {
        metadata = ProblemDetailsCatalog.metadataFor(code);
      } catch (RuntimeException exception) {
        violations.add(
            code.value()
                + " -> lookup raised "
                + exception.getClass().getSimpleName()
                + ": "
                + exception.getMessage());
        continue;
      }
      if (metadata == null) {
        violations.add(code.value() + " -> metadata is null");
        continue;
      }
      if (metadata.status() == null) {
        violations.add(code.value() + " -> HttpStatus is null");
      }
      if (metadata.title() == null || metadata.title().isBlank()) {
        violations.add(code.value() + " -> title is null or blank");
      }
      if (metadata.typeUri() == null || !metadata.typeUri().startsWith("https://")) {
        violations.add(
            code.value()
                + " -> typeUri is missing or not an https URI: "
                + metadata.typeUri());
      }
    }
    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.8",
              "ProblemDetailsCatalog coverage gaps ("
                  + violations.size()
                  + "): "
                  + String.join("; ", violations)));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyDomainErrorCodeRoundTripsThroughTheMapperWithItsRegistryValueOnTheWire() {
    // Construct the mapper directly — no Spring context — by mocking the two ObjectProvider
    // dependencies. We exercise only the DomainException handler, which needs neither a real
    // RedactionPolicyService nor a real ObjectMapper for empty-details exceptions (the mapper
    // returns `null` from publicDetailsFor when details is empty, so no redaction path runs).
    ObjectProvider<RedactionPolicyService> redactionProvider = Mockito.mock(ObjectProvider.class);
    when(redactionProvider.getIfAvailable()).thenReturn(null);
    ObjectProvider<ObjectMapper> objectMapperProvider = Mockito.mock(ObjectProvider.class);
    when(objectMapperProvider.getIfAvailable(any())).thenReturn(new ObjectMapper());
    ProblemDetailsMapper mapper = new ProblemDetailsMapper(redactionProvider, objectMapperProvider);

    HttpServletRequest request = new MockHttpServletRequest("POST", "/test/foundation-gate/probe");
    List<String> violations = new ArrayList<>();
    for (DomainErrorCode code : DomainErrorCode.values()) {
      DomainException exception = new DomainException(code, "foundation-gate runtime probe");
      ResponseEntity<ProblemDetail> response;
      try {
        response = mapper.handleDomainException(exception, request);
      } catch (RuntimeException re) {
        violations.add(
            code.value()
                + " -> handleDomainException raised "
                + re.getClass().getSimpleName()
                + ": "
                + re.getMessage());
        continue;
      }
      ProblemDetail body = response.getBody();
      if (body == null) {
        violations.add(code.value() + " -> mapper returned a ResponseEntity with null body");
        continue;
      }
      Object wireCode =
          body.getProperties() == null ? null : body.getProperties().get("code");
      if (wireCode == null) {
        violations.add(
            code.value() + " -> ProblemDetail.properties has no `code` property on the wire");
        continue;
      }
      if (!code.value().equals(wireCode.toString())) {
        violations.add(
            code.value()
                + " -> ProblemDetail.code mismatch: expected="
                + code.value()
                + " actual="
                + wireCode);
      }
      HttpStatus expectedStatus = HttpStatus.valueOf(response.getStatusCode().value());
      if (expectedStatus != ProblemDetailsCatalog.metadataFor(code).status()) {
        violations.add(
            code.value()
                + " -> ResponseEntity status mismatch: expected="
                + ProblemDetailsCatalog.metadataFor(code).status()
                + " actual="
                + expectedStatus);
      }
    }
    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.8",
              "ProblemDetailsMapper runtime round-trip gaps ("
                  + violations.size()
                  + "): "
                  + String.join("; ", violations)));
    }
  }
}
