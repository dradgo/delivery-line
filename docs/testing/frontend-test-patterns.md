# Frontend Test Patterns — recurring gotchas and the right assertion for each

> **Knowledge-capture deliverable (story 3c.13, retro action D2).** These are the
> frontend-test traps that recurred across Epics 2 and 3 — each one passed in
> isolation and failed only under the full `mvn -pl deliveryline-frontend -am package`
> vitest run (or at runtime on a specific run state). For each, the symptom, the
> cause, and the assertion/structure that fixes it durably.

This doc is a companion to
[`snapshots-vs-assertions.md`](snapshots-vs-assertions.md) (the project's default of
focused assertions + `waitFor` over byte-exact DOM snapshots). The patterns below are
*why* that default holds up: they are all about asserting against **settled,
guarded** state rather than a raw render.

---

## 1. The `useLiveAnnouncement` one-render defer

**Symptom:** a `QueueShell` test does
`await waitFor(() => …data-queue-state="empty")` and then **synchronously**
`expect(queue-announcer).toHaveTextContent('Review queue is empty')` — and
intermittently reads the stale `Loading the review queue` text. Passes in isolation;
flakes only under full-suite load, so it looks like a regression but is not.

**Cause:** `src/lib/a11y/useLiveAnnouncement.ts` holds the message in `useState('')`
and only sets it in a `useEffect`, so the announced text **lags the value that drives
it by one commit**. This is intentional — an `aria-live` region only announces
*changes*, so the very first message must be deferred to be spoken. In `QueueShell.tsx`
the `data-queue-state` attribute updates synchronously, but the `queue-announcer` text
trails it by a render.

**Fix:** when asserting any `useLiveAnnouncement`-backed live region after an
async/state transition, wrap the assertion in its own `waitFor` — the announcer is
always the **last** thing to settle, so waiting on it is the strongest barrier:

```ts
await waitFor(() =>
  expect(announcer).toHaveTextContent('Review queue is empty'),
);
```

Never read the announcer synchronously right after a *different* state-driven
`waitFor`. (A synchronous first-paint assert is fine **only** because `render()`'s
act-wrapper flushes the one-commit deferral before returning — that is the one safe
case.)

---

## 2. Vitest-4 per-worker shared module registry

**Symptom:** three separate test files that each
`vi.mock('@tanstack/react-router', () => ({ useNavigate: … }))` race when run
together — a hook binds to the wrong `navigate` spy (the asserted call shows `params`
as a function). Each file passes in isolation; only the combined run fails.

**Cause:** Vitest 4 **shares a module registry across test files in one worker**, so
multiple `vi.mock` registrations of the **same** module interfere with each other. A
per-file `beforeEach(() => vi.mocked(useNavigate).mockReturnValue(spy))` does **not**
fix it — the registration itself is the shared state.

**Fix:** when several tests must mock the same module, put them in **one** test file
(one mock registration per module per file). For example, the nav-hook tests were
consolidated into `src/lib/navigation/__tests__/navigationHooks.test.tsx`. This is a
structural fix, not an assertion fix — do not try to paper over it with reset hooks.

---

## 3. Wire `null` vs TS `?: optional` — guard `!= null`

**Symptom:** opening a run in a particular state throws
`TypeError: Cannot read properties of null (reading 'trim')`, surfacing as the
"Something went wrong" error boundary. The field is typed `string | undefined`, so a
`value !== undefined && value.trim()` guard "should" be safe.

**Cause:** the generated `src/lib/api/schema.d.ts` declares many `WorkflowDetail`
fields as `?: string` (optional, non-null), but the backend serializes absent
nullable fields as **JSON `null`, not omitted**. So at runtime a field typed
`string | undefined` can actually be `null`, and a `!== undefined`-only guard passes
`null` straight into the string op. The original sighting: an **Investigating** run
uniquely sends some failure fields populated and others `null` (clean runs omit them →
`undefined` → safe; fully-Failed runs send strings → safe), so only that one state
crashed the Run Context Strip.

**Fix:** when consuming a nullable-looking `WorkflowDetail` / wire field before a
string op, guard with `!= null` (loose — covers **both** `null` and `undefined`) and
type the param `string | null | undefined`:

```ts
function presentOrUndefined(value: string | null | undefined): string | undefined {
  return value != null && value.trim() !== '' ? value : undefined;
}
```

Fixed in `runContextView.ts` (`presentOrUndefined`) and the `failureCategoryView.ts`
humanizers. Treat every `?: string` wire field as `string | null | undefined` at the
consumption site, not at the type.

---

## 4. `ArtifactView` variant field fan-out

**Symptom:** adding a field to a frontend-owned `ArtifactView` variant
(`src/features/workflows/artifactView.ts`) type-breaks the build, or makes every live
artifact of that variant silently render `error` instead of its content.

**Cause / the fan-out — three sites move together when you touch a variant:**

1. **`isArtifactView` guard** (`artifactView.ts`) — the variant branch must validate
   the new field. If the guard returns `false`, `ArtifactReviewPanel` renders
   **`error`, not `default`**, *silently* (see
   [`../patterns/registry-recipe.md`](../patterns/registry-recipe.md) for the
   "exhaustive mirror sites" shape this rhymes with).
2. **`toArtifactView` in `src/lib/api/queryOptions.ts`** — the **live** artifact-read
   mapper constructs each variant from the `ArtifactDetail` wire DTO. Even a
   "fixture-driven, no live source" variant still type-breaks here; the live wire DTO
   carries only `artifactId/artifactType/body/checksum/classification/createdAt/
   status/version`, so the mapper has to supply defaults for richer fields.
3. **`exactOptionalPropertyTypes: true`** is on — never assign `undefined` to an
   optional `foo?: T` field in an object literal. Build the literal conditionally, or
   type the field `T | undefined`.

**Fix — make variant-enrichment fields OPTIONAL with when-present validation.** When
a renderer needs richer fields than the wire DTO carries, make them **optional** on
the `*ArtifactView` type and validate them **only when present** in `isArtifactView`
— never required/non-empty. A required guard forces `toArtifactView` to either
fabricate the fields or render every live artifact of that variant as `error`.
Optional fields keep the live body-only mapping valid (the renderer defaults to `[]`
and a graceful empty state) while fixtures drive the full structured rendering, and
`toArtifactView` needs no per-variant change.

**Note:** `StubArtifactRenderers.test.tsx` was **deleted** when the last variant
graduated from a stub — a test *file* that contains zero test cases fails with "No
test suite found in file" (distinct from Vitest's `passWithNoTests`, which only
governs a run where *no files match* the pattern). Do not recreate it.

---

## The common thread

All four traps are the same lesson in different clothes: **assert against settled,
null-guarded, contract-validated state, not against a raw first render.** That is
exactly why this project defaults to Testing-Library focused assertions + `waitFor`
over byte snapshots — see [`snapshots-vs-assertions.md`](snapshots-vs-assertions.md).
