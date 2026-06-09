/**
 * Story 2.25 (Task 1 — AC2, AC6, D1) — shared axe-core harness for the Vitest suite.
 *
 * `expectNoA11yViolations(container)` runs an axe-core scan over a rendered DOM
 * subtree, configured for the WCAG-2.1-AA tag set, and FAILS the test on any
 * violation. Every composite + primitive test renders its documented states and
 * calls this helper (AC2).
 *
 * Tag set (AC2): `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`.
 *
 * Contrast (AC6): axe-core's `color-contrast` rule needs a real layout/canvas to
 * sample rendered pixels — jsdom has neither, so it reports `color-contrast` as
 * *incomplete* (not a violation) here. That is expected: the authoritative
 * contrast gate stays the token-pair `npm run check:contrast` node-test (story
 * 2.3). This harness leaves `color-contrast` enabled so it runs wherever a real
 * layout engine is present (e.g. the story-2.27 Playwright tier), and never
 * masks a pair with `aria-hidden`/off-screen tricks.
 *
 * Suppression convention (AC2): a single rule may be disabled ONLY via the
 * `rules` option together with an inline
 *   // a11y-justification: <rule-id> — <reason>
 * comment at the call site naming the rule and the reason. Zero unjustified
 * suppressions are allowed.
 */
import type { RunOptions } from 'axe-core';
import { configureAxe } from 'vitest-axe';
import { expect } from 'vitest';

/** The WCAG 2.1 AA conformance target for Epic 2 (AC2). */
export const WCAG_21_AA_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] as const;

// A single pre-configured runner scoped to the AA tag set, reused across the
// whole suite so every scan asserts the same conformance bar.
const runAxe = configureAxe({
  runOnly: { type: 'tag', values: [...WCAG_21_AA_TAGS] },
});

/**
 * Scan `container` for WCAG-2.1-AA violations and fail on any.
 *
 * @param container a rendered DOM subtree (RTL `container`/`baseElement`, an
 *   `Element`, or an HTML string) attached to `document`.
 * @param options per-call axe overrides — e.g. `{ rules: { 'rule-id': { enabled: false } } }`
 *   to suppress a single rule (requires an inline `// a11y-justification:` comment).
 */
export async function expectNoA11yViolations(
  container: Element | string,
  options?: RunOptions,
): Promise<void> {
  const results = await runAxe(container, options);
  expect(results).toHaveNoViolations();
}
