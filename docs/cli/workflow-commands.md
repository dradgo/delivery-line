# DeliveryLine Workflow CLI Commands

This reference covers the three foundation Spring Shell commands shipped by story 1-15:
`deliveryline submit`, `deliveryline status`, and `deliveryline history`.

Each command is a thin Spring Shell adapter over an application service. Orchestration,
persistence, redaction, and approval logic live behind `WorkflowCommandService` and
`WorkflowInspectionService` — the adapter only parses options, threads MDC, and renders output.

For the cross-cutting error contract and exit-code bands, see [`README.md`](README.md).

---

## `deliveryline submit`

Submit a Linear ticket reference for governed execution. Creates the workflow run, the initial
`workflow.stateChanged` event, and an `integration_links` row in the same transaction (story 1.14
+ 1.15 wiring) — if the Linear ticket cannot be resolved the entire submit rolls back.

```
deliveryline submit \
  --ticket <ref> \
  --actor-identity <id> \
  --actor-type <type> \
  [--idempotency-key <k>] \
  [--correlation-id <c>] \
  [--verbose]
```

Successful output (single line):

```
{runId} submitted (state: Inbox)
```

When `--idempotency-key` is omitted in an interactive shell, the CLI generates a UUIDv7 and
surfaces it on stdout as `[generated-idempotency-key: <uuid>]` so the operator can replay if the
response is ever lost. In non-interactive (scripted) mode, omitting `--idempotency-key` fails fast
with `[MISSING_IDEMPOTENCY_KEY] ...` and exit code `101`.

---

## `deliveryline status`

Print the current state of a governed workflow run.

```
deliveryline status <runId> [--format text|json] [--correlation-id <c>]
```

Text mode emits, in order: `current state`, `current actor`, `last event type`, `last event
timestamp` (ISO-8601 UTC), `latest artifact <type> v<version>` (one line per artifact type that
has any record), `linked ticket: <type>:<externalRef>` (omitted if no active link), and `next safe
action: pending (story 1.18)` (placeholder until story 1.18 lands real recovery inspection).

JSON mode emits a single `application/json`-compatible object conforming to the
[`workflow-status.v1`](../../deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v1.schema.json)
schema.

```bash
deliveryline status run_abc1234 --format json
```

---

## `deliveryline history`

List append-only workflow events for a run in chronological order.

```
deliveryline history <runId> [--format text|json] [--since <iso-8601>] [--correlation-id <c>]
```

`--since` filters to events with `created_at >= since`; values must parse as `OffsetDateTime`
(ISO-8601 with an explicit zone, e.g. `2026-05-13T09:00:00Z`). Invalid values fail with
`[INVALID_TIME_RANGE] ...` and exit code `101`. The inspection layer is bounded at **1000 events
per page** — when a run exceeds that ceiling the command fails with `[HISTORY_TOO_LARGE] ...` and
the operator must pass `--since` to narrow the range.

Text mode emits one line per event:

```
<isoTimestamp> <eventType> <actorIdentity>/<actorType> <priorState>-><resultingState> [reason="..."] [[intervention]] [details={k=v, ...}]
```

JSON mode emits a single object conforming to the
[`workflow-history.v1`](../../deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json)
schema.

### Redaction contract for `details`

The `workflow_events.details` map is free-form and may carry caller-supplied fields. The CLI
allows only a closed set of keys to surface through `status` / `history`:

- `linearTicketReference`
- `artifactId`
- `artifactVersion`
- `contextVersion`
- `correlationId`

Every other key — including `idempotencyKey` — is dropped before render. The remaining payload
also passes through `RedactionPolicyService.redact(...)` as a defense-in-depth pass so a secret
that an operator naively pasted into a permitted field is still scrubbed before reaching stdout.

---

## JSON schema version contract

The `schemaVersion` field on both `workflow-status.v1` and `workflow-history.v1` is a const `1`.
The backward-compatibility rule:

- **Additive fields** (new optional properties) are allowed within `schemaVersion: 1`.
- **Removing** or **renaming** any existing field requires bumping `schemaVersion` to `2` and is
  a breaking change for downstream consumers.

---

## End-to-end example (with `linear-mock` + `runners.mock` profiles)

```bash
# 1. Bring up Postgres + the backend with mock profiles.
docker compose up -d
SPRING_PROFILES_ACTIVE=local,linear-mock,runners.mock \
  ./mvnw -pl deliveryline-backend spring-boot:run

# 2. From a separate terminal, submit a fixture ticket.
deliveryline submit \
  --ticket LIN-101 \
  --actor-identity alex \
  --actor-type human \
  --correlation-id quickstart-1

# 3. Inspect the run state and event history.
deliveryline status  run_<<from step 2>>
deliveryline history run_<<from step 2>> --format json
```

Story 1.22 owns the full quickstart and pilot-setup walkthrough — this page only documents the
three commands themselves.
