# DeliveryLine CLI

## Exit Code Bands

DeliveryLine shell commands keep successful output unchanged. When a command fails with a governed `DomainException`, the CLI emits a single-line machine-readable error in this format:

`[{code}] {detail}`

The non-zero exit code band communicates the error family:

- `1xx`: client-like request or validation errors
- `2xx`: concurrency or idempotency conflicts
- `3xx`: runner or integration execution failures
- `4xx`: infrastructure or unexpected internal failures

Current mappings in the foundation slice:

- `101`: stable client-side failures such as `INVALID_COMMAND_PAYLOAD`, `ILLEGAL_TRANSITION`, `RUN_NOT_FOUND`, and other governed non-retry transport errors
- `201`: `IDEMPOTENCY_KEY_CONFLICT`, `CONCURRENT_TRANSITION_CONFLICT`, `APPROVAL_VERSION_MISMATCH`
- `301`: `RUNNER_TIMEOUT`, `RUNNER_CONTRACT_VIOLATION`, `ARTIFACT_PAYLOAD_UNAVAILABLE`
- `401`: infrastructure and safety failures including `DOCTOR_*` codes and `INTERNAL_ERROR`
