package org.dradgo.application.runner.workspace;

import java.util.List;
import org.dradgo.application.runner.workspace.spi.RepoTreeEntry;

/**
 * Project-owned record (story 3a-2 AC6, Decision D3) carrying the curated repo-context summary the
 * spec-stage bundle embeds. Derived by {@link RepositoryWorkspaceService#summarize} from a prepared
 * {@link RepositoryWorkspaceService.RepositoryMount}, passed as a plain method parameter into
 * {@code ContextBundleService.createForSpecInvestigation} (NEVER as a constructor dependency —
 * Traps T1/T2) so the unconditional {@code ContextBundleService} stays free of the profile-gated
 * workspace bean.
 *
 * <ul>
 *   <li>{@code mountPath} — the deterministic container mount path ({@code /workspace/repo});
 *   <li>{@code treeSummary} — depth-bounded, entry-capped, {@code .gitignore}-respecting tree;
 *   <li>{@code readmeRef} — mount-relative path to the detected README, or {@code null} when none;
 *   <li>{@code manifestRefs} — mount-relative references to detected package/build manifests;
 *   <li>{@code mappingVersion} — the Linear↔GitHub mapping marker (config-derived for the pilot,
 *       Decision D2).
 * </ul>
 *
 * <p>Every path is mount-relative or the container mount path — NEVER a host absolute path (Trap
 * T7).
 */
public record RepositoryContextSummary(
    String mountPath,
    List<RepoTreeEntry> treeSummary,
    String readmeRef,
    List<RepoManifestRef> manifestRefs,
    String mappingVersion) {

  public RepositoryContextSummary {
    if (mountPath == null || mountPath.isBlank()) {
      throw new IllegalArgumentException("mountPath must be non-blank");
    }
    if (mappingVersion == null || mappingVersion.isBlank()) {
      throw new IllegalArgumentException("mappingVersion must be non-blank");
    }
    treeSummary = treeSummary == null ? List.of() : List.copyOf(treeSummary);
    manifestRefs = manifestRefs == null ? List.of() : List.copyOf(manifestRefs);
    // readmeRef stays nullable (absent README is valid — AC1); mountPath non-null/blank already
    // enforced by the guard above.
  }

  public boolean readmePresent() {
    return readmeRef != null && !readmeRef.isBlank();
  }
}
