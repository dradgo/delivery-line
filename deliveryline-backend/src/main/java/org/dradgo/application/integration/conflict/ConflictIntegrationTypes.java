package org.dradgo.application.integration.conflict;

/**
 * Story 4.17 — single source of the {@code integration_links.integration_type} literals the
 * conflict-detection producer ({@link IntegrationConflictDetectionService}) and the read surface
 * ({@link IntegrationConflictService}) both dispatch on. Kept in one place so adding a third
 * integration type cannot silently diverge the detector from the read-surface filter validator (the
 * two previously held independent copies of these literals).
 */
public final class ConflictIntegrationTypes {

  public static final String LINEAR = "linear";
  public static final String GITHUB_PR = "github_pr";

  private ConflictIntegrationTypes() {}
}
