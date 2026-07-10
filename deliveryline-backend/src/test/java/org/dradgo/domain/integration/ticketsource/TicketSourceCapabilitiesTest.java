package org.dradgo.domain.integration.ticketsource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TicketSourceCapabilitiesTest {

  @Test
  void noCreationDefaultKeepsTicketCreationUnsupported() {
    TicketSourceCapabilities capabilities = TicketSourceCapabilities.noCreation(true, true, true);

    assertTrue(capabilities.supportsCommentOnTicket());
    assertTrue(capabilities.supportsPolling());
    assertTrue(capabilities.supportsTicketStateUpdates());
    assertFalse(capabilities.supportsTicketCreation());
    // Story 3g-1 — a no-creation connector also cannot build a source-ticket URL.
    assertFalse(capabilities.supportsSourceTicketUrl());
    // Story 3i-2 — nor can it be browsed with a filter.
    assertFalse(capabilities.supportsTicketQuery());
  }

  @Test
  void linearDefaultsIncludeTicketCreationSupport() {
    TicketSourceCapabilities capabilities = TicketSourceCapabilities.linearDefaults();

    assertTrue(capabilities.supportsTicketCreation());
    // Story 3g-1 — Linear can build a source-ticket URL.
    assertTrue(capabilities.supportsSourceTicketUrl());
    // Story 3i-2 — Linear does NOT implement the filtered candidate browse.
    assertFalse(capabilities.supportsTicketQuery());
  }

  @Test
  void jiraDefaultsAdvertiseTheFullCapabilitySet() {
    // Story 3i-1 AC2 — JIRA supports comment-posting, polling, source-status ids, sub-task
    // creation, and the /browse/ source-ticket URL.
    TicketSourceCapabilities capabilities = TicketSourceCapabilities.jiraDefaults();

    assertTrue(capabilities.supportsCommentOnTicket());
    assertTrue(capabilities.supportsPolling());
    assertTrue(capabilities.supportsTicketStateUpdates());
    assertTrue(capabilities.supportsTicketCreation());
    assertTrue(capabilities.supportsSourceTicketUrl());
    // Story 3i-2 AC1 — and it is the only connector that advertises the JQL-backed browse.
    assertTrue(capabilities.supportsTicketQuery());
  }

  /**
   * Story 3i-2 — there is no reflective capability-drift contract test in this repo, so these
   * explicit per-factory assertions ARE the pin. A new flag that is not asserted here is unguarded.
   */
  @Test
  void jiraIsTheOnlyFactoryAdvertisingTheFilteredBrowse() {
    assertTrue(TicketSourceCapabilities.jiraDefaults().supportsTicketQuery());
    assertFalse(TicketSourceCapabilities.linearDefaults().supportsTicketQuery());
    assertFalse(TicketSourceCapabilities.noCreation(true, true, true).supportsTicketQuery());
  }

  @Test
  void subticketRecordsAreVendorNeutralAndCarryReplayKey() {
    SubticketDraft draft =
        new SubticketDraft(
            "run_parent01",
            "proposal_01",
            "subtask_01",
            2,
            "Implement cache invalidation",
            "Redacted scope body",
            "split:run_parent01:proposal_01:2");
    CreateSubticketResult result =
        new CreateSubticketResult(
            TicketRef.of("LIN-102-2"),
            "split:run_parent01:proposal_01:2",
            "comment-fp-1",
            false,
            java.util.Map.of("source", "linear", "ordinal", "2"));

    assertEquals("split:run_parent01:proposal_01:2", draft.idempotencyKey());
    assertEquals(2, draft.ordinal());
    assertEquals(TicketRef.of("LIN-102-2"), result.childRef());
    assertEquals("comment-fp-1", result.parentLinkFingerprint());
    assertFalse(result.replay());
    assertEquals("linear", result.metadata().get("source"));
  }
}
