package org.dradgo.foundation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Aggregate verification that every Epic-1 foundation contract is live end-to-end.
 *
 * <p>This is the structural close of Epic 1 (story 1.23). It does <strong>not</strong> re-author
 * any per-contract assertion; instead each {@link Nested} class delegates via the JUnit Platform
 * Launcher API to the existing source-of-truth test class. Stronger-than-contract assertions
 * (cross-product legal-table check, exhaustive fixture sweeps, parameterized DomainErrorCode
 * mapper, structural CLI/REST symmetry) live in adjacent {@code *FoundationContract} classes that
 * are reached <strong>only</strong> through this aggregator — they are deliberately named with a
 * suffix that does not match Surefire's {@code **&#47;*Test.java} pattern nor Failsafe's include
 * list, so Maven discovery skips them; the Launcher API discovers them by FQN.
 *
 * <p><strong>Maven routing.</strong> Tagged {@code @Tag("foundation-gate")}. Both Surefire and
 * Failsafe in {@code deliveryline-backend/pom.xml} list {@code foundation-gate} under {@code
 * <excludedGroups>}; the dedicated {@code foundation-gate} Maven profile clears that exclusion and
 * adds {@code <groups>foundation-gate</groups>} so the test only runs in its dedicated CI tier.
 *
 * <p>Per story 1.23 AC1, failure messages produced by the helpers in {@link
 * FoundationGateAssertions} start with {@code [story X.Y]} so CI logs point at the broken contract
 * on first glance.
 */
@Tag("foundation-gate")
@DisplayName("Foundation Gate Verification (story 1.23)")
class FoundationGateVerificationTest {

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #1 — ArchUnit package boundaries (story 1.11)")
  class Contract01ArchUnitBoundaries {

    @Test
    @DisplayName("ArchitectureBoundaryTest passes")
    void archUnitBoundariesAreGreen() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.11", "org.dradgo.architecture.ArchitectureBoundaryTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #2 — Flyway V1 applies cleanly on a fresh DB (story 1.3)")
  class Contract02FlywayClean {

    @Test
    @DisplayName("FlywayMigrationsFoundationContract passes")
    void flywayApplied() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.3", "org.dradgo.foundation.FlywayMigrationsFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #3 — Central registries drift tests (story 1.4)")
  class Contract03RegistryDrift {

    @Test
    @DisplayName("RegistryContractTest passes")
    void registriesInLockstep() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.4", "org.dradgo.contract.RegistryContractTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #4 — WorkflowTransitionService legal table (story 1.5)")
  class Contract04TransitionTable {

    @Test
    @DisplayName("WorkflowTransitionServiceContractTest passes")
    void contractTestDelegate() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.5", "org.dradgo.contract.WorkflowTransitionServiceContractTest");
    }

    @Test
    @DisplayName("Cross-product (state x state) exhausts the legal-table")
    void crossProductTransitionsExhaustLegalTable() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.5", "org.dradgo.foundation.TransitionTableCrossProductFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #5 — RunnerContractValidator fixture sweep (story 1.6)")
  class Contract05RunnerContractFixtures {

    @Test
    @DisplayName("RunnerContractFixturesFoundationContract passes")
    void allFixturesValidatedAgainstManifest() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.6", "org.dradgo.foundation.RunnerContractFixturesFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #6 — Shared command model surface symmetry (story 1.7)")
  class Contract06CommandModelSymmetry {

    @Test
    @DisplayName("CommandModelSymmetryFoundationContract passes")
    void everyWorkflowCommandPermitHasBothCliAndRestSurface() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.7", "org.dradgo.foundation.CommandModelSymmetryFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #7 — ProblemDetails returns stable DomainErrorCodes (story 1.8)")
  class Contract07ProblemDetailsCodes {

    @Test
    @DisplayName("ProblemDetailsCoverageFoundationContract passes")
    void everyDomainErrorCodeHasMapperCoverage() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.8", "org.dradgo.foundation.ProblemDetailsCoverageFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #8 — IdempotencyService replay/conflict/race matrix (story 1.9)")
  class Contract08IdempotencyService {

    @Test
    @DisplayName("IdempotencyServiceContractTest passes")
    void idempotencyContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.9", "org.dradgo.application.idempotency.IdempotencyServiceContractTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName(
      "Contract #9 — RedactionPolicyService redacts every adversarial fixture (story 1.10)")
  class Contract09RedactionAdversarial {

    @Test
    @DisplayName("RedactionAdversarialFoundationContract passes")
    void everyFixtureRedactsItsManifestSecrets() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.10", "org.dradgo.foundation.RedactionAdversarialFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #10 — ArtifactOperationService availability gating (story 1.12)")
  class Contract10ArtifactOperation {

    @Test
    @DisplayName("ArtifactOperationServiceContractTest passes")
    void artifactOperationsContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "1.12", "org.dradgo.application.artifact.ArtifactOperationServiceContractTest");
    }

    /**
     * Direct short-circuit assertion (story 1.23 review patch P15). The "draft → available →
     * approval-eligible" sequence the AC describes is, at the workflow level, "any state →
     * (artifact reaches AVAILABLE, which advances the run to WAITING_FOR_SPEC_APPROVAL) → EXECUTING
     * (approved)". Skipping AVAILABLE means the workflow run never advances to
     * WAITING_FOR_SPEC_APPROVAL, and the legal-table makes WAITING_FOR_SPEC_APPROVAL the ONLY
     * source state allowed to transition to EXECUTING via the approve path. This nested assertion
     * proves the gate independently of the delegate-run above so a corrupted
     * ArtifactOperationServiceContractTest cannot silently mask the invariant.
     */
    @Test
    @DisplayName(
        "Direct short-circuit assertion — non-approval-ready states cannot reach EXECUTING")
    void cannotShortCircuitFromNonApprovalStateToExecuting() {
      org.dradgo.application.workflow.WorkflowTransitionTable table =
          org.dradgo.application.workflow.WorkflowTransitionTable.defaultTable();
      org.dradgo.domain.registry.WorkflowState[] preApprovalStates = {
        org.dradgo.domain.registry.WorkflowState.INBOX,
        org.dradgo.domain.registry.WorkflowState.PLANNED,
        org.dradgo.domain.registry.WorkflowState.INVESTIGATING,
      };
      for (org.dradgo.domain.registry.WorkflowState prior : preApprovalStates) {
        try {
          table.assertTransitionAllowed(
              "run_foundation_gate_artifact_probe",
              prior,
              org.dradgo.domain.registry.WorkflowState.EXECUTING,
              null,
              null);
          org.junit.jupiter.api.Assertions.fail(
              FoundationGateAssertions.tagged(
                  "1.12",
                  "short-circuit accepted: "
                      + prior.value()
                      + " -> Executing (no AVAILABLE traversal). This bypasses the artifact-"
                      + "availability gate."));
        } catch (org.dradgo.domain.DomainException expected) {
          if (expected.errorCode()
              != org.dradgo.domain.registry.DomainErrorCode.ILLEGAL_TRANSITION) {
            org.junit.jupiter.api.Assertions.fail(
                FoundationGateAssertions.tagged(
                    "1.12",
                    "short-circuit "
                        + prior.value()
                        + " -> Executing raised "
                        + expected.errorCode().value()
                        + ", expected ILLEGAL_TRANSITION"));
          }
        }
      }
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #11 — GitHub adapter port exists and the mock implements it (story 3.13)")
  class Contract11GitHubMockAdapter {

    @Test
    @DisplayName("GitHubMockAdapterUnitTest passes (mock implements the GitHubAdapter port)")
    void gitHubMockImplementsPort() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.13", "org.dradgo.adapters.integration.github.GitHubMockAdapterUnitTest");
    }

    @Test
    @DisplayName("GitHubScenarioContractTest passes (port + deterministic fixtures load)")
    void gitHubFixturesLoad() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.13", "org.dradgo.adapters.integration.github.GitHubScenarioContractTest");
    }

    @Test
    @DisplayName(
        "GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT is enforced (via ArchitectureBoundaryTest)")
    void gitHubPortBoundaryEnforced() {
      // The dedicated ArchUnit rule is registered as an @ArchTest field in ArchitectureBoundaryTest
      // (already delegate-run by Contract #1); re-running it here keeps the GitHub port-boundary
      // assertion legible at the GitHub contract site.
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.13", "org.dradgo.architecture.ArchitectureBoundaryTest");
    }

    /**
     * Story 3.13 AC10 — mock-vs-real interface parity is explicitly deferred to story 3.14 ("once
     * story 3.14 lands"). The {@code GitHubRealAdapter} does not exist yet, so authoring a parity
     * test now would assert against a non-existent type. This disabled placeholder is the
     * documented TODO referencing 3.14; story 3.14 removes the {@code @Disabled} and implements the
     * parity assertion (both adapters implement the same {@code GitHubAdapter} method set).
     */
    @Test
    @Disabled(
        "Deferred to story 3.14 (AC10): GitHubRealAdapter does not exist yet — parity asserted then.")
    @DisplayName("TODO(story 3.14) — GitHub mock vs real adapter interface parity")
    void gitHubMockVsRealParityDeferredToStory314() {
      // Intentionally empty — enabled by story 3.14.
    }
  }
}
