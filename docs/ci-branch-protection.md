# CI Branch Protection — `foundation-gate`

Story 1.21 wires a `foundation-gate` job in `.github/workflows/ci.yml` that aggregates the nine
foundation-tier checks plus the OS-matrix `doctor-smoke`. The job is a placeholder until story 1.23
fills it with deterministic-fixture event-stream verification. **What story 1.21 ships** is the
required-status-check NAME (`foundation-gate`) that the repository admin must register on the
default branch so any Epic 2/3/4 PR is structurally blocked until the foundation gate is green.

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
   `backend-unit-tests (ubuntu-latest)`, `backend-unit-tests (windows-latest)`, `doctor-smoke
   (ubuntu-latest)`, `doctor-smoke (windows-latest)`. `foundation-gate` already depends on these,
   so they are implicitly required, but listing them explicitly gives clearer PR-page UI signal.
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
  -f required_status_checks[strict]=true \
  -F 'required_status_checks[contexts][]=foundation-gate' \
  -F 'required_status_checks[contexts][]=format-static-checks (ubuntu-latest)' \
  -F 'required_status_checks[contexts][]=format-static-checks (windows-latest)' \
  -F 'required_status_checks[contexts][]=backend-unit-tests (ubuntu-latest)' \
  -F 'required_status_checks[contexts][]=backend-unit-tests (windows-latest)' \
  -F 'required_status_checks[contexts][]=doctor-smoke (ubuntu-latest)' \
  -F 'required_status_checks[contexts][]=doctor-smoke (windows-latest)' \
  -f enforce_admins=true \
  -F 'required_pull_request_reviews[required_approving_review_count]=1' \
  -f restrictions=
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
├── frontend-build-tests    (placeholder until story 2.1)
├── backend-unit-tests      (Linux + Windows matrix; Surefire only)
├── backend-contract-tests  (Linux; Failsafe — ArchUnit + Testcontainers + contract + integration)
├── runner-image-compat
├── jar-packaging
├── export-redaction-verify
└── doctor-smoke            (Linux + Windows matrix; story 1.17)
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
