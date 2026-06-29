package org.dradgo.runnercontracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.junit.jupiter.api.Test;

/** Story 3f-4 — contract coverage for the split-proposal.v1 schema. */
class SplitProposalContractTest {

  private final RunnerContractValidator validator = new RunnerContractValidator();

  private ValidationResult validate(String json) {
    return validator.validate(
        ValidationTarget.SPLIT_PROPOSAL,
        json.getBytes(StandardCharsets.UTF_8),
        ValidationContext.defaults());
  }

  @Test
  void wellFormedProposalWithSubtasksAndDependenciesIsValid() {
    String json =
        """
        {"schemaVersion":1,"workflowRunId":"run_split_aaaa","runnerExecutionId":"rex_split_aaaa",
         "subtasks":[{"ordinal":1,"title":"Part one","scope":"Do the first thing"},
                     {"ordinal":2,"title":"Part two","scope":"Do the second thing"}],
         "dependencies":[{"fromOrdinal":2,"toOrdinal":1}],
         "classification":"shareable-redacted","failureCategory":null}
        """;
    assertTrue(validate(json).valid(), () -> "errors: " + validate(json).errors());
  }

  @Test
  void degradedFailureResultMayCarryEmptySubtasks() {
    // failureCategory set => the if/then relaxes the minItems:1 requirement (the harvester
    // degrades).
    String json =
        """
        {"schemaVersion":1,"workflowRunId":"run_split_aaaa","runnerExecutionId":"rex_split_aaaa",
         "subtasks":[],"dependencies":[],
         "classification":"shareable-redacted","failureCategory":"runner_malformed_output"}
        """;
    assertTrue(validate(json).valid(), () -> "errors: " + validate(json).errors());
  }

  @Test
  void successResultWithEmptySubtasksIsInvalid() {
    // No failureCategory => a successful proposal MUST carry >=1 subtask.
    String json =
        """
        {"schemaVersion":1,"workflowRunId":"run_split_aaaa","runnerExecutionId":"rex_split_aaaa",
         "subtasks":[],"dependencies":[],"classification":"shareable-redacted"}
        """;
    assertFalse(validate(json).valid());
  }

  @Test
  void subtaskMissingScopeIsInvalid() {
    String json =
        """
        {"schemaVersion":1,"workflowRunId":"run_split_aaaa","runnerExecutionId":"rex_split_aaaa",
         "subtasks":[{"ordinal":1,"title":"No scope"}],"classification":"shareable-redacted"}
        """;
    assertFalse(validate(json).valid());
  }

  @Test
  void wrongSchemaVersionIsInvalid() {
    String json =
        """
        {"schemaVersion":2,"workflowRunId":"run_split_aaaa","runnerExecutionId":"rex_split_aaaa",
         "subtasks":[{"ordinal":1,"title":"t","scope":"s"}],"classification":"shareable-redacted"}
        """;
    assertFalse(validate(json).valid());
  }

  @Test
  void unknownPropertyIsRejected() {
    String json =
        """
        {"schemaVersion":1,"workflowRunId":"run_split_aaaa","runnerExecutionId":"rex_split_aaaa",
         "subtasks":[{"ordinal":1,"title":"t","scope":"s"}],"classification":"shareable-redacted",
         "approveSplit":true}
        """;
    assertFalse(validate(json).valid());
  }
}
