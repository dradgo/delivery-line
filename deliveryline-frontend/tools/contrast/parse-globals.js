/**
 * Story 2.3 — read the SHIPPED `src/styles/globals.css` `:root` block and
 * return its CSS custom properties as a name → value map. The contrast (AC4),
 * signifier-parity (AC5), and prominence (AC6) tests all read this single
 * source of truth, so token values can never drift from a parallel JS/JSON copy
 * (Task 2 "single source of truth", Dev Notes "Testing approach").
 */
import { existsSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

/** Absolute path to the source global stylesheet. */
export const SOURCE_GLOBALS_CSS_PATH = resolve(here, '../../src/styles/globals.css');
/** Build output entry HTML used to locate the emitted CSS asset when present. */
export const DIST_INDEX_HTML_PATH = resolve(here, '../../target/dist/index.html');

function resolveGlobalsCssPath() {
  if (!existsSync(DIST_INDEX_HTML_PATH)) {
    return SOURCE_GLOBALS_CSS_PATH;
  }

  const html = readFileSync(DIST_INDEX_HTML_PATH, 'utf8');
  const hrefMatch = html.match(/href="(?:\.\/|\/)?(assets\/[^"]+\.css)"/);
  if (!hrefMatch) {
    return SOURCE_GLOBALS_CSS_PATH;
  }

  const distCssPath = resolve(here, '../../target/dist', hrefMatch[1]);
  if (!existsSync(distCssPath)) {
    return SOURCE_GLOBALS_CSS_PATH;
  }

  // Prefer the emitted bundle only when it is at least as fresh as the source.
  // This keeps the Maven/CI path validating the shipped CSS after `npm run build`,
  // while local token checks still see current source edits instead of a stale
  // leftover `target/dist` bundle from an earlier run.
  if (existsSync(SOURCE_GLOBALS_CSS_PATH)) {
    const sourceMtimeMs = statSync(SOURCE_GLOBALS_CSS_PATH).mtimeMs;
    const distMtimeMs = statSync(distCssPath).mtimeMs;
    if (sourceMtimeMs > distMtimeMs) {
      return SOURCE_GLOBALS_CSS_PATH;
    }
  }

  return distCssPath;
}

/** Absolute path to the preferred stylesheet under test: emitted bundle if available, source otherwise. */
export const GLOBALS_CSS_PATH = resolveGlobalsCssPath();

/**
 * Extract the `:root { ... }` declaration block (NOT the `.dark` overrides — the
 * dark values are provisional per AC8 and are not contrast-gated).
 * @param {string} css
 * @returns {string} the contents between `:root {` and its closing brace
 */
function extractRootBlock(css) {
  const cssWithoutComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const selectorMatch = /:root\s*\{/.exec(cssWithoutComments);
  if (!selectorMatch) {
    throw new Error('No :root block found in globals.css');
  }

  const selectorStart = selectorMatch.index;
  const open = cssWithoutComments.indexOf('{', selectorStart);
  if (open === -1) {
    throw new Error('Malformed :root block in globals.css');
  }

  let depth = 0;
  for (let index = open; index < cssWithoutComments.length; index += 1) {
    const char = cssWithoutComments[index];
    if (char === '{') {
      depth += 1;
    } else if (char === '}') {
      depth -= 1;
      if (depth === 0) {
        return cssWithoutComments.slice(open + 1, index);
      }
    }
  }

  throw new Error('Malformed :root block in globals.css');
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
  // The trailing `;` is OPTIONAL: CSS minifiers (esbuild/lightningcss in the
  // `vite build` output) drop the semicolon on the LAST declaration of a block,
  // so requiring `;` silently dropped the final `:root` var when parsing the
  // emitted bundle (story 2.4). `[^;]+` already stops at the next `;` or the end
  // of the extracted block, so omitting the required `;` is safe.
  const declRe = /(--[\w-]+)\s*:\s*([^;]+)/g;
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
