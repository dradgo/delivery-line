# Demo-Profile JSON Log Schema

**Owner:** Story 1.19 (Structured Logging + Correlation IDs)
**Active when:** Spring profile `demo` is enabled.
**Encoder:** `net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder` (via
`logback-spring.xml`).

## Field Set

Every log line emitted under the `demo` profile is a single-line JSON object with the following
top-level fields:

| Field         | Type   | Notes                                                                |
|---------------|--------|----------------------------------------------------------------------|
| `timestamp`   | string | ISO-8601 UTC, milli precision (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).      |
| `level`       | string | One of `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`.                    |
| `logger`      | string | SLF4J logger name, shortened to 64 characters.                       |
| `thread`      | string | Thread name at the call site.                                        |
| `message`     | string | Formatted log message **after** routing through `RedactionPolicyService`. |
| `mdc`         | object | Map of all MDC keys present at emission time (see below).            |
| `stack_trace` | string | Optional. Present only when an `ERROR` line carries a `Throwable`.   |

## MDC Key Surface

Stable surface; pinned by `LoggingFieldNameContractTest`. Exact camelCase, no drift:

| Key                   | Source                                                        |
|-----------------------|---------------------------------------------------------------|
| `correlationId`       | CLI `--correlation-id` flag, REST `X-Correlation-Id` header, or auto-generated UUIDv7. Always present once an entry-point runs. |
| `workflowRunId`       | Stamped by `WorkflowCommandService.*`, `RecoveryService.*`, `WorkflowInspectionService.*`, `RunnerBroker.dispatch/.onResult`, `ArtifactOperationService.recordOperation`. |
| `runnerExecutionId`   | Stamped by `RunnerBroker.dispatch` after the `runner_executions` row is created; carried through `RunnerBroker.onResult`. |
| `artifactId`          | Stamped by `ArtifactOperationService` (record/mark) and `ArtifactService`. |
| `artifactOperationId` | Stamped by `ArtifactOperationService.recordOperation` after the operation row is created. |

No other MDC keys are permitted. The set is enforced by `MdcKeys.ALL` and the field-name
contract test.

## Example (Demo Profile)

```json
{
  "timestamp": "2026-05-16T13:42:18.493Z",
  "level": "INFO",
  "logger": "org.dradgo.application.recovery.RecoveryService",
  "thread": "main",
  "message": "recovery retry success workflowRunId=run_logging123 recoveryActionId=rcv_log1-aaaaa newRunnerExecutionId=rex_log1-bbbbb durationMs=14",
  "mdc": {
    "correlationId": "01964c38-1c45-7000-8000-000000000001",
    "workflowRunId": "run_logging123"
  }
}
```

## Pattern Layout (Local / Test Profile)

The default profile uses the following pattern (UTC):

```
%d{yyyy-MM-dd HH:mm:ss.SSS, UTC} %-5level [%X{correlationId:-no-correlation}] %X{workflowRunId:-} %X{runnerExecutionId:-} %X{artifactId:-} %X{artifactOperationId:-} %logger{36} - %redactedMsg%n
```

The `%redactedMsg` conversion word is the call-site-friendly entry point into
`RedactingMessageConverter` — see `logback-spring.xml`.

## Encoder Choice

Spring Boot 4.0.6 ships an in-tree structured-log encoder
(`logging.structured.format.console=logstash`). We evaluated it first per story 1.19's Open
Clarification 1, but its provider SPI did not give us a clean hook for the
`RedactingJsonProvider`, so we fell back to `net.logstash.logback:logstash-logback-encoder`
version 8.0 — which exposes the composite-encoder pattern documented above. This decision is
re-litigated in `docs/adr/0019-structured-logging.md`.

## Forbidden Categories

Across all log levels, the following MUST NOT appear in the rendered output, with the
`RedactionPolicyService`-driven layout as the defense-in-depth safety net:

- Payload bytes (artifact content, runner output, request/response bodies).
- Authorization headers, bearer tokens, API keys, dotenv secrets, GraphQL request bodies.
- Configuration file contents, environment-variable values.
- Full context-bundle JSON; per-stage allow-list at `RunnerBroker.dispatch` is the call-site
  gate (`workflowRunId`, `runnerExecutionId`, `stage`, `idempotencyKey`,
  `contextBundleVersion`).

Pinned by `LoggingRedactionContractTest` (sweep) and `LoggingForbiddenPayloadContractTest`
(per-surface).
