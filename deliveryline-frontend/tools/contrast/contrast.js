/**
 * Story 2.3 — WCAG contrast math for the design-token contrast gate (AC4).
 *
 * Hand-rolled (NO runtime dependency — Task 8): HSL channel-triplet → sRGB →
 * WCAG 2.1 relative luminance → contrast ratio. The token files store colors as
 * channel triplets (`H S% L%`) so the existing `hsl(var(--x))` Tailwind mapping
 * works; this helper parses that exact format.
 *
 * Pure functions, no I/O — see parse-globals.js for reading the shipped CSS.
 */

/**
 * Parse an HSL channel-triplet string like `215 64% 30%` into numbers.
 * Throws on a malformed triplet so a typo in globals.css fails loud.
 * @param {string} triplet
 * @returns {{ h: number, s: number, l: number }}
 */
export function parseHslTriplet(triplet) {
  const match = /^\s*([\d.]+)\s+([\d.]+)%\s+([\d.]+)%\s*$/.exec(triplet);
  if (!match) {
    throw new Error(`Not an HSL channel triplet: "${triplet}"`);
  }
  return { h: Number(match[1]), s: Number(match[2]), l: Number(match[3]) };
}

/**
 * HSL (h in [0,360], s/l in percent) → sRGB channels in [0,1].
 * @param {{ h: number, s: number, l: number }} hsl
 * @returns {[number, number, number]}
 */
export function hslToRgb({ h, s, l }) {
  const sN = s / 100;
  const lN = l / 100;
  const c = (1 - Math.abs(2 * lN - 1)) * sN;
  const hp = h / 60;
  const x = c * (1 - Math.abs((hp % 2) - 1));
  let r1 = 0;
  let g1 = 0;
  let b1 = 0;
  if (hp >= 0 && hp < 1) {
    [r1, g1, b1] = [c, x, 0];
  } else if (hp < 2) {
    [r1, g1, b1] = [x, c, 0];
  } else if (hp < 3) {
    [r1, g1, b1] = [0, c, x];
  } else if (hp < 4) {
    [r1, g1, b1] = [0, x, c];
  } else if (hp < 5) {
    [r1, g1, b1] = [x, 0, c];
  } else {
    [r1, g1, b1] = [c, 0, x];
  }
  const m = lN - c / 2;
  return [r1 + m, g1 + m, b1 + m];
}

/**
 * WCAG 2.1 relative luminance of an sRGB color (channels in [0,1]).
 * https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 * @param {[number, number, number]} rgb
 * @returns {number}
 */
export function relativeLuminance([r, g, b]) {
  const lin = (c) => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

/**
 * WCAG contrast ratio between two HSL channel-triplet strings.
 * Returns a value in [1, 21]; order of arguments does not matter.
 * @param {string} tripletA
 * @param {string} tripletB
 * @returns {number}
 */
export function contrastRatio(tripletA, tripletB) {
  const lA = relativeLuminance(hslToRgb(parseHslTriplet(tripletA)));
  const lB = relativeLuminance(hslToRgb(parseHslTriplet(tripletB)));
  const lighter = Math.max(lA, lB);
  const darker = Math.min(lA, lB);
  return (lighter + 0.05) / (darker + 0.05);
}
