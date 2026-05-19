package org.dradgo.foundation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 1.23 AC11 — regression-detection meta-test (documentation made structural).
 *
 * <p>This class is intentionally {@code @Disabled} — actually breaking the foundation contracts
 * on {@code main} would also break CI for everyone. The class body documents the three executable
 * proof-cases a maintainer follows manually when validating that {@code FoundationGateVerificationTest}
 * actually detects each regression. Its existence in {@code org.dradgo.foundation} keeps the
 * meta-test discoverable next to the real verification class without running in CI.
 *
 * <h2>Proof case 1 — ArchUnit violation (contract #1, story 1.11)</h2>
 * <p><strong>How to verify:</strong>
 * <ol>
 *   <li>In any production source under {@code deliveryline-backend/src/main/java/org/dradgo/domain/},
 *       add an import for a class under {@code org.dradgo.adapters.*}. (Example: edit
 *       {@code org.dradgo.domain.DomainException} and add {@code import org.dradgo.adapters.rest.ProblemDetailsCatalog;}
 *       with an unused reference.)</li>
 *   <li>Run: {@code ./mvnw -pl deliveryline-backend -Pfoundation-gate failsafe:integration-test failsafe:verify -Dit.test='*FoundationGateVerificationTest*'}.</li>
 *   <li><strong>Expected failure message:</strong> the {@code Contract01ArchUnitBoundaries}
 *       nested class fails with a message starting {@code [story 1.11] delegate-run reported N
 *       failure(s) in org.dradgo.architecture.ArchitectureBoundaryTest: ...}, identifying the
 *       offending illegal cross-layer import.</li>
 *   <li><strong>Revert:</strong> remove the import + reference.</li>
 * </ol>
 *
 * <h2>Proof case 2 — Registry drift (contract #3, story 1.4)</h2>
 * <p><strong>How to verify:</strong>
 * <ol>
 *   <li>In {@code org.dradgo.domain.registry.WorkflowEventType}, add a new enum value (e.g.
 *       {@code SYNTHETIC_TEST_VALUE("synthetic.test.value")}) without updating
 *       {@code PersistedRegistryValues} / contract registry fixtures.</li>
 *   <li>Run the same Maven command as proof case 1.</li>
 *   <li><strong>Expected failure message:</strong> the {@code Contract03RegistryDrift} nested
 *       class fails with a message starting {@code [story 1.4] delegate-run reported N failure(s)
 *       in org.dradgo.contract.RegistryContractTest: ...}, naming the unregistered value.</li>
 *   <li><strong>Revert:</strong> remove the added enum value.</li>
 * </ol>
 *
 * <h2>Proof case 3 — Legal-table illegal-case omission (contract #4, story 1.5)</h2>
 * <p><strong>How to verify:</strong>
 * <ol>
 *   <li>In {@code org.dradgo.application.workflow.WorkflowTransitionTable#defaultTable}, add an
 *       illegal target to an existing source state — e.g. extend the {@code WorkflowState.COMPLETED}
 *       row from {@code Set.of()} (terminal) to {@code Set.of(WorkflowState.EXECUTING)}.</li>
 *   <li>Run the same Maven command as proof case 1.</li>
 *   <li><strong>Expected failure message:</strong> the {@code Contract04TransitionTable} nested
 *       class's {@code crossProductTransitionsExhaustLegalTable} method fails with a message
 *       starting {@code [story 1.5] legal-table cross-product mismatch (1 violation): Completed
 *       -> Executing was accepted but is NOT in the legal table} OR the inverse if the existing
 *       {@code WorkflowTransitionServiceContractTest} also asserts terminal-state immutability.</li>
 *   <li><strong>Revert:</strong> restore the {@code Set.of()} terminal entry.</li>
 * </ol>
 *
 * <p>Each proof case is independently reversible; do not run them as a batch on {@code main}.
 * The intended workflow is local-branch experimentation with {@code git stash} or
 * {@code git checkout --} on revert.
 */
@Disabled("Meta-test — see class Javadoc for usage. Story 1.23 AC11.")
@Tag("foundation-gate")
class FoundationGateRegressionDetectionTest {

  @Test
  void documentationProofCasesAreDeclaredInTheClassJavadoc() {
    // No-op. The proof cases live in the class Javadoc; this method exists so JUnit Platform
    // discovers the class and the @Disabled annotation is observed (which surfaces the class in
    // the test report as skipped rather than empty).
  }
}
