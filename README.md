# DeliveryLine

DeliveryLine is a governed delivery-pipeline runtime: it submits Linear tickets for
AI-assisted execution against a real append-only audit trail, with a CLI (`submit`,
`status`, `history`, `retry`) shipping in Epic 1 and an operator console arriving in later
epics. The foundation slice (Epic 1) runs entirely against mock Linear + mock runner
adapters so you can complete a first governed run in ~15 minutes without external
dependencies.

## Quick links

- **Pilot installer?** Start with → [`docs/quickstart.md`](docs/quickstart.md)
- **Product Manager reviewing a spec?** → [`docs/pm-loop-walkthrough.md`](docs/pm-loop-walkthrough.md)
- **Developer reviewing implementation output?** → [`docs/execution-walkthrough.md`](docs/execution-walkthrough.md)
- **Configuring a project (connectors + credentials)?** → [`docs/project-configuration-walkthrough.md`](docs/project-configuration-walkthrough.md)
- **Reviewing, running manually, or observing a step?** → [`docs/per-step-execution-control-walkthrough.md`](docs/per-step-execution-control-walkthrough.md)
- **Run failed?** → [`docs/failure-recovery-walkthrough.md`](docs/failure-recovery-walkthrough.md)
- **All CLI commands** → [`docs/cli/`](docs/cli/README.md) (start at the CLI index for
  exit-code bands, idempotency-key contract, and per-command references —
  [`submit` / `status` / `history` / `retry`](docs/cli/workflow-commands.md) and
  [`doctor`](docs/cli/doctor.md))
- **Supported environments** → [`docs/supported-environments.md`](docs/supported-environments.md)
- **Glossary** → [`docs/glossary.md`](docs/glossary.md)
- **Contributor — extending a governed registry?** → [`docs/patterns/registry-recipe.md`](docs/patterns/registry-recipe.md) (the mirror sites + foundation-gate test that go red together)
- **Contributor — writing frontend tests?** → [`docs/testing/frontend-test-patterns.md`](docs/testing/frontend-test-patterns.md) and [`docs/testing/snapshots-vs-assertions.md`](docs/testing/snapshots-vs-assertions.md)
- **Epic 1 close status** → [`docs/epic-1-close-checklist.md`](docs/epic-1-close-checklist.md)

## Project layout

- `deliveryline-backend/` — Spring Boot 3 backend, Spring Shell CLI commands, Flyway
  migrations, runner + Linear adapters.
- `deliveryline-runner-contracts/` — runner-output JSON schema (v1) and contract validator.
- `deliveryline-frontend/` — Epic 2+ Vite / React / TypeScript module (placeholder pom
  today; story 2.1 scaffolds the real module).
- `docs/` — all project documentation; see Quick links above.
- `scripts/` — cross-platform entrypoints in PowerShell and bash flavours. Active in
  Epic 1: `start-all`, `doctor`, `reset-local`. Placeholder until Epic 5:
  `export-run` (exits non-zero with an Epic-5 stub message).
- `.github/workflows/ci.yml` — the tiered CI pipeline (story 1.21). Story 1.23 will
  register `foundation-gate` as the required status check via branch protection.

## How to contribute

This is an active product build, not an OSS project — the repository is private to the
DeliveryLine pilot team. If you have access, follow
[`docs/quickstart.md`](docs/quickstart.md) for first-run setup; story planning artifacts
live under `_bmad-output/`.
