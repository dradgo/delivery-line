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
  }

  @Test
  void linearDefaultsIncludeTicketCreationSupport() {
    TicketSourceCapabilities capabilities = TicketSourceCapabilities.linearDefaults();

    assertTrue(capabilities.supportsTicketCreation());
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
