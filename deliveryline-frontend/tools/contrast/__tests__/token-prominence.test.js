// Story 2.3 AC6 — programmatic blocker/warning DOMINANCE proxy over the shipped
// globals.css. True pixel-level visual regression (Playwright/Chromatic) is
// DEFERRED to 2.27/2.25 (Q3); this guards against a future edit that accidentally
// makes blocker recede below an informational/draft/empty badge of the same size.
//
// Prominence metric (documented): for a same-size badge, perceived prominence is
// driven by (a) fill saturation and (b) the fill's lightness delta from the page
// background, plus (c) a non-transparent border. We score = saturation +
// lightnessDeltaFromBackground and require the DOMINANT states (blocker, warning)
// to outscore EVERY recessive state (informational, draft, empty), and to carry a
// visible (>=3:1 vs background) border.
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseHslTriplet, contrastRatio } from '../contrast.js';
import { parseRootColorVars } from '../parse-globals.js';

const vars = parseRootColorVars();
const fill = (state) => parseHslTriplet(vars.get(`--state-${state}`));
const bgL = parseHslTriplet(vars.get('--background')).l;

const prominence = (state) => {
  const { s, l } = fill(state);
  return s + Math.abs(l - bgL);
};

const DOMINANT = ['blocker', 'warning'];
const RECESSIVE = ['informational', 'draft', 'empty'];

test('blocker and warning are more prominent than recessive states', () => {
  const minDominant = Math.min(...DOMINANT.map(prominence));
  const maxRecessive = Math.max(...RECESSIVE.map(prominence));
  assert.ok(
    minDominant > maxRecessive,
    `dominant prominence ${minDominant.toFixed(1)} must exceed recessive ${maxRecessive.toFixed(1)} ` +
      `(blocker=${prominence('blocker').toFixed(1)}, warning=${prominence('warning').toFixed(1)}, ` +
      `informational=${prominence('informational').toFixed(1)}, draft=${prominence('draft').toFixed(1)}, ` +
      `empty=${prominence('empty').toFixed(1)})`,
  );
});

test('dominant states carry a visible border (>= 3:1 vs background)', () => {
  const bg = vars.get('--background');
  for (const state of DOMINANT) {
    const border = vars.get(`--state-${state}-border`);
    const ratio = contrastRatio(border, bg);
    assert.ok(
      ratio >= 3,
      `--state-${state}-border vs --background is ${ratio.toFixed(2)}:1 (need 3:1)`,
    );
  }
});
