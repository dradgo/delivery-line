# Story 1.11 Validation Report

**Story:** `1-11-archunit-package-boundary-tests`
**Reviewed:** 2026-05-02
**Reviewer:** `bmad-create-story:validate`
**Outcome:** All 16 improvements applied. Story remains `ready-for-dev`.

## Scope of review

Cross-checked the story against:
- `_bmad-output/planning-artifacts/epics.md` — story 1.11 canonical AC text
- `_bmad-output/planning-artifacts/architecture.md` — boundary/quality-gate clauses
- `_bmad-output/implementation-artifacts/1-5-*.md`, `1-7-*.md`, `1-10-*.md` — predecessor contracts
- Live source: `deliveryline-backend/src/main/java/org/dradgo/{application,adapters,domain,infrastructure}/...`
- `deliveryline-backend/pom.xml` — ArchUnit dependency status

All factual claims about current code state held up: JPA/repo leakage in `WorkflowTransitionService`, `WorkflowCommandService`, `IdempotencyService` is real; no `adapters.persistence.mapper` package exists; no ArchUnit dependency in `pom.xml`; `application.security/` and `domain.registry.DataClassification` are present as referenced; `org.dradgo.infrastructure/` package is present in the tree.

## Critical issues fixed

| # | Issue | Resolution |
|---|---|---|
| C1 | AC7 `Mapper`-suffix rule would misfire on existing `adapters.rest.ProblemDetailsMapper` | AC7 rewritten as **location-qualified**: classes ending in `Mapper` outside `adapters.persistence.mapper` are not flagged. |
| C2 | AC3 left submit-bootstrap exception as a fork ("encode exception OR refactor") | AC3 + Task 3/4 force the **refactor**: initial state set via factory/constructor; `setCurrentState(...)` removed from submit path; no documented exception. |
| C3 | Task ordering would produce a red-CI window mid-story | New "Sequencing constraint" section in Dev Notes: Task 3 lands first (or in same PR as Task 2); never push layer rules ahead of the refactor. |
| C4 | AC1 omitted `infrastructure.*` boundary; package already exists | AC1 expanded into a 4-bullet layered rule covering `domain`, `application`, `adapters.*`, and `infrastructure.*`. |
| C5 | AC5 redaction-fence detection on `DataClassification` enum references would false-positive (enum lives in `domain.registry`, legitimately referenced from `application.security`) | AC5 + Task 4 narrowed: fence is on **credential-regex pattern ownership only**; enum-reference criterion removed; explicit do-not list added. |
| C6 | AC8 "clear, actionable diagnostic" was unverifiable | New AC8 clause + Task 1 subtask: meta-test deliberately violates one named rule and asserts message format (rule name + class + remediation hint). |

## Enhancements added

| # | Issue | Resolution |
|---|---|---|
| E1 | `Commands` (plural, Spring Shell) vs `Command` (singular, application DTOs) collision risk | AC7 + Task 2 disambiguate: `Commands` rule scoped to `adapters.cli`, `Command` rule scoped to `application`. No generic `*Command` predicate. |
| E2 | AC10 registry-drift "delegation" mechanism was hand-wavy | AC10 + Task 5 picked option (a): include `RegistryContractTest` in same Maven Surefire group as the architecture suite; ArchUnit owns shape, contract test owns enum-value sync. |
| E3 | Pom dependency-version guidance missing | Task 1 explicitly: literal `1.4.2` on the dependency, no `<archunit.version>` property unless multiple ArchUnit artifacts are added. |
| E4 | "Architecture test tier" wording premature (story 1-21 owns CI tier) | AC8 clarified: rules run in `mvn test` today; 1-21 owns profile/group routing; this story does not. |
| E5 | Stale comment referenced `validate-create-story` | Updated to `bmad-create-story:validate`. |

## Optimizations applied

| # | Issue | Resolution |
|---|---|---|
| O1 | Pre-filled Dev Agent Record invited the implementing agent to append rather than own it | Dev Agent Record subsections cleared; create-step audit moved to new "Story Creation Audit" section. |
| O2 | Future-class string-based rule pattern was abstract | Added a 5-line commented `ArchCondition` sketch under Task 4 to lock the pattern. |
| O3 | Dev Notes "Current repo state" duplicated Task 3 detail | Trimmed to a path-list with one-line concerns under "Code state to be aware of". |

## LLM optimization applied

| # | Issue | Resolution |
|---|---|---|
| L1 | "Do not..." directives scattered across Tasks 3-5 and Implementation guardrails | Consolidated into single "Hard prohibitions (consolidated)" block at top of Dev Notes. |
| L2 | Tasks mixed imperatives with narrative justification | Trimmed Task bullets to imperatives; "why" content moved to Dev Notes (predecessor-story contracts, guardrails). |

## Sprint-status drift

`_bmad-output/implementation-artifacts/sprint-status.yaml` already shows `1-11-archunit-package-boundary-tests: ready-for-dev` and `last_updated: 2026-05-02`. No drift fix needed.

## Recommendation

Story is fit for `bmad-dev-story`. The seam refactor in Task 3 is the highest-risk slice — recommend the dev agent open the PR with Task 3 + Task 2 in a single review, never separately, and run `mvn test` after each phase.
