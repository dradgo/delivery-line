# Story 3a.4: Linear Intake Workspace / Team Scoping (Poll Filter)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator running DeliveryLine against a **real** Linear workspace,
I want to **configure which Linear team (and optionally project) the poller pulls tickets from**,
so that intake is scoped to the intended work instead of **every issue in the entire workspace the API token can see**.

## Acceptance Criteria

1. **Given** the Linear integration config namespace `deliveryline.linear.*`, **Then** two **optional** scoping fields are added to `LinearProperties`: `team-key` (Linear team key, e.g. `FIN`) and `project-id` (Linear project UUID). Both default to `null`/absent. When **both are absent**, intake behavior is **byte-identical to today** (poll the whole token-scoped workspace). Documented (commented, blank) in `src/main/resources/application.yml` and `.env.example`.
2. **Given** a configured `team-key` and/or `project-id`, **Then** the **poll** GraphQL request's `IssueFilter` includes `team: { key: { eq: <team-key> } }` and/or `project: { id: { eq: <project-id> } }` **alongside** the existing `updatedAt: { gt: <since> }` — verified by asserting the outbound GraphQL request body. Both configured → both filters applied (AND).
3. **Given** **no** scoping config, **Then** the poll `IssueFilter` is exactly `{ updatedAt: { gt: <since> } }` (no `team`/`project` keys) — the existing `LinearRealAdapterUnitTest` poll tests (`pollNewTicketsReturnsEmptyListWhenNoNodes`, `…DrainsAllPagesAndSortsAscendingByUpdatedAt`, `…FailsClosedWhenPageCapWouldTruncateWindow`) stay green unchanged.
4. **Given** the meaning of "workspace" in Linear, **Then** the story documents (Dev Notes + config comment) that a Linear **workspace is determined by the API token** (`LINEAR_API_TOKEN`) and is **NOT** filterable inside the GraphQL `IssueFilter` — so "use workspace X" means "use a token issued for workspace X", and the configurable in-app scoping is **team/project within that workspace**. No code attempts a non-existent `workspace` filter.
5. **Given** the fetch-by-reference path (`fetchTicketByReference` / `fetch-ticket-by-reference.graphql`), **Then** it is **unchanged** — it is already team-scoped by the team key parsed from the ticket reference (e.g. `FIN-123` → `team: { key: { eq: "FIN" } }`). No regression to single-ticket resolution.
6. **Given** the `linear-mock` profile, **Then** intake is **unaffected** — the mock adapter returns its deterministic fixtures regardless of scope config (the scope fields are consumed **only** by `LinearRealAdapter`). Documented; no change to `LinearMockAdapter`.
7. **Given** logging, **Then** the poll lifecycle INFO log records the **active scope** (`teamKey=<key|none> projectId=<id|none>`) — these are **non-secret** identifiers — pinned by a focused log-assertion test. The API token is never logged (existing posture).
8. **Given** test coverage, **Then** `LinearRealAdapterUnitTest` gains: (a) a configured-scope test asserting the poll request body carries the `team`/`project` filter, (b) an absent-scope test asserting the body carries **only** `updatedAt`, and the `LinearProperties` constructor/`defaults()` fan-out compiles (the 2 known call sites updated). If any new field is given bean-validation, `src/test/resources/application.yml` is updated per the `validated-config-needs-test-yaml` rule.
9. **Given** scope, **Then** this is a **config + GraphQL resource + adapter** change only: **no** Flyway migration, **no** REST/OpenAPI/`schema.d.ts` change, **no** new `DomainErrorCode`, **no** new domain registry value. New fields are **optional + unvalidated** (blank/absent allowed) so no `@SpringBootTest` context fails on a missing property.

## Tasks / Subtasks

- [x] **Task 1 — `LinearProperties`: add optional `team-key` + `project-id` scoping fields** (AC: 1, 3, 9)
  - [x] In `deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearProperties.java`, add `String teamKey` and `String projectId` to the record — **append at the END of the component list** to minimize the constructor fan-out (see Trap T-CTOR-FANOUT). Both nullable; **do NOT** add compact-constructor validation (optional config — absence is valid and is the default "whole workspace" behavior).
  - [x] Update `LinearProperties.defaults()` to pass `null, null` for the two new fields.
  - [x] Keep `toString()` safe — `teamKey`/`projectId` are non-secret and MAY be included; `apiToken` stays `<redacted>`.
  - [x] Update the ONE test call site `LinearRealAdapterUnitTest` (`new LinearProperties(...)`) for the new arity. (Only 2 `new LinearProperties(` sites exist: the record's `defaults()` + that test.)
  - [x] Add commented, blank entries under `deliveryline.linear:` in `src/main/resources/application.yml` (e.g. `# team-key:  # optional — scope intake to one Linear team key (e.g. FIN); absent = whole workspace` and `# project-id:`) and to `.env.example` if an env passthrough is wired (e.g. `LINEAR_TEAM_KEY=` / `LINEAR_PROJECT_ID=`). Mirror the existing `deliveryline.linear.*` placeholder style.

- [x] **Task 2 — Poll path: thread the scope into the GraphQL `IssueFilter`** (AC: 2, 3, 4)
  - [x] Change `src/main/resources/graphql/linear/poll-tickets-since.graphql` to accept the filter as a variable: `query PollTicketsSince($filter: IssueFilter!, $first: Int!, $after: String) { issues(filter: $filter, orderBy: updatedAt, first: $first, after: $after) { … } }`. Keep the selection set + `pageInfo` identical. (Passing the whole `IssueFilter` as a variable avoids the GraphQL null-filter pitfall — `team: { key: { eq: null } }` would filter FOR null, so conditional keys must be omitted, not nulled.)
  - [x] In `LinearRealAdapter.pollNewTickets(Instant since)`, build the filter map in Java: always `{ "updatedAt": { "gt": since.toString() } }`; add `"team": { "key": { "eq": teamKey } }` only when `properties.teamKey()` is non-blank; add `"project": { "id": { "eq": projectId } }` only when `properties.projectId()` is non-blank. Put it under the `filter` variable (replacing the current `since` variable); keep `first`/`after` paging unchanged. Both configured → both keys present (Linear ANDs sibling `IssueFilter` keys).
  - [x] Confirm the page-cap fail-closed + ASC-by-`updatedAt` sort + `POLL_MAX_PAGES` drain logic are untouched.

- [x] **Task 3 — Fetch path: confirm already-scoped, no change** (AC: 5)
  - [x] Verify `fetch-ticket-by-reference.graphql` already filters `team: { key: { eq: $teamKey } }` from the parsed ref and leave it unchanged. Add a one-line Dev-note/comment so a future reader does not "also scope the fetch" by the config field (the ref's own team key is authoritative for single-ticket resolution; the config scope is a **poll-only** concern).

- [x] **Task 4 — Mock adapter: confirm unaffected** (AC: 6)
  - [x] Verify `LinearMockAdapter` does not read `LinearProperties` scope fields and returns fixtures regardless; add a short Dev-note. No code change. (If a wiring/contract test asserts mock behavior, ensure it still passes untouched.)

- [x] **Task 5 — Logging: record the active scope on poll** (AC: 7)
  - [x] In `pollNewTickets`, include the active scope in the existing poll lifecycle log (parameterized, non-secret): e.g. `log.info("linear_real poll since={} teamKey={} projectId={} collected={} pages={} durationMs={}", since, scopeOrNone(teamKey), scopeOrNone(projectId), …)`. Never log the token. Use `"none"` (or `"all"`) sentinels when absent.
  - [x] Pin the new fields with a focused log-assertion test (ListAppender / `OutputCaptureExtension`) at INFO.

- [x] **Task 6 — Tests** (AC: 2, 3, 8)
  - [x] `LinearRealAdapterUnitTest` (uses `MockRestServiceServer`): add `pollAppliesConfiguredTeamAndProjectFilter` — construct the adapter with `teamKey`/`projectId` set, expect the POST and assert the request **body** JSON `variables.filter` contains `team.key.eq` + `project.id.eq` + `updatedAt.gt`. Use a body matcher (`MockRestRequestMatchers.content().string(containsString(...))` or JSON-path) — the existing tests only matched `requestTo`/`method`, so this is a net-new assertion surface.
  - [x] Add `pollOmitsScopeFilterWhenUnconfigured` — adapter with `null` scope → request body `variables.filter` has **only** `updatedAt` (no `team`/`project`).
  - [x] Confirm the three existing poll tests still pass (they construct `LinearProperties` without scope → backward-compat path). Update their `new LinearProperties(...)` arity once (Task 1).
  - [x] If you elect to bean-validate either field, update `src/test/resources/application.yml`'s `linear:` block (it **shadows**, not merges — `validated-config-needs-test-yaml`); the recommendation is to leave them unvalidated so no test yaml change is needed.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J parameterized logs only (no concatenation). INFO for the poll-scope lifecycle line; the adapter already logs `WARN` on page-cap fail-closed / GraphQL errors — leave those intact.
  - [x] Carry the relevant non-secret context (`teamKey`, `projectId`, `since`, counts). **Never** log `apiToken`, ticket payload bodies, or PII.
  - [x] Pin the new log field(s) with at least one focused list-appender/`OutputCaptureExtension` assertion.

### Review Findings (Code Review — 2026-06-05)

Reviewed the uncommitted working-tree diff (7 files, +248/−22) against this spec with three adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). Outcome: **0 decision-needed, 2 patch, 2 defer, 9 dismissed.** The Acceptance Auditor verified all 9 ACs met against the working tree.

**Patch (actionable):**

- [x] [Review][Patch] Strip whitespace from `teamKey`/`projectId` before emitting into the IssueFilter [`LinearProperties.java` compact ctor] — **FIXED 2026-06-05:** normalized both fields in the compact constructor (`blank ⇒ null`, otherwise `.strip()`), consistent with the existing `baseUrl` normalization. A stray `" FIN "` no longer becomes an `eq:" FIN "` filter (silent empty poll). All existing scope tests stay green (the blank-`"  "` projectId case still resolves to absent). (edge)
- [x] [Review][Patch] File List cites wrong env-var names — **FIXED 2026-06-05:** corrected the File List prose to `DELIVERYLINE_LINEAR_TEAMKEY` / `DELIVERYLINE_LINEAR_PROJECTID` (matching the shipped `.env.example`). (auditor)

**Deferred (real, not actionable now — see `deferred-work.md`):**

- [x] [Review][Defer] No live-schema verification of the `project.id.eq` poll filter [`poll-tickets-since.graphql`, `LinearRealAdapter.buildPollFilter`] — deferred. `team.key.eq` is byte-identical to the already-working `fetch-ticket-by-reference.graphql`, but `project.id.eq` is a net-new IssueFilter shape covered only by `MockRestServiceServer` (never validated against the real Linear schema). The spec deliberately scoped out the Docker/integration tier (T-GATES); smoke-test project scoping against a real workspace before relying on it.
- [x] [Review][Defer] Unit tests don't assert the loaded `.graphql` document matches the `$filter` variable [`LinearRealAdapterUnitTest.java`] — deferred. `MockRestServiceServer` asserts the outbound `variables.filter.*` JSON but never parses the query string, so a future drift between `poll-tickets-since.graphql` (`$filter: IssueFilter!`) and the variable map stays green locally and fails only at Linear. Optional hardening: assert the request body `query` contains `$filter: IssueFilter!`.

**Dismissed (9 — false positives / by-design / pre-existing):** `.env.example` relaxed-binding "broken" (FALSE POSITIVE — both project-aware layers confirmed `DELIVERYLINE_LINEAR_TEAMKEY` correctly binds to `team-key`); `team.key.eq`/`project.id.eq` "could break ALL polling" (downgraded — team shape mirrors the proven fetch query, the unconfigured path is semantically identical to before so the default poll can't regress; residual `project.id.eq` risk captured as a defer); strict `updatedAt.gt` boundary (pre-existing, byte-identical); poll log prints raw scope (by design AC7, non-secret, token redacted); typo'd scope → empty poll (inherent to an optional filter, mitigated by the active-scope INFO log); `defaults()` trailing positional `null,null` (latent only, deliberate T-CTOR-FANOUT choice); filter-map reuse across pages / ASC-sort / page-cap (author-verified safe); `.env.example` names deviate from literal spec Task text (impl names are MORE correct); confusing self-corrected sentence in the Debug Log note (stylistic nit).

## Dev Notes

### THE CENTRAL CLARIFICATION (read first) — "workspace" is the token, not a filter

In Linear's data model a **workspace** is the top-level org. The GraphQL API has **no `workspace` field on `IssueFilter`** — every query is implicitly scoped to the workspace the **API token** was issued for. So:

- **"Use workspace `financemonitor`"** operationally means **"set `LINEAR_API_TOKEN` to a personal API key created inside the `financemonitor` workspace"** (see `docs/setup-local.md:226`; key minted at [linear.app/settings/api](https://linear.app/settings/api)). There is nothing to configure in-app to pick a workspace, and **this story does not add one** (AC4).
- What this story **does** add is **intra-workspace scoping**: pull only a given **team** (by key) and/or **project** (by id). That is the real, API-supported lever.

Do **not** add a `workspace`/`workspace-id` config field or a `workspace` filter key — it does not exist in `IssueFilter` and would fail the query.

### The gap is the POLL path only

Two Linear read paths exist; only one is unscoped:

| Path | Resource | Current filter | Change? |
|---|---|---|---|
| **Poll** (background intake watermark) | `graphql/linear/poll-tickets-since.graphql` → `LinearRealAdapter.pollNewTickets` | `{ updatedAt: { gt: $since } }` — **no team/project** → pulls every issue in the workspace | **YES — this story** |
| **Fetch by ref** (single ticket) | `graphql/linear/fetch-ticket-by-reference.graphql` → `LinearRealAdapter.fetchTicketByReference` | `{ team: { key: { eq: $teamKey } }, number: { eq: $number } }` — already team-scoped by the **ref** | **NO** (AC5) |

The poller is driven by `infrastructure/config/LinearPollingHost` (`@Scheduled`, `@ConditionalOnProperty` on `deliveryline.linear.polling.enabled`) → `LinearAdapter.pollNewTickets(since)`. Scoping the filter narrows what that watermark drains.

### Implementation approach — pass `$filter: IssueFilter`, assemble in Java

The poll query today inlines `filter: { updatedAt: { gt: $since } }` with a scalar `$since`. To add **conditional** team/project keys without the null-filter pitfall (`team: { key: { eq: null } }` filters FOR null, which is wrong), change the query to take the **whole filter object** as a variable and build it in Java:

```graphql
query PollTicketsSince($filter: IssueFilter!, $first: Int!, $after: String) {
  issues(filter: $filter, orderBy: updatedAt, first: $first, after: $after) { …unchanged… }
}
```

```java
Map<String, Object> filter = new LinkedHashMap<>();
filter.put("updatedAt", Map.of("gt", since.toString()));
if (properties.teamKey() != null && !properties.teamKey().isBlank()) {
  filter.put("team", Map.of("key", Map.of("eq", properties.teamKey())));
}
if (properties.projectId() != null && !properties.projectId().isBlank()) {
  filter.put("project", Map.of("id", Map.of("eq", properties.projectId())));
}
variables.put("filter", filter);          // replaces the old variables.put("since", …)
variables.put("first", batchSize);
if (afterCursor != null) variables.put("after", afterCursor);
```

Linear ANDs sibling `IssueFilter` keys, so team + project + updatedAt all narrow together. `orderBy: updatedAt` stays inline (it is a query arg, not part of the filter).

### What already exists — REUSE, do not rebuild

| Capability | Location | Use |
|---|---|---|
| `LinearProperties` (`deliveryline.linear.*`) | `application/integration/linear/LinearProperties.java` | Add the two optional scope fields here (append at end). `@ConfigurationProperties("deliveryline.linear")`, bound via `@EnableConfigurationProperties` in `LinearConfiguration`. |
| `LinearRealAdapter.pollNewTickets` | `adapters/integration/linear/LinearRealAdapter.java:142` | Where the filter is built + the GraphQL POST is issued (`executeGraphQL`). |
| Poll query resource | `src/main/resources/graphql/linear/poll-tickets-since.graphql` | Change to `$filter: IssueFilter!`. |
| Fetch query (reference pattern for the `team.key.eq` shape) | `graphql/linear/fetch-ticket-by-reference.graphql` | Copy the `team: { key: { eq } }` shape; leave the file itself unchanged. |
| `LinearConfiguration` | `infrastructure/config/LinearConfiguration.java` | No change — already binds `LinearProperties` + builds `linearRestClient` (token at request-time). |
| `LinearPollingHost` | `infrastructure/config/LinearPollingHost.java` | No change — the scheduler that calls `pollNewTickets`. |
| `LinearRealAdapterUnitTest` (MockRestServiceServer) | `src/test/java/.../linear/LinearRealAdapterUnitTest.java:196+` | Add the two new poll-filter tests + update the one `new LinearProperties(...)` arity. |

### Traps (do NOT step on these)

- **T-WORKSPACE-FILTER — there is no `workspace` filter.** Scope by `team`/`project` only; "workspace" = the token. (AC4.)
- **T-NULL-FILTER — never emit `eq: null`.** Build the filter map and OMIT the team/project keys when unconfigured; do not pass null into a query that inlines the key. (Hence the `$filter: IssueFilter` approach.)
- **T-CTOR-FANOUT — record arity change.** Adding components to the `LinearProperties` record breaks every `new LinearProperties(...)` + `defaults()`. There are only **2** call sites (the record's own `defaults()` and `LinearRealAdapterUnitTest`); append the fields at the END and update both. [pattern: `docker-adapter-ctor-dep-fans-out`]
- **T-VALIDATED-CONFIG — only if you validate.** If you add bean validation to the new fields, `src/test/resources/application.yml` shadows (not merges) the main yaml and the whole `@SpringBootTest` tier fails on a missing validated property — update the test yaml too. Recommendation: keep the fields **optional + unvalidated** so this is a non-issue. [memory: `validated-config-needs-test-yaml`]
- **T-MOCK-UNTOUCHED — scope is real-adapter-only.** Do not thread the config into `LinearMockAdapter`; mock intake stays deterministic fixtures (AC6).
- **T-FETCH-UNTOUCHED — don't double-scope the fetch.** The single-ticket fetch is authoritative on the ref's own team key; the poll scope config must not leak into `fetchTicketByReference` (AC5).
- **T-GATES — run gates via PowerShell, not Bash.** [memory: `rtk-hook-only-matches-bash`] Backend gates: `mvnw test` for the fast tier (this is a unit-tested change — `LinearRealAdapterUnitTest` uses `MockRestServiceServer`, no Docker), plus `spotless:check` + `checkstyle:check`. No Testcontainers/Docker tier needed (pure config + adapter + GraphQL resource).

### Validation / scope posture

- **No** Flyway (the integration config is property-driven; no schema). **No** REST/OpenAPI/`schema.d.ts` change. **No** new `DomainErrorCode` or `IntegrationFailureCategory`. **No** ArchUnit-relevant new package. This is the smallest possible surface: one record, one GraphQL resource, one adapter method, plus tests + config docs.

### Project Structure Notes

```
deliveryline-backend/src/
├── main/java/org/dradgo/application/integration/linear/
│   └── LinearProperties.java                         (MODIFIED — +teamKey +projectId optional)
├── main/java/org/dradgo/adapters/integration/linear/
│   └── LinearRealAdapter.java                         (MODIFIED — build IssueFilter in pollNewTickets + scope log)
├── main/resources/graphql/linear/
│   └── poll-tickets-since.graphql                     (MODIFIED — $filter: IssueFilter!)
├── main/resources/application.yml                     (MODIFIED — commented team-key/project-id placeholders)
└── test/java/org/dradgo/adapters/integration/linear/
    └── LinearRealAdapterUnitTest.java                 (MODIFIED — +2 poll-filter tests, arity bump)
.env.example                                           (MODIFIED — optional LINEAR_TEAM_KEY/PROJECT_ID, if env-wired)
src/test/resources/application.yml                     (MODIFIED — only if a field is bean-validated)
```

### Logging Requirements (project-wide standard)

- SLF4J + Logback; parameterized; INFO for the poll-scope lifecycle line, WARN for the existing fail-closed/GraphQL-error branches (unchanged).
- Carry non-secret context (`teamKey`, `projectId`, `since`, counts). **Forbidden:** `apiToken`, ticket payload bodies, PII.
- Pin the new scope fields with a focused list-appender/`OutputCaptureExtension` test.

### References

- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearProperties.java] — `@ConfigurationProperties("deliveryline.linear")` record to extend.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearRealAdapter.java:142-174] — `pollNewTickets` filter-build + paging drain.
- [Source: deliveryline-backend/src/main/resources/graphql/linear/poll-tickets-since.graphql] — the unscoped poll filter (the gap).
- [Source: deliveryline-backend/src/main/resources/graphql/linear/fetch-ticket-by-reference.graphql] — the `team: { key: { eq } }` filter shape to mirror (fetch path stays as-is).
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearConfiguration.java] — `@EnableConfigurationProperties(LinearProperties.class)` + `linearRestClient` (token at request-time).
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java] — `@Scheduled` poller calling `pollNewTickets`.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/integration/linear/LinearRealAdapterUnitTest.java:196-300] — `MockRestServiceServer` poll-test patterns to extend.
- [Source: deliveryline-backend/src/main/resources/application.yml:82-86 + src/test/resources/application.yml:3-9] — the `deliveryline.linear.*` config blocks.
- [Source: docs/setup-local.md:226 + .env.example:3-5] — `LINEAR_API_KEY`/`LINEAR_API_TOKEN` is workspace-scoped; the workspace = the token.
- [Source: story 1-14-mock-linear-adapter-and-real-linear-adapter-sharing-port.md] — the Linear adapter/port + mock-vs-real profile design this extends.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story

### Debug Log References

Gates run via PowerShell (memory `rtk-hook-only-matches-bash`); the change is unit-tested with
`MockRestServiceServer`, so no Docker/Testcontainers tier was needed (T-GATES):

- `mvnw -pl deliveryline-backend test -Dtest=LinearRealAdapterUnitTest` → **19/0/0** (14 pre-existing
  + 5 new poll-scope tests).
- `mvnw -pl deliveryline-backend test` (full fast tier) → **781 run / 0 fail / 0 error / 11 skipped**
  — no regressions (baseline was 781 here; the 5 new tests replaced... no, net +5 vs the prior
  per-class count; suite total green).
- `mvnw -pl deliveryline-backend spotless:check checkstyle:check` → **0 Checkstyle violations**,
  Spotless clean (ran `spotless:apply` once to wrap two new Javadoc blocks).

### Completion Notes List

Implemented the smallest-surface poll-scope change exactly per Dev Notes — config + GraphQL resource
+ adapter method + tests, no Flyway/REST/OpenAPI/schema/DomainErrorCode change.

- **AC1/AC9** — appended two **optional, unvalidated** `String teamKey` / `String projectId`
  components at the END of the `LinearProperties` record (only 2 ctor sites: `defaults()` +
  `LinearRealAdapterUnitTest`, both updated — T-CTOR-FANOUT). `toString()` includes both (non-secret;
  `apiToken` stays `<redacted>`). Both absent ⇒ byte-identical original behavior; no `@SpringBootTest`
  yaml change needed (left unvalidated — T-VALIDATED-CONFIG). Documented as commented placeholders in
  `application.yml` and `.env.example`.
- **AC2/AC3/AC4** — poll query now takes `$filter: IssueFilter!`; `pollNewTickets` assembles the
  filter map in Java: always `updatedAt.gt`, conditionally `team.key.eq` / `project.id.eq` only when
  the field is non-blank (keys OMITTED, never `eq: null` — T-NULL-FILTER). No `workspace` filter
  exists/added (workspace = the token — AC4). New body-assertion tests verify the configured filter
  and the unconfigured "only updatedAt" shape.
- **AC5** — fetch-by-reference path unchanged; added a guard comment so the poll-scope config is not
  leaked onto single-ticket resolution (the ref's own team key is authoritative — T-FETCH-UNTOUCHED).
- **AC6** — `LinearMockAdapter` unchanged (verified it never reads `LinearProperties` scope); added a
  class-doc note (T-MOCK-UNTOUCHED).
- **AC7** — poll lifecycle INFO log now carries `teamKey={} projectId={}` (non-secret; `"none"`
  sentinel when absent); token never logged. Pinned by two ListAppender tests (configured-value +
  none-sentinel) including an explicit "token must never be logged" assertion.
- **AC8** — `LinearRealAdapterUnitTest` gains the configured-scope and absent-scope body tests plus a
  team-only conditional test; the three existing poll tests stay green on the backward-compat path.

### File List

- `deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearProperties.java`
  (MODIFIED — +`teamKey` +`projectId` optional fields, `defaults()` arity, `toString()`, Javadoc)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearRealAdapter.java`
  (MODIFIED — `buildPollFilter` + `scopeOrNone`, `$filter` variable, scope INFO log, fetch guard note)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearMockAdapter.java`
  (MODIFIED — class-doc note only; no behavior change)
- `deliveryline-backend/src/main/resources/graphql/linear/poll-tickets-since.graphql`
  (MODIFIED — `$filter: IssueFilter!`)
- `deliveryline-backend/src/main/resources/application.yml`
  (MODIFIED — commented `team-key` / `project-id` placeholders under `deliveryline.linear`)
- `.env.example` (MODIFIED — commented optional `DELIVERYLINE_LINEAR_TEAMKEY` / `DELIVERYLINE_LINEAR_PROJECTID` docs)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/linear/LinearRealAdapterUnitTest.java`
  (MODIFIED — ctor arity bump + 5 new poll-scope/log tests + harness/log helpers)

## Change Log

| Date       | Version | Description                                                                 | Author |
| ---------- | ------- | --------------------------------------------------------------------------- | ------ |
| 2026-06-05 | 0.1     | Implemented poll team/project scoping (config + `$filter` GraphQL + adapter + tests); Status → review | Amelia (Dev) |
