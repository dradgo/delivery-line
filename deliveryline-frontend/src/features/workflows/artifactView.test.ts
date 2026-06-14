/**
 * Story 2.17 (AC8, AC12) — `deriveSectionAnchors` unit coverage.
 *
 * The anchor list is derived from the RAW markdown source (T-ANCHOR — the
 * sanitizer strips heading ids), so these tests pin: ATX heading parsing across
 * levels, slugification, duplicate-id de-duplication, and code-fence exclusion.
 */
import { describe, expect, it } from 'vitest';

import {
  canEnableCompare,
  deriveSectionAnchors,
  hasComparableRevision,
  isArtifactView,
  resolveArtifactPanelState,
} from './artifactView';
import {
  implementationPlanArtifactView,
  specArtifactView,
} from '@/test/fixtures/artifact/artifactViewFixtures';

describe('deriveSectionAnchors', () => {
  it('parses ATX headings of every level and slugifies the text', () => {
    const anchors = deriveSectionAnchors('# Title One\n\n## Sub Section\n\n### Deep Heading');
    expect(anchors).toEqual([
      { id: 'title-one', text: 'Title One', level: 1 },
      { id: 'sub-section', text: 'Sub Section', level: 2 },
      { id: 'deep-heading', text: 'Deep Heading', level: 3 },
    ]);
  });

  it('de-duplicates colliding slugs with numeric suffixes', () => {
    const anchors = deriveSectionAnchors('# Notes\n\n## Notes\n\n## Notes');
    expect(anchors.map((a) => a.id)).toEqual(['notes', 'notes-1', 'notes-2']);
    // Text stays identical; only the id disambiguates.
    expect(anchors.every((a) => a.text === 'Notes')).toBe(true);
  });

  it('ignores headings inside fenced code blocks (``` and ~~~)', () => {
    const source = [
      '# Real Heading',
      '',
      '```',
      '# Not A Heading',
      '## Also Not',
      '```',
      '',
      '~~~',
      '### Tilde Fenced',
      '~~~',
      '',
      '## Second Real Heading',
    ].join('\n');
    const anchors = deriveSectionAnchors(source);
    expect(anchors.map((a) => a.text)).toEqual(['Real Heading', 'Second Real Heading']);
  });

  it('does not close a longer fence with a shorter same-marker fence', () => {
    const source = [
      '# Real Heading',
      '',
      '````',
      '```',
      '# Still Code',
      '```',
      '````',
      '',
      '## After Fence',
    ].join('\n');
    const anchors = deriveSectionAnchors(source);
    expect(anchors.map((a) => a.text)).toEqual(['Real Heading', 'After Fence']);
  });

  it('strips trailing closing-hash sequences and surrounding whitespace', () => {
    const anchors = deriveSectionAnchors('##   Padded Heading   ###');
    expect(anchors).toEqual([{ id: 'padded-heading', text: 'Padded Heading', level: 2 }]);
  });

  it('does not treat non-heading hash usages as headings', () => {
    // No space after the hashes, or more than 6 — not an ATX heading.
    const anchors = deriveSectionAnchors('#nospace\n\n####### too deep\n\nplain text');
    expect(anchors).toEqual([]);
  });

  it('falls back to a stable id when a heading has no slug-able characters', () => {
    const anchors = deriveSectionAnchors('# ***');
    expect(anchors).toEqual([{ id: 'section', text: '***', level: 1 }]);
  });

  it('derives the expected anchors from the spec fixture, excluding its code fence', () => {
    const anchors = deriveSectionAnchors(specArtifactView.body);
    expect(anchors.map((a) => a.text)).toEqual([
      'Overview',
      'Goals',
      'Non-Goals',
      'Implementation Notes',
    ]);
  });
});

describe('isArtifactView', () => {
  it('accepts the frontend-owned artifact variants', () => {
    expect(isArtifactView(specArtifactView)).toBe(true);
    expect(isArtifactView(implementationPlanArtifactView)).toBe(true);
  });

  it('rejects partial summary-like data before renderers consume it', () => {
    expect(isArtifactView({ artifactType: 'spec', version: 2, status: 'available' })).toBe(false);
    expect(isArtifactView({ ...specArtifactView, body: undefined })).toBe(false);
    expect(isArtifactView({ ...specArtifactView, changeSummary: { before: 'old' } })).toBe(false);
  });

  it('story 3.26 (R3) — validates impl-plan steps + refs WHEN PRESENT; tolerates their absence', () => {
    // R3 — steps/contextReferences are OPTIONAL (the live story-3a-9 wire DTO omits them),
    // so absence + empty arrays are valid; the body-only live mapping stays renderable.
    expect(isArtifactView({ ...implementationPlanArtifactView, steps: undefined })).toBe(true);
    expect(isArtifactView({ ...implementationPlanArtifactView, steps: [] })).toBe(true);
    expect(
      isArtifactView({ ...implementationPlanArtifactView, contextReferences: undefined }),
    ).toBe(true);
    expect(isArtifactView({ ...implementationPlanArtifactView, contextReferences: [] })).toBe(true);

    // But a malformed step / ref shape is still rejected before it reaches the renderer.
    expect(isArtifactView({ ...implementationPlanArtifactView, steps: ['plain string'] })).toBe(
      false,
    );
    expect(isArtifactView({ ...implementationPlanArtifactView, steps: [{ detail: 'x' }] })).toBe(
      false,
    );
    expect(
      isArtifactView({
        ...implementationPlanArtifactView,
        contextReferences: [{ label: 'x', internal: true, kind: 'bogus' }],
      }),
    ).toBe(false);
    expect(
      isArtifactView({
        ...implementationPlanArtifactView,
        contextReferences: [{ label: 'x', kind: 'spec' }],
      }),
    ).toBe(false);

    // The enriched fixture (typed steps + refs) passes.
    expect(isArtifactView(implementationPlanArtifactView)).toBe(true);
  });
});

describe('resolveArtifactPanelState', () => {
  const base = { isError: false, isLoading: false, artifact: undefined };

  it('precedence: error wins over everything', () => {
    expect(
      resolveArtifactPanelState({
        ...base,
        isError: true,
        isLoading: true,
        artifact: specArtifactView,
      }),
    ).toBe('error');
  });

  it('loading wins over empty/content when fetching', () => {
    expect(resolveArtifactPanelState({ ...base, isLoading: true })).toBe('loading');
  });

  it('no artifact → empty (the live disabled-stub path)', () => {
    expect(resolveArtifactPanelState(base)).toBe('empty');
  });

  it('spec superseded → superseded (strongest content treatment)', () => {
    expect(
      resolveArtifactPanelState({
        ...base,
        artifact: { ...specArtifactView, superseded: true, stale: true },
      }),
    ).toBe('superseded');
  });

  it('spec stale (not superseded) → stale', () => {
    expect(
      resolveArtifactPanelState({ ...base, artifact: { ...specArtifactView, stale: true } }),
    ).toBe('stale');
  });

  it('spec truncated (not stale/superseded) → incomplete', () => {
    expect(
      resolveArtifactPanelState({ ...base, artifact: { ...specArtifactView, truncated: true } }),
    ).toBe('incomplete');
  });

  it('clean spec → default', () => {
    expect(resolveArtifactPanelState({ ...base, artifact: specArtifactView })).toBe('default');
  });

  it('clean implementation-plan (no dormant flags) → default', () => {
    expect(resolveArtifactPanelState({ ...base, artifact: implementationPlanArtifactView })).toBe(
      'default',
    );
  });

  it('story 3.26 (D3) — implementation-plan dormant flags resolve to banner states', () => {
    expect(
      resolveArtifactPanelState({
        ...base,
        artifact: { ...implementationPlanArtifactView, superseded: true, stale: true },
      }),
    ).toBe('superseded');
    expect(
      resolveArtifactPanelState({
        ...base,
        artifact: { ...implementationPlanArtifactView, stale: true },
      }),
    ).toBe('stale');
    expect(
      resolveArtifactPanelState({
        ...base,
        artifact: { ...implementationPlanArtifactView, truncated: true },
      }),
    ).toBe('incomplete');
  });
});

describe('canEnableCompare / hasComparableRevision', () => {
  it('enabled only when the compare action is present AND a comparable revision exists', () => {
    expect(canEnableCompare(['compare'], true)).toBe(true);
    expect(canEnableCompare(['compare'], false)).toBe(false);
    expect(canEnableCompare(['approveSpec'], true)).toBe(false);
    expect(canEnableCompare([], true)).toBe(false);
  });

  it('undefined actions (disabled stub) → false (the safe default)', () => {
    expect(canEnableCompare(undefined, true)).toBe(false);
  });

  it('hasComparableRevision is true only past v1', () => {
    expect(hasComparableRevision(specArtifactView)).toBe(true); // v2
    expect(hasComparableRevision({ ...specArtifactView, version: 1 })).toBe(false);
    expect(hasComparableRevision(undefined)).toBe(false);
  });
});
