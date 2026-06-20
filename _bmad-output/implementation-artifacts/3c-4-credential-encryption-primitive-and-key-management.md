# Story 3c.4: Credential Encryption Primitive + Key Management (Security-Gated)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ READ FIRST — this is the security-gated crypto primitive at the heart of Epic 3c's credential subsystem.** Story 3c-1 (status: review) shipped the **`V17`** schema whose `project_credentials` table already has the columns this story fills: `ciphertext bytea`, `key_id text`, `algo text`. Story 3c-2 (ready-for-dev) owns the `Project` aggregate + registries. **This story owns the cipher only** — the encryption/decryption primitive + host-env master-key management + a startup fail-fast guard + ADR 0013 + the security-review sign-off. It deliberately does **NOT** persist credentials, expose REST, or wire redaction — those are **3c-5** (which is *blocked* on this story's security sign-off, epic 3c-4 AC6). The epic/proposal still say "Flyway V14"; that is stale (3c-1 shipped V17). The story key/filename keeps its slug (synced to `sprint-status.yaml` — do not rename).
>
> **THE CENTRAL RECONCILIATION (R1 below): the cipher CANNOT live entirely in `infrastructure.crypto` as the epic literally states.** ArchUnit forbids `application..` from importing `infrastructure..`, but story 3c-5's credential store lives in `application.project` and must call the cipher. Resolution: the **port** (`CredentialCipher` interface + `EncryptedSecret` record + `CredentialCipherException`) lives in **`application.security`**; the **implementation** (`EnvelopeCredentialCipher`) + `CryptoProperties` + the master-key guard live in **`infrastructure.crypto` / `infrastructure.config`**. Infrastructure may import application; application never imports infrastructure. This is the same port/adapter seam the codebase already uses for `DockerHostPort`. Follow R1 exactly.

## Story

As a security-conscious backend developer,
I want an application-level envelope-encryption primitive for connector credentials with host-environment master-key management, fronted by an application-layer port and implemented in infrastructure,
so that per-project secrets can be stored encrypted at rest (3c-5) without reintroducing hosted-grade key infrastructure, without violating the layered architecture, and only after a recorded security review.

## Acceptance Criteria

> These ACs are **reconciled** against the live architecture boundaries, config/guard patterns, and three-sites error-code machinery (see Dev Notes → "Why these ACs differ from the epic"). Where the epic wording conflicts with an enforced codebase invariant, the reconciled wording below is authoritative.

1. **Port + envelope encryption.** A `CredentialCipher` **interface** lives in **`application.security`** (NOT infrastructure — see R1); a concrete `EnvelopeCredentialCipher` in **`infrastructure.crypto`** implements it and performs **envelope encryption**: a per-secret random **data key (DEK)** encrypts the plaintext (AES-256-GCM); the DEK is **wrapped** by a **master key (KEK)** resolved from a host env var `DELIVERYLINE_MASTER_KEY` (bound via `deliveryline.crypto.master-key`) that is **never** persisted to the DB or any file. The DEK and KEK are both 256-bit; the KEK is supplied Base64-encoded.
2. **Cipher API + `algo`/`keyId`.** The port exposes `EncryptedSecret encrypt(String plaintext)` returning `(byte[] ciphertext, String keyId, String algo)` and `String decrypt(byte[] ciphertext, String keyId, String algo)`. `algo` records the cipher suite — the constant **`"AES-256-GCM"`** — so future rotation to a new suite is non-breaking. `keyId` is a stable, non-secret identifier of the active master key (derived deterministically from the key, e.g. `"mk_" + first 12 hex of SHA-256(keyBytes)`) so a stored row can be tied to the key that wrapped it.
3. **Fail-fast only when credentials exist.** A startup guard fails the boot with `CREDENTIAL_MASTER_KEY_UNCONFIGURED` **iff** the master key is missing/blank **AND** at least one `project_credentials` row exists; with **no** credential rows present the app boots normally (greenfield/test parity — the credential subsystem is dormant until 3c-5 writes a row). The "credentials present" check is a thin `SELECT count(*) FROM project_credentials` (no JPA entity — that is 3c-5), and the guard is inert in contexts without a `DataSource`.
4. **Key rotation indirection.** The `keyId` indirection + a **documented** re-wrap path exist (rotate master key → unwrap each DEK with the old KEK → re-wrap with the new KEK). Rotation **mechanics** may be a documented stub (ADR 0013 + a `decrypt` that accepts the stored `keyId`); the schema/`keyId` column must support multiple key versions **without a migration** (it already does — `key_id text`). No functional rotation command is required in this story.
5. **ADR `docs/adr/0013-credential-encryption.md`.** It records: the deliberate reversal of the "no app-level encryption" MVP non-goal; the threat model (**defends at-rest DB compromise, NOT host-environment compromise** — the KEK lives in the same host env, so a host breach reads it); envelope-encryption decision + `algo`/`keyId` rotation path; and the single-operator / no-RBAC posture. It follows the existing ADR template and is cross-referenced from the related Epic-3c ADRs (0007/0008).
6. **Security-review gate.** This story does **not** close (and **3c-5 does not start**) until a security review signs off the primitive + threat model. Sign-off is recorded in the story's Completion Notes **and** the PR description (no CI job exists — see R6); run the `security-review` skill against the branch and capture its outcome.
7. **Tests.** Coverage asserts: round-trip `encrypt`→`decrypt` returns the exact plaintext; **tamper detection** (flip a ciphertext byte → GCM auth-tag failure → `CredentialCipherException`, **never** silent/partial plaintext); **wrong-`keyId` rejection** (decrypt with a `keyId` the active key does not match → error); **unsupported-`algo` rejection**; and the guard's **fail-fast** logic (missing key + credentials present → throw; missing key + no credentials → boot; key present → boot). New `infrastructure.crypto` code meets a **≥90%** line-coverage bar (credential/crypto code, per 3c-11 AC5).

## Tasks / Subtasks

- [x] **Task 1 — Define the application-layer port** (AC: 1, 2; R1)
  - [x] Create `src/main/java/org/dradgo/application/security/CredentialCipher.java` — interface with `EncryptedSecret encrypt(String plaintext)` and `String decrypt(byte[] ciphertext, String keyId, String algo)`. No Spring annotation needed on the interface. JDK-only imports.
  - [x] Create `src/main/java/org/dradgo/application/security/EncryptedSecret.java` — a `record EncryptedSecret(byte[] ciphertext, String keyId, String algo)` mapping 1:1 to the V17 `project_credentials` columns (`ciphertext`, `key_id`, `algo`). Compact constructor: `Objects.requireNonNull` all three; reject blank `keyId`/`algo`; defensively clone the `ciphertext` array on construction and on the accessor (mutable-array hygiene for a secret-bearing value).
  - [x] Create `src/main/java/org/dradgo/application/security/CredentialCipherException.java` — `extends RuntimeException` (unchecked). Thrown on tamper (GCM tag mismatch), wrong-`keyId`, unsupported-`algo`, and undecodable/short ciphertext. **Do NOT** map it to a `DomainErrorCode` here — there is no REST surface for the cipher yet; the REST/Problem-Details mapping of credential failures is a **3c-8** concern. Keep the message generic ("credential decryption failed") and **never** include plaintext/key material in the message or cause chain.
- [x] **Task 2 — Implement the envelope cipher in infrastructure** (AC: 1, 2, 4, 7)
  - [x] Create `src/main/java/org/dradgo/infrastructure/crypto/EnvelopeCredentialCipher.java` — `@Component implements CredentialCipher`. Constructor takes `CryptoProperties`. Resolve + validate the KEK once at construction (Base64-decode `master-key`; if blank → mark "no key" and let the guard own the fail-fast; if present-but-not-32-bytes → throw `IllegalStateException` with an actionable message — a malformed key is an operator error, not a silent fallback). Compute the active `keyId` from the KEK bytes. (Class made `final` per SpotBugs CT_CONSTRUCTOR_THROW hardening.)
  - [x] Encrypt: generate a random 32-byte DEK (`SecureRandom`); `nonce1`(12B) + AES-256-GCM(KEK, nonce1, DEK) → `wrappedDek`; `nonce2`(12B) + AES-256-GCM(DEK, nonce2, plaintext UTF-8) → `ct`; frame `ciphertext` = `[version(1B)][nonce1(12B)][wrappedDekLen(2B)][wrappedDek][nonce2(12B)][ct]`. Return `EncryptedSecret(frame, activeKeyId, "AES-256-GCM")`. Zero the DEK bytes in a `finally`.
  - [x] Decrypt: reject if `algo` != `"AES-256-GCM"` (`CredentialCipherException`); reject if `keyId` != active `keyId` (wrong-key, `CredentialCipherException` — until a rotation map exists this is a hard reject; AC4/ADR documents the re-wrap path); parse the frame (reject short/invalid → `CredentialCipherException`); unwrap DEK with KEK; decrypt `ct` with DEK; **any** `AEADBadTagException`/`GeneralSecurityException` → `CredentialCipherException` (catch-and-wrap so no `javax.crypto` type leaks and so tamper never yields partial plaintext). Return the UTF-8 plaintext.
  - [x] Use `Cipher.getInstance("AES/GCM/NoPadding")`, 128-bit tag (`GCMParameterSpec(128, nonce)`), `SecretKeySpec(bytes, "AES")`. No third-party crypto dependency — JDK `javax.crypto` only (confirmed: zero new `pom.xml` dependency).
- [x] **Task 3 — `CryptoProperties` (blank-tolerant, NOT @Validated)** (AC: 1, 3; R3)
  - [x] Create `src/main/java/org/dradgo/infrastructure/crypto/CryptoProperties.java` — `@ConfigurationProperties("deliveryline.crypto")` record `CryptoProperties(String masterKey)`. Compact constructor **normalizes-never-throws**: blank/null `masterKey` → `""`. **NOT `@Validated`**, no non-blank constraint ([[validated-config-needs-test-yaml]]).
  - [x] Register it: added new `infrastructure.crypto.CryptoConfiguration` with `@EnableConfigurationProperties(CryptoProperties.class)` (the project uses explicit per-subsystem `@EnableConfigurationProperties`, mirroring `RestBindingConfiguration`/`RunnerConfiguration` — NOT `@ConfigurationPropertiesScan`).
  - [x] Add to **main** `src/main/resources/application.yml` only: `deliveryline.crypto.master-key: ${DELIVERYLINE_MASTER_KEY:}`. **Not** added to `src/test/resources/application.yml` — test tier stays key-less (guard inert, count=0).
- [x] **Task 4 — Startup fail-fast guard** (AC: 3, 7; R4)
  - [x] Create `src/main/java/org/dradgo/infrastructure/crypto/CredentialMasterKeyGuard.java` — `@Component`, `@DependsOn("flywayInitializer")`, inert without a DB via injected `ObjectProvider<JdbcTemplate>` (skip when `getIfAvailable()` is null). Escape-hatch `@ConditionalOnProperty("deliveryline.crypto.fail-on-missing-master-key", havingValue="true", matchIfMissing=true)` mirroring `EmbeddedFrontendGuard`.
  - [x] `@PostConstruct` reads `count = jdbcTemplate.queryForObject("select count(*) from project_credentials", Long.class)` and calls package-private static `assertMasterKeyConfigured(boolean masterKeyPresent, long credentialCount)`: `!present && count>0` → `DomainException(CREDENTIAL_MASTER_KEY_UNCONFIGURED, …, details{credentialCount})`; otherwise returns + `LOG.info` dormant/active.
  - [x] Actionable message names `DELIVERYLINE_MASTER_KEY`, states it must never be committed, and the dev escape hatch.
- [x] **Task 5 — Register `CREDENTIAL_MASTER_KEY_UNCONFIGURED` (three sites)** (AC: 3; R5)
  - [x] `domain/registry/DomainErrorCode.java`: appended `CREDENTIAL_MASTER_KEY_UNCONFIGURED("CREDENTIAL_MASTER_KEY_UNCONFIGURED")` after `UNSUPPORTED_CONNECTOR_KIND` (moved the `;`; `// Story 3c-4 (AC3)` comment; wireValue == constant name).
  - [x] `adapters/rest/ProblemDetailsCatalog.java`: registered SERVICE_UNAVAILABLE + non-retryable (mirrors `DOCTOR_GITHUB_TOKEN_MISSING`/`DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED`).
  - [x] `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`: added the `problemTypeUris` entry.
- [x] **Task 6 — ADR 0013** (AC: 5)
  - [x] Created `docs/adr/0013-credential-encryption.md` (Status/Driver/Context/Decision/Alternatives/Consequences/References template). **Status kept "Proposed (pending security review)"** at authoring; security review AC6 now signs off (see Completion Notes) — flip to Accepted on commit. Cross-references 0003 (non-goal reversed) + 0007/0008.
- [x] **Task 7 — Tests (≥90% on `infrastructure.crypto`)** (AC: 7)
  - [x] `EnvelopeCredentialCipherTest.java` (12 tests, pure JUnit, fixed Base64 32-byte test KEK): round-trip (ascii/unicode/empty/blank/4KB); tamper→`CredentialCipherException` (no partial plaintext); wrong-`keyId`; unsupported-`algo`; two encrypts → different ciphertext; malformed/empty/short ciphertext; malformed-KEK-length + non-Base64-KEK → `IllegalStateException`; deterministic keyId; key-less refuses encrypt/decrypt; null plaintext; cipher-emits-no-logs.
  - [x] `CredentialMasterKeyGuardTest.java` (10 tests): static `assertMasterKeyConfigured` (false,1)→throws / (false,0) / (true,5) / (true,0) + message; `@PostConstruct` via mocked `JdbcTemplate`/`ObjectProvider` (throws / dormant / active / inert-without-DataSource); dormant+active INFO line asserted with a Logback list-appender + no-key-leak assertion; `CryptoConfiguration` instantiable.
  - [x] Guard wiring IT: relied on the static-method + mocked-`@PostConstruct` tests + the foundation-gate key-less boot (count=0) as the fail-fast evidence (OQ-5 default). No heavy `@SpringBootTest` IT added.
  - [x] **Coverage:** added the jacoco `PACKAGE` rule for `org.dradgo.infrastructure.crypto` at `LINE COVEREDRATIO minimum 0.90` after the existing 0.80 rule (OQ3 default — land here). Full `verify` jacoco-check passed.
- [x] **Task 8 — Glossary (concepts this story introduces)** (AC: 5; R7)
  - [x] Added `docs/glossary.md` **master key** + **credential encryption** entries under a new "Epic 3c vocabulary" section (matching `### term` + **See also:** format, cross-linked to ADR 0013). `project`/`connector`/`credential` left to 3c-12.
- [x] **Task 9 — Security review + gated suites** (AC: 6, 7)
  - [x] Ran the `security-review` skill against the branch — **APPROVED, zero HIGH/MEDIUM findings** (sign-off recorded in Completion Notes; goes in the PR description). 3c-5 unblocked.
  - [x] `RegistryContractTest` 19/19 (round-trips the new code through `ProblemDetailsMapper`). `-Pfoundation-gate verify` BUILD SUCCESS (fresh-container V17 boot, key-less count=0 → guard logs dormant; the 503 mapping round-tripped through the mapper probe). `ArchitectureBoundaryTest` 56/56 (port/impl split holds — `application` free of `infrastructure`). `spotless:apply`/`checkstyle:check` clean. Full module `verify` BUILD SUCCESS (Surefire 1130 + Failsafe 685, jacoco floors incl. crypto 0.90).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **Scoped + SECRET-HOSTILE.** Guard logs only the dormant ("0 project_credentials rows — master key not required") / active ("N row(s) present, master key configured") state + the failure carries only `credentialCount`. Cipher emits NO logs. List-appender assertions added: the guard's active+dormant INFO lines are emitted AND no log line carries the key material; a cipher round-trip emits zero log events. Foundation-gate redaction assertion expectation set for 3c-5.

### Review Findings

Code review 2026-06-20 (adversarial: Blind Hunter + Edge Case Hunter + Acceptance Auditor). Security-sensitive crypto: no Critical/High defect found — nonces are random-per-op, DEKs fresh + zeroed, GCM tags verified, frame parsing bounds-checked, exceptions secret-hostile. Findings are doc/AC gaps plus defense-in-depth polish. 0 decision-needed, 3 patch, 6 defer, 8 dismissed.

**Patch:**

- [x] [Review][Patch] ADR 0013 not back-referenced from ADRs 0007/0008 (AC5 violation, Medium) — FIXED 2026-06-20: added a back-reference line to 0013 in both `0007-ticket-source-abstraction.md` and `0008-repository-host-abstraction.md` References sections. [docs/adr/0007-ticket-source-abstraction.md, docs/adr/0008-repository-host-abstraction.md]
- [x] [Review][Patch] ADR 0013 Status still "Proposed (pending security review)" despite recorded AC6 sign-off (Low) — FIXED 2026-06-20: Status flipped to "Accepted (2026-06-20) — security review signed off (AC6: APPROVED, no HIGH/MEDIUM findings)". [docs/adr/0013-credential-encryption.md]
- [x] [Review][Patch] Port Javadoc over-promises `CredentialCipherException` for the key-less path (Low) — FIXED 2026-06-20: added a class-level Javadoc note that a key-less *misconfiguration* surfaces as an unchecked `IllegalStateException` from both `encrypt`/`decrypt` (distinct from a decryption failure); used `{@code}` not `{@link}` to avoid an application→infrastructure reference. [deliveryline-backend/src/main/java/org/dradgo/application/security/CredentialCipher.java]

**Deferred:**

- [x] [Review][Defer] Guard count query unguarded vs DataSource-present-but-table-missing — deferred, latent. `verifyMasterKeyConfigured` is inert only when no `DataSource`; if a `DataSource` exists while Flyway is disabled / V17 absent, `queryForObject` throws `BadSqlGrammarException` and aborts boot with a raw stack instead of a typed fault. Not triggered by current Flyway-always config. [CredentialMasterKeyGuard.java:62-68]
- [x] [Review][Defer] Unchecked `(short)` narrowing on `wrappedDekLen` — deferred, latent. `frame.putShort((short) wrappedDek.length)` has no `> 0xFFFF` bounds check; safe today (wrapped DEK ≈48 bytes) but would silently mis-frame if the wrapped-DEK size ever exceeded 65535. [EnvelopeCredentialCipher.java:112]
- [x] [Review][Defer] KEK field + JCA `SecretKeySpec` copies never zeroed — deferred, ADR-accepted. The per-op DEK is zeroed in `finally`, but the `kek` field + the `SecretKeySpec` copies live for the process lifetime; accepted by the ADR threat model (excludes host/memory compromise), defense-in-depth only. [EnvelopeCredentialCipher.java:60,195]
- [x] [Review][Defer] `twoEncrypts…` asserts whole-frame inequality, not inner-ct uniqueness — deferred, test polish. Frames differ via nonce1/wrappedDek regardless, so the test would pass even with a deterministic inner cipher; strengthen to assert the ct region differs. [EnvelopeCredentialCipherTest.java]
- [x] [Review][Defer] No hostile/lying `wrappedDekLen`-header test — deferred, test polish. Malformed-frame coverage uses only truncation; add a test that sets `wrappedDekLen` past `remaining` to prove the `readBytes` bounds guard directly. [EnvelopeCredentialCipherTest.java]
- [x] [Review][Defer] Untested branch arms (`count==null` ternary, `version != FRAME_VERSION`) — deferred, coverage polish. LINE-covered incidentally under the 0.90 floor but no dedicated assertion exercises these guarded arms. [CredentialMasterKeyGuard.java:69, EnvelopeCredentialCipher.java:142]

**Dismissed (8):** empty-plaintext encryptable (primitive; blank policy is 3c-5); wrong-key vs tamper share a message (deliberate no-oracle); `fail-on-missing-master-key=false` removes the whole guard bean (spec Task 4 sanctioned, mirrors `EmbeddedFrontendGuard`, documented in the error message); actuator `/env` KEK exposure (Spring Boot 4 defaults `show-values=NEVER` → masked, endpoint not exposed by default); `CryptoProperties` null-guard duplication (records guarantee normalized non-null); ADR has no CI gate (R6 explicitly procedural); unsalted `keyId` confirmation oracle (only for weak keys; design assumes a length-checked 256-bit random KEK); security sign-off not in the diff (process reminder — confirm it is copied into the PR description per R6).

## Dev Notes

### Why these ACs differ from the epic (the reconciliations that matter)

The dev agent will only have this file. These are the traps where the epic text collides with an **enforced** codebase invariant; follow the reconciled ACs, not the epic wording.

- **R1 — The cipher CANNOT live wholly in `infrastructure.crypto` (THE central reconciliation).** Epic 3c-4 AC1 says "Given `infrastructure.crypto`, Then a `CredentialCipher` performs envelope encryption", and 3c-5 AC1 says the credential store in **`application.project`** "encrypts via `CredentialCipher`". But `ArchitectureRuleCatalog.APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE` (`noClasses().that().resideInAPackage("org.dradgo.application..").should().dependOnClassesThat().resideInAPackage("org.dradgo.infrastructure..")`) makes `application.project → infrastructure.crypto` a hard ArchUnit failure. Resolution (honors both the epic's "infrastructure.crypto" home for the *crypto* AND the boundary): the **port** `CredentialCipher` + `EncryptedSecret` + `CredentialCipherException` live in **`application.security`** (alongside `RedactionPolicyService`, the other security primitive 3c-5 wires); the **implementation** `EnvelopeCredentialCipher` lives in **`infrastructure.crypto`**. The `LAYERED_BOUNDARIES` rule permits **Infrastructure → Application** ("Application mayOnlyBeAccessedByLayers Adapters, Infrastructure"), so the impl can implement the application interface; Spring injects the impl wherever the interface is required. This is the same port/adapter seam as `DockerHostPort`/`RecoverableRunnerAdapter` ([[application-cannot-import-adapters]]) and the reason `RunnerProperties` was deliberately placed in `application.runner`, not infrastructure. **`ArchitectureBoundaryTest` is the gate that proves you got this right.** [Source: ArchitectureRuleCatalog.java:82-104,194-203; application/runner/RunnerProperties.java javadoc; application/security/RedactionPolicyService.java]
- **R2 — `infrastructure` already exists; `infrastructure.crypto` is a new peer.** The top-level package layout is `domain` / `application` / `adapters` / `infrastructure` (the last has `config`, `observability`, `web`). `infrastructure.crypto` is a clean new sibling. The guard may live in `infrastructure.crypto` or `infrastructure.config` (where `EmbeddedFrontendGuard`/`RestBindingGuard` live) — prefer `infrastructure.crypto` to keep the subsystem cohesive. [Source: src/main/java/org/dradgo/infrastructure/]
- **R3 — `CryptoProperties` must be blank-tolerant, NOT `@Validated`.** A non-blank-required `@Validated` master-key property would fail **every** key-less `@SpringBootTest` context at binding ([[validated-config-needs-test-yaml]] — the test yaml shadows, not merges). The greenfield/test-parity branch of AC3 (boot with no key when no credentials exist) *requires* binding to succeed with a blank key. So `CryptoProperties` normalizes blank→`""` and never throws; the **guard** (count-gated) is the only thing that fails fast. Add the key line to **main** yaml only. [Source: application.yml linear/github token pattern; [[validated-config-needs-test-yaml]]]
- **R4 — The fail-fast guard needs a DB count, so it runs after Flyway and is DB-gated.** AC3's "at least one project credential present" forces a `count(*)` on `project_credentials`. Unlike `RestBindingGuard` (a `BeanFactoryPostProcessor` — too early, no DataSource) this guard must run **after** migrations: use `@PostConstruct` on a `@Component` `@DependsOn("flywayInitializer")` (Spring Boot's Flyway initializer bean), reading via `JdbcTemplate`. `@PostConstruct` runs during bean init — **before** the servlet connector binds at `finishRefresh()` — so a refused boot never serves traffic (same timing guarantee `EmbeddedFrontendGuard` relies on). Use a **raw `count(*)`** (no `ProjectCredential` JPA entity — that is 3c-5). Gate the guard inert when no `DataSource` bean exists so non-DB slice contexts still boot. [Source: EmbeddedFrontendGuard.java:43-49; RestBindingGuard.java:24-32]
- **R5 — `CREDENTIAL_MASTER_KEY_UNCONFIGURED` is the only new `DomainErrorCode`; three sites or the gate reds.** The startup guard throws a typed `DomainException` (precedent: `RestBindingGuard` throws `DomainException(DOCTOR_REST_BIND_UNAVAILABLE)`), so register the code at all three sites (enum + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json`) — `RegistryContractTest.domainErrorCodesStayAlignedWithProblemTypeOwnershipManifest` (default `verify` tier) reds on a gap ([[new-domainerrorcode-three-sites]], docs/patterns/registry-recipe.md §3). 503 + non-retryable. **Cipher decrypt failures (tamper/wrong-key/bad-algo) do NOT get a `DomainErrorCode`** — they throw `CredentialCipherException` (unchecked); there is no REST surface for the cipher until 3c-8, and inventing Problem-Details mappings now would be ahead-of-need and untestable through the mapper. [Source: ProblemDetailsCatalog.java:330-346; DomainErrorCode.java:138; RestBindingGuard.java:89-109]
- **R6 — The "security-review gate" is a documented sign-off, not a CI job.** No `security-review` CI step exists in `.github/workflows/ci.yml`. The convention is a story-AC + recorded sign-off (Completion Notes + PR description). A `security-review` **skill** exists ("Complete a security review of the pending changes on the current branch") — run it, capture the outcome. The gate's teeth are procedural: **3c-5 must not start until sign-off is recorded** (epic 3c-4 AC6), and 3c-11 AC6 requires the sign-off be recorded in the test-suite evidence. [Source: epic-03c §3c-4 AC6, §3c-11 AC6]
- **R7 — Glossary split with 3c-12.** NFR43/glossary discipline ("any doc that introduces a new term adds an entry in the same PR") means ADR 0013's new terms (**master key**, **envelope/credential encryption**) get glossary entries **here**. `project`/`connector`/`credential` are 3c-12 AC5's job. Different terms → no edit collision; a brief coordination note in Completion Notes is enough. [Source: docs/glossary.md:1-11; epic-03c §3c-12 AC5]
- **R8 — `algo` value is `"AES-256-GCM"`, not the V17 test fixture's `"AES_GCM"`.** `FlywaySchemaContractTest` inserts a literal `"AES_GCM"` placeholder into `algo` purely to exercise the `text` column — it is **not** a contract. The cipher's real `algo` constant is **`"AES-256-GCM"`** (epic AC2). No test ties the two; do not "reconcile" them. [Source: 3c-1 FlywaySchemaContractTest credential fixture; epic-03c §3c-4 AC2]

### Envelope-encryption design (concrete, copy-ready shape)

```java
// application.security — the PORT (no Spring import; JDK-only)
public interface CredentialCipher {
  EncryptedSecret encrypt(String plaintext);
  String decrypt(byte[] ciphertext, String keyId, String algo);
}
public record EncryptedSecret(byte[] ciphertext, String keyId, String algo) {
  public EncryptedSecret {
    Objects.requireNonNull(ciphertext); Objects.requireNonNull(keyId); Objects.requireNonNull(algo);
    if (keyId.isBlank() || algo.isBlank()) throw new IllegalArgumentException("keyId/algo blank");
    ciphertext = ciphertext.clone();            // defensive copy in
  }
  @Override public byte[] ciphertext() { return ciphertext.clone(); }   // defensive copy out
}
public final class CredentialCipherException extends RuntimeException {
  public CredentialCipherException(String message) { super(message); }   // no plaintext/key in message
}
```

- **KEK (master key):** `Base64.getDecoder().decode(masterKey)` → must be **32 bytes**. `keyId = "mk_" + HexFormat.of().formatHex(sha256(kek)).substring(0,12)`.
- **DEK:** `SecureRandom` 32 random bytes, fresh **per encrypt**.
- **Suite:** `AES/GCM/NoPadding`, 12-byte nonce, 128-bit tag, `SecretKeySpec(bytes,"AES")`.
- **Frame (`ciphertext` bytea):** `[ver=1][nonce1(12)][wrappedDekLen(2, big-endian)][wrappedDek][nonce2(12)][ct]`. Self-describing → no schema change for rotation.
- **Decrypt order:** algo check → keyId check → frame parse → unwrap DEK → decrypt. Wrap **all** `GeneralSecurityException` (incl. `AEADBadTagException`) in `CredentialCipherException`. Zero the DEK in `finally`.
- **Rotation (AC4, documented):** new master key → new derived `keyId`; a future re-wrap utility decrypts each row's DEK with the old KEK and re-wraps with the new, updating `key_id`. The `key_id` column already supports many versions; until a rotation map exists, `decrypt` hard-rejects a non-active `keyId` (this is the wrong-`keyId` test, AC7).

### Project Structure Notes

- **New (main):** `application/security/CredentialCipher.java`, `EncryptedSecret.java`, `CredentialCipherException.java`; `infrastructure/crypto/EnvelopeCredentialCipher.java`, `CryptoProperties.java`, `CredentialMasterKeyGuard.java`, `CryptoConfiguration.java` (only if `@ConfigurationPropertiesScan` isn't already in use).
- **Modified (main):** `domain/registry/DomainErrorCode.java`; `adapters/rest/ProblemDetailsCatalog.java`; `src/main/resources/application.yml` (one line); `pom.xml` (jacoco package rule — see OQ3).
- **Modified (test):** `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`.
- **New (test):** `infrastructure/crypto/EnvelopeCredentialCipherTest.java`, `CredentialMasterKeyGuardTest.java` (+ optional guard IT).
- **New (docs):** `docs/adr/0013-credential-encryption.md`; `docs/glossary.md` (2 entries).
- **NOT in this story:** no `project_credentials` read/write (no JPA entity/mapper/repository — 3c-5), no REST/CLI (3c-8), no `RedactionPolicyService` change (3c-5), no `application.project` code, no Flyway change (V17 done), no OpenAPI snapshot regen (no new REST surface), no frontend.
- **No `src/test/resources/application.yml` change** (R3 — keep the test tier key-less). Canonical Postgres image stays `postgres:17.2` if a guard IT is added.

### Logging Requirements (project-wide standard)

Guard-only log surface; cipher is silent (secret hot path). See the Logging task — the operative rule is **never** emit plaintext/ciphertext/DEK/KEK/master key in any log line, exception message, or stack trace. The guard logs only counts + configured/dormant state. This foreshadows the 3c-5 foundation-gate redaction assertion.

### References

- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-4] — source ACs (reconciled here); §3c-5 (downstream consumer, blocked on AC6), §3c-11 AC5/AC6 (coverage + sign-off evidence).
- [Source: _bmad-output/implementation-artifacts/3c-1-flyway-v14-projects-and-credentials-schema-and-project-id-association.md] — V17 `project_credentials` (`ciphertext bytea`, `key_id text`, `algo text`) this cipher fills; the credential-redaction obligation foreshadowed.
- [Source: _bmad-output/implementation-artifacts/3c-2-project-domain-aggregate-and-registries-and-drift-tests.md] — the registry/error-code/three-sites patterns this story reuses; [[story-3c-2-project-domain-and-registries-reconciliations]].
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:82-104,194-203] — `LAYERED_BOUNDARIES` + `APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE` (the R1 constraint); `ArchitectureBoundaryTest` runs them.
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/EmbeddedFrontendGuard.java] — `@PostConstruct` + static-assert fail-fast pattern (guard template); [Source: .../config/RestBindingGuard.java:89-109] — `DomainException`-throwing startup guard precedent.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java] — `@ConfigurationProperties` + compact-constructor normalize-never-throw precedent + the "kept in application to dodge the infra-import rule" javadoc.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionPolicyService.java] — the `application.security` home for the cipher port (and the redaction service 3c-5 extends).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java:138; adapters/rest/ProblemDetailsCatalog.java:330-346; src/test/resources/contracts/openapi/registry-api-schema-placeholders.json] — the three error-code sites; [Source: docs/patterns/registry-recipe.md §3] — the recipe ([[new-domainerrorcode-three-sites]]).
- [Source: docs/adr/0023-elk-replaces-loki.md] — ADR template; [Source: docs/adr/0003-runner-secrets-mvp-posture.md] — the secrets non-goal ADR 0013 reverses; [Source: docs/adr/0007-ticket-source-abstraction.md; 0008-repository-host-abstraction.md] — sibling Epic-3c ADRs to cross-link.
- [Source: deliveryline-backend/pom.xml:405-532] — jacoco per-package rule block (OQ3).

### Open Questions / Decisions for Alex (non-blocking — defaults are in place)

1. **Cipher port home = `application.security`** (chosen). Co-locates with `RedactionPolicyService` (the other security primitive 3c-5 wires) and keeps `application.project` focused on project domain ops. Alternative: a dedicated `application.crypto` package. Proceeding with `application.security`.
2. **`keyId` derivation = `"mk_" + 12-hex of SHA-256(KEK)`** (chosen — zero extra config, rotation-friendly: a new key auto-yields a new id). Alternative: an explicit `deliveryline.crypto.key-id` config value. If you prefer explicit ids, it's a one-field addition to `CryptoProperties`. Proceeding with derived.
3. **jacoco 0.90 rule for `infrastructure.crypto` — land here vs defer to 3c-11?** Epic 3c-11 AC5 *owns* coverage-threshold widening (it folds in `application.project`, `infrastructure.crypto`, project adapters). But this is the **security-gated** code; self-protecting it the moment it lands is safer. **Default: add the `org.dradgo.infrastructure.crypto` 0.90 PACKAGE rule in this story**; 3c-11 then extends to the rest. If you'd rather keep all threshold edits in 3c-11, drop Task 7's pom step and instead just ensure the tests hit ≥90% so 3c-11's rule passes retroactively. Proceeding with adding the rule here.
4. **ADR 0013 number.** The epic explicitly names `0013-credential-encryption.md`; `0013` is a free gap (on-disk ADRs are 0001–0008, 0019–0023; head 0023). Using the epic's `0013` keeps the Epic-3c feature ADRs (0007/0008) in the low band. Proceeding with **0013** (not 0024). Say so if you want it renumbered to the sequential head.
5. **Guard wiring IT.** Default is the static-method unit test + the foundation-gate key-less boot as the fail-fast evidence; a full `@SpringBootTest` that seeds a credential row and asserts a refused boot is added only if the security review wants belt-and-suspenders. Confirm the lighter evidence is acceptable.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Full module `verify` (default profile): Surefire **1130** + Failsafe **685**, 0 failures; jacoco-check BUILD SUCCESS incl. the new `org.dradgo.infrastructure.crypto` 0.90 LINE floor.
- `-Pfoundation-gate verify`: BUILD SUCCESS — fresh Postgres container migrated through V17 and booted **key-less (count=0)**; guard logged "credential subsystem dormant: 0 project_credentials rows"; the foundation-gate probe round-tripped `CREDENTIAL_MASTER_KEY_UNCONFIGURED` → HTTP 503 through `ProblemDetailsMapper`.
- `integration-test -Dit.test=RegistryContractTest,ArchitectureBoundaryTest`: RegistryContractTest **19/19**, ArchitectureBoundaryTest **56/56**.
- New unit tier: `EnvelopeCredentialCipherTest` 12/12 + `CredentialMasterKeyGuardTest` 10/10.
- `spotless:apply` clean (7 files reformatted); `checkstyle:check` 0 violations.
- SpotBugs: one Medium `CT_CONSTRUCTOR_THROW` on `EnvelopeCredentialCipher` (throwing ctor on a non-final class) — fixed by marking the class `final` (failThreshold is High so it was non-blocking; fixed anyway as security hardening).

### Completion Notes List

**Implementation summary.** Shipped the security-gated envelope-encryption primitive for connector credentials. Port (`CredentialCipher` + `EncryptedSecret` + `CredentialCipherException`) in `application.security`; impl (`EnvelopeCredentialCipher`, AES-256-GCM: random per-secret DEK encrypts plaintext, KEK from `DELIVERYLINE_MASTER_KEY` wraps the DEK; self-describing frame `[ver][nonce1][wrappedDekLen][wrappedDek][nonce2][ct]`; `keyId = mk_ + 12-hex SHA-256(KEK)`; `algo = "AES-256-GCM"`) + `CryptoProperties` (blank-tolerant) + `CryptoConfiguration` + `CredentialMasterKeyGuard` (fail-fast 503 only when key missing AND ≥1 credential row exists; dormant key-less boot otherwise) in `infrastructure.crypto`. One new `DomainErrorCode` at all three sites; ADR 0013; 2 glossary entries; jacoco 0.90 crypto floor.

**R1 (the central reconciliation) honored.** Port in `application.security`, impl in `infrastructure.crypto` — `ArchitectureBoundaryTest` 56/56 proves `application` never imports `infrastructure`.

**Open-decision defaults taken:** OQ1 port home = `application.security`; OQ2 `keyId` derived from SHA-256(KEK); OQ3 jacoco 0.90 rule landed here (not deferred to 3c-11); OQ4 ADR number **0013**; OQ5 lighter guard evidence (static + mocked `@PostConstruct` + foundation-gate boot) — no heavy `@SpringBootTest` IT.

**🔒 SECURITY REVIEW SIGN-OFF (AC6 — gate that blocks story 3c-5).** Ran the `security-review` skill against the branch (2026-06-20). **Outcome: APPROVED — no HIGH or MEDIUM severity vulnerabilities.** A senior-security-engineer pass verified AES-256-GCM correctness (random per-encryption DEK + nonces via `SecureRandom`, 256-bit keys, 128-bit tag, `AES/GCM/NoPadding`), authenticated tamper rejection with no partial/silent plaintext, bounds-checked frame parsing with no integer-overflow path (`getShort() & 0xFFFF`), secret-hostile exceptions/logging with DEK zeroing in `finally`, no hardcoded secrets, and an injection-safe (parameter-free constant) guard query. The documented threat model (ADR 0013) — defends at-rest DB compromise, NOT host-environment compromise — was reviewed and accepted as a deliberate design limit. **Story 3c-5 is unblocked.** (This sign-off must be copied into the PR description per R6 — no CI security job exists.)

**Coordination note (R7):** glossary terms **master key** + **credential encryption** added here; `project`/`connector`/`credential` remain 3c-12's job — no edit collision.

### File List

**New (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/security/CredentialCipher.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/security/EncryptedSecret.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/security/CredentialCipherException.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/crypto/EnvelopeCredentialCipher.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/crypto/CryptoProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/crypto/CryptoConfiguration.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/crypto/CredentialMasterKeyGuard.java`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/crypto/EnvelopeCredentialCipherTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/crypto/CredentialMasterKeyGuardTest.java`

**New (docs):**
- `docs/adr/0013-credential-encryption.md`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+`CREDENTIAL_MASTER_KEY_UNCONFIGURED`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (503 mapping)
- `deliveryline-backend/src/main/resources/application.yml` (`deliveryline.crypto.master-key` line)
- `deliveryline-backend/pom.xml` (jacoco `infrastructure.crypto` 0.90 PACKAGE rule)

**Modified (test/docs):**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (problemTypeUris entry)
- `docs/glossary.md` (master key + credential encryption entries)

**Modified (tracking):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/3c-4-credential-encryption-primitive-and-key-management.md`

### Change Log

- 2026-06-20 — Implemented story 3c-4 credential-encryption primitive + key management. Port/impl split (R1), AES-256-GCM envelope cipher, host-env master key, fail-fast guard, `CREDENTIAL_MASTER_KEY_UNCONFIGURED` three-sites, ADR 0013, glossary, jacoco 0.90 floor. Security review APPROVED (AC6 sign-off — 3c-5 unblocked). All gates green; Status → review.
