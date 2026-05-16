# DeliveryLine CLI

The workflow commands (`submit`, `status`, `history`, `retry`) are documented in
[`workflow-commands.md`](workflow-commands.md).

For the failure-recovery walkthrough — when a run hits `Failed` and the operator decides between
`retry`, `await_manual_reconciliation`, and Epic-4 deeper-recovery options — see
[`../failure-recovery-walkthrough.md`](../failure-recovery-walkthrough.md).

The diagnostic `doctor` command is documented in
[`doctor.md`](doctor.md).

The supported-environment matrix (OS / shell / container-runtime / Java / Node) consumed by the
`doctor` `supported-environment` check is documented in
[`../supported-environments.md`](../supported-environments.md).

## Exit Code Bands

DeliveryLine shell commands keep successful output unchanged. When a command fails with a governed `DomainException`, the CLI emits a single-line machine-readable error in this format:

`[{code}] {detail}`

The non-zero exit code band communicates the error family:

- `1xx`: client-like request or validation errors
- `2xx`: concurrency or idempotency conflicts
- `3xx`: runner or integration execution failures
- `4xx`: infrastructure or unexpected internal failures

Current mappings in the foundation slice:

- `101`: stable client-side failures such as `INVALID_COMMAND_PAYLOAD`, `ILLEGAL_TRANSITION`, `RUN_NOT_FOUND`, `MISSING_IDEMPOTENCY_KEY`, `INVALID_IDEMPOTENCY_KEY`, `INVALID_TIME_RANGE`, `HISTORY_TOO_LARGE`, and other governed non-retry transport errors
- `201`: `IDEMPOTENCY_KEY_CONFLICT`, `STALE_IDEMPOTENCY_RESERVATION`, `CONCURRENT_TRANSITION_CONFLICT`, `APPROVAL_VERSION_MISMATCH`, `EXPORT_CLASSIFICATION_VIOLATION`, `RETRY_NOT_APPLICABLE`
- `301`: `RUNNER_TIMEOUT`, `RUNNER_CONTRACT_VIOLATION`, `ARTIFACT_PAYLOAD_UNAVAILABLE`
- `401`: infrastructure and safety failures including `DOCTOR_*` codes and `INTERNAL_ERROR`

## Idempotency Keys

`deliveryline submit` accepts `--idempotency-key` explicitly in all modes.

- Interactive CLI: if `--idempotency-key` is omitted, DeliveryLine generates a UUIDv7 key locally and always surfaces it inline as `[generated-idempotency-key: ...]` so the operator can replay if the response is lost in transit.
- Non-interactive/scripted CLI: an explicit idempotency key is required.
- `--verbose`: retained as a no-op for backward compatibility; the auto-generated key is now surfaced regardless.
