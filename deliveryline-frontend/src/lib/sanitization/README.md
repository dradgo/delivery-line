# `src/lib/sanitization/` — Story 2.24

Hardened markdown + diff rendering pipeline for untrusted runner output, plus
the defense-in-depth frontend redaction filter that closes the F19/F20
redaction-policy gaps deferred from Epic 1.

## Public surface

| Symbol                  | Source                       | Purpose                                                              |
| ----------------------- | ---------------------------- | -------------------------------------------------------------------- |
| `SafeMarkdownRenderer`  | `SafeMarkdownRenderer.tsx`   | The sanctioned renderer for ALL untrusted markdown bodies (AC1–AC4). |
| `SafeDiffRenderer`      | `SafeDiffRenderer.tsx`       | Plain-text before/after panels with `<ins>`/`<del>` semantics (AC5). |
| `MetadataChrome`        | `MetadataChrome.tsx`         | Trusted-metadata wrapper above the generated-content slot (AC6).     |
| `validateUrlScheme`     | `policy.ts`                  | Case-insensitive, whitespace-trimming URL scheme validator (AC4).    |
| `scanForRedactions`     | `redactionFilter.tsx`        | Pure scan over text → match offsets + detected categories.           |
| `renderTextWithRedactions` | `redactionFilter.tsx`     | Wraps newly-detected secrets in `<mark class="redaction-applied">`.  |

All composites outside this package MUST import from `src/lib/sanitization/`
(the barrel), never from individual files. The AC11 ESLint rule
`local-rules/no-unsanitized-html` blocks the only other path that could leak
untrusted HTML into the DOM (`dangerouslySetInnerHTML`).

## Failure semantics

A sanitization regression is **build-blocking** per AC8 — never a warning,
never quarantined under the "no-blanket-retry" rule from story 1.21. The
following failure modes terminate the build:

- Any XSS fixture under `__tests__/xss-fixtures/` whose rendered output
  fails its `.expected.json` sidecar contract.
- Any redaction fixture under `__tests__/redaction-fixtures/` whose
  rendered output fails to wrap a known secret pattern.
- A cross-side drift between this package's `redaction-policy.generated.json`
  and the canonical source at
  `deliveryline-runner-contracts/src/main/resources/runner-contracts/redaction-policy.json`
  (the `deliveryline-runner-contracts` contract test pins parity).

## Bundle impact

Story 2.24 added four runtime dependencies:

- `react-markdown` ^9.0.1
- `rehype-sanitize` ^6.0.0
- `remark-gfm` ^4.0.0
- `shiki` ^1.22.0

The AC9 ceiling is **≤ 250 KB gzipped delta** vs the pre-2-24 baseline.

### Measured 2026-05-26 (code review, post-Shiki integration)

| Scenario | Main JS gz | Lazy grammars gz | Theme gz | CSS gz | Worst-case total gz |
| -------- | ---------- | ---------------- | -------- | ------ | ------------------- |
| Baseline (HEAD pre-2-24, isolated worktree) | 134.26 KB | — | — | 8.48 KB | 142.74 KB |
| Story 2-24 shipped (sanitization package not yet imported anywhere; tree-shaken) | 134.26 KB | — | — | 8.49 KB | 142.75 KB |
| **Story 2-24 fully wired** (probe imports the entire sanitization barrel into `main.tsx` so nothing tree-shakes) | **232.42 KB** | **60.57 KB** (sum of 8 grammars) | **2.49 KB** | **8.49 KB** | **303.97 KB** |

Grammar chunks are **code-split** by Vite — they lazy-load on the first
`SafeMarkdownRenderer` mount of a fenced block in that language. Per-grammar
gzipped sizes: `json` 0.80 KB, `yaml` 2.29 KB, `bash` 6.30 KB, `markdown`
5.66 KB, `python` 10.41 KB, `javascript` 17.75 KB, `typescript` 17.36 KB,
`diff` ~0.6 KB; `github-light` theme 2.49 KB.

**As-shipped delta:** **+0.01 KB gz** — the sanitization package is
introduced but not yet consumed by any composite, so tree-shaking eliminates
it from the production bundle.

**Delta on first consumer (initial load only, no grammars yet):**
**+98.16 KB gz JS** (134.26 → 232.42 KB main bundle).

**Worst-case delta** (all 8 grammars + theme loaded — only possible if a
single page renders fenced blocks in every supported language):
**+161.23 KB gz** (303.97 − 142.74). **Under the 250 KB AC9 ceiling with
~89 KB headroom.**

Why the JavaScript regex engine (`shiki/engine/javascript`) and not Oniguruma
WASM: the WASM engine ships a ~600 KB blob (~232 KB gz) that would single-
handedly push us past the AC9 threshold. The JavaScript engine is slower at
runtime but bundles to ~30 KB gz, and our token surface is small.

### How to re-measure

```bash
# from deliveryline-frontend/
npm run build
ls -la target/dist/assets/*.js
# gzipped sizes are also printed by `vite build` directly
```

For an isolated baseline vs head comparison, create a git worktree at the
prior tag/commit and rebuild there:

```bash
git worktree add ../baseline <baseline-ref>
(cd ../baseline/deliveryline-frontend && npm install --no-audit --no-fund && npx vite build)
```

If the threshold is exceeded, drop a Shiki language in the order: `python`,
`markdown`, `diff` (least frequently needed first). Update `SHIKI_LANGUAGES`
in `policy.ts` and document the choice here.

Shiki itself defers grammar loading via its lazy language registry — only
the eight languages in `SHIKI_LANGUAGES` are bundled when the active
highlighter is wired.

## Regenerating the redaction-policy mirror

When the canonical
`deliveryline-runner-contracts/src/main/resources/runner-contracts/redaction-policy.json`
changes, regenerate the frontend copy via:

```bash
# bash / WSL
./scripts/regen-redaction-policy.sh

# PowerShell / Windows
.\scripts\regen-redaction-policy.ps1
```

Both scripts are pure file-copy + checksum — no platform shims required
(Trap T7). The contract test in `deliveryline-runner-contracts` will fail
CI if the two files drift.

## Trap reference (story 2.24)

The story spec documents twelve traps; the rules most likely to bite a
future contributor are:

- **T1.** Shiki output never reaches the DOM via `dangerouslySetInnerHTML`.
  `SafeCode` calls `highlighter.codeToHast()` to get a HAST tree and then
  `hast-util-to-jsx-runtime` to convert it into React elements directly —
  no HTML strings ever cross the trust boundary. Initial render is plain
  text; the highlighter resolves asynchronously after `useEffect` fires.
- **T2.** Token spans emitted by Shiki carry inline `style` attributes for
  per-token colors. These styles are SHIKI-CONTROLLED (a fixed lookup table
  inside the chosen theme grammar) and are never derived from untrusted
  input — the input is treated by Shiki as a string. The
  `rehype-sanitize` schema's style-stripping on the markdown HAST path
  remains in force for non-code untrusted content. (The original spec's
  CSS-class-only design is preserved as a future cleanup — see story 2.24
  Trap T2.)
- **T3.** URL-scheme validation `.trim()`s AND `.toLowerCase()`s BEFORE
  scheme extraction (`\tjavascript:` and `JaVaScRiPt:` evasions).
- **T6.** The backend `SensitivePayloadAnalyzer` is the implementation
  source of truth. This package's `redaction-policy.generated.json`
  MIRRORS the backend; we never invert the trust direction.
- **T11.** Author-written literal `[REDACTED]` strings (no `<mark>` wrapper)
  render unchanged. Only newly-detected patterns get wrapped.
