package org.dradgo.application.project;

import java.util.List;
import java.util.Objects;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.security.CredentialCipherException;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3c-8 (AC3 / R1 / R5) — the capability-aware test-connection orchestrator. Resolves a
 * project's connector adapters via {@link ProjectConnectorResolver} and runs three per-check probes
 * — <strong>repository reachable</strong>, <strong>ticket-source auth</strong>,
 * <strong>repository-host auth</strong> — returning a tri-state ({@code pass} / {@code fail} /
 * {@code skipped}) result per check. Per-check failures are in-band data (the REST surface returns
 * HTTP 200); only an unregistered connector kind surfaces as a Problem-Details 400 (the resolver
 * throws {@code UNSUPPORTED_CONNECTOR_KIND}).
 *
 * <p><strong>Capability gating (AC3).</strong> A connector that cannot perform a meaningful
 * authenticated probe (a degraded stub declaring no optional capabilities — e.g. the GitLab stubs)
 * yields {@code skipped}, not {@code fail}. The gate reuses an existing capability flag per
 * connector type (no capability-record change): a ticket source is probe-capable when it supports
 * polling; a repository host when it supports PR comments. Both are {@code true} for the real
 * vendors and {@code false} for the degraded stubs.
 *
 * <p><strong>Secret-hostility (R5 / P1).</strong> The per-project credential is resolved at
 * use-time via {@link ProjectConnectorResolver#resolveConnectorSecret} and passed to the probe as a
 * one-off credential override (so a credential set through the REST surface is actually exercised);
 * when no stored credential exists the override is {@code null} and the adapter falls back to its
 * host-env credential (AC3). A tampered stored row ({@link CredentialCipherException}) is caught
 * here and rendered as a per-check {@code fail}, never a 500. The resolved secret is handed only to
 * the adapter for the single probe call — never logged, returned, or persisted.
 */
@Service
public class ProjectConnectivityService {

  private static final Logger log = LoggerFactory.getLogger(ProjectConnectivityService.class);

  static final String CHECK_REPOSITORY_REACHABLE = "repository_reachable";
  static final String CHECK_TICKET_SOURCE_AUTH = "ticket_source_auth";
  static final String CHECK_REPOSITORY_HOST_AUTH = "repository_host_auth";

  // Underscored ConnectorRole wire values (R1) for the at-use-time credential lookup.
  private static final String ROLE_TICKET_SOURCE = "ticket_source";
  private static final String ROLE_REPO_HOST = "repo_host";

  private static final String DETAIL_CREDENTIAL_UNREADABLE =
      "stored credential could not be decrypted";

  private final ProjectStore projectStore;
  private final ProjectConnectorResolver projectConnectorResolver;

  public ProjectConnectivityService(
      ProjectStore projectStore, ProjectConnectorResolver projectConnectorResolver) {
    this.projectStore = Objects.requireNonNull(projectStore, "projectStore");
    this.projectConnectorResolver =
        Objects.requireNonNull(projectConnectorResolver, "projectConnectorResolver");
  }

  /**
   * Run the three connectivity probes for the project. {@code PROJECT_NOT_FOUND} on a missing
   * project; {@code UNSUPPORTED_CONNECTOR_KIND} (400) when a kind has no registered adapter.
   */
  public TestConnectionResult testConnection(String projectPublicId) {
    Project project =
        projectStore
            .findByPublicId(projectPublicId)
            .orElseThrow(() -> ProjectErrors.projectNotFound(projectPublicId));

    // Resolve up front: an unregistered kind throws UNSUPPORTED_CONNECTOR_KIND (a 400 Problem
    // Detail, NOT an in-band check failure — AC3).
    TicketSourceAdapter ticketSource = projectConnectorResolver.resolveTicketSource(project);
    RepositoryHostAdapter repositoryHost = projectConnectorResolver.resolveRepositoryHost(project);

    CheckResult ticketCheck = ticketSourceCheck(project, ticketSource);
    RepoHostChecks repoHostChecks = repositoryHostChecks(project, repositoryHost);

    List<CheckResult> checks =
        List.of(repoHostChecks.reachable(), ticketCheck, repoHostChecks.auth());
    log.info(
        "connection test project={} repoReachable={} ticketAuth={} repoAuth={}",
        project.publicId(),
        repoHostChecks.reachable().status().value(),
        ticketCheck.status().value(),
        repoHostChecks.auth().status().value());
    return new TestConnectionResult(checks);
  }

  private CheckResult ticketSourceCheck(Project project, TicketSourceAdapter ticketSource) {
    if (!ticketSource.getCapabilities().supportsPolling()) {
      return skipped(
          CHECK_TICKET_SOURCE_AUTH,
          "connector kind "
              + project.ticketSourceKind().value()
              + " does not support connectivity probing");
    }
    String credentialOverride;
    try {
      // Resolve the at-use credential: pass it to the probe (so a REST-set credential is
      // exercised);
      // a tampered stored row surfaces as a per-check fail. A missing credential (empty) yields a
      // null override → the adapter falls back to its host-env credential (AC3).
      credentialOverride =
          projectConnectorResolver.resolveConnectorSecret(project, ROLE_TICKET_SOURCE).orElse(null);
    } catch (CredentialCipherException cipher) {
      log.warn(
          "connection test ticket-source credential unreadable project={} check={}",
          project.publicId(),
          CHECK_TICKET_SOURCE_AUTH);
      return fail(CHECK_TICKET_SOURCE_AUTH, DETAIL_CREDENTIAL_UNREADABLE);
    }
    ConnectivityResult result = ticketSource.verifyConnectivity(credentialOverride);
    return authCheck(CHECK_TICKET_SOURCE_AUTH, result);
  }

  private RepoHostChecks repositoryHostChecks(
      Project project, RepositoryHostAdapter repositoryHost) {
    if (!repositoryHost.getCapabilities().supportsPullRequestComments()) {
      String detail =
          "connector kind "
              + project.repoHostKind().value()
              + " does not support connectivity probing";
      return new RepoHostChecks(
          skipped(CHECK_REPOSITORY_REACHABLE, detail), skipped(CHECK_REPOSITORY_HOST_AUTH, detail));
    }
    RepositoryRef repoRef = repositoryRefOf(project);
    String credentialOverride;
    try {
      credentialOverride =
          projectConnectorResolver.resolveConnectorSecret(project, ROLE_REPO_HOST).orElse(null);
    } catch (CredentialCipherException cipher) {
      log.warn(
          "connection test repository-host credential unreadable project={}", project.publicId());
      return new RepoHostChecks(
          fail(CHECK_REPOSITORY_REACHABLE, DETAIL_CREDENTIAL_UNREADABLE),
          fail(CHECK_REPOSITORY_HOST_AUTH, DETAIL_CREDENTIAL_UNREADABLE));
    }
    ConnectivityResult result = repositoryHost.verifyConnectivity(repoRef, credentialOverride);
    CheckResult reachable =
        repoRef == null
            ? skipped(CHECK_REPOSITORY_REACHABLE, "no repository configured for this project")
            : (result.reachable()
                ? pass(CHECK_REPOSITORY_REACHABLE, result.detail())
                : fail(CHECK_REPOSITORY_REACHABLE, result.detail()));
    CheckResult auth =
        result.authenticated()
            ? pass(CHECK_REPOSITORY_HOST_AUTH, result.detail())
            : fail(CHECK_REPOSITORY_HOST_AUTH, result.detail());
    return new RepoHostChecks(reachable, auth);
  }

  private static RepositoryRef repositoryRefOf(Project project) {
    String bare = RepositoryRef.normalizeRepositoryUrl(project.repositoryUrl());
    return bare == null ? null : RepositoryRef.of(bare);
  }

  private static CheckResult authCheck(String check, ConnectivityResult result) {
    return (result.reachable() && result.authenticated())
        ? pass(check, result.detail())
        : fail(check, result.detail());
  }

  private static CheckResult pass(String check, String detail) {
    return new CheckResult(check, CheckStatus.PASS, detail);
  }

  private static CheckResult fail(String check, String detail) {
    log.warn("connection test check failed check={} detail={}", check, detail);
    return new CheckResult(check, CheckStatus.FAIL, detail);
  }

  private static CheckResult skipped(String check, String detail) {
    return new CheckResult(check, CheckStatus.SKIPPED, detail);
  }

  /**
   * Tri-state of a single connectivity check. A plain wire-valued enum — NOT a {@code
   * RegistryValue} (it is an in-band response detail, not a persisted/registered domain registry),
   * so it stays out of the registry-contract gates.
   */
  public enum CheckStatus {
    PASS("pass"),
    FAIL("fail"),
    SKIPPED("skipped");

    private final String value;

    CheckStatus(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  /** A single check's outcome — secret-free {@code detail}. */
  public record CheckResult(String check, CheckStatus status, String detail) {}

  /** The full test-connection outcome the controller maps to {@code TestConnectionResponse}. */
  public record TestConnectionResult(List<CheckResult> checks) {}

  private record RepoHostChecks(CheckResult reachable, CheckResult auth) {}
}
