package org.dradgo.domain.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.dradgo.domain.DomainException;
import org.junit.jupiter.api.Test;

/**
 * Story 3c-2 (AC8) — canonical-value parsing + fail-fast coverage for the two new project
 * registries, mirroring {@code runnerKindPersistedRegistryParserAcceptsCanonicalValues}. Lives in
 * the {@code domain.registry} package so it can exercise the package-private {@code
 * fromValue(String)} convenience overload (the methods that are otherwise unused until
 * 3c-3/3c-6/3c-8).
 */
class ProjectRegistryParsingTest {

  @Test
  void projectStatusAcceptsCanonicalValues() {
    assertEquals(ProjectStatus.ACTIVE, ProjectStatus.fromValue("active"));
    assertEquals(ProjectStatus.DISABLED, ProjectStatus.fromValue("disabled"));
  }

  @Test
  void connectorKindAcceptsCanonicalValues() {
    assertEquals(ConnectorKind.LINEAR, ConnectorKind.fromValue("linear"));
    assertEquals(ConnectorKind.GITHUB, ConnectorKind.fromValue("github"));
  }

  @Test
  void projectStatusFailsFastOnUnknownAndCaseMismatch() {
    DomainException unknown =
        assertThrows(DomainException.class, () -> ProjectStatus.fromValue("__bogus__"));
    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, unknown.errorCode());
    assertEquals("__bogus__", unknown.details().get("value"));

    DomainException caseMismatch =
        assertThrows(DomainException.class, () -> ProjectStatus.fromValue("ACTIVE"));
    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, caseMismatch.errorCode());
  }

  @Test
  void connectorKindFailsFastOnUnknownAndCaseMismatch() {
    DomainException unknown =
        assertThrows(DomainException.class, () -> ConnectorKind.fromValue("__bogus__"));
    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, unknown.errorCode());

    DomainException caseMismatch =
        assertThrows(DomainException.class, () -> ConnectorKind.fromValue("GitHub"));
    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, caseMismatch.errorCode());
  }
}
