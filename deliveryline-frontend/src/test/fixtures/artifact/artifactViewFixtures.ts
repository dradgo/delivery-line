/**
 * Story 2.17 (AC12) — frontend-owned `ArtifactView` fixtures.
 *
 * These mirror the TERMINAL states of the story-1.23 fixture event streams that
 * carry all three artifact variants (`happy-path-success.json` /
 * `execution-failure-with-retry.json` → spec + implementationPlan + prOutput;
 * `spec-rejection-and-resubmit.json` → spec advanced to v2). The backend fixtures
 * are NOT served to the SPA (OQ-3), so these are frontend-owned copies of the
 * intended read model. There is NO live artifact-read endpoint — the panel is
 * presentational and tests drive it from these fixtures (T4 — never fabricated
 * from live `WorkflowDetail.latestArtifacts`, which has no body and no id).
 */
import type {
  ImplementationPlanArtifactView,
  PrOutputArtifactView,
  SpecArtifactView,
} from '@/features/workflows/artifactView';

/**
 * The spec variant, aligned to `run_fix_rej_001` (`DEL-9002`): the spec advanced to
 * v2 after a v1 rejection-and-resubmit. Multi-heading body so the section-anchor
 * derivation (AC8) has real structure to walk.
 */
export const specArtifactView: SpecArtifactView = {
  artifactType: 'spec',
  artifactId: 'art_spec_fix_rej_001',
  title: 'Specification — DEL-9002 v2',
  version: 2,
  classification: 'internal',
  createdAt: '2026-05-30T12:00:00Z',
  checksum: 'sha256:9f1c2a7b4e6d8033',
  body: [
    '# Overview',
    '',
    'This specification describes the corrected delivery flow after the v1 rejection.',
    '',
    '## Goals',
    '',
    '- Resolve the reviewer-flagged ambiguity in the acceptance criteria.',
    '- Keep the public API stable.',
    '',
    '## Non-Goals',
    '',
    'Performance tuning is explicitly out of scope for this revision.',
    '',
    '### Implementation Notes',
    '',
    'See the linked ticket for the original discussion.',
    '',
    '```',
    '# This heading lives inside a code fence and MUST NOT become an anchor',
    '```',
  ].join('\n'),
};

/** A spec variant carrying a non-null `changeSummary` — drives the `SafeDiffRenderer` slot (AC2). */
export const specArtifactViewWithChangeSummary: SpecArtifactView = {
  ...specArtifactView,
  artifactId: 'art_spec_fix_rej_001_diff',
  changeSummary: {
    before: 'The system SHOULD validate the payload.\nLegacy clause retained.',
    after: 'The system MUST validate the payload.\nLegacy clause removed.',
  },
};

/**
 * An XSS-bearing spec body (AC7) — a `<script>`, a `javascript:` link, and an
 * `<img onerror>`. The panel test feeds this through and asserts no active
 * `<script>`/`<iframe>` renders (proving the panel routes untrusted content through
 * the safe path — it does NOT re-test the sanitizer, which is story 2.24's own suite).
 */
export const specArtifactViewXss: SpecArtifactView = {
  ...specArtifactView,
  artifactId: 'art_spec_xss_001',
  title: 'Specification — XSS probe',
  body: [
    '# Heading',
    '',
    '<script>window.__xss_executed = true;</script>',
    '',
    '[click me](javascript:alert(1))',
    '',
    '<img src="x" onerror="window.__xss_executed = true" />',
    '',
    'Trailing paragraph.',
  ].join('\n'),
};

/** The implementation-plan variant (Epic 3 scope) — stub-rendered in E2. */
export const implementationPlanArtifactView: ImplementationPlanArtifactView = {
  artifactType: 'implementationPlan',
  artifactId: 'art_plan_fix_rej_001',
  title: 'Implementation Plan — DEL-9002 v1',
  version: 1,
  classification: 'internal',
  createdAt: '2026-05-30T12:30:00Z',
  body: '# Implementation Plan\n\nStep-by-step plan rendered fully in Epic 3.',
};

/** The PR-output variant (Epic 3 scope) — stub-rendered in E2. */
export const prOutputArtifactView: PrOutputArtifactView = {
  artifactType: 'prOutput',
  artifactId: 'art_pr_fix_rej_001',
  title: 'PR Output — DEL-9002 v1',
  version: 1,
  classification: 'internal',
  createdAt: '2026-05-30T13:00:00Z',
  body: '# Pull Request\n\nGenerated PR summary rendered fully in Epic 3.',
};
