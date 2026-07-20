/**
 * Story 4.26 (AC8, Task 3) — Epic-4 build-blocking sanitization fixture sweep for the two Epic-4
 * content types whose render path does NOT route through the `SafeMarkdownRenderer` /
 * `SafeUnifiedDiffRenderer` loops (those cover markdown + diff, and the Compare-Mode spec-section /
 * prOutput-diff fixtures join THOSE loops — see `src/lib/sanitization/__tests__`):
 *
 *   1. Reconciliation-snapshot JSONB (4.18/4.23) — `ReconciliationDialog` renders each internal /
 *      external snapshot field (and the "Raw metadata" `<pre>`) as React-escaped text nodes.
 *   2. Classification descriptions + examples (4.24) — `FailureClassificationDialog` renders each
 *      taxonomy card's `description` + `examples[]` as React-escaped text nodes.
 *
 * Each fixture drives the REAL component (via MSW-fed reads) with an adversarial payload and asserts,
 * against the LOAD-BEARING contract keys only, that the payload renders inert (no `<script>` /
 * `<a>` / `on*` handler) while its literal text is preserved. A single payload that survives as live
 * markup reds the build (AC8: one failing fixture is build-blocking). This is the dedicated
 * `.expected.json`-contract loop the story calls for, mirroring `SafeMarkdownRenderer.test.tsx`'s
 * floor pattern; it renders the components rather than a primitive because these paths rely on React
 * auto-escaping — the assertion must survive a future switch to `dangerouslySetInnerHTML`.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';

import { ReconciliationDialog } from '../ReconciliationDialog';
import { FailureClassificationDialog } from '../FailureClassificationDialog';

interface SanitizationContract {
  renderedTextContains?: string[];
  renderedTextDoesNotContain?: string[];
  noScriptElements?: boolean;
  noIframeElements?: boolean;
  noActiveElements?: boolean;
  noAnchorElements?: boolean;
}

/**
 * Assert an adversarial payload rendered inert.
 *
 * The structural inertness checks (no `<script>` / `<a>` / `on*` handler) are MANDATORY, not opt-in:
 * every fixture MUST declare `noScriptElements` / `noAnchorElements` / `noActiveElements` as `true`.
 * If those checks were gated on the flags being present, a new fixture whose `.expected.json` listed
 * only `renderedTextContains: ["window.__x=1"]` (the inner JS, no tag keys) would PASS even if the
 * payload rendered as a live `<script>` — a live script's `textContent` is exactly that inner JS.
 * Requiring the keys forces every fixture to opt IN to the full inertness contract.
 *
 * `root` is the UNTRUSTED container (the snapshot section / the taxonomy card), NOT the whole
 * document — so a future benign anchor in the dialog CHROME (a "learn more" link) can never trip the
 * "text became an <a>" check with a misleading message.
 */
const REQUIRED_INERT_KEYS = [
  'noScriptElements',
  'noAnchorElements',
  'noActiveElements',
] as const satisfies ReadonlyArray<keyof SanitizationContract>;

function assertInert(root: HTMLElement, name: string, expected: SanitizationContract) {
  const text = root.textContent ?? '';
  for (const needle of expected.renderedTextContains ?? []) {
    expect(text, `${name}: rendered text should contain ${JSON.stringify(needle)}`).toContain(
      needle,
    );
  }
  for (const banned of expected.renderedTextDoesNotContain ?? []) {
    expect(text, `${name}: rendered text must NOT contain ${JSON.stringify(banned)}`).not.toContain(
      banned,
    );
  }
  // Mandatory: a fixture may not weaken the inertness contract by omitting a key.
  for (const key of REQUIRED_INERT_KEYS) {
    expect(
      expected[key],
      `${name}: .expected.json must set "${key}": true — structural inertness is mandatory, not opt-in`,
    ).toBe(true);
  }
  expect(root.querySelectorAll('script'), `${name}: must not emit <script>`).toHaveLength(0);
  expect(
    root.querySelectorAll('a'),
    `${name}: untrusted snapshot/description text must never become an <a>`,
  ).toHaveLength(0);
  for (const el of root.querySelectorAll('*')) {
    for (const attr of el.attributes) {
      expect(
        attr.name.startsWith('on'),
        `${name}: <${el.tagName}> retained event handler "${attr.name}"`,
      ).toBe(false);
    }
  }
  // `<iframe>` stays optional (no current fixture exercises it, but the guard is cheap).
  if (expected.noIframeElements === true) {
    expect(root.querySelectorAll('iframe'), `${name}: must not emit <iframe>`).toHaveLength(0);
  }
}

function client() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// 1. Reconciliation-snapshot JSONB (4.18/4.23)
// ---------------------------------------------------------------------------

interface ReconciliationFixture {
  internal: Record<string, unknown>;
  external: Record<string, unknown>;
}

const reconciliationFixtures = import.meta.glob<ReconciliationFixture>(
  './reconciliation-snapshot-fixtures/*.json',
  { import: 'default', eager: true },
);
const reconciliationExpected = import.meta.glob<SanitizationContract>(
  './reconciliation-snapshot-fixtures/*.expected.json',
  { import: 'default', eager: true },
);

const RECON_RUN_ID = 'run_recon_sanitize_001';
const RECON_CONFLICT_ID = 'icf_sanitize_001';
const RECON_CONFLICT_URL = 'http://localhost/api/v1/integration-conflicts/:conflictId';

describe('Epic-4 sanitization — reconciliation-snapshot JSONB fixture loop (story 4.26 AC8)', () => {
  const names = Object.keys(reconciliationFixtures)
    .filter((path) => !path.endsWith('.expected.json'))
    .map((path) => path.replace(/^.*\//, '').replace(/\.json$/, ''))
    .sort();

  // AC8 floor — the reconciliation-JSONB sanitization sweep must keep at least three attack-class
  // fixtures (scriptable known + raw field, javascript:/entity-encoded, polyglot in status + nested).
  expect(
    names.length,
    'Reconciliation-snapshot JSONB fixture set must not shrink — at least three attack-class fixtures required (story 4.26 AC8)',
  ).toBeGreaterThanOrEqual(3);

  for (const name of names) {
    it(`renders reconciliation snapshot fixture "${name}" inert`, async () => {
      const fixture = reconciliationFixtures[`./reconciliation-snapshot-fixtures/${name}.json`];
      const expected =
        reconciliationExpected[`./reconciliation-snapshot-fixtures/${name}.expected.json`];
      expect(fixture, `fixture ${name} missing`).toBeDefined();
      expect(expected, `${name}.expected.json missing`).toBeDefined();

      server.use(
        http.get(RECON_CONFLICT_URL, () =>
          HttpResponse.json({
            conflictId: RECON_CONFLICT_ID,
            workflowRunId: RECON_RUN_ID,
            conflictCategory: 'external_state_advanced',
            integrationType: 'github_pr',
            externalRef: 'octo/repo#7',
            internalStateSnapshot: JSON.stringify(fixture!.internal),
            externalStateSnapshot: JSON.stringify(fixture!.external),
            suggestedDecisions: [{ decision: 'accept_external_state', safety: 'safe' }],
          }),
        ),
      );

      render(
        <QueryClientProvider client={client()}>
          <ReconciliationDialog
            workflowRunId={RECON_RUN_ID}
            conflictId={RECON_CONFLICT_ID}
            open
            onClose={vi.fn()}
          />
        </QueryClientProvider>,
      );

      // Scope to the snapshot section (both panels + each panel's "Raw metadata" <pre>) — the whole
      // untrusted surface, and nothing of the trusted dialog chrome (Finding 4, code-review 2026-07-17).
      const snapshots = await screen.findByTestId('reconciliation-snapshots');
      assertInert(snapshots, name, expected!);
    });
  }
});

// ---------------------------------------------------------------------------
// 2. Classification descriptions + examples (4.24)
// ---------------------------------------------------------------------------

interface ClassificationFixture {
  value: string;
  humanReadableName: string;
  description: string;
  examples: string[];
  deprecated: boolean;
}

const classificationFixtures = import.meta.glob<ClassificationFixture>(
  './classification-description-fixtures/*.json',
  { import: 'default', eager: true },
);
const classificationExpected = import.meta.glob<SanitizationContract>(
  './classification-description-fixtures/*.expected.json',
  { import: 'default', eager: true },
);

const CLS_RUN_ID = 'run_classify_sanitize_001';
const TAXONOMY_URL = 'http://localhost/api/v1/registries/failure-taxonomy';
const CLASSIFICATION_URL = `http://localhost/api/v1/workflows/${CLS_RUN_ID}/failure-classification`;
const DETAIL_URL = `http://localhost/api/v1/workflows/${CLS_RUN_ID}`;
const DIAGNOSTICS_URL = `http://localhost/api/v1/workflows/${CLS_RUN_ID}/failure-diagnostics`;

describe('Epic-4 sanitization — classification description/examples fixture loop (story 4.26 AC8)', () => {
  const names = Object.keys(classificationFixtures)
    .filter((path) => !path.endsWith('.expected.json'))
    .map((path) => path.replace(/^.*\//, '').replace(/\.json$/, ''))
    .sort();

  // AC8 floor — the classification-description sanitization sweep must keep at least two attack-class
  // fixtures (payload in `description`, payload in `examples[]`).
  expect(
    names.length,
    'Classification-description fixture set must not shrink — at least two attack-class fixtures required (story 4.26 AC8)',
  ).toBeGreaterThanOrEqual(2);

  for (const name of names) {
    it(`renders classification fixture "${name}" inert`, async () => {
      const fixture = classificationFixtures[`./classification-description-fixtures/${name}.json`];
      const expected =
        classificationExpected[`./classification-description-fixtures/${name}.expected.json`];
      expect(fixture, `fixture ${name} missing`).toBeDefined();
      expect(expected, `${name}.expected.json missing`).toBeDefined();

      server.use(
        http.get(TAXONOMY_URL, () => HttpResponse.json({ values: [fixture] })),
        http.get(CLASSIFICATION_URL, () =>
          HttpResponse.json({
            workflowRunId: CLS_RUN_ID,
            deprecated: false,
            priorClassifications: [],
          }),
        ),
        http.get(DETAIL_URL, () =>
          HttpResponse.json({
            workflowRunId: CLS_RUN_ID,
            currentState: 'Failed',
            failureCategory: 'runner_timeout',
            failedStage: 'execution',
          }),
        ),
        http.get(DIAGNOSTICS_URL, () =>
          HttpResponse.json({
            currentState: 'Failed',
            failedStage: 'execution',
            failureCategory: 'runner_timeout',
            failureReason: 'container exited',
            integrationSyncStatus: { linear: null, github: null },
            recommendedRecoveryActions: [],
          }),
        ),
      );

      render(
        <QueryClientProvider client={client()}>
          <FailureClassificationDialog workflowRunId={CLS_RUN_ID} onClose={vi.fn()} />
        </QueryClientProvider>,
      );

      // Scope to the taxonomy card carrying this fixture's description + examples — the untrusted
      // surface, excluding the trusted dialog chrome (Finding 4, code-review 2026-07-17).
      await waitFor(() =>
        expect(
          screen.getByTestId(`failure-classification-option-${fixture!.value}`),
        ).toBeInTheDocument(),
      );
      const card = screen.getByTestId(`failure-classification-option-${fixture!.value}`);
      assertInert(card, name, expected!);
    });
  }
});
