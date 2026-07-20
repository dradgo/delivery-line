package org.dradgo.domain.integration.repohost;

import java.util.List;
import java.util.Objects;

/**
 * Story 3h-5 (AC1, FR79) — the vendor-neutral, domain-shaped CI verdict for a pushed commit,
 * returned by {@code RepositoryHostAdapter.readCheckRuns}. Adapters compose it from a host's native
 * check/pipeline API (GitHub check-runs + annotations, Bitbucket Pipelines, …) so that no
 * host-specific type leaks through the port ({@code
 * REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT}) and the scheduled {@code
 * CiStatusPollingService} sweep drives its decisions off the neutral {@link CiConclusion} alone.
 *
 * <p>{@code headSha} is the commit SHA the checks were read for (the pushed commit from {@code
 * RepositoryPushOutcome.commitSha()}). {@code checks} carries the per-check detail; for a {@link
 * CiConclusion#FAILURE} verdict the failed checks carry a bounded {@link CiCheck#failureText()}
 * that becomes the redaction-policed CI feedback reference.
 */
public record CiStatus(CiConclusion conclusion, String headSha, List<CiCheck> checks) {

  public CiStatus {
    Objects.requireNonNull(conclusion, "conclusion");
    Objects.requireNonNull(headSha, "headSha");
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  /** A still-running verdict for {@code headSha} — keep polling. */
  public static CiStatus pending(String headSha) {
    return new CiStatus(CiConclusion.PENDING, headSha, List.of());
  }

  /** A verdict for {@code headSha} that could not be read within the poll-attempt budget. */
  public static CiStatus unavailable(String headSha) {
    return new CiStatus(CiConclusion.UNAVAILABLE, headSha, List.of());
  }
}
