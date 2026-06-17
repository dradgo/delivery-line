/**
 * Story 2.19 (Task 5, AC11 / OQ-5) — the eligibility-import guard.
 *
 * UX-DR12 hard rule + party-mode #3: the bar must read backend-reported allowed actions
 * and NEVER re-derive approval eligibility frontend-side. There is no permission-
 * inference module to forbid importing (eligibility lives entirely backend-side), so a
 * full custom ESLint rule is low-value today (OQ-5) — this focused import-graph test
 * pins the convention instead: the container sources eligibility ONLY from
 * `useAllowedActions`, and neither the bar nor the container imports any inference helper
 * or re-derives a permission flag. A generalized ArchUnit-equivalent rule is deferred to
 * the 2.31 rule-set story.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const BAR = readFileSync(join(HERE, 'ApprovalDecisionBar.tsx'), 'utf8');
const CONTAINER = readFileSync(join(HERE, 'ApprovalDecisionBarContainer.tsx'), 'utf8');
// Story 3.28 — the impl-review container wires its own eligibility; the same UX-DR12 rule
// applies (3.28 review P4). Source eligibility ONLY from `useAllowedActions`, no inference.
const IMPL_CONTAINER = readFileSync(
  join(HERE, 'ImplementationReviewDecisionBarContainer.tsx'),
  'utf8',
);

/** Patterns that would indicate frontend-side eligibility inference (UX-DR12 violation). */
const FORBIDDEN_PATTERNS: ReadonlyArray<RegExp> = [
  /viewerAuthorized/,
  /permissionInference/i,
  /computeEligibility/i,
  /canApprove\s*=/,
  /isAuthorized/i,
];

describe('AC11 — the bar consumes only backend-reported allowed actions', () => {
  it('the container sources eligibility from useAllowedActions', () => {
    expect(CONTAINER).toContain("from '../hooks/useAllowedActions'");
    expect(CONTAINER).toContain('useAllowedActions(workflowRunId)');
  });

  it('the impl-review container sources eligibility from useAllowedActions (3.28 / 3b-4)', () => {
    expect(IMPL_CONTAINER).toContain("from '../hooks/useAllowedActions'");
    // Story 3b-4: the call gained the developer role arg — the substring updates with it.
    expect(IMPL_CONTAINER).toContain('useAllowedActions(workflowRunId, DEVELOPER_REVIEWER_ROLE)');
  });

  it('neither the bar nor the containers import a permission-inference helper', () => {
    for (const pattern of FORBIDDEN_PATTERNS) {
      expect(BAR).not.toMatch(pattern);
      expect(CONTAINER).not.toMatch(pattern);
      expect(IMPL_CONTAINER).not.toMatch(pattern);
    }
  });

  it('the presentational bar imports/calls no data hooks (no eligibility re-derivation)', () => {
    // Precise: it must not IMPORT or CALL the data hooks (prose mentions in the file
    // header are fine — the bar is documented in terms of the live seams it consumes).
    expect(BAR).not.toMatch(/import\s[^\n]*useWorkflowDetail/);
    expect(BAR).not.toMatch(/import\s[^\n]*useAllowedActions/);
    expect(BAR).not.toMatch(/useWorkflowDetail\s*\(/);
    expect(BAR).not.toMatch(/useAllowedActions\s*\(/);
  });
});
