# ADR 0019: Structured Logging & Stable Correlation-ID Surface

**Status:** Accepted
**Date:** 2026-05-16
**Story:** 1.19

## Decision

Application logs use **SLF4J + Logback** with two profile-conditional appenders:

- `demo` profile → JSON (via `logstash-logback-encoder` composite encoder) for machine ingestion.
- `local` / `test` (default) profile → human-readable `PatternLayout` for `tail -f` operators.

Both appenders route every log message through the redacting layer
(`RedactingMessageConverter` for pattern; `RedactingJsonProvider` for JSON), which delegates to
`RedactionPolicyService` so no raw secrets, unredacted context bundles, or runner output reach
the log stream — even if a call-site forgets to filter.

A stable MDC key surface is published from
`org.dradgo.infrastructure.observability.MdcKeys`: exactly five keys, exact camelCase casing:

```
correlationId, workflowRunId, runnerExecutionId, artifactId, artifactOperationId
```

No other MDC keys are introduced. Drift is enforced by `LoggingFieldNameContractTest`.

## Context

- The architecture's *Hard Invariants* (architecture.md line 848) require stable correlation
  field names across CLI, REST, runner dispatch, artifact operations, and failure paths.
- Stories 1.13–1.18 each added SLF4J logging at call sites with parameterised `correlationId={}`
  placeholders. Story 1.19's contribution is **infrastructure** — JSON encoding, MDC
  propagation, redacting layer — not new log statements.
- The redaction policy (story 1.10) is the source of truth for "what is a secret"; the layout
  is defense-in-depth, not the primary gate. Each call site at the four flagged surfaces
  (`RunnerBroker.dispatch`, `LinearRealAdapter`, `DoctorProbeAdapter.probeConfigPermissions`,
  `LocalArtifactStore`) MUST also restrict to permitted fields at the call site (AC6).

## Consequences

### Positive

- Operators get a stable JSON schema in the `demo` profile that downstream log shippers (ELK,
  CloudWatch) can index without per-message parsing.
- The `correlationId` REST extension on Problem Details (AC8) lets API clients copy/paste a
  single value into log search to reconstruct any failure.
- The redacting layer survives careless call sites — a future developer logging
  `log.info("payload {}", rawBody)` cannot leak a secret, because the layout strips it before
  write.

### Negative / Open

- The `logstash-logback-encoder` Maven dependency is a transitive add (~150 KB). Boot 4's
  built-in `StructuredLogEncoder` was evaluated first per the story's Open Clarification 1; the
  logstash encoder's custom `JsonProvider` SPI was preferred because it cleanly accepts our
  `RedactingJsonProvider` for the message field. The built-in encoder lacks a comparable hook.
- The redacting layer relies on a static `RedactionLayoutHolder` (Logback instantiates
  converters reflectively, outside the Spring container). Log lines emitted before the holder
  is wired (Spring's own startup banners) flow through unredacted. By construction these lines
  carry no project payloads, so the cold-start window is safe.

## Review Checklist for Future Stories

When adding a log statement, ask:

> Am I logging because I need to (a) **debug a production incident** (→ logs), or
> (b) **reconstruct what the user / system did** (→ workflow event)?

If (b), open a story to add the event type to the registry instead of adding a log line.
Workflow events are the audit record; logs are diagnostics. This is **not** an automated CI
gate — it is a review-time check. Pull requests that add log lines reproducing audit-shaped
content should be reverted with this checklist as the rationale.

## References

- `docs/observability/log-schema.md` — the demo-profile JSON schema.
- `docs/architecture.md` §`Logging Patterns` (lines 780–788), §`Hard Invariants` (line 848),
  §`Consistency Drift Prevention` (line 833).
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/` — `MdcKeys`,
  `CorrelationIdFilter`, `RedactingMessageConverter`, `RedactingJsonProvider`,
  `RedactionLayoutInitializer`, `RedactionLayoutHolder`.
- `deliveryline-backend/src/test/java/org/dradgo/observability/` — `CorrelationIdMdcLeakageTest`,
  `LoggingFieldNameContractTest`, `LoggingRedactionContractTest`,
  `LoggingForbiddenPayloadContractTest`, `JsonSchemaStabilityTest`.
