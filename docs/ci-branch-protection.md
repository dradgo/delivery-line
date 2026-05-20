# CI Branch Protection — `foundation-gate`

Story 1.21 wires the `foundation-gate` job in `.github/workflows/ci.yml` (aggregator of the nine
foundation-tier checks plus the OS-matrix `doctor-smoke`). Story 1.23 replaces the placeholder
body with real `FoundationGateVerificationTest` execution — ten Epic-1 contracts re-verified on
every PR via the dedicated `foundation-gate` Maven profile. **Repository admins must register
`foundation-gate` as a required status check on `main`** so any Epic 2/3/4 PR is structurally
blocked until the foundation gate is green.

## Required status checks (story 1.23)

| Check | Required | Notes |
| --- | --- | --- |
| `foundation-gate` | **required** | Aggregate Epic-1 contract verification (story 1.23). MUST be a required status check on `main`. |
| `format-static-checks (ubuntu-latest)` | recommended | Implicit via `foundation-gate` `needs:`. Listing explicitly improves PR-page UI signal. |
| `format-static-checks (windows-latest)` | recommended | Same — explicit listing surfaces the per-OS pass in the PR UI. |
| `frontend-build-tests (ubuntu-latest)` | recommended | Implicit dependency after story 2.1. Listing explicitly surfaces the Linux leg of the frontend matrix. |
| `frontend-build-tests (windows-latest)` | recommended | Same — explicit listing surfaces the Windows leg of the frontend matrix. |
| `backend-unit-tests (ubuntu-latest)` | recommended | Implicit dependency. |
| `backend-unit-tests (windows-latest)` | recommended | Implicit dependency. |
| `doctor-smoke (ubuntu-latest)` | recommended | Implicit dependency. Linux-only — story 1.17's matrix was collapsed to Ubuntu when the job grew a full-Spring-context boot; Windows coverage is tracked in `_bmad-output/implementation-artifacts/deferred-work.md`. |

The `foundation-gate` row is the load-bearing check; the others are convenience signals.

## Scripted helper

Two helper scripts ship under `scripts/ci/` to apply the required-check configuration idempotently
via `gh api -X PUT`:

- `scripts/ci/configure-branch-protection.sh` (POSIX/Bash).
- `scripts/ci/configure-branch-protection.ps1` (PowerShell 5.1+).

Both scripts use the same `REQUIRED_CHECKS_START` / `REQUIRED_CHECKS_END` marker block as their
source-of-truth contexts list. `BranchProtectionConfigSmokeTest` (in the backend test suite)
parses the bash helper at build time and asserts `foundation-gate` is present, preventing a
silent removal.

Usage (set `OWNER` / `REPO` to match your fork, or rely on the `gh` CLI's current-repo default):

```bash
OWNER=<owner> REPO=<repo> ./scripts/ci/configure-branch-protection.sh
```

The script uses `gh api -X PUT` (full replace), not `PATCH`, so re-running it produces no diff
against the desired state. Read access to `branches/main/protection` is verified before the PUT
to catch missing-permission errors early.

GitHub branch-protection rules cannot be wired in code — they live in repository settings. Below
are the operational steps for a repo admin to register `foundation-gate` as a required check.

## Option A — GitHub UI

1. Open **Settings → Branches → Branch protection rules**.
2. Edit (or create) the rule for `main`.
3. Under **Require status checks to pass before merging**, ensure the option is enabled.
4. In the **Status checks that are required** search box, type `foundation-gate` and select it from
   the dropdown. (The job must have run at least once on a PR so GitHub can offer it as a
   selectable check.)
5. (Optional) Also select the per-OS jobs that block foundation-gate — e.g.
    `format-static-checks (ubuntu-latest)`, `format-static-checks (windows-latest)`,
   `frontend-build-tests (ubuntu-latest)`, `frontend-build-tests (windows-latest)`,
   `backend-unit-tests (ubuntu-latest)`, `backend-unit-tests (windows-latest)`, and
   `doctor-smoke (ubuntu-latest)` (Linux-only — see the table above). `foundation-gate`
   already depends on these, so they are implicitly required, but listing them explicitly
   gives clearer PR-page UI signal.
6. Save the rule.

## Option B — `gh api` (scriptable)

The required-check name to register is `foundation-gate`. Replace `<OWNER>/<REPO>` with the
repository identifier:

```bash
gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  /repos/<OWNER>/<REPO>/branches/main/protection \
  -F required_status_checks[strict]=true \
  -F 'required_status_checks[contexts][]=foundation-gate' \
  -F 'required_status_checks[contexts][]=format-static-checks (ubuntu-latest)' \
  -F 'required_status_checks[contexts][]=format-static-checks (windows-latest)' \
  -F 'required_status_checks[contexts][]=frontend-build-tests (ubuntu-latest)' \
  -F 'required_status_checks[contexts][]=frontend-build-tests (windows-latest)' \
  -F 'required_status_checks[contexts][]=backend-unit-tests (ubuntu-latest)' \
  -F 'required_status_checks[contexts][]=backend-unit-tests (windows-latest)' \
  -F 'required_status_checks[contexts][]=doctor-smoke (ubuntu-latest)' \
  -F enforce_admins=true \
  -F 'required_pull_request_reviews[required_approving_review_count]=1' \
  -F restrictions=null
```

`strict=true` requires the PR branch to be up-to-date with `main` before the check is considered
passing. Adjust the `required_pull_request_reviews[required_approving_review_count]` integer to
your team's review policy.

## Why this is not in code

GitHub does not allow a repository workflow file to grant itself protection over its own merges.
The admin must explicitly opt in to the requirement at the repo level — this is intentional
defense-in-depth so a compromised PR can't relax its own merge gate.

## What `foundation-gate` aggregates today

```text
foundation-gate
├── format-static-checks    (Linux + Windows matrix; Spotless + Checkstyle + SpotBugs)
├── runner-contract-fixtures
├── frontend-build-tests    (Linux + Windows matrix; Vite + frontend-maven-plugin)
├── backend-unit-tests      (Linux + Windows matrix; Surefire only)
├── backend-contract-tests  (Linux; Failsafe — ArchUnit + Testcontainers + contract + integration)
├── runner-image-compat
├── jar-packaging
├── export-redaction-verify
└── doctor-smoke            (Linux only; story 1.17 matrix collapsed when 1.21 added the Spring boot)
```

`bundled-jar-smoke` is intentionally NOT a foundation-gate dependency — it runs only on
`push: refs/heads/main` for release-readiness and would otherwise block all PR merges.

## Related

- `.github/workflows/ci.yml` — the workflow definition. Read the job-level comments for the
  per-tier rationale.
- `docs/ci-pipeline.md` — operator-facing tour of each tier (purpose, OS scope, expected runtime,
  failure modes).
- Story 1.23 (in backlog) — replaces the `foundation-gate` shell-echo body with deterministic
  fixture event-stream verification.
