/**
 * Story 2.23 (AC4) — confirm-before catalog invariants.
 *
 * Asserts every UX-DR18 confirm-before action is documented, marked
 * `requiresConfirmation: true`, and carries a non-empty consequence template.
 */
import { describe, expect, it } from 'vitest';

import {
  CONFIRMATION_ACTION_IDS,
  CONFIRMATION_CATALOG,
  type ConfirmationActionId,
} from '../confirmationCatalog';

const EXPECTED_ACTIONS: readonly ConfirmationActionId[] = [
  'rejectWithReason',
  'approveWhenStaleOrConflict',
  'stopOrchestrator',
  'retryOrRecoverConsequential',
];

describe('confirmationCatalog', () => {
  it('AC4 — enumerates every UX-DR18 confirm-before action', () => {
    for (const id of EXPECTED_ACTIONS) {
      expect(CONFIRMATION_CATALOG[id]).toBeDefined();
    }
    // The exported id list matches the record keys exactly (no drift).
    expect([...CONFIRMATION_ACTION_IDS].sort()).toEqual([...EXPECTED_ACTIONS].sort());
    expect(Object.keys(CONFIRMATION_CATALOG).sort()).toEqual([...EXPECTED_ACTIONS].sort());
  });

  it.each(EXPECTED_ACTIONS)(
    'AC4 — "%s" requires confirmation with a non-empty consequence',
    (id) => {
      const entry = CONFIRMATION_CATALOG[id];
      expect(entry.id).toBe(id);
      expect(entry.requiresConfirmation).toBe(true);
      expect(entry.consequenceTemplate.trim().length).toBeGreaterThan(0);
      expect(entry.owningStory.trim().length).toBeGreaterThan(0);
      expect(['danger', 'warning', 'info']).toContain(entry.intent);
    },
  );
});
