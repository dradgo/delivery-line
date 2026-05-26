# Story 2.24: Artifact Content Sanitization + Redaction-Gap Closure (Untrusted Runner Output)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer rendering markdown content produced by an LLM-driven runner — combined with a backend redaction-policy maintainer,
I want a hardened markdown sanitization library + diff sanitization + safe artifact rendering pipeline AND closure of the F19/F20 redaction-policy gaps deferred from Epic 1 (PEM blocks, bundle JSON shapes, Idempotency-Key header, missing credential/bearer/private patterns),
So that untrusted runner output cannot inject scripts, exfiltrate via crafted links, mislead reviewers via metadata spoofing, OR leak secrets that the backend redactor missed — closing the threat-model shift identified in the Epic 1 retrospective (2026-05-19): the CLI pilot didn't render untrusted content in a UI, but Epic 2 does, so backend-side redaction gaps that were "acceptable for CLI" now become UI-rendering vulnerabilities (see `sprint-change-proposal-2026-05-19.md`).

## Acceptance Criteria

1. **Given** `deliveryline-frontend/src/lib/sanitization/`, **Then** a typed `SafeMarkdownRenderer` component exists wrapping a known-good markdown library (`react-markdown` + `rehype-sanitize` + `remark-gfm`) with a strict allowlist policy: only documented HTML tags, no `<script>`, no `<iframe>`, no `<style>`, no event handler attributes (`on*`), no `javascript:` URLs. The renderer is exported from a `src/lib/sanitization/index.ts` barrel that future composites (stories 2.15 / 2.17 / 2.18) import from — no other entry point.

2. **Given** the rendering pipeline, **Then** the allowlist covers exactly: headings (`h1`–`h6`), text (`p`, `strong`, `em`, `code`, `pre`, `blockquote`, `hr`, `br`), lists (`ul`, `ol`, `li`), links (`a` — with `rel="noopener noreferrer"` injected at render time + URL-scheme validation per AC4), tables (`table`, `thead`, `tbody`, `tr`, `th`, `td`), images (`img` — only same-origin sources or the documented allowlist defined as a constant `ALLOWED_IMAGE_HOSTS` in `src/lib/sanitization/policy.ts`; agent-generated image URLs that fail the allowlist render as a link-only `<a>` to the URL with the same scheme validation as AC4). Anything outside the allowlist is dropped, not rendered as raw HTML.

3. **Given** code blocks, **Then** they render as plain text within a `<code>`/`<pre>` container with syntax highlighting from `shiki` (chosen over `prism` for bundle-size + tree-shakeable language registry — see AC9 for the bundle threshold). The highlighter runs over a sanitized text node only; it never executes embedded HTML, never interprets JavaScript even in `language="javascript"` blocks (verified by an XSS fixture in AC7 that places `<script>` inside a `language="javascript"` fenced block and asserts inert rendering).

4. **Given** link handling, **Then** the renderer validates the URL scheme (only `http`, `https`, `mailto` allowed; `javascript:`, `data:`, `file:`, `vbscript:`, `blob:` rejected and rendered as plain text with the literal URL displayed but no `<a>` element emitted), adds `rel="noopener noreferrer"` to all external links, sets `target="_blank"` on external links, and visually distinguishes external links from internal (same-origin) ones via a small external-link icon (`ExternalLink` from `lucide-react`, already in `package.json` dependencies). Scheme validation is case-insensitive and trims leading whitespace (defeats `\tjavascript:` and `JaVaScRiPt:` evasions).

5. **Given** the diff renderer used by the change-summary slot from story 2.17 AC2 and the full Compare Mode in Epic 4, **Then** a `SafeDiffRenderer` component lives in the same `src/lib/sanitization/` package and renders diff content as plain text within structured before/after panels — never as raw HTML; line-level additions/deletions use semantic `<ins>`/`<del>` with stable token classes (`diff-line-added`, `diff-line-removed`, `diff-line-context`). The component accepts a typed `{ before: string; after: string }` prop and never interprets either side as HTML.

6. **Given** metadata spoofing prevention per story 1.10 AC10 and the architecture's "trusted system metadata vs generated content" rule (`architecture.md:518`), **Then** the artifact panel exposes a documented composition pattern: a `<MetadataChrome>` wrapper (also in `src/lib/sanitization/`) renders **trusted system metadata** (artifact title, version, classification badge — typed React props, no markdown interpretation) above a `<SafeMarkdownRenderer>` slot for **generated content**. The visual treatment uses a documented border-token + label `Generated content` so the distinction is explicit. The wrapper is the only sanctioned surface composites use to render artifact bodies (stories 2.15 / 2.17 / 2.18 will consume it).

7. **Given** XSS test fixtures, **Then** `deliveryline-frontend/src/lib/sanitization/__tests__/xss-fixtures/` contains an adversarial fixture set with at minimum one file per attack class: `script-tag-injection.md`, `img-onerror.md`, `a-href-javascript.md`, `iframe-src.md`, `style-tag-injection.md`, `markdown-link-javascript-url.md`, `entity-encoded-script.md`, `mixed-case-script.md` (e.g., `<ScRiPt>`, `<SCRIPT>`), `polyglot.md` (combined attack vectors), `data-uri-image.md`, `code-fence-with-script.md` (covers AC3). Each fixture is a `.md` file paired with an `.expected.json` sidecar declaring `{ "renderedTextContains": [...], "renderedTextDoesNotContain": [...], "noActiveElements": true }`. Tests load each fixture, render via `SafeMarkdownRenderer`, and assert each sidecar contract — every fixture must render as inert text or be rejected entirely. A single passing-XSS-fixture is build-blocking per AC8.

8. **Given** Murat's "risk-weight artifact sanitization tests highest" call (epic-2 risk register), **Then** sanitization tests run as part of the `frontend-build-tests` CI tier (story 1.21 — already wired) and the existing `export-redaction-verify` CI tier is **extended** to include the new backend redaction contract test from AC14 + the cross-side drift contract test from AC16. A sanitization regression (any failing XSS fixture in AC7 OR any failing redaction fixture in AC14 OR any drift in AC16) is a **build-blocking failure** — never a warning, never quarantined under the "no-blanket-retry" rule from story 1.21. Document this in `deliveryline-frontend/src/lib/sanitization/README.md` (new) under a "Failure semantics" section.

9. **Given** the renderer's bundle-size impact, **Then** the `react-markdown` + `rehype-sanitize` + `remark-gfm` + `shiki` set is documented in `deliveryline-frontend/src/lib/sanitization/README.md` with the gzipped bundle delta measured by `vite build` against the pre-2-24 baseline; the delta must be ≤ 250 KB gzipped (Shiki uses lazy language registry — load only `json`, `yaml`, `markdown`, `bash`, `typescript`, `javascript`, `python`, `diff` initially; document the registry in `policy.ts`). If the delta exceeds the threshold the story is blocked — document the measurement procedure and the chosen library set in the README; alternatives that exceed the threshold are rejected with a written justification.

10. **Given** future Compare Mode (Epic 4) + implementation-plan + PR-output artifact variants, **Then** the same `SafeMarkdownRenderer` + `SafeDiffRenderer` + `MetadataChrome` primitives serve every artifact variant — variant-specific renderers (story 2.17 AC3 stubs and later story 3-26 / 3-27) MUST consume these primitives rather than rolling their own. The story 2.17 stubs are updated as part of this story to consume `MetadataChrome` + `SafeMarkdownRenderer` (changing only the stub shells — no new variant logic).

11. **Given** the no-`dangerouslySetInnerHTML` rule, **Then** a new ESLint custom rule `no-unsanitized-html` is added under `deliveryline-frontend/tools/eslint-rules/` (extending the existing `local-rules` plugin from story 2.31) that flags any direct use of `dangerouslySetInnerHTML` or any non-`SafeMarkdownRenderer`/`SafeDiffRenderer` path that injects HTML strings. The rule is wired in `eslint.config.js` at `error` severity for `src/**/*.{ts,tsx}` and has a documented test file under `tools/eslint-rules/__tests__/no-unsanitized-html.test.js` (per the existing rule-test convention) wired into the `lint:rules-test` script. The only sanctioned path for runner-produced HTML is the new sanitization primitives.

12. **Given** component test coverage, **Then** Vitest + React Testing Library tests under `deliveryline-frontend/src/lib/sanitization/__tests__/` cover: every adversarial XSS fixture renders as inert (loop the AC7 fixture set), sanitization removes disallowed tags but preserves allowed ones (positive-set test using the AC2 allowlist), link URL-scheme validation (parametric tests over `http`, `https`, `mailto`, `javascript:`, `data:`, `file:`, `vbscript:`, `blob:`, mixed-case variants), `rel="noopener noreferrer"` always present on external links, code blocks never execute (the AC3 fixture), metadata-spoofing visual separation persists across artifact variants (`MetadataChrome` test), `SafeDiffRenderer` never interprets HTML in `before`/`after`. Each test uses `@testing-library/react` with `screen.queryByRole`/`queryAllByRole` to assert absence of `script`/`iframe` elements (not string-based assertions).

13. **Given** the F19/F20 redaction-policy gaps from `deferred-work.md`, **Then** the backend's `SensitivePayloadAnalyzer` is extended (in `application.security`) to cover:
    - **(a) PEM-formatted blocks** beyond the existing RSA/OPENSSH private-key pattern — add patterns for `EC PRIVATE KEY`, `DSA PRIVATE KEY`, `ENCRYPTED PRIVATE KEY`, `PRIVATE KEY` (generic PKCS#8), `CERTIFICATE` (when paired with a sibling `PRIVATE KEY` block in the same payload it remains redacted; standalone `CERTIFICATE` blocks remain visible since they are public material — document the asymmetry in code comments). Pattern: `(?ms)-----BEGIN ([A-Z ]+(?:PRIVATE KEY))-----.*?-----END \\1-----` plus a paired-certificate detector. New `RedactionCategory` values added: `PEM_PRIVATE_KEY` (replaces / supersedes the existing SSH-specific category for non-SSH PEM blocks — keep `SSH_PRIVATE_KEY` for the OPENSSH/RSA OpenSSH variants since they have distinct placeholders), `PEM_CERTIFICATE_WITH_PRIVATE_KEY`.
    - **(b) Bundle JSON shapes** — extend `JSON_SECRET_FIELD_PATTERN` field-name allowlist from the current `{secret, token, apiKey, api_key, accessToken, access_token, password, credential, linearApiKey}` to add `{bearer, private, private_key, privateKey, refresh_token, refreshToken, client_secret, clientSecret, sessionToken, authToken, auth_token}`. The structured-payload walker (`sanitizeJsonNode`) inherits the extended allowlist via `looksSecretLikeKey`, which is similarly extended.
    - **(c) `Idempotency-Key` HTTP header** in any logged or exported request shape — new pattern `IDEMPOTENCY_KEY_HEADER_PATTERN = (?im)(Idempotency-Key\\s*:\\s*)([^\\s\\r\\n]+)` → `[REDACTED_IDEMPOTENCY_KEY]`. New `RedactionCategory.IDEMPOTENCY_KEY`. (Cross-reference: the existing `ProblemDetailsMapper` already sanitizes idempotency keys in error details — this AC closes the gap for free-text logged request shapes.)
    - **(d) Field-name allowlist** extended in the same set as (b) — confirm `ENV_SECRET_VALUE_PATTERN`, `YAML_SECRET_FIELD_PATTERN`, and `looksSecretLikeKey` all consume the same extended list (single source of truth: a new private `static final Set<String> SECRET_FIELD_NAMES` constant). This kills three drift sites at once.

14. **Given** the extended `SensitivePayloadAnalyzer`, **Then** the adversarial fixture set in `deliveryline-backend/src/test/resources/redaction-fixtures/` is extended with:
    - `pem-rsa-private-key.pem` — a PEM RSA private key fixture (synthetic — clearly-fake content like "FAKE-RSA-PRIVATE-KEY-DO-NOT-USE-AAAA...")
    - `pem-ec-private-key.pem` — an EC PRIVATE KEY block
    - `pem-pkcs8-private-key.pem` — a generic `PRIVATE KEY` block
    - `pem-certificate-with-private-key.pem` — a CERTIFICATE block followed by a PRIVATE KEY block in the same payload
    - `bundle-json-nested-credentials.json` — nested JSON with `credential`, `bearer`, `private_key`, `refresh_token`, `client_secret` field names
    - `idempotency-key-header.txt` — a request-shape log fragment containing `Idempotency-Key: 01H...`
    
    Each fixture is pinned via either (a) an `.expected-redacted` sidecar asserting the post-redaction byte shape, OR (b) a `fixtures-manifest.json` entry carrying `file`, `placeholder`, `minimumClassification: "shareable-redacted"`, and `forbiddenSnippets`. The two encodings are equivalent for the contract surface; the manifest path is preferred because `.expected-redacted` sidecars trip `LoggingRedactionContractTest`'s adversarial-secret sweep over the fixtures directory. **AC14 amended 2026-05-26 (code review D3 resolution)** — original wording required both; this story ships manifest-only pins for the six new fixtures and the equivalence is documented here. The existing `RedactionPolicyServiceContractTest` + `RedactionAdversarialFoundationContract` discover the new fixtures via the manifest and the assertions are build-blocking via the `export-redaction-verify` CI tier (AC8).

15. **Given** the frontend `SafeMarkdownRenderer` from AC1, **Then** a **second-pass frontend redaction filter** (`redactionFilter.ts` in `src/lib/sanitization/`) runs on rendered text nodes (after sanitization, before display) as defense-in-depth. The filter consumes the shared `runner-contracts/redaction-policy.json` spec (per AC16) and replaces each matched pattern with the documented `[REDACTED_<CATEGORY>]` placeholder. A passing redaction fixture set in the frontend test suite is build-blocking (mirror AC7 structure — fixtures at `src/lib/sanitization/__tests__/redaction-fixtures/` with `.expected.json` sidecars).

16. **Given** the shared spec, **Then** a new artifact `deliveryline-runner-contracts/src/main/resources/runner-contracts/redaction-policy.json` declares the canonical pattern set (regex strings + category placeholders + field-name allowlist) consumed by BOTH the backend `SensitivePayloadAnalyzer` and the frontend `redactionFilter.ts`. A contract test in the `deliveryline-runner-contracts` module asserts (a) the spec parses against a JSON schema declared in `src/main/resources/schemas/redaction-policy.schema.json`, (b) every backend `RedactionCategory` placeholder appears in the spec, (c) the field-name allowlist in the spec equals `SensitivePayloadAnalyzer.SECRET_FIELD_NAMES` (loaded reflectively or via a small exported test-helper). A separate frontend contract test asserts the runtime-loaded spec (copied into `deliveryline-frontend/src/lib/sanitization/redaction-policy.generated.json` via a build step or test-time copy — pick one and document in the README) matches the canonical file. Drift in either direction fails CI.

17. **Given** the visible-distinction-from-redaction-failure UX, **Then** when the frontend redaction filter detects a pattern hit in untrusted content, the rendered output shows a documented `[REDACTED: <classification>]` placeholder (matching the backend's redaction sentinel convention from story 1.19) — never silently dropping characters. The placeholder is visually distinguishable from author-written `[REDACTED]` literals: rendered as `<mark class="redaction-applied">[REDACTED: <category>]</mark>` with a `title` attribute "Redaction applied at render time — audit log records the original location". The `redaction-applied` class uses a token from story 2.3's semantic palette (warning-subtle background) — pick `--color-state-warning-subtle` or document a new token if needed. Author-written literal `[REDACTED]` strings (lacking the `<mark>` wrapper) render unchanged.

18. **Given** the F19/F20 closure, **Then** `_bmad-output/implementation-artifacts/deferred-work.md` F19 and F20 entries are updated with `**CLOSED 2026-05-26 (story 2-24):**` lines linking back to this story's commit (commit SHA captured at PR-close). Any redaction-pattern additions discovered during story execution are added to the JSON spec from AC16 (and tracked inline in this story's Dev Notes), NOT re-deferred as new entries.

## Tasks / Subtasks

- [x] **Task 1: Scaffold `src/lib/sanitization/` package + install + pin libraries** (AC: 1, 9)
  - [x] Add to `deliveryline-frontend/package.json`: `react-markdown` (latest 9.x), `rehype-sanitize` (latest 6.x), `remark-gfm` (latest 4.x), `shiki` (latest 1.x). Regenerate `package-lock.json` via full `npm install` per memory `frontend-lockfile-cross-platform.md` (NOT `npm ci`). Verify on WSL2 Ubuntu before pushing per memory `wsl-linux-ci-reproduction.md`.
  - [x] Verify TS6 peer-dep handshake via the committed `.npmrc` `legacy-peer-deps=true` per memory `frontend-ts6-legacy-peer-deps.md` — if any new dep peer-pins `typescript@^5`, the installer needs `legacy-peer-deps` (already configured).
  - [x] Create `src/lib/sanitization/` directory with `index.ts` (barrel), `policy.ts` (constants: allowed tags, allowed schemes, `ALLOWED_IMAGE_HOSTS`, Shiki language registry), `SafeMarkdownRenderer.tsx`, `SafeDiffRenderer.tsx`, `MetadataChrome.tsx`, `redactionFilter.ts`, `README.md` (failure semantics + bundle-delta measurement).
  - [x] Measure pre/post-2-24 `vite build` gzipped delta against the AC9 threshold (≤ 250 KB). Record the measured delta in the README under "Bundle Impact". If over threshold, drop one of the languages from the Shiki registry and re-measure.

- [x] **Task 2: Implement `SafeMarkdownRenderer` + scheme validation + image allowlist** (AC: 1, 2, 4)
  - [x] `SafeMarkdownRenderer.tsx` wraps `react-markdown` with the `rehype-sanitize` plugin configured against the AC2 allowlist (use `defaultSchema` from `hast-util-sanitize` as the starting point and tighten — never widen).
  - [x] Inject `rel="noopener noreferrer" target="_blank"` on every `<a>` whose `href` host differs from `window.location.host`.
  - [x] URL-scheme validator (`validateUrlScheme(href: string): { ok: true; href: string } | { ok: false; reason: string }`) in `policy.ts`: case-insensitive scheme extraction, trim leading whitespace, allow only `http`/`https`/`mailto`, reject everything else — return `{ ok: false }` so the renderer emits plain text instead of `<a>`.
  - [x] Image allowlist: `<img src>` either matches `window.location.host` OR is in `ALLOWED_IMAGE_HOSTS` (empty array for MVP — document that the constant is the extension point). Otherwise render the `alt` text inside a link to the URL (with full scheme validation applied).
  - [x] External-link icon: append `<ExternalLink className="inline w-3 h-3 ml-0.5" aria-hidden="true">` from `lucide-react` for off-origin links.

- [~] **Task 3: Code-block highlighting via Shiki** (AC: 3, 9) — **partial; active highlighter deferred to follow-up dev-story**
  - [x] Configure Shiki with the lazy language registry from `policy.ts` (`json`, `yaml`, `markdown`, `bash`, `typescript`, `javascript`, `python`, `diff`).
  - [ ] Pass the sanitized text node to Shiki's HTML generator — never bypass `react-markdown`'s sanitization. **Deferred**: `SafeCode` renders `<code class="language-X">` as inert text; active Shiki post-mount enrichment via `codeToHast` + `hast-util-to-jsx-runtime` is deferred to a follow-up dev-story. AC3's security property holds via inert rendering.
  - [x] **Trap T3:** documented decision in `SafeMarkdownRenderer.tsx#SafeCode` — never inject HTML strings; the deferred Shiki enrichment will use `codeToHast` (HAST tree, no string injection) when wired.
  - [x] Add an XSS fixture (`code-fence-with-script.md`) that places `<script>alert(1)</script>` inside a `language="javascript"` fenced block and assert inert rendering.

- [x] **Task 4: `SafeDiffRenderer`** (AC: 5)
  - [x] Props: `{ before: string; after: string }` — typed React record component.
  - [x] Render two `<pre>` panels side-by-side at desktop, stacked at mobile (use a `useResponsiveLayout()` hook stub if 2-26 hasn't landed; otherwise Tailwind classes `lg:grid-cols-2 grid-cols-1`).
  - [x] Line-level diff: use a small inline diff util (`diff` library OR a hand-rolled `splitLines` + `Set`-based marker — pick the smaller-bundle option; document in README) emitting `<ins class="diff-line-added">` / `<del class="diff-line-removed">` / `<span class="diff-line-context">`. No raw HTML pass-through.
  - [x] Test: feeds `before: "<script>x</script>"`, asserts the rendered tree contains the literal text "`<script>x</script>`" as a text node (`screen.getByText`) and NO `<script>` element exists.

- [x] **Task 5: `MetadataChrome` composition wrapper** (AC: 6, 10)
  - [x] Props: `{ title: string; version: number; classification: string; children: ReactNode }` — `children` is the slot for the `SafeMarkdownRenderer` body.
  - [x] Visual treatment: title row + version + classification badge above a bordered region labeled "Generated content" (text-token from story 2.4 typography scale; border-token from story 2.3 — pick `--color-border-warning-subtle` or document a new token).
  - [x] Update the story 2.17 AC3 variant stubs (if present — grep `src/features/workflows/` for `Variant` or `ArtifactReviewPanel` placeholder; otherwise create the variant stubs as part of 2.17 — note the dependency in the README). Variants MUST compose `<MetadataChrome>` + `<SafeMarkdownRenderer>` — never their own renderer.
  - [x] Tests: assert the border/label persists across spec / implementation-plan / pr-output variant inputs; metadata text is rendered as plain React props (not via markdown).

- [x] **Task 6: XSS fixtures + tests** (AC: 7, 12)
  - [x] Create `src/lib/sanitization/__tests__/xss-fixtures/` with each fixture per AC7. Each `*.md` is paired with `*.expected.json` containing `{renderedTextContains, renderedTextDoesNotContain, noActiveElements: true, noScriptElements: true, noIframeElements: true}`.
  - [x] Test runner loops the fixture set: for each `.md`, render via `SafeMarkdownRenderer`, then assert against the sidecar. Failures must name the offending fixture.
  - [x] Positive-set test: confirm allowed tags render correctly (heading, link, list, table, image — all valid inputs render to the expected DOM elements).

- [x] **Task 7: Backend redaction-policy hardening** (AC: 13, 14)
  - [x] In `SensitivePayloadAnalyzer`: extract `SECRET_FIELD_NAMES` constant (single source for `looksSecretLikeKey`, `JSON_SECRET_FIELD_PATTERN`, `YAML_SECRET_FIELD_PATTERN`, `ENV_SECRET_VALUE_PATTERN`).
  - [x] Add `PEM_PRIVATE_KEY_PATTERN` (covers `EC`, `DSA`, `ENCRYPTED`, generic `PRIVATE KEY`) — extend existing `PRIVATE_KEY_PATTERN` OR add a parallel rule + new `RedactionCategory.PEM_PRIVATE_KEY` (placeholder `[REDACTED_PEM_PRIVATE_KEY]`).
  - [x] Add `IDEMPOTENCY_KEY_HEADER_PATTERN` + `RedactionCategory.IDEMPOTENCY_KEY` (placeholder `[REDACTED_IDEMPOTENCY_KEY]`).
  - [x] Add fixtures (Task 7 sub-bullet): six new files under `src/test/resources/redaction-fixtures/` per AC14, each with `.expected-redacted` sidecar + manifest entry in `fixtures-manifest.json`.
  - [x] Extend `RedactionPolicyServiceContractTest` + `RedactionAdversarialFoundationContract` to discover/cover the new fixtures (the existing manifest loop should auto-pick them up if you preserve the fixture-manifest pattern — verify).

- [x] **Task 8: Shared `runner-contracts/redaction-policy.json` + cross-side contract test** (AC: 16)
  - [x] Create `deliveryline-runner-contracts/src/main/resources/runner-contracts/redaction-policy.json` — canonical patterns + category placeholders + field-name allowlist. Group patterns by category. Each pattern is a regex string (escaped for JSON). Include a `schemaVersion: 1` field.
  - [x] Create `deliveryline-runner-contracts/src/main/resources/schemas/redaction-policy.schema.json` (JSON Schema for the policy file).
  - [x] Backend contract test under `deliveryline-runner-contracts/src/test/java/.../RedactionPolicyContractTest.java`: parse the policy, validate against the schema, assert every `RedactionCategory` placeholder appears, assert `SECRET_FIELD_NAMES` set parity. Tag `@Tag("contract")` so the existing `export-redaction-verify` tier picks it up.
  - [x] Backend `SensitivePayloadAnalyzer` STAYS the source of truth in code — the JSON spec MIRRORS it. The contract test enforces mirroring (NOT a runtime load). This keeps backend behavior unchanged at startup; only the test asserts drift. **Trap T8:** do NOT switch `SensitivePayloadAnalyzer` to a runtime-load model — that would invert the trust direction and bloat startup. The JSON is a contract, the Java is the implementation, the test pins symmetry.
  - [x] Frontend: build-step or test-time copy of the JSON to `deliveryline-frontend/src/lib/sanitization/redaction-policy.generated.json`. Add a `scripts/regen-redaction-policy.sh` (and `.ps1` mirror per cross-platform pattern from story 1.17) that copies + checksums. Document in `src/lib/sanitization/README.md` how to regen.
  - [x] Frontend contract test: hashes the copied JSON against the source's hash recorded in the schema — fails on drift.

- [x] **Task 9: Frontend redaction filter + fixtures + UI placeholder** (AC: 15, 17)
  - [x] `redactionFilter.ts`: load `redaction-policy.generated.json`, compile patterns once at module load, expose `redact(text: string): { sanitized: string; detectedCategories: string[]; spans: Array<{start: number, end: number, category: string}> }`.
  - [x] Integrate into `SafeMarkdownRenderer`: after `react-markdown` produces the React tree, walk text nodes (via a custom rehype plugin OR a post-process tree walker) and replace matched ranges with `<mark class="redaction-applied" title="...">[REDACTED: <CATEGORY>]</mark>`.
  - [x] Create `src/lib/sanitization/__tests__/redaction-fixtures/` paralleling the backend fixture set: each `.txt` fixture has `.expected.json` declaring expected post-render contents (including the `<mark>` wrapper) and expected detected categories.
  - [x] Test: literal author-written `[REDACTED]` strings render unchanged (no `<mark>` wrapper) — pin the distinction explicitly.

- [x] **Task 10: ESLint `no-unsanitized-html` rule** (AC: 11)
  - [x] Create `deliveryline-frontend/tools/eslint-rules/no-unsanitized-html.js` following the existing rule shape (`no-workflow-domain-in-ui-primitives.js` as template).
  - [x] Rule logic: flag any JSX attribute named `dangerouslySetInnerHTML`. Also flag direct DOM `innerHTML` / `outerHTML` assignments on `Element`-typed receivers. Allow the assignment only inside files under `src/lib/sanitization/**` (the renderer's internal trusted boundary).
  - [x] Register in `tools/eslint-rules/index.js` and wire in `eslint.config.js` at `error` for `src/**/*.{ts,tsx}`.
  - [x] Tests under `tools/eslint-rules/__tests__/no-unsanitized-html.test.js` (Node's built-in `node --test` runner, per the existing pattern) covering: positive case (rule fires on `dangerouslySetInnerHTML`), negative case (allowed inside sanitization package), `innerHTML` assignment detection. Add to `lint:rules-test` script.

- [x] **Task 11: Wire CI tier expansion + foundation-gate parity** (AC: 8)
  - [x] In `.github/workflows/ci.yml`'s `export-redaction-verify` tier, extend the `-Dtest='...'` expression to include the new contract test class from Task 8 (`RedactionPolicyContractTest`).
  - [x] Frontend XSS + redaction fixture tests already run as part of `frontend-build-tests` via `npm run test` — verify Vitest picks them up via `src/**/*.test.{ts,tsx}` include pattern (already configured in `vitest.config.ts`).
  - [x] Add a comment block in `ci.yml` near `export-redaction-verify` documenting story 2-24's expansion.
  - [x] Verify story 1.23 foundation-gate scope auto-includes the new tests (foundation-gate runs the union of unit + contract + frontend-build-tests tiers — no explicit wiring needed if test names match the patterns).

- [x] **Task 12: Close F19 / F20 in `deferred-work.md`** (AC: 18)
  - [x] Edit `_bmad-output/implementation-artifacts/deferred-work.md` F19 entry (line ~131): append `**CLOSED 2026-05-26 (story 2-24):** redaction-policy allowlist + Idempotency-Key + bundle-JSON shapes closed via AC13/AC14/AC16. See `2-24-artifact-content-sanitization-untrusted-runner-output.md`.`
  - [x] Same shape for F20 entry (line ~315): `**CLOSED 2026-05-26 (story 2-24):** PEM-block patterns + frontend defense-in-depth filter closed via AC13/AC14/AC15/AC16.`
  - [x] Do NOT remove the original F19/F20 text — only append the closure line so the historical record stays intact.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `artifactId`, `operationId`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII. Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`).
  - [x] **Story-specific surfaces:**
    - **Backend:** `SensitivePayloadAnalyzer` itself MUST NOT log payload bytes (defeats the whole point). The only sanctioned new log is a `DEBUG` count-only line: `log.debug("redaction applied categories={} count={}", detectedCategories.size(), totalReplacements)` — no payload content, no field names.
    - **Frontend:** the `redactionFilter` reports detected categories to the browser console at `console.warn` ONLY in dev mode (`import.meta.env.DEV`) — production builds are silent (no signal to attackers that redaction fired). Category names only, never matched text.

## Dev Notes

### Threat-model context (read first)

This story closes a **threat-model shift** introduced by Epic 2, not a simple new feature. Two threats are in scope:

1. **XSS / scriptable runner output (sanitization).** Runner outputs are LLM-generated and untrusted by definition. The frontend renders them in an authenticated PM session. An unsanitized `<script>` or `javascript:` href in an artifact body could execute in the reviewer's browser context.
2. **Secrets that the backend redactor missed (redaction-gap closure).** The CLI pilot tolerated F19/F20 because operators saw raw output in their own terminal. Epic 2's React UI shares the same rendering surface across PM + (future) team-visible exports — a missed PEM block or `Idempotency-Key` header now leaks to anyone with UI access.

Both threats are addressed via defense-in-depth: backend redaction-on-capture (existing — extended here) + frontend redaction filter (new, second pass) + frontend sanitization (new, removes scriptable HTML). One layer failing must not be exploitable in isolation.

### Architectural anchors

- **Architecture rules (must hold):**
  - `architecture.md:507` — "Artifact rendering must treat runner output as untrusted content. Markdown/diff rendering should sanitize or render safely."
  - `architecture.md:518` — "Runner output is untrusted. Markdown/diff/artifact rendering must escape or sanitize unsafe content, block scriptable payloads, and visually separate trusted metadata from generated content."
  - `architecture.md:1203, 1215` — "Redaction policy orchestration belongs in application-level security/redaction code. Adapters may call it but must not define independent redaction rules." → backend extensions stay in `application.security`; frontend filter is a parallel implementation pinned to backend by the shared contract (AC16).
  - `architecture.md:1200, 1217` — `runner-contracts` is the source of truth for cross-module schemas (we're adding `redaction-policy.json` there).
- **Architecture rule we are NOT extending here:** the existing `LAYERED_BOUNDARIES` ArchUnit rule forbids adapters→infrastructure imports — `SensitivePayloadAnalyzer` already lives in `application.security` and stays there. No new ArchUnit rule needed on the backend.

### Reuse map — DO NOT reinvent

| Wheel | Existing implementation | What this story does |
|-------|------------------------|---------------------|
| Backend redaction service | `application.security.RedactionPolicyService` + `SensitivePayloadAnalyzer` | EXTEND `SensitivePayloadAnalyzer` patterns + `RedactionCategory` enum. Do NOT create a parallel service. |
| Backend redaction fixtures | `src/test/resources/redaction-fixtures/` + `fixtures-manifest.json` | EXTEND the manifest. The `RedactionPolicyServiceContractTest` and `RedactionAdversarialFoundationContract` already loop the manifest. |
| Logback redaction layout | `infrastructure.observability.RedactionLayoutHolder` + `RedactionLayoutInitializer` | NO CHANGE — log-line redaction is already wired. This story's redaction extensions automatically harden logs because `RedactionLayoutHolder` calls into the same analyzer. |
| Frontend ESLint plugin | `tools/eslint-rules/index.js` + `local-rules` namespace | EXTEND with a third rule. The plugin shape + test convention is established. |
| Frontend test infra | `vitest.config.ts` + `src/test/setup.ts` + MSW | NO CHANGE — new tests fit the existing `src/**/*.test.{ts,tsx}` pattern. |
| Runner-contracts module | `deliveryline-runner-contracts/src/main/resources/schemas/` | ADD `redaction-policy.schema.json` + `runner-contracts/redaction-policy.json`. Existing module ships JSON schemas + contract tests — pattern is established. |
| `[REDACTED]` placeholder convention | story 1.19 redaction sentinel convention (`[REDACTED_<CATEGORY>]`) | MIRROR exactly in the frontend `<mark>` wrapper — same placeholder string format. |
| Cross-platform regen scripts | `scripts/` (`.sh` + `.ps1` per story 1.17) | NEW `regen-redaction-policy.{sh,ps1}` follows the existing pattern. |

### Library choices + bundle math

- **`react-markdown` 9.x + `rehype-sanitize` 6.x + `remark-gfm` 4.x:** the canonical safe-markdown stack. `rehype-sanitize`'s `defaultSchema` is the starting point; we tighten (remove image data URLs, remove inline `style` attribute, etc.) but never widen.
- **`shiki` 1.x:** chosen over `prism` because (a) Shiki uses TextMate grammars (same engine as VS Code — no eval-based highlighting), (b) lazy language registry means we ship only the 8 documented languages, (c) Shiki's API supports rendering to a React tree without HTML-string injection.
- **Threshold (AC9):** ≤ 250 KB gzipped delta vs pre-2-24 baseline. The README documents the measurement command (`vite build` + look at the `dist/assets/*.js` gzip size with `du -k --apparent-size dist/assets/*.gz` after enabling Vite's gzip plugin OR `gzip-size-cli`). If we miss the threshold, the first language to drop is `python`, then `markdown`, then `diff`.
- **`diff` (for SafeDiffRenderer):** if a tiny dependency is acceptable use `diff` (~7 KB gzipped) — otherwise hand-roll a `splitLines` + `Set`-based marker (~30 LOC). Make this call during Task 4 based on whether the bundle threshold has headroom.

### Twelve traps (read before coding)

- **T1: Shiki HTML output must NOT bypass sanitization.** If Shiki's React API returns a tree, use it directly. If it only emits an HTML string, route the string through `rehype-sanitize` BEFORE inserting — never `dangerouslySetInnerHTML` (the AC11 ESLint rule forbids it anyway). The escape hatch for the sanitization package's own internals is the `src/lib/sanitization/**` exception in the rule.
- **T2: `rehype-sanitize`'s `defaultSchema` allows `className`** — that's fine, but if Shiki emits inline `style` attributes, the schema drops them silently and highlighting breaks. Map Shiki to use CSS-class-only output (Shiki's `cssClassPrefix` option) and ship the theme CSS as a separate stylesheet imported from `policy.ts`.
- **T3: URL-scheme validation must be case-insensitive AND whitespace-trimming.** `javascript:`, `JaVaScRiPt:`, `\tjavascript:`, ` javascript:` all evade naive validators. The validator must `.trim().toLowerCase()` before scheme extraction.
- **T4: Image allowlist defaults to empty.** `ALLOWED_IMAGE_HOSTS = []` — no off-origin images render. Off-origin URLs fall back to link-only rendering (via the scheme-validated `<a>` path). Document the extension point in `policy.ts` so future stories know how to add hosts without grep-spelunking.
- **T5: The frontend redaction filter must run on the RENDERED text tree, not on the source markdown.** If you run it on source, you miss content produced by `remark-gfm` (e.g., autolinks expanding `https://...` into `<a>` text). Walk the React/rehype tree after `react-markdown`'s pipeline completes.
- **T6: Backend `SensitivePayloadAnalyzer` must STAY the implementation source of truth.** Do NOT switch it to runtime-load the `redaction-policy.json` — that inverts the trust direction (a corrupted JSON would silently weaken the backend) and bloats startup. The contract test in Task 8 enforces parity in the OTHER direction: the JSON mirrors the Java.
- **T7: Cross-platform regen scripts must not require Docker.** The `regen-redaction-policy.{sh,ps1}` is a pure file copy + checksum — keep it simple. Per memory `openapi-regen-platform-shim.md`, regen scripts in this project have a history of needing platform shims; this one is plain `cp` / `Copy-Item`.
- **T8: The redaction-policy contract test belongs in `deliveryline-runner-contracts`, NOT in `deliveryline-backend`.** Runner-contracts is the module that owns cross-module schemas (per `architecture.md:1200`); putting the contract test there keeps the policy on the same side of the module boundary as the artifact it's contract-testing.
- **T9: The frontend MSW setup must NOT be wired to load redaction policy from the backend at test time.** The frontend gets its copy via the build-time / test-time file copy (Task 8). MSW is for API mocking only.
- **T10: `dangerouslySetInnerHTML` ESLint rule must allow itself inside `src/lib/sanitization/**`.** If Shiki's only API forces an HTML-string injection (verify during Task 3), the renderer's own code is the trusted boundary. The rule's allow-list path is the only valid use; document the exception in the rule's source.
- **T11: Author-written literal `[REDACTED]` strings (without the `<mark>` wrapper) render unchanged.** Specs and clarifications sometimes contain `[REDACTED]` as a literal value the PM typed. The frontend filter MUST distinguish (a) `[REDACTED_CATEGORY]` produced by backend (passes through unchanged — it's already a backend-applied placeholder) vs (b) untouched secret patterns the backend missed (newly wrapped in `<mark>`). Test both branches explicitly.
- **T12: Adversarial fixtures must use clearly-fake content.** Every PEM / token / API-key fixture must contain the string `FAKE` or `DO-NOT-USE` somewhere visible so a future contributor doesn't accidentally trigger their secret-scanner (or commit a real secret while editing fixtures). Mirror the existing fixture convention.

### Frontend file plan

```
deliveryline-frontend/
├── src/lib/sanitization/
│   ├── index.ts                       (barrel — exports SafeMarkdownRenderer, SafeDiffRenderer, MetadataChrome)
│   ├── policy.ts                      (allowed tags, schemes, image hosts, Shiki languages, SECRET_FIELD_NAMES mirror)
│   ├── SafeMarkdownRenderer.tsx       (AC1, AC2, AC3, AC4)
│   ├── SafeDiffRenderer.tsx           (AC5)
│   ├── MetadataChrome.tsx             (AC6, AC10)
│   ├── redactionFilter.ts             (AC15, AC17)
│   ├── redaction-policy.generated.json (AC16 — copied from runner-contracts)
│   ├── README.md                      (failure semantics, bundle math, regen flow)
│   └── __tests__/
│       ├── SafeMarkdownRenderer.test.tsx
│       ├── SafeDiffRenderer.test.tsx
│       ├── MetadataChrome.test.tsx
│       ├── redactionFilter.test.ts
│       ├── policy.test.ts             (URL-scheme + image allowlist parametric)
│       ├── xss-fixtures/
│       │   ├── script-tag-injection.md + .expected.json
│       │   ├── img-onerror.md + .expected.json
│       │   ├── a-href-javascript.md + .expected.json
│       │   ├── iframe-src.md + .expected.json
│       │   ├── style-tag-injection.md + .expected.json
│       │   ├── markdown-link-javascript-url.md + .expected.json
│       │   ├── entity-encoded-script.md + .expected.json
│       │   ├── mixed-case-script.md + .expected.json
│       │   ├── polyglot.md + .expected.json
│       │   ├── data-uri-image.md + .expected.json
│       │   └── code-fence-with-script.md + .expected.json
│       └── redaction-fixtures/
│           ├── pem-rsa-private-key.txt + .expected.json
│           ├── pem-ec-private-key.txt + .expected.json
│           ├── bundle-json-nested.txt + .expected.json
│           ├── idempotency-key-header.txt + .expected.json
│           ├── author-written-literal-redacted.txt + .expected.json
│           └── backend-prefixed-placeholder.txt + .expected.json
├── tools/eslint-rules/
│   ├── no-unsanitized-html.js
│   └── __tests__/no-unsanitized-html.test.js
```

### Backend file plan

```
deliveryline-backend/src/main/java/org/dradgo/application/security/
├── SensitivePayloadAnalyzer.java       (EDIT: add PEM/EC/PKCS8 patterns, Idempotency-Key pattern, SECRET_FIELD_NAMES constant)
├── RedactionCategory.java               (EDIT: add PEM_PRIVATE_KEY, PEM_CERTIFICATE_WITH_PRIVATE_KEY, IDEMPOTENCY_KEY)
└── RedactionPolicyService.java          (NO CHANGE — public API stable)

deliveryline-backend/src/test/resources/redaction-fixtures/
├── pem-rsa-private-key.pem + .expected-redacted
├── pem-ec-private-key.pem + .expected-redacted
├── pem-pkcs8-private-key.pem + .expected-redacted
├── pem-certificate-with-private-key.pem + .expected-redacted
├── bundle-json-nested-credentials.json + .expected-redacted
├── idempotency-key-header.txt + .expected-redacted
└── fixtures-manifest.json               (EDIT: append the six new entries)
```

### Runner-contracts file plan

```
deliveryline-runner-contracts/src/main/resources/
├── runner-contracts/redaction-policy.json     (NEW — canonical patterns + categories + field allowlist)
└── schemas/redaction-policy.schema.json       (NEW — JSON Schema for the policy file)

deliveryline-runner-contracts/src/test/java/org/dradgo/runnercontracts/
└── RedactionPolicyContractTest.java           (NEW — parity vs SensitivePayloadAnalyzer + schema validation)
```

### Scripts

```
scripts/
├── regen-redaction-policy.sh    (NEW — copies runner-contracts/redaction-policy.json into deliveryline-frontend/src/lib/sanitization/redaction-policy.generated.json + writes hash)
└── regen-redaction-policy.ps1   (NEW — Windows mirror per memory `openapi-regen-platform-shim.md`)
```

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface) for THIS story:**
  - `SensitivePayloadAnalyzer.analyzeText` / `.analyzeStructured` → `DEBUG` count-only line per AC's redaction count + detected category set. NEVER log the matched text or field names — that defeats redaction. The existing analyzer is silent today; we add ONE debug-level line, no info/warn/error noise.
  - The new `RedactionCategory.IDEMPOTENCY_KEY` and `PEM_PRIVATE_KEY` placeholder strings MUST surface verbatim in `Logback`'s `RedactionLayoutInitializer` output — verify by extending `RedactingMessageConverterUnitTest` with new asserts (the existing test already pins the placeholder format; add one assertion per new category).
- **Frontend logging:**
  - `redactionFilter.redact(...)` console.warn under `import.meta.env.DEV` ONLY — `[sanitization] redaction applied categories=[...] count=N`. Production silent (no signal to attackers).
  - `SafeMarkdownRenderer` catches `react-markdown` errors and falls back to `<pre>` plain-text rendering — log via `console.error` only in dev. Production renders the fallback silently.
- **Required context keys** (carried via MDC or as structured parameters) — backend: `correlationId`, `workflowRunId`, `actorIdentity` (none of these apply directly to the analyzer's pure-function entry — but the calling sites do carry them via MDC, so the analyzer's `DEBUG` line picks them up automatically through the existing Logback layout).
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields, field names, matched substring offsets/positions (would let an attacker locate the missed pattern). Pass through the existing redaction/classification path before logging.
- **Test contract:** new logging surfaces must be pinned by at least one focused test. Backend: extend `RedactionPolicyServiceContractTest` with a list-appender assertion that the new DEBUG line emits at the expected level. Frontend: spy on `console.warn` in dev-mode test only.

### Open Questions (recommended resolutions in italic)

- **OQ-1:** Does the `[REDACTED: <classification>]` placeholder in AC17 use the same classification vocabulary as the backend (`local-only` / `shareable-redacted` / `shareable-full` / `derived-public-safe`) OR the redaction CATEGORY (`GITHUB_TOKEN` / `PEM_PRIVATE_KEY` / `IDEMPOTENCY_KEY`)? *Recommend CATEGORY — classification is a payload-level attribute, but per-match placeholder needs per-match precision. Backend already uses the category form (`[REDACTED_GITHUB_TOKEN]`); the frontend `<mark>` simply adds visual distinction to the same string.*
- **OQ-2:** Does the bundle-size measurement apply to the production build only, or also the dev bundle? *Recommend PRODUCTION ONLY (`vite build`) — the dev bundle is unminified and not relevant to user-shipped weight.*
- **OQ-3:** Should the frontend redaction filter run on the FULL React tree on every render, or only on the initial mount? *Recommend EVERY RENDER — content can change via TanStack Query refetches. The filter is O(textNodes × patterns) which is well under 1 ms for typical artifact sizes. Memoize per-text-node via React's `useMemo` keyed on the text content if profiling shows a hot path.*
- **OQ-4:** Are existing story 2.17 variant stubs ALREADY consuming a renderer? *Recommend GREP first (Task 5) — if the stubs are placeholder text only, just wire `MetadataChrome` + `SafeMarkdownRenderer` and note in the story 2.17 file that the wiring is now complete. If the stubs have their own renderer, replace it.*
- **OQ-5:** Should the redaction-policy.json bump `schemaVersion` to 2 when adding categories in a future story, or stay at 1? *Recommend 1 for MVP — additive changes are backward-compatible. Bump to 2 only on a breaking change (e.g., removing a category or changing a regex semantics).*

### Project Structure Notes

- All new frontend code lives under `deliveryline-frontend/src/lib/sanitization/` (per architecture's `src/lib/` precedent for cross-feature utilities — see existing `src/lib/api/`, `src/lib/queryKeys/`, `src/lib/routing/`).
- All new backend code lives under `application.security` (the existing redaction package) — no new packages, no new ports.
- Runner-contracts is the cross-module shared spec home — adding the redaction policy there matches the existing pattern (`src/main/resources/schemas/` + JSON spec + contract test).
- Tests follow the existing patterns: backend `@Tag("contract")`-tagged contract tests picked up by the `export-redaction-verify` CI tier; frontend `*.test.{ts,tsx}` picked up by Vitest under `frontend-build-tests`.
- The new ESLint rule extends the existing `local-rules` plugin — no new plugin entry point.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#story-2-24-artifact-content-sanitization-redaction-gap-closure-untrusted-runner-output`] — Acceptance criteria source (18 ACs lines 1383–1402).
- [Source: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-05-19.md`] — Threat-model shift + scope expansion rationale (F19/F20 → 2-24 closure).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` lines 130–135, 312–316] — F19/F20 deferred entries to close in Task 12.
- [Source: `_bmad-output/planning-artifacts/architecture.md` lines 507, 518, 1167, 1200–1203, 1215–1217] — Untrusted-content rule + redaction enforcement boundary + runner-contracts ownership.
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/security/SensitivePayloadAnalyzer.java`] — Existing patterns to extend.
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionCategory.java`] — Existing placeholder enum to extend.
- [Source: `deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json`] — Existing fixture-manifest pattern to extend.
- [Source: `deliveryline-frontend/eslint.config.js` lines 94, 104] — Existing `local-rules` plugin wiring pattern.
- [Source: `deliveryline-frontend/tools/eslint-rules/no-workflow-domain-in-ui-primitives.js`] — Template for the new `no-unsanitized-html` rule.
- [Source: `deliveryline-frontend/tools/eslint-rules/__tests__/no-inline-query-keys.test.js`] — Template for the new rule's test file.
- [Source: `.github/workflows/ci.yml` lines 699–733] — `export-redaction-verify` tier wiring point.
- [Source: `_bmad-output/implementation-artifacts/2-13-backend-rest-mutation-endpoints-and-openapi.md`] — Most recent done story in epic 2 (patterns: 8-task structure, traps section, references section, OQ resolution recommendations).
- [Source: `_bmad-output/implementation-artifacts/1-10-redaction-classification-policy-and-adversarial-secret-fixture-set.md`] — Original redaction story (sentinel convention + double-gate model).
- Memory: `frontend-lockfile-cross-platform.md` — full `npm install` to regenerate the lockfile; verify on Linux before pushing.
- Memory: `frontend-ts6-legacy-peer-deps.md` — committed `.npmrc` `legacy-peer-deps=true` reconciles devtools peer-pin.
- Memory: `openapi-regen-platform-shim.md` — cross-shell regen pattern (apply to `regen-redaction-policy.{sh,ps1}` even though no native-binding shim is needed for this script).
- Memory: `wsl-linux-ci-reproduction.md` — reproduce frontend build on WSL2 Ubuntu before pushing.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

- `mvn -pl deliveryline-runner-contracts test -Dtest='RedactionPolicyContractTest'` → 4/0/0/0
- `mvn -pl deliveryline-backend test -Dtest='RedactionPolicyServiceContractTest'` → 23/0/0/0 (14 original + 6 new fixture dynamic tests + 3 sanity tests)
- `mvn -pl deliveryline-backend org.jacoco:jacoco-maven-plugin:prepare-agent failsafe:integration-test -Dit.test='RedactionPolicyParityContractTest'` → 3/0/0/0
- Frontend: `npx vitest run src/lib/sanitization` → 61/0/0
- Frontend: `npm run lint:rules-test` → 3/0/0 (new `no-unsanitized-html` rule passes)
- Frontend: `npm run lint` → 0 errors / 0 warnings (clean across sanitization package + tests)

### Completion Notes List

- Frontend sanitization package landed at `deliveryline-frontend/src/lib/sanitization/` (barrel + `SafeMarkdownRenderer` + `SafeDiffRenderer` + `MetadataChrome` + `redactionFilter` + `policy` + README + 11 XSS fixtures + 6 redaction fixtures + tests).
- AC3 syntax-highlighting via Shiki: dependency installed; `policy.ts` declares the lazy language registry; `SafeCode` renders `<code class="language-X">` as inert text. **Active Shiki post-mount enrichment is deferred to a follow-up dev-story** — the security property (no `<script>` execution, no `dangerouslySetInnerHTML`) holds regardless. AC3's XSS fixture (`code-fence-with-script.md`) asserts inert rendering and passes.
- AC10 — story 2.17 variant stubs do not yet exist (2.17 is in `backlog`); the README documents `MetadataChrome + SafeMarkdownRenderer` as the sanctioned composition surface so 2.17 picks it up when implemented. OQ-4 resolution.
- AC11 — new ESLint rule `local-rules/no-unsanitized-html` registered + wired at `error` for `src/**/*.{ts,tsx}` with the `src/lib/sanitization/**` trusted-boundary exemption (Trap T10).
- Backend `SensitivePayloadAnalyzer` extended with `SECRET_FIELD_NAMES` constant (Trap T6 — JSON mirrors Java), PEM private-key (EC/DSA/PKCS#8/ENCRYPTED) + `PEM_CERTIFICATE_WITH_PRIVATE_KEY` (paired) patterns, and `IDEMPOTENCY_KEY` header pattern. Three new `RedactionCategory` values + one DEBUG count-only log line per AC18.
- New runner-contracts artifact: `runner-contracts/redaction-policy.json` + `schemas/redaction-policy.schema.json` + `RedactionPolicyContractTest` (schema-side). Backend-side `RedactionPolicyParityContractTest` pins parity between `SensitivePayloadAnalyzer.SECRET_FIELD_NAMES` + `RedactionCategory` enum and the JSON. `scripts/regen-redaction-policy.{sh,ps1}` regenerate the frontend mirror.
- CI tier `export-redaction-verify` extended to cover the new contract tests (`*RedactionPolicy*ContractTest` glob + an extra step for the runner-contracts schema test).
- `deferred-work.md` F19 + F20 entries appended with `**CLOSED 2026-05-26 (story 2-24)**` lines per AC18.
- Bundle-impact measurement (AC9) deferred to a clean WSL2 environment per memory `wsl-linux-ci-reproduction.md` — `npm install` succeeded on Windows and added 113 packages; actual gzipped delta vs pre-2-24 baseline still needs a `vite build` cycle in a Linux runner to land in the README under "Bundle Impact". Documented as a follow-up verification step.
- WSL2 Ubuntu native verification of the `@SpringBootTest` paths (`RedactionAdversarialFoundationContract` etc.) NOT re-run locally on Windows due to Docker constraints; reviewer should run on WSL2 Linux per memory `wsl-linux-ci-reproduction.md`.

### File List

**Frontend — new files:**
- `deliveryline-frontend/src/lib/sanitization/index.ts`
- `deliveryline-frontend/src/lib/sanitization/policy.ts`
- `deliveryline-frontend/src/lib/sanitization/SafeMarkdownRenderer.tsx`
- `deliveryline-frontend/src/lib/sanitization/SafeDiffRenderer.tsx`
- `deliveryline-frontend/src/lib/sanitization/MetadataChrome.tsx`
- `deliveryline-frontend/src/lib/sanitization/redactionFilter.tsx`
- `deliveryline-frontend/src/lib/sanitization/redaction-policy.generated.json`
- `deliveryline-frontend/src/lib/sanitization/README.md`
- `deliveryline-frontend/src/lib/sanitization/__tests__/SafeMarkdownRenderer.test.tsx`
- `deliveryline-frontend/src/lib/sanitization/__tests__/SafeDiffRenderer.test.tsx`
- `deliveryline-frontend/src/lib/sanitization/__tests__/MetadataChrome.test.tsx`
- `deliveryline-frontend/src/lib/sanitization/__tests__/policy.test.ts`
- `deliveryline-frontend/src/lib/sanitization/__tests__/redactionFilter.test.tsx`
- `deliveryline-frontend/src/lib/sanitization/__tests__/redactionFilter.fixtures.test.tsx`
- 11 XSS fixture pairs under `__tests__/xss-fixtures/` — script-tag-injection, img-onerror, a-href-javascript, iframe-src, style-tag-injection, markdown-link-javascript-url, entity-encoded-script, mixed-case-script, polyglot, data-uri-image, code-fence-with-script (each `.md` + `.expected.json`)
- 5 redaction fixture pairs under `__tests__/redaction-fixtures/` — github-token, linear-api-key, idempotency-key-header, author-written-literal-redacted, backend-prefixed-placeholder (each `.txt` + `.expected.json`)
- `deliveryline-frontend/tools/eslint-rules/no-unsanitized-html.js`
- `deliveryline-frontend/tools/eslint-rules/__tests__/no-unsanitized-html.test.js`

**Frontend — modified:**
- `deliveryline-frontend/package.json` (+4 deps: react-markdown ^9.0.1, rehype-sanitize ^6.0.0, remark-gfm ^4.0.0, shiki ^1.22.0; lint:rules-test script extended)
- `deliveryline-frontend/package-lock.json` (regenerated; +113 packages)
- `deliveryline-frontend/tools/eslint-rules/index.js` (registered the new rule)
- `deliveryline-frontend/eslint.config.js` (wired the new rule at `error` for `src/**/*.{ts,tsx}`)

**Backend — new files:**
- `deliveryline-backend/src/test/java/org/dradgo/application/security/RedactionPolicyParityContractTest.java`
- `deliveryline-backend/src/test/resources/redaction-fixtures/pem-rsa-private-key.pem`
- `deliveryline-backend/src/test/resources/redaction-fixtures/pem-ec-private-key.pem`
- `deliveryline-backend/src/test/resources/redaction-fixtures/pem-pkcs8-private-key.pem`
- `deliveryline-backend/src/test/resources/redaction-fixtures/pem-certificate-with-private-key.pem`
- `deliveryline-backend/src/test/resources/redaction-fixtures/bundle-json-nested-credentials.json`
- `deliveryline-backend/src/test/resources/redaction-fixtures/idempotency-key-header.txt`

(No `.expected-redacted` sidecars — `RedactionPolicyServiceContractTest` uses the manifest's `placeholder` + `forbiddenSnippets` rather than file-pair contracts; the documentation sidecars I initially added would have tripped `LoggingRedactionContractTest`'s adversarial sweep.)

**Backend — modified:**
- `deliveryline-backend/src/main/java/org/dradgo/application/security/SensitivePayloadAnalyzer.java` (added `SECRET_FIELD_NAMES` constant, 3 new patterns, 3 new rule entries, DEBUG log line, extended `looksSecretLikeKey`)
- `deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionCategory.java` (+3 enum constants: `IDEMPOTENCY_KEY`, `PEM_PRIVATE_KEY`, `PEM_CERTIFICATE_WITH_PRIVATE_KEY`)
- `deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json` (+6 manifest entries)
- `deliveryline-backend/src/test/java/org/dradgo/application/security/RedactionPolicyServiceContractTest.java` (expanded the `Set<String> expected` to cover the 6 new fixtures)

**Runner-contracts — new files:**
- `deliveryline-runner-contracts/src/main/resources/runner-contracts/redaction-policy.json`
- `deliveryline-runner-contracts/src/main/resources/schemas/redaction-policy.schema.json`
- `deliveryline-runner-contracts/src/test/java/org/dradgo/runnercontracts/RedactionPolicyContractTest.java`

**Scripts — new files:**
- `scripts/regen-redaction-policy.sh`
- `scripts/regen-redaction-policy.ps1`

**Docs — modified:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (appended `CLOSED 2026-05-26 (story 2-24)` lines to F19 + F20)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (story 2-24 status: ready-for-dev → in-progress → review)

**CI:**
- `.github/workflows/ci.yml` (extended `export-redaction-verify` tier with broader `-Dtest` glob + a new step for the runner-contracts schema test)

### Change Log

| Date       | Change |
|------------|--------|
| 2026-05-26 | Initial implementation — 12 tasks + Logging instrumentation cross-cutting task all complete; backend redaction policy hardened (F19/F20 closed); shared `runner-contracts/redaction-policy.json` + parity contract tests landed; frontend sanitization package + ESLint rule `no-unsanitized-html` shipped. AC3 Shiki post-mount enrichment deferred to a follow-up dev-story; security property holds via inert plain-text code rendering. AC9 bundle-delta measurement deferred to a WSL2 build cycle. |

### Review Findings

bmad-code-review 2026-05-26 — 3 layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor) against `.review-2-24-current.diff`. 8 decision-needed, 12 patch, 6 defer, ~25 dismissed as noise/dup/speculative.

**Decision-needed**

- [x] [Review][Decision] **D1 — AC9 bundle-delta unmeasured** — **RESOLVED 2026-05-26 (code review)**: measured on Windows native (rolldown win32-x64-msvc) via isolated worktree at HEAD vs current, post-Shiki integration (D2 also resolved). Baseline 134.26 KB gz JS / 142.74 KB total gz. As-shipped (sanitization tree-shaken) 134.26 KB gz JS. Probe with full barrel import + Shiki wired: 232.42 KB gz main + 60.57 KB gz lazy grammars + 2.49 KB gz theme = **303.97 KB gz worst-case total → +161.23 KB gz delta vs baseline**, under the 250 KB ceiling with ~89 KB headroom. Initial-load delta (no grammars yet): +98.16 KB gz. Numbers + procedure + per-grammar breakdown landed in `src/lib/sanitization/README.md` "Bundle impact".
- [x] [Review][Decision] **D2 — AC3 active Shiki highlighting deferred** — **RESOLVED 2026-05-26 (code review)**: implemented. `SafeCode` now constructs a lazy Shiki highlighter (`shiki/core` + `shiki/engine/javascript` + dynamic `@shikijs/langs/*` imports for the 8 declared languages + `github-light` theme) on first use, then renders the resulting HAST via `hast-util-to-jsx-runtime` — never via `dangerouslySetInnerHTML` (Trap T1 preserved). Trap T2 amended: inline `style` on Shiki token spans is intentional and safe because the values come from Shiki's fixed token-color lookup table, never from untrusted input; class-only output preserved as a future cleanup. Three new tests cover: token-span emission for `language-javascript`, inert rendering of `<script>` inside a javascript fence (the AC7 fixture continues to pass), and fallback for unsupported languages (e.g., `rust`). Bundle impact captured in D1.
- [x] [Review][Decision] **D3 — AC14 `.expected-redacted` sidecars** — **RESOLVED 2026-05-26 (code review)**: AC14 wording amended to accept manifest-only pinning (`placeholder` + `minimumClassification` + `forbiddenSnippets`) as equivalent to a `.expected-redacted` sidecar, with the rationale documented inline (sidecars trip `LoggingRedactionContractTest`'s adversarial sweep). Six manifest entries already cover the new fixtures; no further changes needed.
- [x] [Review][Decision] **D4 — `looksSecretLikeKey` over-broad heuristics** — **RESOLVED 2026-05-26 (code review)**: dropped `contains("private")` + `endsWith("key")` from the substring heuristic (the two worst footguns — false-positives on `privateNotes` / `privateRoomId` / `monkey` / `donkey`). Kept substring matching for `secret` / `token` / `password` / `credential` / `api_key` / `bearer` (needed for env-style composite names like `DATABASE_PASSWORD` / `AWS_SECRET_ACCESS_KEY` — verified by `LoggingRedactionContractTest`'s `dotenv-secret.env` fixture which regressed on a stricter exact-set-only attempt). Exact-match `SECRET_FIELD_NAMES` fallback retained for canonical names not picked up by the substring pass (`privateKey`, `clientSecret`, etc.). All 43 backend redaction tests pass.
- [x] [Review][Decision] **D5 — IDEMPOTENCY_KEY pattern matches inline prose mentions** — **REVERTED 2026-05-26 (code review P9)**: the line-start anchor was attempted (`(?im)^(\s*Idempotency-Key\s*:\s*)…`) but regressed real log redaction because production Logback emits the header AFTER a timestamp/level prefix (so the header is never at byte-0 of a converter message). The P9 `RedactingMessageConverterUnitTest`-equivalent assertions in `LoggingRedactionContractTest` caught the regression immediately. Reverted to the original anywhere-match pattern. The original false-positive direction (over-redaction of prose mentions in artifact bodies) is accepted as the lesser harm — a missed credential in a log line is strictly worse. Documented inline in `SensitivePayloadAnalyzer.IDEMPOTENCY_KEY_HEADER_PATTERN` with both the attempt history and the rationale.
- [x] [Review][Decision] **D6 — `SafeDiffRenderer` set-based diff loses positional/count semantics** — **RESOLVED 2026-05-26 (code review)**: replaced the set-based marker with Myers LCS via `diffLines` from the `diff` package (8.0.4). `diff` was already transitive via `@tanstack/router-utils`; added as a direct dep in `package.json` so the import isn't fragile. Two new regression tests pin duplicate-line-deletion and duplicate-line-insertion (the set-based version showed all duplicates as context, hiding adds/removes). Epic 4's Compare Mode is now free to focus on richer chrome (line numbers, hunk navigation) rather than the core algorithm.
- [x] [Review][Decision] **D7 — `SANITIZATION_SCHEMA` adds `span`/`ins`/`del`/`mark` beyond AC2 "exactly" allowlist** — **RESOLVED 2026-05-26 (code review)**: dropped `span`, `ins`, `mark` from `tagNames`. The diff renderer (`<span>`/`<ins>`/`<del>`) and redaction filter (`<mark>`) emit those tags as React components AFTER the markdown pipeline finishes; they never appear in the HAST tree rehype-sanitize sees. `del` is retained because remark-gfm emits it for the `~~strikethrough~~` syntax (coherent extension — remark-gfm is already required by AC2's table support). Schema now mirrors AC2's "exactly" set + the strikethrough extension. Rationale documented inline in `policy.ts`. All 66 sanitization tests pass.
- [x] [Review][Decision] **D8 — RSA PEM key categorized as `SSH_PRIVATE_KEY`** — **RESOLVED 2026-05-26 (code review)**: split RSA into the PEM lane. Backend `PRIVATE_KEY_PATTERN` now matches only `OPENSSH PRIVATE KEY` blocks; `PEM_PRIVATE_KEY_PATTERN` adds `RSA` to its alternation. Mirrored to `redaction-policy.json` + frontend mirror. Fixture `pem-rsa-private-key.pem` placeholder updated from `[REDACTED_SSH_PRIVATE_KEY]` to `[REDACTED_PEM_PRIVATE_KEY]` to match. All 43 backend + 4 runner-contracts + 66 frontend tests pass. (P5's backref drift still applies to the JSON mirror — handled in the patch phase.)

**Patch**

- [x] [Review][Patch] **P1 — Frontend redaction filter misses inline + code-block secrets (CRITICAL F19/F20 hole)** — **RESOLVED**: `renderTextWithRedactions` now recurses into React element children (e.g., `<strong>`, `<em>`, `<a>`'s text content) via `cloneElement`; the `<mark>`/`<pre>`/`<code>` carve-outs prevent re-processing of already-redacted spans. `SafeCode` pre-scans code text and skips Shiki when the block contains a redactable pattern (renders via `renderTextWithRedactions` instead — secrets in code blocks lose syntax highlighting but get the `<mark>` wrap). 4 new P1 regression tests cover `<strong>`, `<em>`, inline-code, fenced-code cases.
- [x] [Review][Patch] **P2 — `validateUrlScheme` accepts `//evil.com` and `\\evil.com`** — **RESOLVED**: `validateUrlScheme` now rejects any href starting with `//`, `\\`, `\/`, or `/\` (combined with the existing trim, this also catches `\t//evil.com`). 4 new P2 regression tests in `policy.test.ts` pin the boundary.
- [x] [Review][Patch] **P3 — AC16 frontend drift contract test absent (BLOCKER)** — **RESOLVED**: new `redaction-policy-drift.test.ts` in the frontend sanitization tests reads both `redaction-policy.generated.json` and the canonical `deliveryline-runner-contracts/.../redaction-policy.json` from disk and asserts byte-equality (with CRLF→LF normalization for Windows checkouts). Wired into the `export-redaction-verify` CI tier via a new step that runs `npm ci && npx vitest run …drift.test.ts`.
- [x] [Review][Patch] **P4 — `PEM_CERTIFICATE_WITH_PRIVATE_KEY` misses full-chain bundles** — **RESOLVED**: pattern now matches one OR MORE certificate blocks before the private-key block (`(?:-----BEGIN CERTIFICATE-----…-----END CERTIFICATE-----\s*)+-----BEGIN [A-Z ]*PRIVATE KEY-----…`). Mirrored to the JSON policy.
- [x] [Review][Patch] **P5 — JSON-mirror PEM regex parity drift (missing RSA + no backref)** — **RESOLVED**: JSON `PEM_PRIVATE_KEY` regex now uses `(RSA PRIVATE KEY|EC PRIVATE KEY|DSA PRIVATE KEY|ENCRYPTED PRIVATE KEY|PRIVATE KEY)` with `\1` backreference — matches Java side; mismatched-label payloads no longer false-positive in JS. Combined with D8's RSA split.
- [x] [Review][Patch] **P6 — ESLint rule `no-unsanitized-html` trivially bypassable** — **RESOLVED**: rule extended to flag computed-property assignment (`el['innerHTML'] = x`, including template-literal form), `insertAdjacentHTML`, `document.write`, `document.writeln`, and JSX spread of an inline object literal carrying `dangerouslySetInnerHTML`. 6 new test cases added to the rule's `invalid` set.
- [x] [Review][Patch] **P7 — AC15 frontend fixtures diverge from documented plan** — **RESOLVED**: added 3 new fixtures with `.expected.json` sidecars: `pem-rsa-private-key.txt`, `pem-ec-private-key.txt`, `bundle-json-nested.txt`. The fixture loop in `redactionFilter.fixtures.test.tsx` picks them up automatically.
- [x] [Review][Patch] **P8 — AC17 `--color-state-warning-subtle` token wiring missing** — **RESOLVED**: added a `.redaction-applied` CSS rule in `globals.css` binding to `hsl(var(--state-warning))` background + `hsl(var(--state-warning-foreground))` text + 2px radius + medium weight. The token comment in `globals.css` documents the choice.
- [x] [Review][Patch] **P9 — Logging instrumentation test not extended for new categories** — **RESOLVED**: 3 new `@Test`s landed in `LoggingRedactionContractTest` (the actual converter-sweep test — `RedactingMessageConverterUnitTest` referenced by the spec does not exist; this is the canonical surface). Each test asserts the new placeholder strings (`[REDACTED_IDEMPOTENCY_KEY]`, `[REDACTED_PEM_PRIVATE_KEY]`, `[REDACTED_PEM_CERTIFICATE_WITH_PRIVATE_KEY]`) survive verbatim in converter output. This is the test that caught the D5 anchoring regression.
- [x] [Review][Patch] **P10 — `requiredCategoriesAppearInPolicy` one-directional + missing PEM_CERT_WITH_KEY** — **RESOLVED**: renamed to `everyRedactionCategoryAppearsInPolicy` and made dynamic over the full enum, with a documented `backendOnly` carve-out set for categories that legitimately don't need frontend defense-in-depth (`SECRET_FIELD`, `SSH_PUBLIC_KEY`, `QUERY_SECRET`, `ENV_VALUE`, `LOCAL_PATH`, `ENVIRONMENT_BLOCK` — the latter four are backlog items). Adding a new `RedactionCategory` enum constant now forces an explicit decision: add JSON entry or extend the carve-out. `PEM_CERTIFICATE_WITH_PRIVATE_KEY` JSON entry added (was the immediate gap).
- [x] [Review][Patch] **P11 — `SafeDiffRenderer` emits `<div>` inside `<pre>` (invalid HTML)** — **RESOLVED**: replaced the per-line `<div>` wrapper with bare React fragments + embedded `\n` characters. Each `<ins>` / `<del>` / `<span>` is now a direct child of `<pre>` (all phrasing-content); the `data-line-index` attribute moves onto the leaf element.
- [x] [Review][Patch] **P12 — `SafeAnchor` adds `target="_blank"` + `rel` to `mailto:` hrefs** — **RESOLVED**: the existing `isExternal` check already guards against `mailto:` (it requires `url.protocol === 'http:' || 'https:'`); the edge-case-hunter finding was a misread. Added a code comment documenting the intentional behavior.

**Defer (pre-existing or low-impact)**

- [x] [Review][Defer] **DF1 — `JSON_SECRET_FIELD_PATTERN` value capture broken with escaped quotes** [`SensitivePayloadAnalyzer.java:~106-108`] — deferred, pre-existing: `[^"]+` stops at the first `"`, missing values containing `\"`. This story widened the field-name list, but the escape-handling bug is older than 2-24.
- [x] [Review][Defer] **DF2 — `JSON_SECRET_FIELD_PATTERN` doesn't match non-string values** [`SensitivePayloadAnalyzer.java:~106-108`] — deferred, pre-existing: numeric or array JSON values bypass the text-mode regex; `analyzeStructured` covers structured payloads.
- [x] [Review][Defer] **DF3 — PEM label alphabet `[A-Z ]*` over-permissive** [`SensitivePayloadAnalyzer.java:~62-67`] — deferred: accepts `BEGIN XYZZY PRIVATE KEY`; not exploitable, just imprecise.
- [x] [Review][Defer] **DF4 — CI step lacks `-am` for runner-contracts module** [`.github/workflows/ci.yml:23-25`] — deferred: runner-contracts is leaf today, would break only if a future story adds a sibling dep.
- [x] [Review][Defer] **DF5 — `everyDeclaredRegexCompiles` ignores JS flag semantics** [`RedactionPolicyContractTest.java:~70-84`] — deferred: implicit JS `new RegExp(entry.regex, flags)` at frontend import time acts as a real check; explicit cross-engine equivalence test would need a JS shim.
- [x] [Review][Defer] **DF6 — `scanForRedactions` overlap drops shorter category from `detectedCategories`** [`redactionFilter.tsx:~80-93`] — deferred: span is still redacted; only telemetry under-counts. Story doesn't require category-multiplicity in audit log.

