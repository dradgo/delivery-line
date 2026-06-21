package org.dradgo.application.project;

import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;

/**
 * Story 3c-10 — the single source of truth for "is this active project structurally configured?",
 * shared by the doctor {@code projects} probe (its WARN roll-up, AC1/AC3) and the {@code
 * ProjectHealthMetricsBinder} (its {@code deliveryline.projects.configured} gauge, AC5) so the two
 * cannot drift on what "configured" means.
 *
 * <p>"Structurally configured" = ACTIVE + a non-blank repository binding + both connector kinds
 * resolvable to a registered adapter. Credential PRESENCE is deliberately NOT part of this
 * predicate — it is reported by the probe only (per active project/role) and is never read from the
 * metric scrape hot path (Open Decision #3). This class never reads or emits a credential value.
 */
public final class ProjectConfigChecks {

  private ProjectConfigChecks() {}

  /** True when the project carries a non-blank repository URL binding. */
  public static boolean repositoryBound(Project project) {
    return project.repositoryUrl() != null && !project.repositoryUrl().isBlank();
  }

  /**
   * True when both the ticket-source and repo-host kinds resolve to a registered adapter. A {@code
   * null} resolver (a lean context with no resolver bean) cannot perform the check and is treated
   * as resolvable — the structural roll-up degrades to the repository-binding sub-check only. Only
   * an {@link DomainErrorCode#UNSUPPORTED_CONNECTOR_KIND} fault is read as "not resolvable"; any
   * other {@link DomainException} propagates (it is not a misconfiguration signal and must
   * surface).
   */
  public static boolean kindsResolvable(Project project, ProjectConnectorResolver resolver) {
    if (resolver == null) {
      return true;
    }
    try {
      resolver.resolveTicketSource(project);
      resolver.resolveRepositoryHost(project);
      return true;
    } catch (DomainException ex) {
      if (ex.errorCode() == DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND) {
        return false;
      }
      throw ex;
    }
  }

  /**
   * Structural configuration verdict for a project: ACTIVE + repository bound + connector kinds
   * resolvable. A disabled (or archived) project is never "configured" for run purposes. Credential
   * presence is layered on by the probe's WARN roll-up, not by this predicate.
   */
  public static boolean isStructurallyConfigured(
      Project project, ProjectConnectorResolver resolver) {
    return project.status() == ProjectStatus.ACTIVE
        && repositoryBound(project)
        && kindsResolvable(project, resolver);
  }
}
