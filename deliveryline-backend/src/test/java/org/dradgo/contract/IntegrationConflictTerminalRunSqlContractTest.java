package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 4.30 review (P2) — pins the terminal-state literals hardcoded in {@code
 * IntegrationConflictPersistenceAdapter.FIND_UNRESOLVED_ON_TERMINAL_RUNS_SQL} against {@link
 * WorkflowState#isTerminal()}, the single source of truth introduced by this story.
 *
 * <p>The detector terminal-run guard, the reconcile guard, and the allowed-actions overlay all
 * route terminality through {@code WorkflowState.isTerminal()}, but a raw SQL {@code IN (...)}
 * clause cannot reference a Java constant — so the sweep read query re-embeds the wire values
 * {@code ('Completed', 'TakenOver', 'Reconciled')} as string literals. Without this guard, adding a
 * fourth terminal state to {@code WorkflowState} would update every Java consumer automatically yet
 * silently leave the sweep query blind to the new state (stranding its conflicts forever) — the
 * exact divergence the {@code isTerminal()} centralization set out to eliminate. This test scans
 * the adapter source and asserts the literal {@code IN}-set is byte-for-byte the set of terminal
 * wire values, so any drift in either direction forces an intentional SQL edit (mirrors {@link
 * WorkflowEventDetailKeysContractTest#repositoryNativeQueryReferencesCorrelationIdLiteralMatchingTheHolderConstant}).
 */
class IntegrationConflictTerminalRunSqlContractTest {

  private static final Path ADAPTER_PATH =
      Path.of(
          "src/main/java/org/dradgo/adapters/persistence/IntegrationConflictPersistenceAdapter.java");

  // Captures the parenthesized list from `... current_state in ( '<a>', '<b>', ... )`.
  private static final Pattern IN_CLAUSE =
      Pattern.compile("current_state\\s+in\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']*)'");

  @Test
  void terminalRunSweepSqlInClauseMatchesWorkflowStateIsTerminalWireValues() throws IOException {
    String adapterSource = Files.readString(ADAPTER_PATH, StandardCharsets.UTF_8);

    Matcher inClause = IN_CLAUSE.matcher(adapterSource);
    assertTrue(
        inClause.find(),
        () ->
            "IntegrationConflictPersistenceAdapter must contain a `current_state in (...)` clause "
                + "(the terminal-run sweep query). If the query was renamed/removed, update this "
                + "contract test to point at its new terminality filter.");

    Set<String> sqlStates = new LinkedHashSet<>();
    Matcher literals = QUOTED_LITERAL.matcher(inClause.group(1));
    while (literals.find()) {
      sqlStates.add(literals.group(1));
    }
    assertFalse(
        inClause.find(),
        "More than one `current_state in (...)` clause found — this test assumes a single "
            + "terminal-run filter. Narrow the scan to the terminal-run sweep query.");

    Set<String> terminalWireValues =
        Arrays.stream(WorkflowState.values())
            .filter(WorkflowState::isTerminal)
            .map(WorkflowState::value)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    org.junit.jupiter.api.Assertions.assertEquals(
        terminalWireValues,
        sqlStates,
        "The terminal-run sweep SQL `current_state in (...)` literals MUST equal the wire values of "
            + "the WorkflowState members where isTerminal() is true. A mismatch means a terminal "
            + "state was added/removed/renamed on the enum without updating "
            + "FIND_UNRESOLVED_ON_TERMINAL_RUNS_SQL — conflicts on the diverged state would be "
            + "stranded (or wrongly swept). Update the SQL literals to match WorkflowState.");
  }
}
