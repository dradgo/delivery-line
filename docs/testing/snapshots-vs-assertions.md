# Snapshots vs. Assertions — the project default

> **Knowledge-capture deliverable (story 3c.13, retro action D3).** This captures a
> default the project has followed in practice since Epic 1 but never wrote down: for
> component tests, prefer **focused Testing-Library assertions + `waitFor`** over
> byte-exact DOM snapshots. This doc states the rule, the rationale, and the narrow
> places where a byte-exact snapshot **is** the right tool.

## The default

**For frontend component tests, assert behaviour with Testing-Library queries and
`waitFor`, not whole-DOM snapshots.**

```ts
// Preferred — focused, intention-revealing, resilient to incidental markup churn
await waitFor(() =>
  expect(screen.getByRole('status')).toHaveTextContent('Review queue is empty'),
);
expect(screen.getByRole('button', { name: 'Retry' })).toBeEnabled();
```

```ts
// Avoid for component behaviour — a byte-exact DOM snapshot
expect(container).toMatchSnapshot();
```

### Why

- **Maintenance burden.** A whole-DOM snapshot fails on *every* incidental markup
  change — a wrapper `div`, a reordered class, a design-system bump — even when
  behaviour is unchanged. Reviewers learn to "just re-bless the snapshot," which
  trains the team to stop reading the diff and defeats the test's purpose.
- **Intent.** A focused assertion says *what matters* ("the announcer eventually says
  the queue is empty", "the Retry button is enabled"). A snapshot asserts *everything*
  with equal weight, so it communicates nothing about intent.
- **Async correctness.** Component state in this app settles across commits — most
  visibly the one-render-deferred `aria-live` announcer (see
  [`frontend-test-patterns.md`](frontend-test-patterns.md) §1). `waitFor` is the
  barrier that makes those assertions reliable; a synchronous snapshot races them and
  flakes under full-suite load. The frontend-test-patterns doc catalogues the specific
  settle/guard traps that make raw renders unreliable to snapshot.

This is a *default*, not an absolute — but deviating toward a DOM snapshot for
component behaviour should be a deliberate, justified exception, not the reflex.

## Where byte-exact snapshots ARE the right tool

A byte-exact snapshot is correct when the artifact under test **is a published
contract** whose exact bytes are the thing you want to freeze — not incidental render
output. The canonical case in this repo is the backend **OpenAPI contract snapshot**:

- `OpenApiSnapshotContractTest` (`deliveryline-backend`,
  `src/test/java/org/dradgo/adapters/rest/`) pins
  `src/main/resources/openapi/openapi.json` byte-for-byte. Here the snapshot is the
  point: the committed `openapi.json` is the API contract consumed downstream (e.g.
  the generated frontend `schema.d.ts`), so any drift **must** fail loudly and be
  reviewed.
- **Regenerating it is intentional, not auto-blessed.** Run the lifecycle **phase**
  (not the plugin goal — a direct `failsafe:` goal crashes the fork on the
  unsubstituted jacoco `@{argLine}`) with the write flag:

  ```
  ./mvnw -pl deliveryline-backend integration-test \
      -Dtest=ZzzNoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
      -Dit.test=OpenApiSnapshotContractTest \
      -Dopenapi.snapshot.write=true -Djacoco.skip=true
  ```

  The test **writes the snapshot then self-fails**; re-run **without** the write flag
  to confirm the regenerated output is byte-identical and the contract test is green.

The distinction: snapshot the artifact when its **bytes are the contract** (OpenAPI
JSON, a serialized schema, a generated manifest). Use focused assertions when you are
testing **behaviour rendered into incidental markup** (every React component test).

## See also

- [`frontend-test-patterns.md`](frontend-test-patterns.md) — the specific
  settle/null/contract traps that make focused-assertion + `waitFor` the reliable
  choice for component tests.
- [`../patterns/registry-recipe.md`](../patterns/registry-recipe.md) — the
  foundation-gate tier (where the OpenAPI snapshot and other contract tests run) and
  how to run it locally.
