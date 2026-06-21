package org.dradgo.application.project;

import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;

/**
 * Story 3c-8 — shared typed-error factories for the project application surface, so {@code
 * ProjectManagementService} and {@code ProjectConnectivityService} raise an identical {@code
 * PROJECT_NOT_FOUND} shape.
 */
final class ProjectErrors {

  private ProjectErrors() {}

  static DomainException projectNotFound(String publicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("projectId", publicId);
    return new DomainException(
        DomainErrorCode.PROJECT_NOT_FOUND, "No project found for id " + publicId, details);
  }
}
