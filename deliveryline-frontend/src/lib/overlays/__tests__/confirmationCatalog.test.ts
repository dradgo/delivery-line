/**
 * Story 2.23 (AC4) — confirm-before catalog invariants.
 *
 * Asserts every UX-DR18 confirm-before action is documented, marked
 * `requiresConfirmation: true`, and carries a non-empty consequence template.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

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
  'takeoverWorkflow',
  // Story 4.22 — the deeper recovery_operator confirmations.
  'resumeRun',
  'pauseRun',
  'rerunFromStep',
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

  it('story 3.28 (R6) — takeoverWorkflow consequence is the VERBATIM OpenAPI takeover.post.description', () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const openapiPath = join(
      here,
      '../../../../../deliveryline-backend/src/main/resources/openapi/openapi.json',
    );
    const openapi = JSON.parse(readFileSync(openapiPath, 'utf8')) as {
      paths: Record<string, { post?: { description?: string } }>;
    };
    const description =
      openapi.paths['/api/v1/workflows/{workflowRunId}/takeover']?.post?.description;
    expect(description).toBeDefined();
    expect(CONFIRMATION_CATALOG.takeoverWorkflow.consequenceTemplate).toBe(description);
    expect(CONFIRMATION_CATALOG.takeoverWorkflow.intent).toBe('danger');
  });
});
