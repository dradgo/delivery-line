package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.application.project.ProjectConnectivityService.TestConnectionResult;

/**
 * Story 3c-8 (AC3) — wire shape for {@code POST /api/v1/projects/{projectId}/test-connection}. One
 * {@link CheckResult} per probe (repository reachable, ticket-source auth, repository-host auth).
 * Per-check failures are in-band data (HTTP 200), not Problem-Details errors.
 */
@Schema(name = "TestConnection", description = "Per-check connectivity probe results (HTTP 200).")
public record TestConnectionResponse(
    @Schema(description = "One result per connectivity check.") List<CheckResult> checks) {

  public static TestConnectionResponse from(TestConnectionResult result) {
    List<CheckResult> checks =
        result.checks().stream()
            .map(c -> new CheckResult(c.check(), c.status().value(), c.detail()))
            .toList();
    return new TestConnectionResponse(checks);
  }

  /** A single connectivity check outcome. */
  @Schema(
      name = "ConnectionCheckResult",
      description = "One connectivity check's tri-state result.")
  public record CheckResult(
      @Schema(
              description = "Which check.",
              example = "repository_reachable",
              allowableValues = {
                "repository_reachable",
                "ticket_source_auth",
                "repository_host_auth"
              })
          String check,
      @Schema(
              description = "Tri-state outcome.",
              example = "pass",
              allowableValues = {"pass", "fail", "skipped"})
          String status,
      @Schema(description = "Secret-free human detail.", example = "authenticated")
          String detail) {}
}
