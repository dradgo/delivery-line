package org.dradgo.application.project;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Story 3c-3 — the first occupant of {@code application.project} and the keystone that turns the
 * 3.32/3.33 ports + the 3c-2 {@code Project}/{@code ConnectorKind} registry into a working
 * per-project connector resolver.
 *
 * <p><strong>Selector, not decorator.</strong> The resolver indexes the injected {@code
 * List<TicketSourceAdapter>} / {@code List<RepositoryHostAdapter>} by each adapter's self-declared
 * {@link TicketSourceAdapter#connectorKind()} / {@link RepositoryHostAdapter#connectorKind()} and
 * returns the matching adapter <em>untouched</em> for a {@link Project} — so the 3.32/3.33
 * capability-degradation contract is preserved verbatim (AC4): consumers still call {@code
 * getCapabilities()} and gate optional operations themselves. Resolution is registry-driven (the
 * adapter's declared kind), NOT the global {@code deliveryline.integration.*.kind} config keys
 * (AC2).
 *
 * <p>An unregistered kind is rejected at the <em>application</em> layer with {@link
 * DomainErrorCode#UNSUPPORTED_CONNECTOR_KIND} (HTTP 400, non-retryable) — not relying solely on the
 * V18 DB CHECK (AC3). A duplicate kind (two beans claiming the same {@link ConnectorKind}) is a
 * fail-fast misconfiguration at construction (AC9).
 *
 * <p><strong>Boundary (AC7).</strong> Depends only on the two abstract application ports + {@code
 * domain.project}/{@code domain.registry}/{@code domain.integration.*} types — never a concrete
 * vendor adapter nor any {@code adapters..}/{@code infrastructure..} package.
 *
 * <p><strong>Credential seam (AC6).</strong> The {@link ProjectCredentialSource} is injected via
 * {@link ObjectProvider} and resolved lazily at call time; no implementation exists today (the
 * encrypted store is 3c-5), so {@link #resolveConnectorSecret} returns empty and adapters keep
 * using their host-env secret path. The resolver retains <strong>no</strong> plaintext state.
 */
// @Component (not @Service) so the class keeps its *Resolver name (story 3c-3 AC1): the
// APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES ArchUnit rule requires @Service beans to
// end in Service/Orchestrator. @Component registers the bean identically.
@Component
public class ProjectConnectorResolver {

  private static final Logger log = LoggerFactory.getLogger(ProjectConnectorResolver.class);

  static final String TICKET_SOURCE_ROLE = "ticket-source";
  static final String REPOSITORY_HOST_ROLE = "repository-host";

  private final Map<ConnectorKind, TicketSourceAdapter> ticketSourceByKind;
  private final Map<ConnectorKind, RepositoryHostAdapter> repositoryHostByKind;
  private final ObjectProvider<ProjectCredentialSource> credentialSourceProvider;

  public ProjectConnectorResolver(
      List<TicketSourceAdapter> ticketSourceAdapters,
      List<RepositoryHostAdapter> repositoryHostAdapters,
      ObjectProvider<ProjectCredentialSource> credentialSourceProvider) {
    this.ticketSourceByKind =
        indexByKind(ticketSourceAdapters, TicketSourceAdapter::connectorKind, TICKET_SOURCE_ROLE);
    this.repositoryHostByKind =
        indexByKind(
            repositoryHostAdapters, RepositoryHostAdapter::connectorKind, REPOSITORY_HOST_ROLE);
    this.credentialSourceProvider =
        Objects.requireNonNull(credentialSourceProvider, "credentialSourceProvider");
    log.info(
        "ProjectConnectorResolver constructed ticketSourceKinds={} repositoryHostKinds={}",
        kindValues(ticketSourceByKind),
        kindValues(repositoryHostByKind));
  }

  /**
   * AC1/AC2 — the {@link TicketSourceAdapter} whose declared kind equals {@code
   * project.ticketSourceKind()}. Raises {@link DomainErrorCode#UNSUPPORTED_CONNECTOR_KIND} when no
   * adapter is registered for that kind (AC3).
   */
  public TicketSourceAdapter resolveTicketSource(Project project) {
    Objects.requireNonNull(project, "project");
    ConnectorKind kind = project.ticketSourceKind();
    log.info("resolveTicketSource projectId={} connectorKind={}", project.publicId(), kind.value());
    TicketSourceAdapter adapter = ticketSourceByKind.get(kind);
    if (adapter == null) {
      throw unsupported(project, kind, TICKET_SOURCE_ROLE);
    }
    return adapter;
  }

  /**
   * Story 3c-7 (AC4 / R3) — the {@link TicketSourceAdapter} for the project's kind <em>when one is
   * registered in this context</em>, else empty. Unlike {@link #resolveTicketSource} this does NOT
   * throw {@link DomainErrorCode#UNSUPPORTED_CONNECTOR_KIND} on a miss: the Linear completion-sync
   * path runs in many profile-gated contexts with no ticket-source adapter active for the project's
   * kind, and must <strong>preserve its {@code SKIPPED_NO_LINEAR_PROFILE} skip</strong> rather than
   * surface a thrown error. Probe-before-resolve.
   */
  public Optional<TicketSourceAdapter> findTicketSource(Project project) {
    Objects.requireNonNull(project, "project");
    return Optional.ofNullable(ticketSourceByKind.get(project.ticketSourceKind()));
  }

  /**
   * AC1/AC2 — the {@link RepositoryHostAdapter} whose declared kind equals {@code
   * project.repoHostKind()}. Raises {@link DomainErrorCode#UNSUPPORTED_CONNECTOR_KIND} when no
   * adapter is registered for that kind (AC3).
   */
  public RepositoryHostAdapter resolveRepositoryHost(Project project) {
    Objects.requireNonNull(project, "project");
    ConnectorKind kind = project.repoHostKind();
    log.info(
        "resolveRepositoryHost projectId={} connectorKind={}", project.publicId(), kind.value());
    RepositoryHostAdapter adapter = repositoryHostByKind.get(kind);
    if (adapter == null) {
      throw unsupported(project, kind, REPOSITORY_HOST_ROLE);
    }
    return adapter;
  }

  /**
   * AC5 — project-scoped {@code LINEAR_GITHUB_REPO_MISMATCH} guard: reject when the requested
   * {@code repositoryRef} does not match the {@code owner/repo} ref derived from {@code
   * project.repositoryUrl()}. A null/blank project binding is a no-op (nothing to assert until
   * run&harr;Project wiring lands in 3c-6/3c-7). Both sides are run through the shared {@link
   * RepositoryRef#normalizeRepositoryUrl} and compared case-insensitively, so a URL/{@code .git}/
   * trailing-slash/case-variant request form of the same repo is accepted (host repo identity is
   * case-folding); the comparison is idempotent on an already-bare lowercase ref, preserving the
   * byte-identical parity the 3c-3 seam relies on.
   */
  public void assertRepositoryRefMatchesProject(Project project, String repositoryRef) {
    Objects.requireNonNull(project, "project");
    String expected = RepositoryRef.normalizeRepositoryUrl(project.repositoryUrl());
    if (expected == null) {
      log.debug(
          "assertRepositoryRefMatchesProject no-op projectId={} reason=no_repo_binding",
          project.publicId());
      return;
    }
    String requested = RepositoryRef.normalizeRepositoryUrl(repositoryRef);
    if (!expected.equalsIgnoreCase(requested)) {
      log.warn(
          "repo mismatch projectId={} expectedRepoRef={} requestedRepoRef={}",
          project.publicId(),
          expected,
          repositoryRef);
      throw new DomainException(
          DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH,
          "Requested repository "
              + repositoryRef
              + " does not match project "
              + project.publicId()
              + " binding "
              + expected);
    }
  }

  /**
   * AC6 — at-use-time per-project secret lookup. Resolves the {@link ProjectCredentialSource}
   * lazily; with no bean present today the resolver returns empty (adapters use their host-env
   * secret path). The returned secret is for immediate use only — never retained or logged here.
   */
  public Optional<String> resolveConnectorSecret(Project project, String role) {
    Objects.requireNonNull(project, "project");
    ProjectCredentialSource source = credentialSourceProvider.getIfAvailable();
    if (source == null) {
      log.debug(
          "resolveConnectorSecret no credential source bean present; adapters use host-env secret path projectId={} role={}",
          project.publicId(),
          role);
      return Optional.empty();
    }
    return source.resolveSecret(project, role);
  }

  private static <A> Map<ConnectorKind, A> indexByKind(
      List<A> adapters, Function<A, ConnectorKind> kindOf, String role) {
    Map<ConnectorKind, A> byKind = new EnumMap<>(ConnectorKind.class);
    for (A adapter : adapters) {
      ConnectorKind kind =
          Objects.requireNonNull(
              kindOf.apply(adapter),
              () -> role + " adapter " + adapter.getClass().getName() + " declared a null kind");
      A previous = byKind.putIfAbsent(kind, adapter);
      if (previous != null) {
        log.error(
            "duplicate {} adapter for connectorKind={} ({} and {})",
            role,
            kind.value(),
            previous.getClass().getName(),
            adapter.getClass().getName());
        throw new IllegalStateException(
            "Duplicate "
                + role
                + " adapter for connector kind "
                + kind.value()
                + ": "
                + previous.getClass().getName()
                + " and "
                + adapter.getClass().getName());
      }
    }
    return byKind;
  }

  private DomainException unsupported(Project project, ConnectorKind kind, String role) {
    log.warn(
        "unsupported connector kind projectId={} connectorKind={} role={}",
        project.publicId(),
        kind.value(),
        role);
    return new DomainException(
        DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND,
        "No " + role + " connector registered for kind " + kind.value());
  }

  private static List<String> kindValues(Map<ConnectorKind, ?> byKind) {
    return byKind.keySet().stream().map(ConnectorKind::value).toList();
  }
}
