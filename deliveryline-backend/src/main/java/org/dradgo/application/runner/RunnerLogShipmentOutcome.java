package org.dradgo.application.runner;

/** Story 3.7 (Decision D5) — outcome of a {@link RunnerLogShippingService#shipIfPermitted} call. */
public enum RunnerLogShipmentOutcome {
  /** The redacted log was written to the observability ingest location. */
  SHIPPED,
  /**
   * The log's classification was not shippable (e.g. {@code local-only}); nothing was exposed to
   * the ingest location — the fail-closed default.
   */
  SKIPPED_NOT_SHIPPABLE
}
