# Story 3c.2: Project Domain Aggregate + Central Registries + Drift Tests

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ READ FIRST — this is the domain/registry half of the Epic 3c foundation.** Story 3c-1 (status: review) already shipped the **`V17`** schema (`projects` + `project_credentials` + nullable `project_id text` FKs) and extended `FlywaySchemaContractTest`. It deliberately **stayed out** of `PublicIdPrefixes.java` / `RegistryContractTest` / the API placeholder manifest and added **no `src/main/java`** code. **This story owns exactly that deferred work**: the `Project` domain aggregate, the `ProjectStatus` + `ConnectorKind` registries, the `prj_`/`cred_` prefix-registry entries, the three new `DomainErrorCode`s, and the drift tests that pin all of them against the V17 DB CHECKs + API manifest. The epic still says "Flyway V14" — that number is stale (head is `V16`, 3c-1 shipped `V17`); the schema you drift-test against is **V17**. The story key/filename keeps its slug (synced to `sprint-status.yaml` — do not rename).

## Story

As a foundation developer,
I want a `Project` domain aggregate plus `ProjectStatus` / `ConnectorKind` registries and `prj_`/`cred_` prefix registrations, drift-tested against the V17 schema and the API manifest,
so that project configuration has an authoritative, drift-protected domain model consistent with every other registry-backed value set in the system.

## Acceptance Criteria

> These ACs are **reconciled** against the live registry/drift machinery (see Dev Notes → "Why these ACs differ from the epic"). Where the epic wording conflicts with an enforced codebase invariant, the reconciled wording below is authoritative.

1. **Given** `domain.project`, **Then** a `Project` aggregate (a Java `record`) models: `publicId` (a `prj_` public id), `name`, `slug`, `status` (`ProjectStatus`), `repositoryUrl` (nullable), `ticketSourceKind` (`ConnectorKind`), `repoHostKind` (`ConnectorKind`), `openspecEnabled` (boolean), `createdAt`, `archivedAt` (nullable) — mirroring exactly the V17 `projects` columns. Its compact constructor enforces invariants: non-blank `name`/`slug`, non-null `status`/`ticketSourceKind`/`repoHostKind`, and `publicId` validated through `PublicIdPrefixes.require(publicId, PublicIdPrefixes.PROJECT)`.
2. **Given** the central registries, **Then** `ProjectStatus` (`active`, `disabled`) and `ConnectorKind` (`linear`, `github`) are added in `domain.registry` (alongside `ArtifactStatus` et al.), each implementing `RegistryValue` and following the `ArtifactStatus`/`RunnerStage` template exactly (private `LOOKUP` via `RegistryParsers.index`, `value()`, package-private `fromValue(String)` + public `fromValue(String, String field)`). Both are exposed through `DomainRegistry.projectStatuses()` / `DomainRegistry.connectorKinds()`.
3. **Given** the prefix registry, **Then** `PublicIdPrefixes` gains `PROJECT("project", "prj_", "ck_projects_public_id_format")` and `PROJECT_CREDENTIAL("projectCredential", "cred_", "ck_project_credentials_public_id_format")` — alias + prefix + the **exact** V17 constraint names.
4. **Given** the drift-test pattern from story 1.4 (`RegistryContractTest`), **Then** the test is extended so: `ProjectStatus` aligns with the `ck_projects_status` DB CHECK and the `projectStatuses` API-placeholder array; `ConnectorKind` aligns with **both** `ck_projects_ticket_source_kind` and `ck_projects_repo_host_kind` DB CHECKs and the `connectorKinds` API-placeholder array; `prj_`/`cred_` align with their format CHECKs (`extractPublicIdPrefixesFromSql` + `publicIdPrefixSqlRegexExactlyMatchesJavaSuffixPattern` pick them up automatically once the enum entries exist) and the `publicIdPrefixes` API-placeholder object; and `DomainRegistry` exposes each new enum's authoritative value set. A mismatch on any axis fails the test.
5. **Given** new domain error codes `PROJECT_NOT_FOUND`, `PROJECT_SLUG_CONFLICT`, `UNSUPPORTED_CONNECTOR_KIND`, **Then** all three are registered at the **three sites** (the `DomainErrorCode` enum + `ProblemDetailsCatalog` + the `registry-api-schema-placeholders.json` `problemTypeUris` object), with statuses `PROJECT_NOT_FOUND → 404`, `PROJECT_SLUG_CONFLICT → 409`, `UNSUPPORTED_CONNECTOR_KIND → 400`, all `retryable=false`. (Ahead-of-use registration: their throw sites land in 3c-3/3c-8; the foundation gate round-trips every code regardless, so no production throw site is required here.)
6. **Given** ArchUnit boundaries, **Then** the `Project` aggregate lives in `domain.project` and the `ProjectStatus`/`ConnectorKind` registries in `domain.registry`; no Spring/JPA/Jackson/adapter import leaks into either. **No ArchUnit rule edit is needed** — `LAYERED_BOUNDARIES` and `DOMAIN_MUST_BE_FRAMEWORK_FREE` already cover `org.dradgo.domain..` by wildcard. (This story introduces **no** `application.project` code; the `ProjectConnectorResolver` + services arrive in 3c-3. Do not create empty packages.)
7. **Given** the foundation gate (story 1.23), **Then** the new registries + prefixes + error codes are covered **automatically** — `FoundationGateVerificationTest` Contract #3 delegates to `RegistryContractTest` and Contract #7 to `ProblemDetailsCoverageFoundationContract`; extending those two test classes is what widens the gate. **Do NOT add a new `@Nested Contract` class** to `FoundationGateVerificationTest`.
8. **Given** focused tests, **Then** coverage asserts: `Project` aggregate construction + each invariant rejection; `ProjectStatus`/`ConnectorKind` canonical-value parsing + fail-fast on unknown/case-mismatch; registry authority + prefix correctness + schema/API drift (AC4); and error-code registration completeness (AC5). New `domain.project`/`domain.registry` code meets the standing ≥80% line-coverage threshold.

## Tasks / Subtasks

- [x] **Task 1 — Add the two registry enums** (AC: 2, 8)
  - [x] Create `src/main/java/org/dradgo/domain/registry/ProjectStatus.java` — values `ACTIVE("active")`, `DISABLED("disabled")`. Copy the `ArtifactStatus.java` structure verbatim (same package): `implements RegistryValue`, `private static final Map<String, ProjectStatus> LOOKUP = RegistryParsers.index(values())`, `value()`, `static ProjectStatus fromValue(String rawValue)` → `fromValue(rawValue, null)`, `public static ProjectStatus fromValue(String rawValue, String field)` → `RegistryParsers.parse("ProjectStatus", rawValue, field, LOOKUP)`.
  - [x] Create `src/main/java/org/dradgo/domain/registry/ConnectorKind.java` — values `LINEAR("linear")`, `GITHUB("github")`. Same template; registry name string `"ConnectorKind"`.
  - [x] Wire both into `DomainRegistry.java`: add `private static final Set<String> PROJECT_STATUSES = valuesOf(ProjectStatus.values());` + `CONNECTOR_KINDS = valuesOf(ConnectorKind.values());` and accessors `public static Set<String> projectStatuses()` / `connectorKinds()`.
- [x] **Task 2 — Register the public-id prefixes** (AC: 3, 4)
  - [x] In `src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java`, append two entries to the enum:
    - `PROJECT("project", "prj_", "ck_projects_public_id_format")`
    - `PROJECT_CREDENTIAL("projectCredential", "cred_", "ck_project_credentials_public_id_format")`
  - [x] No other change needed — the static prefix-of-prefix invariant block accepts `prj_`/`cred_` (neither shadows nor is shadowed by an existing prefix), and `prefixMap()`/`fromPublicId()`/`require()` pick them up automatically.
- [x] **Task 3 — Create the `Project` domain aggregate** (AC: 1, 6, 8)
  - [x] Create `src/main/java/org/dradgo/domain/project/Project.java` as a `record`:
    ```java
    public record Project(
        String publicId,
        String name,
        String slug,
        ProjectStatus status,
        String repositoryUrl,       // nullable
        ConnectorKind ticketSourceKind,
        ConnectorKind repoHostKind,
        boolean openspecEnabled,
        OffsetDateTime createdAt,
        OffsetDateTime archivedAt)  // nullable
    ```
  - [x] Compact constructor enforces invariants (throw `IllegalArgumentException` for blank/null per the `TicketRef`/`RepositoryRef` precedent, OR `DomainException(INVALID_COMMAND_PAYLOAD, ...)` — pick `IllegalArgumentException` to keep the domain value-object consistent with `TicketRef`; the application/REST layer maps user input to typed errors later). Validate: `name`/`slug` non-blank; `status`/`ticketSourceKind`/`repoHostKind` non-null (`Objects.requireNonNull`); `publicId` via `PublicIdPrefixes.require(publicId, PublicIdPrefixes.PROJECT)`. `repositoryUrl`/`archivedAt` may be null; `createdAt` non-null.
  - [x] Import `ProjectStatus`/`ConnectorKind` from `org.dradgo.domain.registry` and `PublicIdPrefixes` from `org.dradgo.domain.id` (both same layer — allowed). **No Spring/JPA/Jackson imports.**
- [x] **Task 4 — Register the three domain error codes (three sites)** (AC: 5, 7)
  - [x] `DomainErrorCode.java`: add `PROJECT_NOT_FOUND("PROJECT_NOT_FOUND")`, `PROJECT_SLUG_CONFLICT("PROJECT_SLUG_CONFLICT")`, `UNSUPPORTED_CONNECTOR_KIND("UNSUPPORTED_CONNECTOR_KIND")` near the end (keep a short `// Story 3c-2 (AC5)` comment; wireValue must equal the constant name per the V1 contract).
  - [x] `ProblemDetailsCatalog.java`: add three `register(metadata, ...)` calls before the "must map every DomainErrorCode" guard — `PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Project not found", false`; `PROJECT_SLUG_CONFLICT, HttpStatus.CONFLICT, "Project slug conflict", false`; `UNSUPPORTED_CONNECTOR_KIND, HttpStatus.BAD_REQUEST, "Unsupported connector kind", false`. (The type URI auto-derives from the code via `toUriSlug`.)
  - [x] `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`: add three entries to the `problemTypeUris` object — `"PROJECT_NOT_FOUND": "https://deliveryline.local/problems/project-not-found"`, `"PROJECT_SLUG_CONFLICT": ".../project-slug-conflict"`, `"UNSUPPORTED_CONNECTOR_KIND": ".../unsupported-connector-kind"`.
- [x] **Task 5 — Add the registry/prefix value sets to the API placeholder manifest** (AC: 4)
  - [x] In the same `registry-api-schema-placeholders.json`, add top-level arrays `"projectStatuses": ["active", "disabled"]` and `"connectorKinds": ["linear", "github"]`, and add `"project": "prj_"` + `"projectCredential": "cred_"` to the `publicIdPrefixes` object (keys = the PublicIdPrefixes **aliases**).
- [x] **Task 6 — Extend `RegistryContractTest` (the 1.4 drift gate)** (AC: 4, 7, 8)
  - [x] Add to `registryCatalogExposesTheAuthoritativeFoundationValueSets()`:
    `assertEquals(registryValues(ProjectStatus.values()), DomainRegistry.projectStatuses());`
    `assertEquals(registryValues(ConnectorKind.values()), DomainRegistry.connectorKinds());`
  - [x] Add a new `@Test void projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest()`:
    ```java
    assertEquals(DomainRegistry.projectStatuses(), extractConstraintValues("ck_projects_status"));
    assertEquals(DomainRegistry.projectStatuses(), readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "projectStatuses"));
    assertEquals(DomainRegistry.connectorKinds(), extractConstraintValues("ck_projects_ticket_source_kind"));
    assertEquals(DomainRegistry.connectorKinds(), extractConstraintValues("ck_projects_repo_host_kind"));
    assertEquals(DomainRegistry.connectorKinds(), readArrayNonEmpty(API_PLACEHOLDER_RESOURCE, "connectorKinds"));
    ```
    (Imports: `org.dradgo.domain.registry.ProjectStatus`, `...ConnectorKind`.)
  - [x] **Do not** touch `everyCurrentPersistenceBoundaryUsesUniformFailFastRegistryParsing` — there is no project persistence boundary yet (no JPA entity/mapper in 3c-2; that arrives in 3c-6). Adding a `PersistedRegistryValues.projectStatus(...)` wrapper + a boundary entry is **deferred to 3c-6**.
  - [x] The existing `publicIdPrefixesStayAlignedWithSqlChecksAndParsingHelpers` and `publicIdPrefixSqlRegexExactlyMatchesJavaSuffixPattern` now also exercise `prj_`/`cred_` (they iterate `PublicIdPrefixes.values()` and query the DB) — confirm they pass against V17 with no edit.
- [x] **Task 7 — Focused unit tests** (AC: 8)
  - [x] `src/test/java/org/dradgo/domain/project/ProjectTest.java` — valid construction; one rejection case each for blank `name`, blank `slug`, null `status`, null `ticketSourceKind`, and a bad `publicId` prefix (assert the `require()` path throws).
  - [x] `src/test/java/org/dradgo/domain/registry/ProjectRegistryParsingTest.java` (or fold into one class) — `ProjectStatus.fromValue("active")` / `ConnectorKind.fromValue("github")` canonical accept; unknown + case-mismatch (`"ACTIVE"`, `"__bogus__"`) throw `DomainException` with `UNKNOWN_REGISTRY_VALUE`, mirroring `runnerKindPersistedRegistryParserAcceptsCanonicalValues`. (These tests are what cover the otherwise-unused `fromValue` methods for JaCoCo.)
- [x] **Task 8 — Run the gated suites and capture evidence** (AC: all)
  - [x] Backend integration tier (Testcontainers PG 17.2): `RegistryContractTest` green (new alignment test + catalog additions). Use the lifecycle `integration-test` phase, not the `failsafe:` direct goal (Maven argLine note).
  - [x] `-Pfoundation-gate verify`: BUILD SUCCESS — Contract #3 (`RegistryContractTest`) + Contract #7 (`ProblemDetailsCoverageFoundationContract`, which round-trips the 3 new codes through the mapper) both green; `domainErrorCodesStayAlignedWithProblemTypeOwnershipManifest` green (enum ↔ catalog ↔ manifest in lockstep).
  - [x] `ArchitectureBoundaryTest` green (confirms `domain.project` stays framework-free with zero rule edits).
  - [x] `spotless:check` / `checkstyle:check` clean on the touched files. (The pre-existing `WorkflowCommandService.java:810` `ForbiddenThreadSleep` checkstyle violation is from unrelated uncommitted work — not this story; do not "fix" it here.)
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **N/A with rationale — record in Completion Notes.** This story adds only a domain `record`, two registry enums, prefix-registry + error-code declarations, and contract/unit tests. There is **no** application-service entry/exit, no SPI/persistence/state-machine code, no `DomainException` raise site reachable from production flow (the new codes are registered ahead of their 3c-3/3c-8 throw sites), and no recovery branch — so there is no SLF4J/MDC surface to instrument. The `Project` invariant guards throw `IllegalArgumentException`/`DomainException` synchronously to their caller; logging belongs at the first **application** caller (3c-3 resolver, 3c-6 persistence, 3c-8 REST). **Forward note for those stories:** project credential plaintext/ciphertext, `key_id`, `algo`, and the master key must **never** reach logs/events/artifacts/exports (becomes a foundation-gate assertion in 3c-5).

## Dev Notes

### Why these ACs differ from the epic (the reconciliations that matter)

The dev agent will only have this file. These are the traps where the epic text collides with an **enforced** codebase invariant; follow the reconciled ACs, not the epic wording.

- **R1 — `ConnectorKind` = `{linear, github}`, NOT `{ticket_source, repo_host}`.** Epic AC2 says `ConnectorKind` is "ticket-source kinds + repo-host kinds". In V17 those are the **`ck_projects_ticket_source_kind`** / **`ck_projects_repo_host_kind`** CHECKs, both `in ('linear','github')`. `('ticket_source','repo_host')` is a **different** value set — the `connector_role` CHECK on `project_credentials` (`ck_project_credentials_connector_role`). 3c-2 does **not** add a registry for `connector_role`; that is a credential concern owned by **3c-5** (where the credential store reads/writes the role). Drift-test `ConnectorKind` against the two `*_kind` CHECKs only. [Source: 3c-1 story Dev Notes "Exact migration SQL"; epic-03c §Story-3c-2 AC2]
- **R2 — Drift against V17, not "V14".** Epic AC3 and `architecture.md:283` say the registries drift against the "V14 DB CHECK constraints". 3c-1 shipped them in **`V17__create_projects_and_credentials.sql`** (head on disk was `V16`). The CHECK **names** are what `extractConstraintValues`/`extractPublicIdPrefixesFromSql` query (`ck_projects_status`, `ck_projects_ticket_source_kind`, `ck_projects_repo_host_kind`, `ck_projects_public_id_format`, `ck_project_credentials_public_id_format`) — version-agnostic, so this is purely a "where the values live" clarification. [Source: [[story-3c-1-projects-schema-reconciliations]]]
- **R3 — "frontend allowed-value lists" (epic AC3) is N/A here.** The only frontend drift placeholder today is `contracts/frontend/allowed-actions.placeholder.json` (for `AllowedAction`). There is **no** frontend consumer of `ProjectStatus`/`ConnectorKind` until the Projects UI (3c-9). Drift is asserted against the **SQL CHECK + the backend API placeholder JSON** only. Do not invent a frontend placeholder for these enums. [Source: RegistryContractTest.java:76-77,322-326]
- **R4 — `prj_`/`cred_` are public_id *prefixes*, and the format CHECKs already exist.** 3c-1 created `ck_projects_public_id_format` (`^prj_[A-Za-z0-9_-]{4,64}$`) and `ck_project_credentials_public_id_format` (`^cred_...$`) precisely so 3c-2 can wire the `PublicIdPrefixes` enum entries with **zero schema rework**. Once `PROJECT`/`PROJECT_CREDENTIAL` exist, `extractPublicIdPrefixesFromSql()` (iterates `values()`) and `publicIdPrefixSqlRegexExactlyMatchesJavaSuffixPattern` (also iterates `values()`, asserts `matcher.group(1) == prefix()`) cover them automatically. [Source: 3c-1 story AC11; RegistryContractTest.java:216-288,553-567; PublicIdPrefixes.java:13-23]
- **R5 — AC6/AC7 ("add to the foundation gate") is satisfied by extending the *delegate* tests, not by editing `FoundationGateVerificationTest`.** That aggregator only delegates by FQN: Contract #3 → `RegistryContractTest`, Contract #7 → `ProblemDetailsCoverageFoundationContract`. Both are re-run under `-Pfoundation-gate`; extending them (Tasks 4-6) IS the gate-widening. Adding a `@Nested Contract16…` would duplicate, not strengthen. [Source: FoundationGateVerificationTest.java:59-70,118-129]
- **R6 — Error codes are registered *ahead of use*.** `PROJECT_NOT_FOUND`/`PROJECT_SLUG_CONFLICT` are thrown by the REST/service layer in **3c-8**; `UNSUPPORTED_CONNECTOR_KIND` by the resolver in **3c-3**. No production throw site exists in 3c-2 — and none is required: `ProblemDetailsCoverageFoundationContract` constructs a `DomainException` for **every** `DomainErrorCode` and round-trips it through `ProblemDetailsMapper`, so registration alone is fully verified. The three-sites rule ([[new-domainerrorcode-three-sites]]) still applies: enum + `ProblemDetailsCatalog` + manifest, or `-Pfoundation-gate` reds. [Source: docs/patterns/registry-recipe.md §3]
- **R7 — No persistence, no JPA entity, no `application.project` in this story.** AC1 is a **domain `record`** (config value object), not a JPA `@Entity`. The persistence adapter/entity/mapper + `PersistedRegistryValues` field-context wrappers land when rows are first read/written through the app (3c-6 default-project seed; 3c-8 CRUD). 3c-1 noted "Project entity = 3c-2/3c-5" — the *domain* `Project` is here; the *persisted* entity is 3c-5/3c-6. Keep 3c-2 to `domain.*` + contract/test wiring so it stays a pure, low-risk foundation increment. [Source: 3c-1 story Dev Notes Task 3; epic-03c §Story-3c-2 AC1/AC5]
- **R8 — `Project` invariants throw `IllegalArgumentException`, not typed `DomainException`.** Follow the `TicketRef`/`RepositoryRef` value-object precedent: domain value objects guard with `IllegalArgumentException`/`Objects.requireNonNull`. The one exception is the `publicId` guard, which routes through `PublicIdPrefixes.require(...)` (which throws a typed `DomainException(INVALID_ID_PREFIX)` — that's the established prefix-validation path, keep it). User-facing slug/name validation → typed errors happens at the REST boundary in 3c-8, not in the domain record. [Source: domain/integration/ticketsource/TicketRef.java:14-26; PublicIdPrefixes.java:103-109]

### Exact deltas (copy-ready)

**`ProjectStatus.java`** (new, `org.dradgo.domain.registry`) — mirror `ArtifactStatus.java`:
```java
public enum ProjectStatus implements RegistryValue {
  ACTIVE("active"),
  DISABLED("disabled");

  private static final Map<String, ProjectStatus> LOOKUP = RegistryParsers.index(values());
  private final String value;

  ProjectStatus(String value) { this.value = value; }

  @Override public String value() { return value; }

  static ProjectStatus fromValue(String rawValue) { return fromValue(rawValue, null); }

  public static ProjectStatus fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ProjectStatus", rawValue, field, LOOKUP);
  }
}
```
**`ConnectorKind.java`** (new, same package) — identical shape, values `LINEAR("linear")` / `GITHUB("github")`, registry name `"ConnectorKind"`.

**`PublicIdPrefixes.java`** — append (after `BATCH_SUBMISSION`):
```java
  PROJECT("project", "prj_", "ck_projects_public_id_format"),
  PROJECT_CREDENTIAL("projectCredential", "cred_", "ck_project_credentials_public_id_format");
```
(Move the `;` to the last entry; the static prefix-of-prefix block needs no change.)

**`DomainRegistry.java`** — two fields + two accessors (see Task 1).

**API placeholder JSON** — two arrays + two `publicIdPrefixes` keys + three `problemTypeUris` entries (Tasks 4-5). The `RegistryContractTest` helpers assert the placeholder arrays contain **no duplicates** and are non-empty.

### Project Structure Notes

- New: `src/main/java/org/dradgo/domain/project/Project.java`; `src/main/java/org/dradgo/domain/registry/ProjectStatus.java`, `ConnectorKind.java`.
- Modified: `src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java`; `src/main/java/org/dradgo/domain/registry/DomainRegistry.java`; `src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`; `src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`; `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`; `src/test/java/org/dradgo/contract/RegistryContractTest.java`.
- New tests: `src/test/java/org/dradgo/domain/project/ProjectTest.java`; a `ProjectStatus`/`ConnectorKind` parse test under `src/test/java/org/dradgo/domain/registry/`.
- **No** `application/**`, no persistence, no REST controller, no OpenAPI snapshot regen, no `application.yml`/test-yaml change (no validated `@ConfigurationProperties`), no Flyway change (V17 is done), no frontend change.
- `domain.registry` is the right home for the two enums (every sibling registry — `ArtifactStatus`, `RunnerKind`, `RunnerStage` — lives there and `DomainRegistry` references them same-package). The aggregate `record` lives in `domain.project` per AC6.

### Logging Requirements (project-wide standard)

Domain-model + registry-wiring story: **no application log surface is introduced** (see the Logging task rationale above). Do not add logging to satisfy the template. The credential-redaction obligation foreshadowed by 3c-1 (ciphertext/`key_id`/`algo`/master key must never reach logs/events/artifacts/exports) is implemented and gated when the credential code lands (3c-4/3c-5; 3c-5 AC6 makes it a foundation-gate assertion).

### References

- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-2] — the source ACs (reconciled here).
- [Source: _bmad-output/implementation-artifacts/3c-1-flyway-v14-projects-and-credentials-schema-and-project-id-association.md] — V17 schema, CHECK names, and the "3c-2 owns the enum/prefix-registry/manifest" handoff (story AC11, Dev Notes R5/R8).
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java] — the 1.4 drift gate this story extends (catalog test :86-116; status drift :136-178; prefix drift :216-288; error-code manifest :329-371; `extractConstraintValues` :525-551; `extractPublicIdPrefixesFromSql` :553-567).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java:12-23] — prefix enum to extend.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainRegistry.java] — central catalog to extend; [Source: .../domain/registry/ArtifactStatus.java; RunnerStage.java] — the enum template.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java] — three-sites error-code catalog; [Source: .../domain/registry/DomainErrorCode.java] — the enum.
- [Source: deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json] — the API ownership manifest (arrays + `publicIdPrefixes` + `problemTypeUris`).
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java:59-70,118-129] — Contract #3/#7 delegation (why AC6/AC7 need no aggregator edit).
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java] — `LAYERED_BOUNDARIES` + `DOMAIN_MUST_BE_FRAMEWORK_FREE` (wildcard `org.dradgo.domain..`, covers `domain.project` with no edit).
- [Source: docs/patterns/registry-recipe.md §3] — the DomainErrorCode three-sites recipe ([[new-domainerrorcode-three-sites]]).

### Open Questions / Decisions for Alex (non-blocking — defaults are in place)

1. **`ProjectStatus` value set = `('active','disabled')`** (matches the V17 CHECK + epic "e.g. active, disabled"). If you want `archived` as a first-class status it must widen both the enum and the `ck_projects_status` CHECK (a new migration) — **not** done here. Proceeding with two values.
2. **`ConnectorKind` = `('linear','github')`.** The "one additional proof kind" (epic 3c-3 AC8) is **3c-3**'s job (widen `connector_kind` via the V12/V16 drop-then-readd idiom). 3c-2 registers only the two current kinds.
3. **No `ConnectorRole` registry for `connector_role` (`ticket_source`/`repo_host`).** Deferred to **3c-5** (credential store consumes it). The V17 `ck_project_credentials_connector_role` CHECK stays enum-invisible (consistent with 3c-1). Confirm 3c-5 owns it — assumed yes.
4. **`Project` invariant exceptions are `IllegalArgumentException`** (value-object precedent), except `publicId` → typed `DomainException(INVALID_ID_PREFIX)` via `PublicIdPrefixes.require`. If you'd prefer the whole aggregate to throw typed `INVALID_COMMAND_PAYLOAD`, say so — it's a one-method change.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context) — bmad-dev-story workflow

### Debug Log References

- `-Pfoundation-gate verify` (Testcontainers PG 17.2): **BUILD SUCCESS** — Surefire unit tier 1084 tests / 0 failures (incl. `ProjectTest` 9 [original 6 + 2 null-invariant + 1 cred_-prefix tests added in review], `ProjectRegistryParsingTest` 4); foundation-gate Failsafe tier 36 tests / 0 failures incl. **Contract #3** (RegistryContractTest — extended drift gate), **Contract #7** (ProblemDetails/DomainErrorCode round-trip for the 3 new codes), **Contract #1** (ArchUnit package boundaries). spotless/checkstyle/jacoco gates clean (bound to `verify`). _(Review 2026-06-20: `mvnw -o -pl deliveryline-backend test -Dtest=ProjectTest` → 9 tests / 0 failures.)_
- `ArchitectureBoundaryTest` (Failsafe, `integration-test` phase + `-Djacoco.skip=true` per the Maven argLine note): **56 tests / 0 failures** — confirms `domain.project` + the two new `domain.registry` enums stay framework-free with zero ArchUnit rule edits (AC6).

### Completion Notes List

- **AC1/AC6** — `Project` record created in `domain.project` mirroring the V17 `projects` columns; compact constructor enforces non-blank `name`/`slug` (IllegalArgumentException), non-null `status`/`ticketSourceKind`/`repoHostKind`/`createdAt` (Objects.requireNonNull → NullPointerException), and `publicId` via `PublicIdPrefixes.require(publicId, PublicIdPrefixes.PROJECT)` (typed `DomainException(INVALID_ID_PREFIX)`). `repositoryUrl`/`archivedAt` nullable. No Spring/JPA/Jackson imports (R8 precedent; R7 — no persistence/JPA entity/`application.project` in this story).
- **AC2** — `ProjectStatus`(active,disabled) + `ConnectorKind`(linear,github) added to `domain.registry` on the `ArtifactStatus` template; both exposed via `DomainRegistry.projectStatuses()`/`connectorKinds()`.
- **AC3** — `PublicIdPrefixes` gained `PROJECT("project","prj_","ck_projects_public_id_format")` + `PROJECT_CREDENTIAL("projectCredential","cred_","ck_project_credentials_public_id_format")`; the prefix-of-prefix invariant and `prefixMap()`/`fromPublicId()`/`require()` pick them up automatically.
- **AC4** — `RegistryContractTest` extended: catalog assertions for the two new value sets + a new `projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest()` aligning `ProjectStatus` ↔ `ck_projects_status` + manifest, and `ConnectorKind` ↔ **both** `ck_projects_ticket_source_kind` and `ck_projects_repo_host_kind` + manifest (R1). The pre-existing prefix-drift tests now also exercise `prj_`/`cred_` against V17 with no edit.
- **AC5/AC7** — `PROJECT_NOT_FOUND`(404), `PROJECT_SLUG_CONFLICT`(409), `UNSUPPORTED_CONNECTOR_KIND`(400), all retryable=false, registered at the three sites (DomainErrorCode enum + ProblemDetailsCatalog + manifest `problemTypeUris`). Registered ahead of their 3c-3/3c-8 throw sites (R6); the foundation gate round-trips every code through `ProblemDetailsMapper` so no production throw site is required. AC7 satisfied by extending the delegate tests (Contract #3 / Contract #7) — **no** `@Nested Contract` added to `FoundationGateVerificationTest` (R5).
- **AC8** — focused `ProjectTest` (valid construction + 6 rejection cases covering every invariant: blank name, blank slug, null status, null ticketSourceKind, null repoHostKind, null createdAt, bad publicId prefix + a positive `cred_`/`PROJECT_CREDENTIAL` prefix exercise) and `ProjectRegistryParsingTest` (canonical accept + unknown/case-mismatch fail-fast on `UNKNOWN_REGISTRY_VALUE`) cover the new domain/registry code and the otherwise-unused `fromValue` methods. _(Review 2026-06-20: added null `repoHostKind`/`createdAt` rejection tests + the `cred_` positive case.)_
- **Logging task — N/A (per the story rationale).** This story adds only a domain `record`, two registry enums, prefix-registry + error-code declarations, and contract/unit tests — no application-service entry/exit, no SPI/persistence/state-machine code, no production-reachable `DomainException` raise site, no recovery branch. There is no SLF4J/MDC surface to instrument; logging belongs at the first application caller (3c-3 resolver, 3c-6 persistence, 3c-8 REST). **Forward note:** project credential plaintext/ciphertext, `key_id`, `algo`, and the master key must never reach logs/events/artifacts/exports (becomes a foundation-gate assertion in 3c-5).
- **Open Questions** — proceeded with all four documented defaults (ProjectStatus={active,disabled}; ConnectorKind={linear,github}; no ConnectorRole registry here; Project invariants throw IllegalArgumentException/NullPointerException except publicId→typed DomainException). None blocking.

### File List

**New (src/main):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/project/Project.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ProjectStatus.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ConnectorKind.java`

**Modified (src/main):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainRegistry.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`

**New (src/test):**
- `deliveryline-backend/src/test/java/org/dradgo/domain/project/ProjectTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/domain/registry/ProjectRegistryParsingTest.java`

**Modified (src/test):**
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`

### Change Log

- 2026-06-20 — Implemented story 3c-2 (Project domain aggregate + ProjectStatus/ConnectorKind registries + prj_/cred_ prefix registrations + 3 ahead-of-use DomainErrorCodes + drift-test extensions). All 8 ACs satisfied. `-Pfoundation-gate verify` BUILD SUCCESS (1081 unit + 36 foundation-gate tests, 0 failures); `ArchitectureBoundaryTest` 56/56. Status → review.

### Review Findings

_Adversarial code review 2026-06-20 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). All 8 ACs PASS; R1–R8 reconciliations honored. 0 decision-needed, 2 patch, 0 defer, 13 dismissed as by-design or verified false-positive._

- [x] [Review][Patch] `ProjectTest` omits null-rejection cases for `repoHostKind` and `createdAt` — the compact constructor guards all six invariants but only 4 of them are tested (blank name, blank slug, null status, null ticketSourceKind, bad publicId). Two `Objects.requireNonNull` branches (`repoHostKind`, `createdAt`) have zero coverage and are visually identical to the tested ones, so a future reorder/delete would not red any test. AC8 + Completion Notes claim "each invariant rejection" — not strictly true until these are added. Triple-sourced (blind+edge+auditor). [deliveryline-backend/src/test/java/org/dradgo/domain/project/ProjectTest.java]
- [x] [Review][Patch] No positive exercise of the `cred_` / `PROJECT_CREDENTIAL` prefix — `cred_` appears only as the negative case in `rejectsPublicIdWithWrongPrefix`; nothing constructs/validates a `cred_` id positively. Prefix *correctness* is already drift-covered by `RegistryContractTest` (iterates `PublicIdPrefixes.values()` against the V17 format CHECK regex), so this is a belt-and-suspenders coverage add, not a correctness gap. Optional. [deliveryline-backend/src/test/java/org/dradgo/domain/project/ProjectTest.java]

> **Dismissed (verified by-design or false-positive — no action):** exception-type mix IAE/NPE/typed-DomainException (spec R8 + Open Q4 default); `repositoryUrl` unvalidated (deferred to 3c-8 REST boundary per javadoc); `UNSUPPORTED_CONNECTOR_KIND` vs `UNKNOWN_REGISTRY_VALUE` overlap (intentional — distinct 3c-3 resolver throw site, registry-valid-but-unsupported semantic, not a parse failure); manifest URIs uncross-checked (round-tripped by Contract #7 + `domainErrorCodesStayAlignedWithProblemTypeOwnershipManifest`); drift test Set-vs-List ordering (both `LinkedHashSet`, order-insensitive); `connectorKinds()` shared across both `*_kind` CHECKs (R1 by-design); `PROJECT_SLUG_CONFLICT`/409 has no enforcement here (`uq_projects_slug` exists in V17; throw site 3c-8); dead error codes (ahead-of-use per R6); no null/empty parse test + `openspecEnabled` true-path + name/slug trim-vs-uniqueness (minor/deferred to 3c-8); `require(null)` javadoc wording (behavior correct — typed `INVALID_ID_PREFIX`). _(The Auditor's "ProjectTest has 5 @Test, record says 6" nit was itself a miscount — the file had 6; it now has 9 after the patches above.)_

**Patches applied (2026-06-20):** both `[Review][Patch]` items above fixed in `ProjectTest.java` — added `rejectsNullRepoHostKind`, `rejectsNullCreatedAt`, and `projectCredentialPrefixIsRegisteredAndAcceptsCredId`. Verified: `mvnw -o -pl deliveryline-backend test -Dtest=ProjectTest` → **9 tests / 0 failures**.
