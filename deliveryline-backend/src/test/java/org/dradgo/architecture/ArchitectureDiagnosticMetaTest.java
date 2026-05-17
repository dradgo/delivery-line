package org.dradgo.architecture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.dradgo.adapters.cli.InvalidShellAdapter;
import org.dradgo.adapters.persistence.entity.InvalidWorkflowStateMutator;
import org.dradgo.application.artifact.InvalidArtifactWriteHelper;
import org.dradgo.application.workflow.InvalidWorkflowStatePortMutator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(ArchitectureRuleCatalog.ARCHITECTURE_TAG)
class ArchitectureDiagnosticMetaTest {

  @Test
  void ruleFailuresIncludeRuleNameOffendingClassAndRemediationHint() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importClasses(InvalidShellAdapter.class);

    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> ArchitectureRuleCatalog.SPRING_SHELL_COMMANDS_MUST_BE_PLURALIZED.check(classes));

    assertTrue(
        error
            .getMessage()
            .contains(
                "Spring Shell command adapters must end in Commands and stay under adapters.cli"));
    assertTrue(error.getMessage().contains(InvalidShellAdapter.class.getName()));
    assertTrue(
        error
            .getMessage()
            .contains(
                "Remediation: rename Spring Shell transport classes to *Commands and keep them under org.dradgo.adapters.cli."));
  }

  @Test
  void workflowStateMutationRuleFailsWhenNonTransitionCodeCallsTheEntitySetter() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importClasses(InvalidWorkflowStateMutator.class);

    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                ArchitectureRuleCatalog.ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE
                    .check(classes));

    assertTrue(
        error
            .getMessage()
            .contains("workflow state changes must go through WorkflowTransitionService"));
    assertTrue(error.getMessage().contains(InvalidWorkflowStateMutator.class.getName()));
  }

  @Test
  void workflowStateMutationRuleFailsWhenNonTransitionCodeCallsTheStatePort() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importClasses(InvalidWorkflowStatePortMutator.class);

    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                ArchitectureRuleCatalog.ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE
                    .check(classes));

    assertTrue(
        error
            .getMessage()
            .contains("workflow state changes must go through WorkflowTransitionService"));
    assertTrue(error.getMessage().contains(InvalidWorkflowStatePortMutator.class.getName()));
  }

  @Test
  void artifactWriteRuleFailsWhenNonArtifactApplicationCodeCallsTheStoreDirectly() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importClasses(InvalidArtifactWriteAdapter.class);

    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                ArchitectureRuleCatalog.ARTIFACT_WRITES_MUST_GO_THROUGH_ARTIFACT_OPERATION_SERVICE
                    .check(classes));

    assertTrue(
        error.getMessage().contains("artifact writes must go through ArtifactOperationService"));
    assertTrue(error.getMessage().contains(InvalidArtifactWriteAdapter.class.getName()));
    assertTrue(error.getMessage().contains("org.dradgo.adapters.files.LocalArtifactStore"));
  }

  @Test
  void artifactWriteRuleFailsWhenArtifactHelperBypassesTheNamedService() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importClasses(InvalidArtifactWriteHelper.class);

    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                ArchitectureRuleCatalog.ARTIFACT_WRITES_MUST_GO_THROUGH_ARTIFACT_OPERATION_SERVICE
                    .check(classes));

    assertTrue(
        error.getMessage().contains("artifact writes must go through ArtifactOperationService"));
    assertTrue(error.getMessage().contains(InvalidArtifactWriteHelper.class.getName()));
    assertTrue(error.getMessage().contains("org.dradgo.adapters.files.LocalArtifactStore"));
  }
}
