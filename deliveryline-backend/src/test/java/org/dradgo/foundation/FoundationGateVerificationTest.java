package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Aggregate verification that every foundation contract is live end-to-end.
 *
 * <p>This is the structural close of Epic 1 (story 1.23), since widened to make Epic 3 (Contracts
 * #11–#15), Epic 3c (Contracts #16–#20, story 3c.11), and Epic 3d (Contracts #21–#26, story 3d.9)
 * named, enforced members of the same gate. It does <strong>not</strong> re-author any per-contract
 * assertion; instead each {@link Nested} class delegates via the JUnit Platform Launcher API to the
 * existing source-of-truth test class. Stronger-than-contract assertions (cross-product legal-table
 * check, exhaustive fixture sweeps, parameterized DomainErrorCode mapper, structural CLI/REST
 * symmetry) live in adjacent {@code *FoundationContract} classes that are reached
 * <strong>only</strong> through this aggregator — they are deliberately named with a suffix that
 * does not match Surefire's {@code **&#47;*Test.java} pattern nor Failsafe's include list, so Maven
 * discovery skips them; the Launcher API discovers them by FQN. The Launcher discovers by FQN
 * regardless of Maven tag, so plain {@code *Test} unit tests and Testcontainers {@code *IT}s can be
 * aggregated here without retagging.
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
          "3.13", "org.dradgo.adapters.integration.repohost.github.GitHubMockAdapterUnitTest");
    }

    @Test
    @DisplayName("GitHubScenarioContractTest passes (port + deterministic fixtures load)")
    void gitHubFixturesLoad() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.13", "org.dradgo.adapters.integration.repohost.github.GitHubScenarioContractTest");
    }

    @Test
    @DisplayName(
        "REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT is enforced (via ArchitectureBoundaryTest)")
    void gitHubPortBoundaryEnforced() {
      // The dedicated ArchUnit rule is registered as an @ArchTest field in ArchitectureBoundaryTest
      // (already delegate-run by Contract #1); re-running it here keeps the GitHub port-boundary
      // assertion legible at the GitHub contract site.
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.13", "org.dradgo.architecture.ArchitectureBoundaryTest");
    }

    /**
     * Story 3.14 AC8 (reciprocal of story 3.13 AC10) — mock-vs-real adapter parity is now
     * implemented and enabled. {@code GitHubMockVsRealParityFoundationContract} drives equivalent
     * scenarios against both {@code GitHubMockAdapter} and {@code GitHubRealAdapter} (the real one
     * against {@code MockRestServiceServer}-stubbed HTTP) and asserts typed-shape + {@code
     * IntegrationFailureCategory} equivalence. The live-repo variant is {@code gh-real-tests}-gated
     * (nightly, story 3.35 AC3) and is NOT part of this gate.
     */
    @Test
    @DisplayName("GitHub mock vs real adapter parity (typed-shape + failure-category equivalence)")
    void gitHubMockVsRealParity() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.14", "org.dradgo.foundation.GitHubMockVsRealParityFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #12 — IntegrationLinkService.linkGitHubPr github-mock e2e (story 3.15)")
  class Contract12IntegrationLinkGitHubPr {

    /**
     * Story 3.15 AC8 — widens the foundation gate to assert {@code linkGitHubPr} works end-to-end
     * against the {@code github-mock} adapter: it resolves the PR, writes a {@code github_pr}
     * {@code integration_links} row (with real redaction preserving the NFR17 reconstruction
     * fields), and appends an {@code integration.linked} workflow event.
     */
    @Test
    @DisplayName("linkGitHubPr writes a github_pr row + integration.linked event against the mock")
    void linkGitHubPrEndToEndAgainstMockAdapter() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.15", "org.dradgo.foundation.IntegrationLinkGitHubPrFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #13 — Runner-queue inspection + Prometheus scrape (story 3.19)")
  class Contract13RunnerQueueInspection {

    /** Story 3.19 AC11a — every RunnerQueueStatus / WorkerStatus field is populated. */
    @Test
    @DisplayName("getRunnerQueueStatus populates every view field")
    void runnerQueueStatusFieldsPopulated() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.19", "org.dradgo.foundation.RunnerQueueInspectionFoundationContract");
    }

    /**
     * Story 3.19 AC11b — the headline metric {@code deliveryline_runner_queue_depth} is scrapeable
     * from Actuator's Prometheus endpoint and its value matches the DB queued count. Delegates to
     * the Testcontainers IT (the gate tier has Docker up).
     */
    @Test
    @DisplayName("deliveryline_runner_queue_depth is scrapeable and matches the DB count")
    void queueDepthMetricScrapeable() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.19", "org.dradgo.adapters.rest.RunnerQueuePrometheusScrapeIT");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName(
      "Contract #14 — TicketSourceAdapter abstraction parity + capability skip (story 3.32)")
  class Contract14TicketSourceAbstraction {

    /**
     * Story 3.32 AC9 — the Linear mock + real adapters satisfy the vendor-neutral {@code
     * TicketSourceAdapter} port, return neutral {@code Ticket}s on a happy read, classify failures
     * identically, and the completion sync skips gracefully when a source declares {@code
     * supportsCommentOnTicket=false}.
     */
    @Test
    @DisplayName("TicketSourceAdapter mock/real parity + capability-driven completion-sync skip")
    void ticketSourceAbstractionContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.32", "org.dradgo.foundation.TicketSourceAbstractionFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName(
      "Contract #15 — RepositoryHostAdapter abstraction parity + capability declaration (story 3.33)")
  class Contract15RepositoryHostAbstraction {

    /**
     * Story 3.33 AC10 — the GitHub mock + real adapters satisfy the vendor-neutral {@code
     * RepositoryHostAdapter} port, return neutral {@code Repository}/{@code PullRequest}s on a
     * happy read, classify failures identically, and declare the same GitHub capability set. The
     * draft-PR clause is vacuously satisfied (no draft path; story 3.33 R5/OQ-5).
     */
    @Test
    @DisplayName("RepositoryHostAdapter mock/real parity + capability declaration")
    void repositoryHostAbstractionContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3.33", "org.dradgo.foundation.RepositoryHostAbstractionFoundationContract");
    }
  }

  // ===========================================================================================
  // Epic 3c — Multi-Project Configuration (story 3c.11). Contracts #16–#20 plug the multi-project
  // test debt that stories 3c-1..3c-10 paid down incrementally into the named gate, so a
  // regression in any of them fails `-Pfoundation-gate verify`. Each contract delegate-runs the
  // existing source-of-truth test (no assertion re-authoring), mirroring Contracts #1–#15.
  // ===========================================================================================

  @Nested
  @Tag("foundation-gate")
  @DisplayName(
      "Contract #16 — Epic 3c registries + prefixes + V17 project schema drift (story 3c.2)")
  class Contract16ProjectRegistriesAndSchema {

    /**
     * Story 3c.2 — {@code RegistryContractTest} is already delegate-run by Contract #3, but re-run
     * here keeps the Epic 3c registry assertions ({@code ProjectStatus} / {@code ConnectorKind} /
     * {@code ConnectorRole} drift vs the {@code ck_projects_*} + {@code
     * ck_project_credentials_connector_role} SQL CHECKs and the API placeholder manifest, plus the
     * {@code prj_}/{@code cred_} prefix registration and persistence-boundary fail-fast parsing)
     * legible at the Epic 3c site — mirroring how Contract #11 re-runs {@code
     * ArchitectureBoundaryTest}.
     */
    @Test
    @DisplayName("RegistryContractTest passes (project_status / connector_kind / prefixes drift)")
    void projectRegistriesInLockstep() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3c.2", "org.dradgo.contract.RegistryContractTest");
    }

    /** Story 3c.2 — V17 {@code projects} + {@code project_credentials} schema shape. */
    @Test
    @DisplayName("FlywaySchemaContractTest passes (V17 projects + project_credentials shape)")
    void projectSchemaShapeHolds() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3c.2", "org.dradgo.contract.FlywaySchemaContractTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #17 — Per-project connector resolution (story 3c.3)")
  class Contract17ProjectConnectorResolution {

    /**
     * Story 3c.3 — {@code ProjectConnectorResolver} maps a project's {@code ConnectorKind} to the
     * right adapter, raises {@code UNSUPPORTED_CONNECTOR_KIND} for unknown kinds, returns empty
     * (not throw) from {@code findTicketSource} so the {@code SKIPPED_NO_LINEAR_PROFILE} path is
     * preserved, keeps capability-degradation parity, raises the project-scoped {@code
     * LINEAR_GITHUB_REPO_MISMATCH}, and never logs credential material. It is a Surefire unit test;
     * the Launcher API discovers it by FQN regardless of Maven tag, so no retag is needed.
     */
    @Test
    @DisplayName("ProjectConnectorResolverTest passes (kind→adapter, unsupported-kind, no-log)")
    void perProjectConnectorResolutionContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3c.3", "org.dradgo.application.project.ProjectConnectorResolverTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #18 — Credential cipher tamper / round-trip / fail-fast (story 3c.4)")
  class Contract18CredentialCipher {

    /**
     * Story 3c.4 AC6 — the envelope cipher round-trips ASCII/unicode/empty/large payloads, is
     * non-deterministic, rejects GCM tampering without leaking partial plaintext, refuses wrong
     * key-id / unsupported algo / malformed ciphertext, and stays silent on the secret hot path.
     */
    @Test
    @DisplayName("EnvelopeCredentialCipherTest passes (round-trip + tamper rejection + no-log)")
    void cipherTamperRoundTripContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3c.4", "org.dradgo.infrastructure.crypto.EnvelopeCredentialCipherTest");
    }

    /**
     * Story 3c.4 — {@code CREDENTIAL_MASTER_KEY_UNCONFIGURED} fail-fast fires only when at least
     * one credential exists (blank-tolerant otherwise).
     */
    @Test
    @DisplayName("CredentialMasterKeyGuardTest passes (fail-fast only when credentials present)")
    void masterKeyGuardContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3c.4", "org.dradgo.infrastructure.crypto.CredentialMasterKeyGuardTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #19 — Credential redaction across egress (story 3c.5)")
  class Contract19CredentialRedaction {

    /**
     * Story 3c.5 AC3 — {@code RedactionAdversarialFoundationContract} is already delegate-run by
     * Contract #9; re-running it here makes the project-credential AR10 coverage a named Epic 3c
     * contract. The sweep strips every forbidden snippet declared by the manifest (including the
     * three {@code project-credential-*.json} fixtures) across all egress surfaces.
     */
    @Test
    @DisplayName(
        "RedactionAdversarialFoundationContract passes (project-credential fixtures swept)")
    void credentialRedactionSweepContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3c.5", "org.dradgo.foundation.RedactionAdversarialFoundationContract");
    }

    /**
     * Direct short-circuit drift guard (mirrors the Contract #10 direct-assertion pattern). Proves
     * the three project-credential fixtures are enumerated in {@code fixtures-manifest.json}
     * independently of the delegate-run above, so a corrupted/emptied manifest cannot silently
     * remove project-credential coverage from the AR10 sweep without failing this gate. See {@code
     * redaction-fixtures/fixtures-manifest.json} (story 3c-5).
     */
    @Test
    @DisplayName(
        "Direct guard — the three project-credential-*.json fixtures are in the redaction manifest")
    void projectCredentialFixturesEnumeratedInManifest() throws IOException {
      Path manifest =
          Path.of("src", "test", "resources", "redaction-fixtures", "fixtures-manifest.json");
      if (!Files.isRegularFile(manifest)) {
        fail(
            FoundationGateAssertions.tagged(
                "3c.5", "fixtures-manifest.json missing at " + manifest.toAbsolutePath()));
        return;
      }
      JsonNode entries = new ObjectMapper().readTree(manifest.toFile()).path("fixtures");
      if (!entries.isArray() || entries.isEmpty()) {
        fail(
            FoundationGateAssertions.tagged(
                "3c.5", "fixtures-manifest.json fixtures array is missing or empty"));
        return;
      }
      Set<String> manifestFiles = new LinkedHashSet<>();
      for (JsonNode entry : entries) {
        manifestFiles.add(entry.path("file").asText(""));
      }
      List<String> required =
          List.of(
              "project-credential-linear-token.json",
              "project-credential-github-token.json",
              "project-credential-opaque-under-key.json");
      List<String> missing = new ArrayList<>();
      for (String name : required) {
        if (!manifestFiles.contains(name)) {
          missing.add(name);
        }
      }
      if (!missing.isEmpty()) {
        fail(
            FoundationGateAssertions.tagged(
                "3c.5",
                "project-credential redaction fixtures missing from fixtures-manifest.json: "
                    + String.join(", ", missing)
                    + " — project-credential secrets would silently drop out of the AR10 sweep"));
      }
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #20 — Config-inversion parity + per-project dispatch (story 3c.6/3c.7)")
  class Contract20ConfigInversionParity {

    /**
     * Story 3c.6/3c.7 AC2 — {@code RunProjectAssociationIT} drives real dispatches and asserts both
     * single-project behavioral parity ({@code defaultProjectRunStaysByteIdenticalToPre3c}: global
     * repo ref + global OpenSpec flag, no per-run OpenSpec env) and that a non-default project uses
     * its own repo / connector / OpenSpec settings ({@code
     * nonDefaultProjectRunDispatchesWithThatProjectsRepoOpenSpecAndConnectorSelection}). It is a
     * Testcontainers IT; the gate tier has Docker up (Contract #13 already runs an IT here).
     */
    @Test
    @DisplayName("RunProjectAssociationIT passes (default byte-parity + non-default dispatch)")
    void configInversionAndPerProjectDispatchContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3c.7", "org.dradgo.application.workflow.RunProjectAssociationIT");
    }

    /**
     * Story 3c.6 — {@code DefaultProjectSeederIT} proves the {@code default} project is seeded from
     * today's global config (byte-parity) and is not duplicated on application restart.
     */
    @Test
    @DisplayName(
        "DefaultProjectSeederIT passes (seed-from-global parity + no-duplicate-on-restart)")
    void defaultProjectSeederContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3c.6", "org.dradgo.application.project.DefaultProjectSeederIT");
    }
  }

  // ===========================================================================================
  // Epic 3d — Per-Step Execution Control (story 3d.9). Contracts #21–#26 plug the per-step-
  // execution test debt that stories 3d-1..3d-8 paid down incrementally into the named gate, so a
  // regression in any of them fails `-Pfoundation-gate verify`. Each contract delegate-runs the
  // existing source-of-truth test (no assertion re-authoring), mirroring Contracts #1–#20.
  // ===========================================================================================

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #21 — Reviewer registries + step_reviews schema drift (story 3d.1)")
  class Contract21ReviewerRegistriesAndSchema {

    /**
     * Story 3d.1 — {@code RegistryContractTest} is already delegate-run by Contract #3/#16, but
     * re-run here keeps the Epic 3d reviewer registry assertions ({@code ReviewOutcome} / {@code
     * reviewer} connector role / {@code rev_} prefix drift vs the SQL CHECKs and the API
     * placeholder manifest) legible at the Epic 3d site — mirroring how Contract #16 re-runs it for
     * Epic 3c.
     */
    @Test
    @DisplayName("RegistryContractTest passes (review_outcome / reviewer role / rev_ prefix drift)")
    void reviewerRegistriesInLockstep() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.1", "org.dradgo.contract.RegistryContractTest");
    }

    /** Story 3d.1 — V19 {@code step_reviews} table shape + {@code rev_} public-id prefix. */
    @Test
    @DisplayName("FlywaySchemaContractTest passes (step_reviews table + rev_ prefix shape)")
    void reviewerSchemaShapeHolds() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.1", "org.dradgo.contract.FlywaySchemaContractTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #22 — Reviewer execution advisory-only + persistence (story 3d.2)")
  class Contract22ReviewerExecutionAdvisory {

    /**
     * Story 3d.2 — the reviewer verdict is advisory-only: the human decision is unaffected, {@code
     * reviewer_gating_enabled} is never consulted in this epic, a no-binding project is byte-
     * identical to pre-3d, and self-review is flagged-not-refused. {@code
     * WorkflowInspectionServiceReviewerVerdictTest} proves the verdict surface adds no {@code
     * AllowedAction} and no binding gate.
     */
    @Test
    @DisplayName(
        "WorkflowInspectionServiceReviewerVerdictTest passes (advisory-only, no-bind parity)")
    void reviewerVerdictAdvisoryOnly() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.2", "org.dradgo.application.workflow.WorkflowInspectionServiceReviewerVerdictTest");
    }

    /** Story 3d.2 — {@code step_reviews} verdict write seam (mirrors the Approvals write path). */
    @Test
    @DisplayName(
        "StepReviewPersistenceAdapterContractTest passes (verdict persisted to step_reviews)")
    void reviewerVerdictPersisted() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.2", "org.dradgo.adapters.persistence.StepReviewPersistenceAdapterContractTest");
    }

    /** Story 3d.2 — the Docker reviewer runner resolves per-project reviewer credentials. */
    @Test
    @DisplayName(
        "DockerRunnerAdapterReviewerCredentialTest passes (per-project reviewer credential)")
    void reviewerCredentialResolution() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.2", "org.dradgo.adapters.runner.DockerRunnerAdapterReviewerCredentialTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #23 — Manual execution park + artifact submission (story 3d.3/3d.4)")
  class Contract23ManualExecution {

    /**
     * Story 3d.3 — a {@code manual} runner-kind parks the run in {@code WaitingForManualExecution}
     * without launching a container or enqueueing a dispatch. Testcontainers IT (the gate tier has
     * Docker up).
     */
    @Test
    @DisplayName("ManualExecutionParkIT passes (manual kind parks, no container/queue)")
    void manualExecutionParks() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.3", "org.dradgo.application.workflow.ManualExecutionParkIT");
    }

    /**
     * Story 3d.4 — manual artifact submission re-enters validation (spec/plan/prOutput), is
     * idempotent (reserve-before-validate), and raises {@code MANUAL_EXECUTION_NOT_APPLICABLE} when
     * the run is not parked.
     */
    @Test
    @DisplayName("ManualArtifactEndpointContractTest passes (submission re-enters validation)")
    void manualArtifactSubmissionEndpoint() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.4", "org.dradgo.adapters.rest.ManualArtifactEndpointContractTest");
    }

    /** Story 3d.4 — the run-scoped manual bundle retrieval surface. */
    @Test
    @DisplayName("ManualBundleEndpointContractTest passes (run-scoped manual bundle retrieval)")
    void manualBundleEndpoint() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.4", "org.dradgo.adapters.rest.ManualBundleEndpointContractTest");
    }

    /**
     * Story 3d.4 — end-to-end manual submission re-enters the orchestration tail (secret-scan /
     * capture-and-push minus the workspace a parked run never made). Testcontainers IT.
     */
    @Test
    @DisplayName("ManualArtifactSubmissionIT passes (submission ingests via the broker tail)")
    void manualArtifactSubmissionEndToEnd() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.4", "org.dradgo.application.runner.ManualArtifactSubmissionIT");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #24 — Live logs + read-only diagnostic console posture (story 3d.5/3d.6)")
  class Contract24LiveLogsAndConsole {

    /**
     * Story 3d.5 — one SSE endpoint serves live ({@code docker logs -f} behind {@code
     * RunnerLogStreamPort}) and finished (replay of the 3.6 persisted post-hoc-redacted log) cases,
     * server-side allowed-action gated, localhost-only.
     */
    @Test
    @DisplayName("RunnerLogStreamControllerTest passes (live+finished SSE, server-side gating)")
    void runnerLogStreamController() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.5", "org.dradgo.adapters.rest.RunnerLogStreamControllerTest");
    }

    /** Story 3d.5 — the live/finished stream service best-effort redacts the live view. */
    @Test
    @DisplayName(
        "StepLogStreamServiceTest passes (live follow + finished replay, best-effort redact)")
    void stepLogStreamService() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.5", "org.dradgo.application.runner.StepLogStreamServiceTest");
    }

    /** Story 3d.5 — the finished-case read of the already-persisted post-hoc-redacted log. */
    @Test
    @DisplayName(
        "LocalRunnerLogStoreTest passes (persisted redacted log unchanged by the live view)")
    void localRunnerLogStore() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.5", "org.dradgo.adapters.files.LocalRunnerLogStoreTest");
    }

    /**
     * Story 3d.6 — the read-only console attaches WITHOUT stdin ({@code withStdIn} never invoked),
     * the provable-non-mutation posture signed off in ADR 0025.
     */
    @Test
    @DisplayName("DefaultDockerEngineGatewayTest passes (attach without stdin — no write channel)")
    void dockerEngineGatewayReadOnlyAttach() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.6", "org.dradgo.adapters.runner.docker.DefaultDockerEngineGatewayTest");
    }

    /**
     * Story 3d.6 — the console is LIVE-ONLY (a terminal/absent runner is rejected with {@code
     * console-not-live}, no attach, no {@code console.opened}) and every session is governed-
     * history recorded ({@code console.opened}/{@code console.closed}).
     */
    @Test
    @DisplayName("DiagnosticConsoleServiceTest passes (live-only + governed console.opened/closed)")
    void diagnosticConsoleService() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.6", "org.dradgo.application.workflow.DiagnosticConsoleServiceTest");
    }

    /** Story 3d.6 — the console REST surface is allowed-action gated and exposes no input path. */
    @Test
    @DisplayName("RunnerDiagnosticConsoleControllerTest passes (gated, receive-only SSE)")
    void diagnosticConsoleController() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.6", "org.dradgo.adapters.rest.RunnerDiagnosticConsoleControllerTest");
    }

    /** Story 3d.6 — the console/log stream beans are localhost-profile wired. */
    @Test
    @DisplayName("RunnerConsoleStreamProfileWiringContractTest passes (localhost-only wiring)")
    void consoleStreamProfileWiring() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.6", "org.dradgo.adapters.runner.RunnerConsoleStreamProfileWiringContractTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #25 — Provider usage/limit status + redaction (story 3d.7)")
  class Contract25ProviderUsage {

    /**
     * Story 3d.7 — the provider-usage snapshot persists (no-secret {@code pul_} rows) and surfaces
     * per-credential attribution. Testcontainers IT.
     */
    @Test
    @DisplayName(
        "ProviderUsageSnapshotPersistenceAdapterIT passes (snapshot persist + attribution)")
    void providerUsageSnapshotPersisted() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.7", "org.dradgo.adapters.persistence.ProviderUsageSnapshotPersistenceAdapterIT");
    }

    /** Story 3d.7 — the usage/limit status REST surface + gate-denial posture. */
    @Test
    @DisplayName("ProviderUsageStatusControllerTest passes (status surface + gate denial)")
    void providerUsageStatusController() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.7", "org.dradgo.adapters.rest.ProviderUsageStatusControllerTest");
    }

    /**
     * Direct manifest drift guard (mirrors the Contract #19 project-credential guard). Proves the
     * {@code provider-usage-snapshot.json} fixture is enumerated in {@code fixtures-manifest.json}
     * independently of the AR10 sweep (Contract #9), so a corrupted/emptied manifest cannot
     * silently remove provider-usage coverage from the redaction sweep without failing this gate.
     * See {@code redaction-fixtures/fixtures-manifest.json} (story 3d-7).
     */
    @Test
    @DisplayName("Direct guard — provider-usage-snapshot.json is in the redaction manifest")
    void providerUsageFixtureEnumeratedInManifest() throws IOException {
      Path manifest =
          Path.of("src", "test", "resources", "redaction-fixtures", "fixtures-manifest.json");
      if (!Files.isRegularFile(manifest)) {
        fail(
            FoundationGateAssertions.tagged(
                "3d.7", "fixtures-manifest.json missing at " + manifest.toAbsolutePath()));
        return;
      }
      JsonNode entries = new ObjectMapper().readTree(manifest.toFile()).path("fixtures");
      if (!entries.isArray() || entries.isEmpty()) {
        fail(
            FoundationGateAssertions.tagged(
                "3d.7", "fixtures-manifest.json fixtures array is missing or empty"));
        return;
      }
      Set<String> manifestFiles = new LinkedHashSet<>();
      for (JsonNode entry : entries) {
        manifestFiles.add(entry.path("file").asText(""));
      }
      if (!manifestFiles.contains("provider-usage-snapshot.json")) {
        fail(
            FoundationGateAssertions.tagged(
                "3d.7",
                "provider-usage-snapshot.json missing from fixtures-manifest.json — the 3d-7"
                    + " provider-usage secret would silently drop out of the AR10 sweep"));
      }
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #26 — Soft-hide append-only invariant (story 3d.8)")
  class Contract26SoftHideAppendOnly {

    /**
     * Story 3d.8 (FR47) — hiding/un-hiding a run NEVER mutates or deletes {@code workflow_events};
     * archived runs remain audit-queryable. Testcontainers IT proves the append-only invariant
     * against real Postgres.
     */
    @Test
    @DisplayName(
        "WorkflowArchiveServiceAppendOnlyIT passes (workflow_events untouched on hide/unhide)")
    void archiveAppendOnlyInvariant() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.8", "org.dradgo.application.workflow.WorkflowArchiveServiceAppendOnlyIT");
    }

    /**
     * Story 3d.8 — the archive service writes only the run (cascade is scope-not-write) and raises
     * {@code ARCHIVE_NOT_APPLICABLE} for an ineligible run.
     */
    @Test
    @DisplayName("WorkflowArchiveServiceTest passes (single-run write + ARCHIVE_NOT_APPLICABLE)")
    void archiveServiceBehavior() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.8", "org.dradgo.application.workflow.WorkflowArchiveServiceTest");
    }

    /** Story 3d.8 — the hide/unhide REST surface + allowed-action gating. */
    @Test
    @DisplayName("ArchiveRunEndpointContractTest passes (hide/unhide REST + gating)")
    void archiveRunEndpoint() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3d.8", "org.dradgo.adapters.rest.ArchiveRunEndpointContractTest");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #27 — JIRA TicketSourceAdapter parity (stories 3i-1, 3i-2)")
  class Contract27JiraTicketSourceParity {

    /**
     * Story 3i-1 AC8 / 3i-2 AC8 — the JIRA mock + real adapters satisfy the vendor-neutral {@code
     * TicketSourceAdapter} port, return neutral {@code Ticket}s on a happy read, classify failures
     * identically, probe connectivity alike, and (3i-2) both advertise {@code supportsTicketQuery}
     * and return the same neutral {@code TicketSummary} shape from {@code queryTickets}.
     *
     * <p>{@code JiraTicketSourceParityFoundationContract} was authored in 3i-1 but never registered
     * here. Its class name does not match any Surefire/Failsafe include pattern, so the only way it
     * runs is this delegate entry — without it the contract was inert. Registered by story 3i-2.
     */
    @Test
    @DisplayName("JIRA mock/real parity + ticket-query capability + neutral TicketSummary shape")
    void jiraTicketSourceParityContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3i-1", "org.dradgo.foundation.JiraTicketSourceParityFoundationContract");
    }
  }

  @Nested
  @Tag("foundation-gate")
  @DisplayName("Contract #28 — Bitbucket RepositoryHostAdapter parity (story 3i-3)")
  class Contract28BitbucketRepositoryHostParity {

    /**
     * Story 3i-3 AC7 — the Bitbucket mock + real adapters satisfy the vendor-neutral {@code
     * RepositoryHostAdapter} port, return neutral {@code Repository}/{@code PullRequest}s on a
     * happy read, classify failures identically, declare the same Bitbucket capability set, and
     * probe connectivity alike. Bitbucket is the second real repository host (the GitHub twin is
     * Contract #15).
     *
     * <p>{@code BitbucketRepositoryHostParityFoundationContract}'s class name matches no
     * Surefire/Failsafe include pattern, so this delegate entry is its only execution path —
     * without it the contract would be inert (the trap 3i-1's JIRA contract fell into, fixed in
     * 3i-2).
     */
    @Test
    @DisplayName("Bitbucket mock/real parity + capability declaration + connectivity probe")
    void bitbucketRepositoryHostParityContract() {
      FoundationGateAssertions.delegateRunAssertGreen(
          "3i-3", "org.dradgo.foundation.BitbucketRepositoryHostParityFoundationContract");
    }
  }
}
