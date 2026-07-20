/**
 * Story 4.23 (Task 4/5/6) — pure helpers for the reconciliation dialog view.
 */
import { describe, expect, it } from 'vitest';

import {
  coerceSuggestedSafety,
  conflictCategoryLabel,
  decisionConsequence,
  decisionLabel,
  diffSnapshots,
  integrationTypeLabel,
  parseSnapshot,
  prettyJson,
  safetyChipLabel,
  safetyStateName,
  unknownSnapshotFields,
} from './reconciliationDialogView';

describe('parseSnapshot (AC3 — defensive)', () => {
  it('parses a JSON object to ok=true + fields', () => {
    const result = parseSnapshot('{"state":"merged","branch":"main"}');
    expect(result.ok).toBe(true);
    expect(result.fields).toEqual({ state: 'merged', branch: 'main' });
  });

  it('degrades (never throws) on null / empty / non-object / malformed', () => {
    expect(parseSnapshot(null).ok).toBe(false);
    expect(parseSnapshot('').ok).toBe(false);
    expect(parseSnapshot('   ').ok).toBe(false);
    expect(parseSnapshot('[1,2,3]').ok).toBe(false); // array is not a plain object
    expect(parseSnapshot('"just a string"').ok).toBe(false);
    expect(parseSnapshot('{not json').ok).toBe(false);
  });
});

describe('diffSnapshots (AC2/AC3)', () => {
  it('classifies added / removed / modified / unchanged across known fields', () => {
    const internal = parseSnapshot('{"state":"open","branch":"feat"}');
    const external = parseSnapshot('{"state":"merged","commitSha":"abc123"}');
    const rows = diffSnapshots(internal, external);
    const byLabel = Object.fromEntries(rows.map((r) => [r.label, r.status]));
    expect(byLabel['State']).toBe('modified'); // open → merged
    expect(byLabel['Branch']).toBe('removed'); // internal only
    expect(byLabel['Commit']).toBe('added'); // external only
  });

  it('treats snake/camel case as the same known field', () => {
    const internal = parseSnapshot('{"commit_sha":"abc"}');
    const external = parseSnapshot('{"commitSha":"abc"}');
    const rows = diffSnapshots(internal, external);
    const commit = rows.find((r) => r.label === 'Commit');
    expect(commit?.status).toBe('unchanged');
  });

  it('drops fields absent from both snapshots', () => {
    const rows = diffSnapshots(parseSnapshot('{}'), parseSnapshot('{}'));
    expect(rows).toEqual([]);
  });
});

describe('unknownSnapshotFields + prettyJson', () => {
  it('separates unknown fields for the Raw metadata section', () => {
    const parsed = parseSnapshot('{"state":"open","weirdField":42,"nested":{"a":1}}');
    const unknown = unknownSnapshotFields(parsed);
    expect(unknown).toEqual({ weirdField: 42, nested: { a: 1 } });
    expect(prettyJson(unknown)).toContain('weirdField');
  });
});

describe('labels', () => {
  it('maps integration + category + decision to human labels with fallbacks', () => {
    expect(integrationTypeLabel('github_pr')).toBe('GitHub');
    expect(integrationTypeLabel('linear')).toBe('Linear');
    expect(integrationTypeLabel(null)).toBe('Integration');
    expect(integrationTypeLabel('bitbucket_pr')).toBe('Bitbucket pr');
    expect(conflictCategoryLabel('external_resource_removed')).toBe('External resource removed');
    expect(conflictCategoryLabel(undefined)).toBe('Integration conflict');
    expect(decisionLabel('accept_external_state')).toBe('Accept external state');
    expect(decisionLabel('some_future_decision')).toBe('Some future decision');
  });
});

describe('safety vocabulary (AC4 — two-tier, no caution)', () => {
  it('coerces to safe/risky (unknown → risky) and maps to chip label + state token', () => {
    expect(coerceSuggestedSafety('safe')).toBe('safe');
    expect(coerceSuggestedSafety('risky')).toBe('risky');
    expect(coerceSuggestedSafety('caution')).toBe('risky'); // no caution tier on the wire
    expect(coerceSuggestedSafety(undefined)).toBe('risky');
    expect(safetyChipLabel('safe')).toBe('SAFE');
    expect(safetyChipLabel('risky')).toBe('RISKY');
    expect(safetyStateName('safe')).toBe('success');
    expect(safetyStateName('risky')).toBe('error');
  });
});

describe('decisionConsequence (Task 6)', () => {
  it('returns bespoke copy per decision and a generic fallback for unknowns', () => {
    expect(decisionConsequence('accept_internal_state')).toMatch(/re-assert/i);
    expect(decisionConsequence('mark_completed_externally')).toMatch(/completed outside/i);
    expect(decisionConsequence('unknown_decision')).toMatch(/resolves the divergence/i);
  });
});
