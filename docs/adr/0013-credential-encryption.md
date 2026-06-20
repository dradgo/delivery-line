# ADR 0013 — Credential Encryption Primitive + Host-Env Master Key

**Status:** Accepted (2026-06-20) — security review signed off (AC6: APPROVED, no HIGH/MEDIUM findings)
**Driver:** Story 3c-4 — provide an application-level envelope-encryption primitive so per-project connector credentials (story 3c-5) can be stored encrypted at rest in the V17 `project_credentials` table, without reintroducing hosted-grade key infrastructure and without violating the layered architecture. Deliberately **reverses** the Epic-3 "no app-level encryption" MVP non-goal of ADR 0003 for this one concern (host-side connector secrets), now that DeliveryLine persists per-project credentials rather than only injecting a single host env var into a runner.

## Context

ADR 0003 (`runner-secrets-mvp-posture`) decided the MVP would **not** encrypt secrets at the application layer: the agent-provider key is resolved from the host environment and injected into the runner container, and the host-side `GITHUB_TOKEN` / `LINEAR_API_TOKEN` never leave the host. That posture holds for single-tenant, single-project operation where one host env var per integration is sufficient.

Epic 3c makes DeliveryLine **multi-project**: each project may carry its own connector credentials, persisted in the `project_credentials` table (story 3c-1 shipped the schema — `ciphertext bytea`, `key_id text`, `algo text`). Persisting per-project secrets in the database means a database compromise (a leaked dump, a backup left readable) would expose every project's connector tokens in plaintext. The MVP non-goal must therefore be revisited **for stored credentials specifically** — not for the runner-injection path, which ADR 0003 still governs unchanged.

The acceptance criteria as written in `epic-03c §Story 3c-4` place the entire cipher in `infrastructure.crypto`. The **live architecture boundary is authoritative** and forces a port/impl split that this ADR records (see Decision 1).

## Decision

**1. Port in `application.security`, implementation in `infrastructure.crypto` (the R1 reconciliation).** The epic places the cipher wholly in `infrastructure.crypto`, but story 3c-5's credential store lives in `application.project` and must call the cipher, and the `APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE` ArchUnit rule forbids `application.. → infrastructure..`. Resolution: the **port** (`CredentialCipher` interface, `EncryptedSecret` record, `CredentialCipherException`) lives in `application.security`; the **implementation** (`EnvelopeCredentialCipher`) + `CryptoProperties` + the startup guard live in `infrastructure.crypto`. The `LAYERED_BOUNDARIES` rule permits Infrastructure → Application, so the impl implements the application port and Spring injects it wherever the port is required. This is the same port/adapter seam the codebase already uses for `DockerHostPort`. `ArchitectureBoundaryTest` is the gate that proves it.

**2. Envelope encryption with a host-env master key.** Each secret is encrypted with a fresh random 256-bit **data key (DEK)** under AES-256-GCM; the DEK is then **wrapped** (encrypted) by a 256-bit **master key (KEK)** resolved once at startup from the `DELIVERYLINE_MASTER_KEY` host env var (bound via `deliveryline.crypto.master-key`, Base64-encoded). The KEK is **never** persisted to the database or any file. A fresh DEK + random GCM nonces per secret mean two encryptions of the same plaintext produce different ciphertext. The stored `ciphertext` is a self-describing frame: `[version(1B)][nonce1(12B)][wrappedDekLen(2B)][wrappedDek][nonce2(12B)][ct]`.

**3. `algo` + `keyId` for non-breaking rotation.** `algo` records the cipher suite — the constant `"AES-256-GCM"` — so a future suite is a new value, not a breaking change. `keyId = "mk_" + first 12 hex of SHA-256(KEK)` is a stable, non-secret identifier derived deterministically from the active key, tying each stored row to the key version that wrapped it. (Note: the V17 `FlywaySchemaContractTest` fixture inserts a literal `"AES_GCM"` placeholder purely to exercise the `text` column — that is not the cipher's real `algo`; the cipher writes `"AES-256-GCM"`.)

**4. Documented key-rotation path (no functional command this story).** Rotating the master key follows: load the new KEK → for each row, unwrap the stored DEK with the **old** KEK → re-wrap with the **new** KEK → update `key_id`. The schema already supports multiple key versions without a migration (`key_id text`). Until a rotation map exists, `decrypt` **hard-rejects** any non-active `keyId`. A functional rotation command is out of scope for 3c-4.

**5. Fail-fast only when credentials exist.** A startup guard (`CredentialMasterKeyGuard`) aborts boot with `CREDENTIAL_MASTER_KEY_UNCONFIGURED` (HTTP 503, non-retryable) **iff** the master key is missing/blank **AND** at least one `project_credentials` row exists. With no credential rows present, the app boots normally — the credential subsystem is dormant until 3c-5 writes the first row (greenfield/test parity). The "credentials present" probe is a thin `SELECT count(*)`; the guard is inert without a `DataSource`.

**6. Single-operator / no-RBAC posture.** Consistent with the Phase-1 loopback-only-as-auth posture (ADR for REST binding), there is no per-user key access control: any operator who can read the host environment can read the KEK. This is acceptable because the threat model (below) explicitly does not defend against host compromise.

### Threat model

- **Defends:** **at-rest database compromise.** A leaked DB dump / readable backup yields only ciphertext + `key_id` + `algo`; without the KEK (which lives only in the host environment, never in the DB) the credentials cannot be recovered. GCM authentication also makes silent tampering with a stored ciphertext detectable on decrypt.
- **Does NOT defend:** **host-environment compromise.** The KEK is read from the same host's environment as the application. An attacker who can read the host's process environment or memory can read the KEK and therefore decrypt everything. This is the deliberate, recorded limit of the design — it raises the bar on DB-only compromise, not host compromise.

## Alternatives Considered

### Alt 1 — Keep the ADR 0003 "no app-level encryption" non-goal and store credentials in plaintext

**Rejected.** Acceptable for a single host env var injected into a runner; not acceptable for per-project credentials persisted in a shared database, where a DB-only compromise would expose every project's tokens. The non-goal is reversed for stored credentials specifically; ADR 0003 still governs the runner-injection path.

### Alt 2 — Direct encryption (no envelope; encrypt each secret straight under the master key)

**Rejected.** Workable, but envelope encryption is the standard shape for credential-at-rest and keeps rotation cheap (re-wrap per-row DEKs rather than re-encrypt every plaintext), and isolates the long-lived KEK from the bulk-encryption key. The cost is one extra wrap/unwrap per operation — negligible for credential-sized payloads.

### Alt 3 — A KMS / hosted key service (AWS KMS, Vault, etc.)

**Rejected for the MVP.** Reintroduces exactly the hosted-grade key infrastructure ADR 0003 set out to avoid for a locally-run, single-operator tool. The host-env master key is the proportionate posture; a KMS-backed `CredentialCipher` implementation is a future, drop-in alternative behind the same port if the deployment model changes.

### Alt 4 — Put the whole cipher in `infrastructure.crypto` as the epic literally states

**Rejected (port/impl split instead).** Story 3c-5's `application.project` credential store must call the cipher, and `application.. → infrastructure..` is an ArchUnit failure. The port lives in `application.security`; only the impl lives in `infrastructure.crypto`.

## Consequences

### Positive

- Per-project connector credentials can be stored encrypted at rest (3c-5) so a DB-only compromise no longer yields plaintext secrets.
- The `algo`/`keyId` indirection makes both suite rotation and key rotation non-breaking and migration-free.
- The port/impl split keeps `application` free of `infrastructure` imports (ArchUnit-clean) and leaves a KMS-backed implementation as a future drop-in behind the same port.
- GCM authentication makes at-rest tamper detectable rather than silently decrypting corrupted/partial plaintext.

### Negative

- The design does **not** defend against host-environment compromise — an explicitly accepted limit, not an oversight. It must be stated wherever operators reason about the credential subsystem's guarantees.
- An operator who loses the master key cannot recover stored credentials (they must be re-entered) — the cost of the KEK living only on the host. No key escrow is provided.

### Neutral

- The credential subsystem is **dormant** until story 3c-5 writes the first `project_credentials` row: with zero rows the app boots without a master key, so greenfield and test contexts are unaffected.
- Cipher decryption faults (tamper / wrong key / unsupported algo / malformed frame) surface as `CredentialCipherException` (unchecked) with no `DomainErrorCode` and no REST mapping in this story — the Problem-Details mapping of credential failures is a story 3c-8 concern.
- This story does not close, and story 3c-5 does not start, until the security review (AC6) signs off the primitive + threat model; the sign-off is recorded in the story Completion Notes and the PR description (no CI job exists).

## References

- [Source: `_bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-4`] — source ACs (reconciled in the story); §3c-5 (downstream consumer, blocked on AC6), §3c-11 AC5/AC6 (coverage + sign-off evidence).
- `docs/adr/0003-runner-secrets-mvp-posture.md` — the runner-secrets non-goal this ADR reverses for stored credentials.
- `docs/adr/0007-ticket-source-abstraction.md`, `docs/adr/0008-repository-host-abstraction.md` — the sibling Epic-3c connector abstractions this credential layer secures.
- `docs/adr/0004-spec-stage-orchestration.md` — ADR format followed here.
- `docs/glossary.md` — `master key` + `credential encryption` entries introduced with this ADR.
