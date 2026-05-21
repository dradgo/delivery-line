// Story 2.5 — pre-build generation of src/routeTree.gen.ts.
//
// Why this exists: `npm run build` is `tsc -b && vite build`. `tsc` runs FIRST and
// imports `./routeTree.gen` (via App.tsx), but the TanStack Router Vite plugin only
// (re)generates that file during `vite dev` / `vite build` — which run AFTER tsc.
// On a clean checkout / CI the file does not exist yet, so tsc would fail. This
// script generates the tree up front using the EXACT config the Vite plugin
// resolves (`getConfig({ target: 'react' }, root)` — same defaults as
// `tanstackRouter({ target: 'react' })` in vite.config.ts), so there is no second
// source of truth for routing config.
//
// `vite build` still regenerates the tree afterwards, so the AC7 drift protection
// (build always compiles against a fresh tree) is unaffected — this only guarantees
// the file EXISTS for the tsc pass.
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import { Generator } from '@tanstack/router-generator';
import { getConfig } from '@tanstack/router-plugin/vite';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const config = getConfig({ target: 'react' }, root);

const generator = new Generator({ config, root });
await generator.run();

console.log(`[routes:generate] wrote ${config.generatedRouteTree}`);
