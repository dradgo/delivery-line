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

/**
 * The implementation-plan variant (Epic 3 scope), aligned to `art_plan_fix_rej_001`
 * (`DEL-9002` v1 — the `happy-path-success.json` / `spec-rejection-and-resubmit.json`
 * terminal state). Enriched with realistic structured steps (≥3, at least one carrying
 * `detail` + `estimatedComplexity`) and typed context references (an internal spec ref
 * + an external repo ref + a branch ref per story 3.9). The schema-v1 sub-schema carries
 * `steps`/`contextReferences` as bare `string[]`; this read model enriches them (R2).
 */
export const implementationPlanArtifactView: ImplementationPlanArtifactView = {
  artifactType: 'implementationPlan',
  artifactId: 'art_plan_fix_rej_001',
  title: 'Implementation Plan — DEL-9002 v1',
  version: 1,
  classification: 'internal',
  createdAt: '2026-05-30T12:30:00Z',
  body: '# Implementation Plan\n\nStep-by-step plan to implement the corrected delivery flow.',
  steps: [
    {
      summary: 'Add the payload validation guard to the request handler.',
      detail:
        'Introduce a `validatePayload` step **before** persistence.\n\n- Reject malformed input with a 400.\n- Keep the public API stable.',
      estimatedComplexity: 'M',
    },
    {
      summary: 'Backfill the regression test for the v1-rejected clause.',
      detail: 'Cover the reviewer-flagged ambiguity with an explicit assertion.',
    },
    {
      summary: 'Update the changelog and open the pull request.',
    },
  ],
  contextReferences: [
    { kind: 'spec', label: 'Approved specification — DEL-9002 v2', internal: true },
    {
      kind: 'repository',
      label: 'dradgo/deliveryline',
      href: 'https://github.com/dradgo/deliveryline',
      internal: false,
    },
    {
      kind: 'branch',
      label: 'feature/del-9002-payload-validation',
      href: 'https://github.com/dradgo/deliveryline/tree/feature/del-9002-payload-validation',
      internal: false,
    },
  ],
};

/**
 * An XSS-bearing implementation-plan fixture (AC3 / AC9 — mirrors `specArtifactViewXss`).
 * Carries scriptable payloads in step `summary`/`detail` (a `<script>` + an `<img onerror>`)
 * and a `javascript:` context-reference href. The renderer test feeds this through and
 * asserts no active `<script>`/`<iframe>` renders and `window.__xss_executed` stays unset —
 * proving the renderer routes untrusted content through the safe path (it does NOT re-test
 * the sanitizer, which is story 2.24's own suite).
 */
export const implementationPlanArtifactViewXss: ImplementationPlanArtifactView = {
  ...implementationPlanArtifactView,
  artifactId: 'art_plan_xss_001',
  title: 'Implementation Plan — XSS probe',
  body: '# Plan\n\n<script>window.__xss_executed = true;</script>\n\nTrailing paragraph.',
  steps: [
    {
      summary: '<script>window.__xss_executed = true;</script> Step with a scriptable summary.',
      detail:
        '<img src="x" onerror="window.__xss_executed = true" />\n\n[click me](javascript:alert(1))',
      estimatedComplexity: 'L',
    },
    {
      summary: 'A second step so the steps list still has structure.',
    },
  ],
  contextReferences: [
    { kind: 'spec', label: '<script>window.__xss_executed = true;</script> spec', internal: true },
    {
      kind: 'repository',
      label: 'malicious link',
      href: 'javascript:alert(1)',
      internal: false,
    },
  ],
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
