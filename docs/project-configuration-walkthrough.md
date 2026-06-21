# Project-Configuration Walkthrough (Epic 3c)

> **Project-configuration walkthrough validator:** `_____________________________` (to be named before Epic 3c close)

This walkthrough is the **operator's end-to-end guide to configuring a project** in the
DeliveryLine web app: opening the Projects area, creating or editing a project, choosing
connector kinds and run options, setting connector credentials safely, running the connection
test, activating the project, and scoping work to it — all in the browser, on your first use,
unaided.

It pairs with [`quickstart.md`](quickstart.md) and [`setup-local.md`](setup-local.md) (which
get DeliveryLine running and a first run submitted) and sits upstream of the review loops
([`pm-loop-walkthrough.md`](pm-loop-walkthrough.md) and
[`execution-walkthrough.md`](execution-walkthrough.md)) — a project is the thing every governed
run is scoped to. This one is **entirely browser-based**: it works identically on Windows,
macOS, and Linux (see [`supported-environments.md`](supported-environments.md)). The single
host-environment touch-point — the `DELIVERYLINE_MASTER_KEY` environment variable that protects
stored credentials — is described as an environment variable, with no OS-specific or
shell-specific syntax.

**Target time:** ~10 minutes from opening the Projects area to a tested, active project.

---

## The one thing to remember

> **Credentials are write-only.** When you set a connector credential, DeliveryLine encrypts it
> and stores it — it is **never displayed back, never pre-filled into a form, never placed in
> the page, and never exported.** A credential field that already has a value still shows up
> empty; typing into it **replaces** the stored secret, leaving it blank **keeps** it. The list
> tells you a role is `configured` or `not_configured`, never what the secret is. There is no
> "show password" — by design. Read the [Set credentials safely](#step-3--set-credentials-safely)
> section before you touch a credential field.

---

## Before you start (prerequisites)

You need one thing in place:

1. **DeliveryLine running locally.** Follow [`quickstart.md`](quickstart.md) (or
   [`setup-local.md`](setup-local.md)) end-to-end first. When the app is up, open it in your
   browser.

You do **not** need any OS-specific setup, and you do not need to create a project to get
started: an existing single-host setup is **migrated transparently** into a seeded `default`
project (see [The default project](#the-default-project-no-action-needed)). This walkthrough is
about configuring an **additional** project — or inspecting and testing the `default` one.

One concept to hold onto before you start: a **project** is the first-class thing every governed
run is scoped to. It binds a repository, a pair of **connectors** (a ticket source and a
repository host), the per-connector **credentials** those connectors use, and a few run options.
All three terms are defined in the [glossary](glossary.md) and recapped in
[Concepts you just used](#concepts-you-just-used) at the end.

---

## The configuration sequence at a glance

Configuring a project is a single linear sequence, each step building on the last:

```text
open the Projects area
   │
   ▼
create / edit a project
 (name · slug · repo URL · ticket-source kind · repo-host kind · OpenSpec toggle)
   │
   ▼
set credentials safely
 (write-only, per connector role)
   │
   ▼
run the connection test
 (3 checks: repository_reachable · ticket_source_auth · repository_host_auth)
   │
   ▼
activate
 (project is `active`, advertises its allowed actions)
   │
   ▼
scope work to the project
 (work is associated at submit / intake)
```

The rest of this doc walks each step.

---

## Step 1 — Open the Projects area

The app's navigation rail (and the mobile navigation drawer on a small screen) carries a
**Projects** landmark. It opens the project-configuration area, which is **distinct from** the
review queue (`/workflows`) and an individual run view (`/workflows/{id}`) — Projects is where
you *configure*, the queue is where you *review*.

The Projects area lists every project. Each row reads:

```text
┌──────────────────────────────────────────────────────────────────────┐
│  Projects                                            [ + New project ] │
│                                                                        │
│  NAME          STATUS    TICKET SRC   REPO HOST   REPOSITORY           │
│  ───────────────────────────────────────────────────────────────────  │
│  default       ● active  linear       github      org/repo             │
│    credentials: ticket_source configured · repo_host configured        │
│    last test: ✓ passed · 2 minutes ago                                 │
│                                                                        │
│  acme-web      ● active  github       github      org/acme-web         │
│    credentials: ticket_source not_configured · repo_host configured    │
│    last test: Not tested                                               │
└──────────────────────────────────────────────────────────────────────┘
```

Each column is real surface, not decoration:

- **Name** and **Status** — the project's display name and its status, shown as an **icon +
  text** label (`active` / `disabled`), never colour alone, so it is readable without relying on
  colour.
- **Ticket source** and **Repository host** — the connector kinds the project is bound to (see
  [Connector kinds](#connector-kinds)).
- **Repository** — the bound repository URL, with a graceful empty state when none is set.
- **Credentials** — per connector role, whether a credential is `configured` or
  `not_configured` (presence only — never the secret itself).
- **Last test** — the most recent connection-test result and when it ran. This is
  **session-scoped**: the backend persists no test history, so a freshly loaded list reads **Not
  tested** until you run a test in this session (see [Step 4](#step-4--run-the-connection-test)).

---

## Step 2 — Create / edit a project

**New project** opens the create form; selecting an existing project and choosing **Edit** opens
the same form pre-filled. The fields:

```text
┌──────────────────────────────────────────────────────────────────────┐
│  New project                                                          │
│                                                                        │
│  Display name   [ Acme Web                            ]   (≤ 256)      │
│  Slug           [ acme-web                            ]   (create-only)│
│  Repository URL [ https://github.com/org/acme-web     ]   (optional)   │
│                                                                        │
│  Ticket source  ( linear  ▾ )                                         │
│  Repository host( github  ▾ )                                         │
│                                                                        │
│  Run options                                                          │
│   [✓] OpenSpec                                                        │
│                                                                        │
│   [ Save ]   [ Cancel ]                                               │
└──────────────────────────────────────────────────────────────────────┘
```

- **Display name** — a human label, up to 256 characters.
- **Slug** — the stable identifier (e.g. `acme-web`). It is **create-only: immutable on edit.**
  On the edit form the slug is shown read-only — pick it carefully when you create the project,
  because it cannot be changed afterward.
- **Repository URL** — the repository the project's runs target. Optional (nullable, up to 2048
  characters); it can be filled later.
- **Ticket source** / **Repository host** — the connector **kind** pickers (see
  [Connector kinds](#connector-kinds)).
- **OpenSpec** — a run-option toggle controlling whether OpenSpec is enabled for this project's
  runs.

The form validates each field and identifies errors explicitly (which field, and why) rather
than rejecting the whole form opaquely. Save persists the project; until you set credentials and
run a test, its connectors are configured but unverified.

### Connector kinds

The ticket-source and repository-host pickers offer the connector **kinds** the platform
registers:

- **`linear`** — the Linear ticket source.
- **`github`** — the GitHub repository host (and, where applicable, ticket source).
- **`gitlab`** — a **registered proof-of-seam kind**. It exists to prove that per-project
  connector resolution is genuinely per-project and not hard-wired to one vendor; it is a
  documented stub, **not** a full vendor implementation. You will see it in the picker; treat it
  as a demonstration of the seam, not a production connector.

The picker is a frontend list that mirrors the platform's connector registry — adding a real new
vendor is a backend change, not something you configure here.

---

## Step 3 — Set credentials safely

A connector authenticates with a **credential** — a per-role secret. There are two roles:

- **`ticket_source`** — the secret the ticket-source connector uses (e.g. a Linear API token).
- **`repo_host`** — the secret the repository-host connector uses (e.g. a GitHub token).

(The roles use their underscored wire form, `ticket_source` / `repo_host`, throughout the UI and
API.)

You set each credential through a `type="password"` field:

```text
┌──────────────────────────────────────────────────────────────────────┐
│  Credentials                                                          │
│                                                                        │
│  ticket_source   status: not_configured                              │
│    [ ••••••••••••••••••••••••• ]   [ Set credential ]                │
│                                                                        │
│  repo_host       status: configured                                  │
│    [                          ]   [ Replace credential ]             │
│    (leave blank to keep the current secret)                          │
└──────────────────────────────────────────────────────────────────────┘
```

What "write-only" means in practice:

- The field is **never seeded** from any server response. Even for a role that is already
  `configured`, the input opens empty.
- **Typing a value and saving sets (or replaces) the stored secret.** Leaving the field blank on
  edit **keeps** the existing secret untouched.
- The server's response to setting a credential is **id-only** — it returns the role, a
  `configured` status, and a non-secret credential id (`cred_…`). **No secret is ever returned**,
  so nothing sensitive is placed in the page or the DOM.
- There is **no read-back, no "reveal", and no export** of credential values, anywhere.

### How credentials are protected at rest

You do not need to understand the cryptography to use this screen, but it is worth knowing what
the platform guarantees:

- Connector credentials are **encrypted at rest** using **envelope encryption** — each secret is
  encrypted with its own random AES-256-GCM **data key**, and that data key is wrapped by a
  single host-supplied **master key**.
- The master key is supplied to the host through the **`DELIVERYLINE_MASTER_KEY`** environment
  variable (Base64-encoded). It is **never** written to the database or any file.
- Because the master key lives in the host environment, the credential subsystem **defends
  against an at-rest database compromise** (a leaked dump yields only ciphertext) but **does not
  defend against a host compromise** — anyone who can read the host's environment can read the
  master key.

The canonical detail — the encryption frame, key rotation, and the full threat model — lives in
[`adr/0013-credential-encryption.md`](adr/0013-credential-encryption.md) and the
[`master key`](glossary.md#master-key) / [`credential encryption`](glossary.md#credential-encryption)
glossary entries. This walkthrough links them rather than re-deriving the cryptography; setting
the `DELIVERYLINE_MASTER_KEY` environment variable on the host is the operator's only crypto
touch-point, and ADR 0013 is the source of truth for how.

---

## Step 4 — Run the connection test

With the project saved and credentials set, run the **connection test** to verify the project
can actually reach its repository and authenticate its connectors. The test runs **three
checks**, each returning `pass`, `fail`, or `skipped` with a short, **secret-free** `detail`
string:

```text
┌──────────────────────────────────────────────────────────────────────┐
│  Connection test                                  [ Run test ]        │
│                                                                        │
│   ✓  repository_reachable    pass     repository resolved             │
│   ✗  ticket_source_auth      fail     authentication rejected         │
│   –  repository_host_auth    skipped  no repo_host credential set     │
└──────────────────────────────────────────────────────────────────────┘
```

The three checks:

| Check | Verifies | Fix a `fail` / `skipped` by… |
|---|---|---|
| **`repository_reachable`** | The bound repository URL resolves and is reachable for the project. | Correcting the **repository URL**, or the **`repo_host` credential** the host needs to reach it. |
| **`ticket_source_auth`** | The ticket-source connector authenticates with its credential. | Checking the **ticket-source kind** is right for the project and its **`ticket_source` credential** is valid. |
| **`repository_host_auth`** | The repository-host connector authenticates with its credential. | Checking the **repository-host kind** and its **`repo_host` credential**. A `skipped` here usually means no credential is set yet for that role. |

Two things to internalise about the results:

- **A `fail` or `skipped` is data, not an error.** The test returns its per-check results as
  in-band data on a successful (HTTP 200) response — a failing check is a *finding*, not a broken
  request. The only genuine error surfaces are a missing project (`PROJECT_NOT_FOUND`) or an
  unsupported connector kind (`UNSUPPORTED_CONNECTOR_KIND`), which appear as proper error
  messages, not as check results.
- **Results are session-scoped.** The backend keeps **no test history**, so the result you see is
  the one from the test you just ran. Reload the page and the project reads **Not tested** again
  until you re-run it.

Fix any failing check (correct the URL, the connector kind, or the credential), then run the
test again until all relevant checks read `pass`.

---

## Step 5 — Activate

A project that is configured and tested is **active**. An `active` project advertises the
**actions you are allowed to take** on it — DeliveryLine reports, per project, an
`allowedActions` list, and the UI shows only the controls in that list (e.g. *edit*, *disable*,
*set-credential*, *test*).

A few rules govern these actions:

- The allowed actions are **status-derived only** — there is **no role dimension and no RBAC** in
  this release. What you can do depends on the project's status, not on who you are.
- A **`disabled`** project advertises **enable** (so you can bring it back), and an `active`
  project advertises **disable**.
- The seeded **`default`** project **never advertises `disable`** — it is the parity-preserving
  baseline (see next section) and cannot be turned off.

Because the controls are driven by the backend-reported allowed actions, you will never see a
button for an action the project doesn't currently permit.

---

## The default project (no action needed)

If you ran a single-host DeliveryLine before multi-project configuration existed, **nothing
changed for you, and you need to do nothing.** On upgrade, DeliveryLine **transparently migrates**
your prior single-host setup into a seeded **`default`** project:

- Its repository URL, `linear` ticket source, `github` repository host, and OpenSpec flag are
  taken from your prior global configuration.
- Its credentials reference the existing global environment-variable secrets you already had.
- Existing runs are associated with the `default` project; single-project behaviour is
  unchanged from before.

No operator action is required. And when the `default` project is the **only** project, the
project **selector collapses to a static label** — there is nothing to pick, so the UI shows the
project as a label rather than asking you to choose. The selector only offers a real choice once
a second project exists.

---

## Step 6 — Scope work to the project

Once you have more than one project, work is **associated with a project at submission / intake**
— when a run is created (for example, the optional project reference accepted by the `submit`
command and the intake API), the run is bound to that project, and everything downstream (the
repository it targets, which connectors and credentials it uses) derives from the run's project.

In the single-project pilot, the **selector collapses to a label** (per the previous section), so
there is nothing to choose — every run lands on the `default` project automatically.

> **What you cannot do yet:** there is **no project filter on the review queue** and **no
> per-run project badge** in this release. The run-review surfaces (`WorkflowSummary` /
> `WorkflowDetail`) do not yet carry a project field, and `GET /api/v1/workflows` has no
> `projectId` parameter. Project-scoped queue filtering and per-run project attribution are a
> documented backend follow-up — see
> [What is NOT in this walkthrough](#what-is-not-in-this-walkthrough).

---

## Concepts you just used

This walkthrough stays within DeliveryLine's established vocabulary — see
[`glossary.md`](glossary.md) for the canonical definitions. The three terms this walkthrough uses,
plus the two credential terms it builds on:

- **[project](glossary.md#project)** — the first-class aggregate every governed run is scoped to.
- **[connector](glossary.md#connector)** — the selectable, vendor-neutral ticket-source /
  repository-host adapter a project binds by kind.
- **[credential](glossary.md#credential)** — the write-only, envelope-encrypted per-role secret a
  connector uses at call time.
- **[master key](glossary.md#master-key)** — the host-supplied key that wraps every stored
  credential.
- **[credential encryption](glossary.md#credential-encryption)** — the envelope-encryption scheme
  that protects credentials at rest.

---

## What is NOT in this walkthrough

This doc covers **configuring and verifying a project**. The following live elsewhere or have not
shipped yet:

- **Submitting and reviewing runs** — covered by [`quickstart.md`](quickstart.md), the
  [`pm-loop-walkthrough.md`](pm-loop-walkthrough.md) (spec review), and the
  [`execution-walkthrough.md`](execution-walkthrough.md) (execution review). A project is the
  thing those runs are scoped to; this doc is upstream of them.
- **Project-scoped queue filtering and per-run project attribution** — **a documented backend
  follow-up, not shipped.** The project selector ships as a collapse-to-label seam; the run-read
  DTOs carry no project field and the workflows API has no `projectId` filter yet (see
  [Step 6](#step-6--scope-work-to-the-project)).
- **Credential rotation mechanics** — the platform supports key/credential rotation by design
  (the `keyId` indirection in [`adr/0013-credential-encryption.md`](adr/0013-credential-encryption.md)),
  but there is no operator rotation command in this release; setting a credential again replaces
  it.
- **Multi-user access control (RBAC)** — out of scope. Allowed actions are status-derived only;
  there is no per-user authorisation in this release.
- **Setting `DELIVERYLINE_MASTER_KEY`** — described here only as the host environment variable
  that protects credentials; the canonical detail is in
  [`adr/0013-credential-encryption.md`](adr/0013-credential-encryption.md).

If you reach a state this walkthrough doesn't describe, that's a signal the UI has moved ahead of
the doc — flag it to the Project-configuration walkthrough validator named at the top.
