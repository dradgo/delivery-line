/**
 * Story 2.3 — read the SHIPPED `src/styles/globals.css` `:root` block and
 * return its CSS custom properties as a name → value map. The contrast (AC4),
 * signifier-parity (AC5), and prominence (AC6) tests all read this single
 * source of truth, so token values can never drift from a parallel JS/JSON copy
 * (Task 2 "single source of truth", Dev Notes "Testing approach").
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

/** Absolute path to the shipped global stylesheet. */
export const GLOBALS_CSS_PATH = resolve(here, '../../src/styles/globals.css');

/**
 * Extract the `:root { ... }` declaration block (NOT the `.dark` overrides — the
 * dark values are provisional per AC8 and are not contrast-gated).
 * @param {string} css
 * @returns {string} the contents between `:root {` and its closing brace
 */
function extractRootBlock(css) {
  const start = css.indexOf(':root');
  if (start === -1) {
    throw new Error('No :root block found in globals.css');
  }
  const open = css.indexOf('{', start);
  // Walk to the matching closing brace (the :root block has no nested braces).
  const close = css.indexOf('}', open);
  if (open === -1 || close === -1) {
    throw new Error('Malformed :root block in globals.css');
  }
  return css.slice(open + 1, close);
}

/**
 * Parse the `:root` block into a Map of `--var-name` → raw value string.
 * @param {string} [cssPath] defaults to the shipped globals.css
 * @returns {Map<string, string>}
 */
export function parseRootVars(cssPath = GLOBALS_CSS_PATH) {
  const css = readFileSync(cssPath, 'utf8');
  const block = extractRootBlock(css);
  /** @type {Map<string, string>} */
  const vars = new Map();
  const declRe = /(--[\w-]+)\s*:\s*([^;]+);/g;
  let m;
  while ((m = declRe.exec(block)) !== null) {
    vars.set(m[1], m[2].trim());
  }
  return vars;
}

/** HSL channel-triplet matcher (`222 47% 11%`) — excludes `--radius` etc. */
const HSL_TRIPLET = /^[\d.]+\s+[\d.]+%\s+[\d.]+%$/;

/**
 * Subset of parseRootVars limited to HSL color triplets (drops `--radius`).
 * @param {string} [cssPath]
 * @returns {Map<string, string>}
 */
export function parseRootColorVars(cssPath = GLOBALS_CSS_PATH) {
  const colors = new Map();
  for (const [name, value] of parseRootVars(cssPath)) {
    if (HSL_TRIPLET.test(value)) {
      colors.set(name, value);
    }
  }
  return colors;
}

/**
 * Discover the semantic state names present in globals.css by scanning for
 * `--state-{name}` base tokens (excludes `-foreground`/`-border`/`-hc*` suffixes).
 * This is the authoritative list the signifier-parity test (AC5) compares against.
 * @param {string} [cssPath]
 * @returns {string[]} sorted state names, e.g. ['blocker', 'draft', ...]
 */
export function parseStateNames(cssPath = GLOBALS_CSS_PATH) {
  const names = new Set();
  for (const name of parseRootColorVars(cssPath).keys()) {
    // Match base fill tokens only: `--state-<name>` with no further `-` segment
    // that is a known sub-part. `<name>` itself may contain hyphens
    // (e.g. permission-restricted), so strip known suffixes instead.
    if (!name.startsWith('--state-')) {
      continue;
    }
    let rest = name.slice('--state-'.length);
    if (
      rest.endsWith('-hc-foreground') ||
      rest.endsWith('-hc-border') ||
      rest.endsWith('-hc') ||
      rest.endsWith('-foreground') ||
      rest.endsWith('-border')
    ) {
      continue;
    }
    names.add(rest);
  }
  return [...names].sort();
}
