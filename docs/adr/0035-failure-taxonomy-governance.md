# ADR 0035 — Failure Taxonomy Governance (Deprecate-Never-Remove)

**Status:** Proposed (2026-07-12) — to be confirmed on merge of story 4-9
**Driver:** Epic 4 (Failure Handling, Recovery & Reconciliation), story 4.9 (`RecoveryService.classifyFailure` — FR37/FR38, NFR33). Workflow owners need to classify failed runs against a governed taxonomy for cross-run pattern analysis (Growth-stage analytics per PRD § Growth Features), and NFR33 demands that a classification applied to a historical run stays interpretable no matter how the taxonomy evolves later. `classifyFailure` is pre-sanctioned on ADR [0033](0033-recovery-service-scope-lift.md)'s (c) allow-list; this ADR records the taxonomy-registry governance that backs it.

## Context

The codebase already carries a failure-classification axis: `FailureCategory` (`runner_timeout`, `runner_crash`, `orphan`, …) — machine-emitted, scoped to the runner-execution lifecycle, and written by the system onto `workflow_events` / `runner_executions` / `artifacts` / `artifact_operations` at failure time. It answers *what the runner did*.

Story 4.9 needs a second, orthogonal axis: *why the run failed, in the operator's judgment*. That is a human-applied, run-scoped, post-hoc triage value (e.g. a run can be `runner_contract_violation` on the machine axis and `specification_gap` on the human axis simultaneously). Because these classifications feed cross-run analytics over long time windows, the registry cannot silently lose values: a hard-removed value would leave historical `workflow_runs.failure_classification` rows unreadable (a raw string with no registry entry, no display label, and a red SQL-drift gate).

## Decision

(a) **Six canonical values, from PRD § Technical Success.** `FailureTaxonomyValue` = { `specification_gap`, `context_gap`, `agent_execution_failure`, `review_rejection`, `integration_or_merge_failure`, `tooling_or_infrastructure_failure` }, a `RegistryValue` enum in `domain/registry/`, persisted on `workflow_runs.failure_classification` (Flyway V44) behind the `ck_workflow_runs_failure_classification` CHECK.

(b) **Orthogonal to `FailureCategory` — never extend, reuse, or replace it.** The two axes coexist on one run. No change to `FailureCategory`, `describeFailure`, or any `failure_category` column is sanctioned by taxonomy work, and vice versa.

(c) **NFR33 — deprecate with a replacement, never hard-remove.** A retired value is marked deprecated by setting its `deprecatedReplacementValue` constructor argument to the replacement's *wire* value (a `String`, not an enum reference — Java forbids the forward reference). `deprecated()` ≡ `deprecatedReplacementValue() != null`. The value's enum constant, its CHECK-constraint slot, and every historical row keep working forever.

(d) **Reads are total; writes reject deprecated values.** `fromValue` never rejects a deprecated value; human-readable output renders it as `"value (deprecated)"` via `displayLabel()`. Only NEW classifications reject: `FailureTaxonomyPolicy.requireNotDeprecated(wire, replacementWire)` throws `DEPRECATED_TAXONOMY_VALUE` (400) carrying `details.replacementValue` as the remediation hint. The guard is a pure static over wire strings so its semantics stay unit-tested with synthetic arguments while the registry ships with zero deprecated values.

(e) **Adding a value is governed.** A new taxonomy value requires: a new ADR (or an amendment to this one), an append to `FROZEN_WIRE_VALUES` in `FailureTaxonomyValueTest` (the stability gate asserts *containment*, so additions are free and removals red the build), and a widening of the `ck_workflow_runs_failure_classification` CHECK in a new migration (the registry-vs-SQL set-equality gate in `RegistryContractTest` reds otherwise).

## Alternatives Considered

### Alt 1 — Reuse/extend `FailureCategory` with the six operator values
**Rejected.** The axes have different writers (system vs human), different scopes (runner execution vs run), and different lifecycles (emitted once at failure vs re-classifiable). Mixing them would let a machine writer stamp an operator-judgment value and would break every `failure_category` consumer's semantics.

### Alt 2 — Hard-remove retired values and migrate historical rows to the replacement
**Rejected.** Rewriting historical classifications destroys the analytical record NFR33 exists to protect (the operator's original judgment is the datum). It also breaks FR47's append-only audit expectation — the `recovery.failureClassified` event chain would disagree with the rewritten rows.

### Alt 3 — A `failure_taxonomy` lookup table instead of a registry enum
**Rejected / out of scope.** Every other governed vocabulary in this codebase is a `RegistryValue` enum with drift gates (SQL CHECK, fixture, placeholder manifest); a lookup table would be the sole runtime-mutable registry, needing its own admin surface, cache, and consistency story. The enum + ADR-governed evolution matches the house pattern and the (low) expected rate of taxonomy change.

## Consequences

- Historical classifications stay interpretable forever: a deprecated value still parses, still satisfies the CHECK, and renders with a `(deprecated)` affix (NFR33 satisfied by construction).
- The write path steers operators to current vocabulary — `DEPRECATED_TAXONOMY_VALUE` carries the replacement, which story 4.14 (REST) and 4.24 (UI dropdown) surface directly.
- Taxonomy evolution is deliberately friction-ful: enum edit + ADR + frozen-set append + CHECK widening. That is the point — the taxonomy is an analytical contract, not a config knob.
- The `ck_workflow_runs_failure_classification` CHECK only ever grows, so the registry-vs-SQL set-equality drift gate coexists with the never-remove rule.
