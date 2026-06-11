# Linear Completion Sync

When a governed DeliveryLine run reaches the `Completed` state, DeliveryLine writes a **merge-ready
completion summary** back to the run's **source Linear ticket** (the ticket the run was created
from, per the intake flow). This closes the loop for Linear-native product managers: they see the
outcome in-place, without opening the DeliveryLine UI.

The sync is **best-effort, after-the-fact, redaction-enforced, and never blocks the governed flow.**
It runs *after* the `Completed` transition has already committed, so a sync failure can never roll
back completion — at worst the Linear ticket simply does not receive its comment, and the failure is
recorded as an auditable event.

## How it works

1. A run transitions to `Completed` (today via a direct transition; the production trigger —
   technical approval of the implementation — arrives in a later story).
2. A **post-commit hook** fires once the transition is durable and calls
   `WorkflowOrchestrationService.syncCompletionToLinear(runId)`.
3. The service resolves the run's linked **Linear ticket** (`integration_links` of type `linear`)
   and **GitHub PR** (`integration_links` of type `github_pr`), composes the summary from the
   configured template, runs it through the **redaction policy** (story 1.10), and posts it via the
   Linear `commentCreate` mutation.
4. If anything is missing (no Linear ticket → nothing to post to; no PR link → the `{prUrl}`
   placeholder degrades to a fallback) the sync degrades gracefully rather than failing.

## Default template

```
DeliveryLine governed run `{runId}` completed: PR `{prUrl}` ready for merge. Spec: `{specSummary}` (v{specVersion}). Reviewers: PM `{pmReviewer}`, Dev `{devReviewer}`. Cycle time: `{durationFormatted}`.
```

### Placeholders

| Placeholder | Meaning | Fallback when unresolved |
|---|---|---|
| `{runId}` | The governed run's public id (`run_…`). **Required.** | — (always present) |
| `{prUrl}` | The merged/ready pull-request URL, reconstructed from the linked PR reference. | `n/a` |
| `{specSummary}` | Pointer to the approved specification. | `n/a` |
| `{specVersion}` | The latest spec artifact version. | `n/a` |
| `{pmReviewer}` | Identity that approved the spec. | `unknown` |
| `{devReviewer}` | Identity that approved the PR output / implementation plan. | `unknown` |
| `{durationFormatted}` | Cycle time (first event → completion), human-formatted (e.g. `2h 15m 3s`). | `n/a` |

## Customization & opt-out

Both knobs live under `deliveryline.workflow.linear-completion-sync` in `application.yml`:

```yaml
deliveryline:
  workflow:
    linear-completion-sync:
      enabled: true        # set false to disable completion-sync entirely (pilot opt-out)
      template: "DeliveryLine governed run `{runId}` completed: ..."   # customize freely
```

- **`enabled`** (default `true`) — the pilot opt-out. When `false`, the post-commit hook never fires
  and no comment is posted. The `doctor` `linear-completion-sync` check reports the current setting.
- **`template`** — the summary text. It may reference any of the placeholders above; `{runId}` is
  **required**. An invalid template (an unknown `{token}` or a missing required placeholder) raises
  `INVALID_COMPLETION_TEMPLATE` **at startup** (when `enabled`), failing context boot so a broken
  template can never silently post a malformed comment.

### Manual retry

If a sync failed (transient network/auth/rate-limit), re-run it from the CLI:

```
deliveryline sync-completion <run_id> [--correlation-id <c>]
```

This is **idempotent**: the Linear adapter embeds a deterministic fingerprint marker derived from
the canonical summary, so re-posting the same summary is a no-op (no duplicate comments).

## Security posture

- **Redaction enforced.** The summary body is passed through `RedactionPolicyService` claiming the
  `shareable-full` classification *before* it is sent. Any secret pattern or local path that leaked
  from a source datum is scrubbed; the comment is never posted with the classification downgraded.
- **No secrets logged.** The Linear API token, the full PR body, and any redacted-away field are
  never logged. Free-text values are sanitized for log injection.
- **Best-effort, non-blocking (after-the-fact).** The sync runs only after the `Completed`
  transition is durably committed. A post failure is recorded as a `linear.completionSyncFailed`
  workflow event (carrying the integration failure category) and never rolls back completion.
- **Narrow boundary.** This is the *only* path that writes to Linear — runner CLIs (Codex/Claude)
  never post to Linear directly. An ArchUnit rule enforces that
  `LinearAdapter.postGovernedRunComment` is reachable only from `WorkflowOrchestrationService` and
  the `sync-completion` CLI command, keeping Linear's role to "intake + completion sync".

See also: [`../cli/doctor.md`](../cli/doctor.md) (the `linear-completion-sync` diagnostic check) and
[`../cli/workflow-commands.md`](../cli/workflow-commands.md).
