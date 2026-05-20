// Story 2.2 — PostCSS pipeline for Tailwind v3.
// package.json has "type": "module", so this file is ESM (export default).
// Tailwind v3 is pure-JS (postcss + autoprefixer) — no native binaries, keeping
// the cross-platform lockfile clean (see story 2.1 rolldown lesson + Task 9).
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
};
