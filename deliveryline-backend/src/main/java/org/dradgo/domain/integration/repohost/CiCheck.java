package org.dradgo.domain.integration.repohost;

import java.util.Objects;

/**
 * Story 3h-5 (AC1, FR79) — a vendor-neutral, domain-shaped projection of a single CI check for a
 * pushed commit. Adapters translate a host's native check/pipeline shape (a GitHub check-run plus
 * its failure annotations, a Bitbucket Pipelines step, …) into this record — no host-specific types
 * may surface here ({@code REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT}).
 *
 * <ul>
 *   <li>{@code name} — the check's display name (e.g. the GitHub Actions job name).
 *   <li>{@code conclusion} — the host's raw completion string, kept for surfacing/diagnostics only
 *       (the sweep decisions run off {@link CiStatus#conclusion()}, never this raw value).
 *   <li>{@code detailsUrl} — a human link to the check on the host (nullable).
 *   <li>{@code summary} — the check's {@code output.summary} (nullable).
 *   <li>{@code failureText} — for a failed check, the composed, <strong>bounded</strong> failure
 *       body (title + summary + text + failure annotations) that becomes the redaction-policed CI
 *       feedback reference. Empty for a non-failed check. Never logged as bytes — only its length.
 * </ul>
 */
public record CiCheck(
    String name, String conclusion, String detailsUrl, String summary, String failureText) {

  public CiCheck {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must be non-blank");
    }
    Objects.requireNonNull(conclusion, "conclusion");
    failureText = failureText == null ? "" : failureText;
  }
}
