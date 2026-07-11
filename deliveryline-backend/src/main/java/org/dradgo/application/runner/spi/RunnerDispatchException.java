package org.dradgo.application.runner.spi;

import java.util.Objects;
import org.dradgo.domain.registry.FailureCategory;

/**
 * DinD Testcontainers Task 6 — typed dispatch failure raised by a {@link RunnerAdapter#dispatch}
 * implementation that already knows the precise {@link FailureCategory} the failure should be
 * recorded under, distinct from the generic {@code RUNNER_CRASH} the broker otherwise assigns to
 * every {@code RuntimeException} escaping {@code dispatch(...)}.
 *
 * <p>Mirrors {@link org.dradgo.application.runner.workspace.spi.GitCommandException} (a typed,
 * category-carrying exception raised by an adapter-side operation and caught by the application
 * layer) — placed in {@code application.runner.spi} next to the {@link RunnerAdapter} port itself
 * so both {@code adapters.runner.*} (which throws it) and {@code application.runner} (which catches
 * it in {@code RunnerBroker}) can depend on it without breaching the {@code
 * application-cannot-import-adapters} ArchUnit rule.
 *
 * <p>First (and, as of this task, only) throw site: {@code DockerRunnerAdapter.dispatch} when the
 * per-run DinD sidecar fails to provision for a {@code testcontainersEnabled} EXECUTION dispatch —
 * carries {@link FailureCategory#TESTCONTAINERS_INFRA_FAILED}. {@code RunnerBroker}'s {@code
 * executeQueuedDispatch} catch block reads {@link #failureCategory()} when present instead of
 * defaulting to {@code RUNNER_CRASH}.
 */
public final class RunnerDispatchException extends RuntimeException {

  private final FailureCategory failureCategory;

  public RunnerDispatchException(FailureCategory failureCategory, String message, Throwable cause) {
    super(message, cause);
    this.failureCategory = Objects.requireNonNull(failureCategory, "failureCategory");
  }

  public FailureCategory failureCategory() {
    return failureCategory;
  }
}
