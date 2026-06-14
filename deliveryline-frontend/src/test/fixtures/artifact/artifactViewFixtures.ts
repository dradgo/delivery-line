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
import { PR_DIFF_MAX_FILES } from '@/lib/sanitization';

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

/**
 * A realistic small unified diff (3 files, ~100 changed lines) for the prOutput fixture
 * (story 3.27 AC9). Built from a few literal hunks plus one generated block so the
 * file/hunk/line counts are deterministic without a giant literal.
 */
function repeatLines(prefix: '+' | '-' | ' ', count: number, label: string): string {
  return Array.from({ length: count }, (_, i) => `${prefix}${label}[${i + 1}]`).join('\n');
}

const PR_OUTPUT_SMALL_DIFF = [
  'diff --git a/src/handler.ts b/src/handler.ts',
  'index 1a2b3c4..5d6e7f8 100644',
  '--- a/src/handler.ts',
  '+++ b/src/handler.ts',
  '@@ -1,7 +1,11 @@',
  ' import { process } from "./process";',
  '+import { validatePayload } from "./validate";',
  ' ',
  '-export function handle(req) {',
  '+export function handle(req: Request) {',
  '+  validatePayload(req.body);',
  '   return process(req);',
  ' }',
  ' ',
  ' export const VERSION = 1;',
  'diff --git a/src/validate.ts b/src/validate.ts',
  'new file mode 100644',
  'index 0000000..abcdef1',
  '--- /dev/null',
  '+++ b/src/validate.ts',
  '@@ -0,0 +1,40 @@',
  '+// Payload validation introduced for DEL-9002.',
  repeatLines('+', 38, 'export const rule'),
  '+export {};',
  'diff --git a/CHANGELOG.md b/CHANGELOG.md',
  'index 2222222..3333333 100644',
  '--- a/CHANGELOG.md',
  '+++ b/CHANGELOG.md',
  '@@ -1,4 +1,6 @@',
  ' # Changelog',
  ' ',
  '+## Unreleased',
  '+- Add payload validation guard (DEL-9002).',
  ' ## 1.0.0',
  ' - Initial release.',
].join('\n');

/**
 * The PR-output variant (Epic 3 scope, story 3.27) — runner-emitted branch/commit/diff +
 * a populated backend-truth `prLinkage` (`open`, `acme/widgets#42`, recently synced).
 */
export const prOutputArtifactView: PrOutputArtifactView = {
  artifactType: 'prOutput',
  artifactId: 'art_pr_fix_rej_001',
  title: 'PR Output — DEL-9002 v1',
  version: 1,
  classification: 'internal',
  createdAt: '2026-05-30T13:00:00Z',
  body: '# Pull Request\n\nAdds a payload-validation guard before persistence and backfills the regression test for the v1-rejected clause.',
  branch: 'feature/del-9002-payload-validation',
  commitSha: '3f9a1c2b4e6d8033a1b2c3d4e5f60718293a4b5c',
  diff: PR_OUTPUT_SMALL_DIFF,
  prLinkage: {
    prReference: 'acme/widgets#42',
    prState: 'open',
    prUrl: 'https://github.com/acme/widgets/pull/42',
    lastSyncedAt: '2026-05-30T13:05:00Z',
    githubReachable: true,
  },
};

/**
 * An XSS-bearing prOutput fixture (story 3.27 AC3 / AC10). Scriptable payloads ride in the
 * PR description body (commit-message analog), a file path, and the diff body + a
 * `javascript:` link. The renderer test feeds this through and asserts no active
 * `<script>`/`<iframe>` renders and `window.__xss_executed` stays unset — proving the
 * renderer routes untrusted content through the safe path (it does NOT re-test the
 * sanitizer, which is story 2.24's own suite).
 */
const PR_OUTPUT_XSS_DIFF = [
  'diff --git a/src/<script>window.__xss_executed = true;</script>.ts b/src/evil.ts',
  '--- a/src/<script>window.__xss_executed = true;</script>.ts',
  '+++ b/src/evil.ts',
  '@@ -1,3 +1,4 @@',
  ' const safe = 1;',
  '-const old = 2;',
  '+const injected = "<script>window.__xss_executed = true;</script>";',
  '+const link = "[click](javascript:alert(1))";',
  ' export {};',
].join('\n');

export const prOutputArtifactViewXss: PrOutputArtifactView = {
  ...prOutputArtifactView,
  artifactId: 'art_pr_xss_001',
  title: 'PR Output — XSS probe',
  body: '# Pull Request\n\n<script>window.__xss_executed = true;</script>\n\n<img src="x" onerror="window.__xss_executed = true" />\n\nTrailing paragraph.',
  diff: PR_OUTPUT_XSS_DIFF,
};

/**
 * A large-diff prOutput fixture (story 3.27 AC5 / AC10) — more files than
 * `PR_DIFF_MAX_FILES`, so the renderer paginates (first page only + a "Show more files"
 * control) and does NOT enumerate every file.
 */
const PR_OUTPUT_LARGE_DIFF = Array.from({ length: PR_DIFF_MAX_FILES + 5 }, (_, i) =>
  [
    `diff --git a/src/module-${i}.ts b/src/module-${i}.ts`,
    `index 000000${i}..111111${i} 100644`,
    `--- a/src/module-${i}.ts`,
    `+++ b/src/module-${i}.ts`,
    '@@ -1,2 +1,3 @@',
    ' const base = 0;',
    `+export const added${i} = ${i};`,
    ' export {};',
  ].join('\n'),
).join('\n');

export const prOutputArtifactViewLargeDiff: PrOutputArtifactView = {
  ...prOutputArtifactView,
  artifactId: 'art_pr_large_001',
  title: 'PR Output — large diff',
  diff: PR_OUTPUT_LARGE_DIFF,
};

/**
 * A stale-GitHub prOutput fixture (story 3.27 AC6 / AC10) — `prLinkage.githubReachable:
 * false` + an old `lastSyncedAt`, so the renderer shows the cached `prState` with the
 * "(last synced X ago)" affordance and emits the field-only `prOutput.githubUnreachable`
 * log.
 */
export const prOutputArtifactViewStaleGitHub: PrOutputArtifactView = {
  ...prOutputArtifactView,
  artifactId: 'art_pr_stale_001',
  title: 'PR Output — stale GitHub',
  prLinkage: {
    prReference: 'acme/widgets#42',
    prState: 'merged',
    prUrl: 'https://github.com/acme/widgets/pull/42',
    lastSyncedAt: '2026-05-29T09:00:00Z',
    githubReachable: false,
  },
};
