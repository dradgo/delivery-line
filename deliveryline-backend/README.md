# deliveryline-backend

The DeliveryLine backend: the Spring Boot 4 application that hosts the governed agent-delivery
workflow — domain model, application services, persistence (JPA + Flyway), the Spring Shell CLI,
and the localhost REST read surface. Its executable jar also embeds the built Vite SPA produced
by `deliveryline-frontend`.

Build the whole project from the reactor root with `mvn install`. Build this module (and the
upstream modules it needs) with `mvn -pl deliveryline-backend -am <goals>`.

## Quality gates

This module is guarded by two independent, complementary tool sets. **Neither substitutes for the
other:** Spotless, Checkstyle, and SpotBugs enforce formatting, style, and bug patterns; JaCoCo
measures test coverage. A high-coverage codebase can still be poorly formatted, and a lint-clean
codebase can still be untested — each tool gates an axis the others do not.

- **Lint & static analysis** (story 2.30) — Spotless (Google Java Format), Checkstyle, and
  SpotBugs, all bound to `verify`. Run `./mvnw spotless:apply` to auto-fix formatting.
- **Test coverage** (story 2.32) — JaCoCo measures how much of the code the test suite exercises
  and fails `verify` when coverage drops below a committed floor. Documented below.

## Test Coverage

### Running it

The standard backend coverage command is:

```
mvn -pl deliveryline-backend verify
```

(run it from the reactor root; add `-am` if the upstream modules are not built yet). JaCoCo is
wired into the normal Maven `verify` lifecycle — there is no separate coverage command and no
separate test command. One `verify` run:

1. instruments the Surefire (unit) and Failsafe (integration / contract / ArchUnit) test JVMs;
2. writes `target/jacoco.exec` (unit) and `target/jacoco-it.exec` (integration);
3. merges them into `target/jacoco-merged.exec` (the `jacoco-merge` execution, `post-integration-test` phase);
4. generates the report and enforces the threshold gate (`jacoco-report` + `jacoco-check`, `verify` phase).

The report and the gate evaluate the **merged** unit + integration data, so coverage produced by
the Failsafe/Testcontainers tier — where most of this module's tests live — is counted, not just
the small Surefire slice.

### The HTML report

After a run, open the local report at:

```
deliveryline-backend/target/site/jacoco/index.html
```

JaCoCo writes `jacoco.xml` and `jacoco.csv` alongside it. CI uploads the whole directory as the
`backend-coverage-jacoco` artifact (downloadable from the `backend-contract-tests` job).

### Thresholds

The `jacoco-check` execution enforces a **BUNDLE-level** (whole-module) minimum:

| Counter  | Minimum (`COVEREDRATIO`) |
| -------- | ------------------------ |
| `LINE`   | `0.75`                   |
| `BRANCH` | `0.55`                   |

These are **regression floors for this phase**, not aspirational targets. They were set
empirically, not guessed:

- Coverage was measured with the binding CI-shaped command on the CI platform — a clean
  Linux + Docker run of `mvn -pl deliveryline-backend -am verify` with the real Testcontainers
  contract suite: **LINE 81.33 % (6049/7438), BRANCH 62.74 % (1381/2201)**. A Windows run
  produced consistent numbers (LINE 81.1 %, BRANCH 62.3 %), confirming the measurement is
  platform-stable.
- Each floor is set a few points **below** that measurement and then rounded to a clean `0.05`
  value: `LINE` `0.75` (~6 points of headroom below ~81 %), `BRANCH` `0.55` (~7 points below
  ~63 %). Branch coverage is inherently more volatile than line coverage, so it carries slightly
  more headroom.

Why a floor below the measurement, and not the measured value itself? A threshold pinned at
current coverage turns the build red the moment code lands slightly under-tested — even small,
reasonable changes that add a little lightly-covered glue. The floor still fails the build on a
genuine regression (a real drop in covered ratio) while leaving headroom for normal churn. A
future story can raise it deliberately once coverage is consistently higher.

### When the gate fails

Maven fails `verify` with a clear, deterministic message — no need to open the HTML report to
detect it:

```
Rule violated for bundle deliveryline-backend: lines covered ratio is 0.73, but expected minimum is 0.75
```

To fix it, **add tests — do not lower the threshold.** Open
`target/site/jacoco/index.html`, drill into the red (uncovered) classes and methods, and add unit
or integration tests for the uncovered logic. Lowering the floor is only appropriate as a
deliberate, reviewed decision when code is intentionally removed.

### What counts toward the gate

The gate measures **all backend production code by default.** New packages added by future
stories automatically participate — there is no per-package allowlist to maintain, only the
`<excludes>` list below.

**Excluded** (committed in `pom.xml` with rationale):

- `org.dradgo.DeliveryLineApplication` — the `@SpringBootApplication` bootstrap class: a one-line
  `main()` delegating to `SpringApplication.run(...)`, with no business logic and not meaningfully
  unit-testable.

Nothing else is excluded. Business and application code — including the `infrastructure/config`
Spring configuration classes — is **never blanket-excluded**; the threshold floor absorbs
lightly-tested glue rather than hiding it. There are no generated Java sources in this module
(`openapi.json` is a committed resource, not generated code), so "generated sources" exclusions do
not apply here.

### CI integration

The binding coverage gate runs in the `backend-contract-tests` CI tier, which executes
`mvn -pl deliveryline-backend -am verify` (Linux, with Docker for the Testcontainers-backed
tests). Because that tier runs the full `verify` lifecycle, the `jacoco-check` gate runs there and
a coverage regression fails the job; the JaCoCo report is published as the `backend-coverage-jacoco`
artifact for inspection.

The `foundation-gate` job runs only a non-representative aggregator subset, so it **skips the
`check` execution** via the `jacoco.check.skip` property set in its Maven profile — `prepare-agent`,
`merge`, and `report` still run, so coverage instrumentation stays intact. Backend coverage is
still gated at the foundation level **transitively**, because `backend-contract-tests` (where the
real gate runs) is a required upstream tier of `foundation-gate`.

## OpenAPI snapshot regeneration

The committed `src/main/resources/openapi/openapi.json` is a tripwire enforced by
`OpenApiSnapshotContractTest`: any drift between the live `/v3/api-docs` and the committed file
fails CI. The frontend client (`deliveryline-frontend/src/lib/api/schema.d.ts`) is generated from
this same snapshot, so a backend REST change requires regenerating BOTH together. Use the combined
script from the repo root — it handles the by-design "regen-and-fail" exit, then runs the frontend
regen, then prints the diff for you to review and commit:

```bash
./scripts/regen-openapi.sh        # Linux / macOS / WSL2 / Git Bash
./scripts/regen-openapi.ps1       # Windows PowerShell + Docker Desktop
```

Requires Docker (Testcontainers spins up Postgres for the backend Spring context). The script does
**not** auto-commit — review the snapshot + `schema.d.ts` diff first to confirm the surface change
is intentional, since the drift gate is the only thing protecting downstream consumers from a silent
contract change.
