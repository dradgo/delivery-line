# deliveryline-frontend

Vite + React 18 + TypeScript frontend module for **deliveryline**. The module is wired
into the Maven reactor: `mvn -pl deliveryline-frontend clean package` runs the full
`frontend-maven-plugin` pipeline (Node install → `npm ci` → `npm run build` → `target/dist/`)
and the backend module copies the bundle into the Spring Boot executable jar under
`BOOT-INF/classes/static/`.

## Quick start

```bash
npm install
npm run dev          # Vite dev server on http://localhost:5173, /api/* → localhost:8080
```

Override the port (5173 collides with another service):

- **POSIX / Git Bash:** `PORT=5174 npm run dev`
- **PowerShell:** `$env:PORT=5174; npm run dev`

## Build

```bash
npm run build        # tsc -b && vite build → target/dist/
npm run preview      # serve the production bundle locally
```

## Maven integration

```bash
# From the repo root — runs Node install + npm ci + npm run build via the
# frontend-maven-plugin. First run is slow (~3-6 min) due to cold Node download;
# subsequent runs are ~30 s.
./mvnw -pl deliveryline-frontend clean package

# Skip the frontend build while iterating on backend code locally:
./mvnw -pl deliveryline-backend package -Dfrontend-maven-plugin.skip=true

# Full reactor — produces the executable jar containing the SPA:
./mvnw clean install
```

CI runs `mvn -pl deliveryline-frontend clean package` on both `ubuntu-latest` and
`windows-latest` via the `frontend-build-tests` matrix; Windows failures are
build-blocking, not warnings.

## Dev server port + proxy

`vite.config.ts` reads `PORT` from `process.env` (default `5173`) and proxies
`/api/*` → `http://localhost:8080`. The proxy is wired so the SPA's API calls
land on the local Spring Boot backend without CORS configuration.

`server.host: true` binds the dev server on all interfaces, so WSL2 / Docker
Desktop containers can reach the dev server via host-side `localhost` forwarding.

When `/api/*` hits the dev server with the backend running, a `404` from
`http://localhost:8080/api/health` is the success signal that the proxy
forwarded the request correctly — story 2.1 doesn't ship `/api/health` yet.
If the backend is down or unreachable, Vite will log `ECONNREFUSED`; that is a
target-availability problem, not proof that the proxy itself is wired
correctly.

## Windows-specific notes

- **Long paths (`MAX_PATH = 260`).** Long-path support is validated for the
  story-2.1 dependency tree — no path under `node_modules/` exceeds 260 chars.
  If a future transitive dep does, enable Windows 10+ long-paths support via
  `reg add HKLM\SYSTEM\CurrentControlSet\Control\FileSystem /v LongPathsEnabled /t REG_DWORD /d 1 /f`
  (admin required) and restart the shell.
- **Line endings.** This module ships a `.gitattributes` declaring `* text=auto eol=lf`
  for source files plus explicit LF for lockfiles, config, and snapshot-bearing files.
  Cloning on Windows with `core.autocrlf=true` is safe — `.gitattributes` overrides.
- **Port conflicts.** If `5173` is in use, set `PORT=5174` (see Quick start above).
- **Antivirus interference.** If `npm ci` errors with `EPERM` or `EBUSY` on file
  rename operations, your antivirus is likely scanning `node_modules/`. Add the
  module path to the exclusions list.

## Scripts

| Script                    | Purpose                                                         |
| ------------------------- | --------------------------------------------------------------- |
| `npm run dev`             | Vite dev server with HMR + `/api/*` proxy                       |
| `npm run build`           | TypeScript project build + Vite production build                |
| `npm run preview`         | Serve the `target/dist/` bundle locally for a smoke test        |
| `npm run lint`            | ESLint (flat config), `--max-warnings=0` — fails on any warning |
| `npm run lint:fix`        | ESLint with `--fix`                                             |
| `npm run lint:rules-test` | Run the custom-ESLint-rule fixture tests (`node --test`)        |
| `npm run format`          | Prettier `--write` (apply formatting)                           |
| `npm run format:check`    | Prettier `--check` (CI gate)                                    |

The Vitest test runner ships with story 2.27.

## Lint & Format (story 2.31)

ESLint (flat config, `eslint.config.js`) + Prettier (`.prettierrc.json`) enforce
TypeScript-strict, React-hooks, accessibility (`jsx-a11y`), and import hygiene on
every PR. `npm run lint` + `npm run lint:rules-test` + `npm run format:check` run inside `mvn -pl
deliveryline-frontend clean package` (via `frontend-maven-plugin` executions), so
the `frontend-build-tests` CI matrix — wired into `foundation-gate` — gates merges
on lint/format cleanliness and custom-rule drift.

**Prettier conventions** (`.prettierrc.json`): single quotes, trailing commas
`all`, semicolons, 2-space indent, 100-char print width. `eslint-config-prettier`
disables stylistic ESLint rules so the two tools never conflict.

**Custom project rules** (`tools/eslint-rules/`, registered as the `local-rules`
plugin):

- **`no-workflow-domain-in-ui-primitives`** — files under `src/components/ui/` may
  not import workflow-domain code (`src/features/workflows/`) through static imports,
  lazy imports, or type-only import expressions. Keeps shadcn/ui primitives generic
  (story 2.2 AC7).
- **`no-inline-query-keys`** — `useQuery`/`useMutation`/`useInfiniteQuery` must use a
  query-key factory (`workflowKeys.*` from `src/lib/queryKeys/`), not an inline array
  literal or other non-factory key expression (story 2.7 AC4). Activates once
  TanStack Query lands in story 2.6; proven now by fixture tests under
  `tools/eslint-rules/__tests__/`.

Run the rule fixtures manually with `npm run lint:rules-test`; the same fixture suite
also runs in the enforced Maven/CI path.

### Optional: local pre-commit hooks (Husky + lint-staged)

Pre-commit hooks are **not installed by default** and are **not required**. If you
want lint/format to run automatically on staged files before each commit, opt in
locally:

```bash
npm install -D husky lint-staged
npx husky init
# .husky/pre-commit:
#   npx lint-staged
# package.json:
#   "lint-staged": { "*.{ts,tsx}": ["eslint --max-warnings=0", "prettier --check"] }
```

This is a personal-workflow convenience; CI is the authoritative gate.

## Design System (story 2.2)

Layer 1 (foundation primitives) of the three-layer design system. Tailwind CSS v3 +
shadcn/ui provide the generic primitive layer; **design tokens** (color palette, teal
accent, typography, spacing) arrive in stories **2.3 / 2.4**, and **workflow composites**
(queue item, review panel, decision bar, …) in **2.15–2.19**.

**Color tokens (story 2.3).** The neutral surface palette, teal `--brand-*` interactive
family, and 12 semantic state token groups (with high-contrast variants and a non-color
signifier map) are documented in [`src/styles/README.md`](src/styles/README.md). The
WCAG contrast, blocker/warning prominence, and signifier-parity gates run via
`npm run check:contrast` on the enforced Maven/CI path.

**Tailwind v3 (not v4).** Pinned to `tailwindcss@^3.4` deliberately: AC1 requires
`tailwind.config.ts` + `postcss.config.js` (v3 idioms), and v3 is pure-JS — it adds no
platform-specific native binaries to the lockfile (v4's `@tailwindcss/oxide` /
`lightningcss` are the exact native-binding hazard that cost story 2.1 four CI rounds).

**shadcn/ui config** (`components.json`): style `new-york`, base color `slate`, CSS
variables enabled, primitives under `src/components/ui/`, `@/*` → `./src/*` alias
(introduced here — supersedes story 2.1's deferral, since shadcn requires it). Generated
with the v3-compatible `shadcn@2.x` CLI; the v4-first `shadcn@4.x` CLI would diverge from
the AC1 `components.json` schema.

**Primitive inventory (20).** `button`, `input`, `textarea`, `label`, `dialog`, `sheet`,
`popover`, `dropdown-menu`, `select`, `tabs`, `badge`, `alert`, `table`, `card`, `tooltip`,
`scroll-area`, `accordion`, `collapsible`, `separator`, and `sonner` (the current shadcn
toast primitive — replaces the deprecated `toast`). Primitives are stock shadcn output;
the only edits are minimal strict-TypeScript fixes (`dropdown-menu` `exactOptionalPropertyTypes`,
`sonner` adapted off the Next.js `next-themes` dependency for this Vite SPA). They must
**never** import workflow-domain code — enforced by the `no-workflow-domain-in-ui-primitives`
ESLint rule (story 2.31, scoped to `src/components/ui/**`).

**`cn()` helper** (`src/lib/utils.ts`): the standard `clsx` + `tailwind-merge` className
combiner that composites use for conditional class composition.

**PrimitivesPlayground** (`src/routes/_dev/PrimitivesPlayground.tsx`): dev-only living
documentation rendering every primitive in its canonical states. TanStack Router is not
installed until story 2.5, so it is **not** a real route yet — in dev, append `?playground`
to the URL (`http://localhost:5173/?playground`) to view it. The `import.meta.env.DEV` guard
makes it statically dead in production, so it (and the example-only primitive states) are
tree-shaken out of the prod bundle.

**Dark mode — wired, NOT activated (AC8).** `tailwind.config.ts` sets `darkMode: ['class']`
and `src/styles/globals.css` ships the `.dark { … }` CSS-variable block, but Epic 2 / MVP
ships **no** theme toggle and never adds a `dark` class to `<html>`. To activate it in a
future post-MVP story: add a toggle that sets `class="dark"` on the root element (e.g. via a
small theme context), and supply the real dark-palette token values once story 2.3 lands.

## Pinned versions

- **React 18.3.x** — pinned (NOT React 19) because Epic 2 downstream stories
  assume React 18 hook semantics + TanStack Router/Query compatibility matrices.
- **TypeScript** — strict mode enabled including `noUncheckedIndexedAccess` and
  `exactOptionalPropertyTypes` (`tsconfig.app.json`).
- **Node v20.19.0** — pinned in `deliveryline-frontend/pom.xml`'s
  `frontend-maven-plugin` config (keep in sync with `docs/supported-environments.md`).
