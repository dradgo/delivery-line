# ADR 0006 — Runner Execution Queue: Postgres `SKIP LOCKED`, One Shared Worker Pool

**Status:** Accepted (2026-06-14)
**Driver:** Epic 3 story 3.17 (RunnerExecutionQueue + Configurable Worker Pool) — the largest story in Epic 3, split into **3.17a** (this substrate) and **3.17b** (activation). This ADR records the load-bearing architecture decision up front, before the worker pool that realizes it lands in 3.17b.

## Context

Today every runner dispatch is **synchronous**: a workflow command calls `RunnerBroker.dispatch`, which composes the context bundle (clone + summarize the repo) and hands off to the runner adapter on the caller's thread. There is no admission control and no concurrency bound — N concurrent submissions compose N bundles and start N containers at once, and a slow bundle composition blocks the calling request thread.

To run real agents concurrently and predictably we need:

1. A **durable queue** of pending runner executions that survives a process restart (an in-memory `BlockingQueue` would lose queued work on crash).
2. **Backpressure** — a cap on queued depth so a submission storm fails fast (`RUNNER_QUEUE_FULL`) rather than exhausting memory or the container host.
3. **Safe concurrent consumption** — multiple workers leasing distinct rows without double-dispatching the same execution.
4. A **bounded worker pool** so the number of in-flight agent containers is a tuned constant, not "however many requests arrived."

The system already runs on PostgreSQL (the single source of truth for all workflow state); introducing a separate broker (Redis, RabbitMQ, SQS) would add an operational dependency, a second consistency domain, and a new failure mode for a pilot that deliberately keeps its infrastructure to "Postgres + Docker."

## Decision

**1. The queue is a PostgreSQL table, consumed with `SELECT … FOR UPDATE SKIP LOCKED`.** The existing `runner_executions` table gains a `queued` status (the pre-dispatch state) plus queue-bookkeeping columns (`queue_priority`, `queue_attempt_count`, `worker_id`, `dispatched_at`, `correlation_id`) in migration **V12** (story 3.17a). `enqueue` inserts a `queued` row; `dequeue` runs

```sql
UPDATE runner_executions SET status='running', worker_id=:w, dispatched_at=now(),
       queue_attempt_count = queue_attempt_count + 1
 WHERE id = (SELECT id FROM runner_executions WHERE status='queued'
             ORDER BY queue_priority ASC, created_at ASC
             FOR UPDATE SKIP LOCKED LIMIT 1)
RETURNING …;
```

`SKIP LOCKED` is the decision's keystone: two workers racing `dequeue` never block each other and never pick the same row — the second worker's sub-select skips the row the first has locked and takes the next. JPA's `@Lock(PESSIMISTIC_WRITE)` emits plain `FOR UPDATE` (which would *block*, not skip), so the dequeue is a native `NamedParameterJdbcTemplate` statement, mirroring the existing raw-SQL mutations in `WorkflowRunPersistenceAdapter`.

**2. One shared worker pool, not per-stage pools.** A single `ThreadPoolTaskExecutor` (story 3.17b) drains the queue across *all* stages (spec-investigation, plan, pr-output). `queue_priority` (then `created_at`) — not a dedicated pool per stage — controls ordering. Per-stage pools were rejected (see Alternatives): they fragment a small worker budget, can idle one pool while another starves, and complicate graceful shutdown. A single pool with priority ordering keeps the concurrency bound a single tuned number and lets any worker serve any stage.

**3. `LISTEN/NOTIFY` is an optimization, not the transport.** The queue's *correctness* rests entirely on the table + `SKIP LOCKED`: a worker that polls the table on an interval drains it correctly with zero notifications. Story 3.17b adds `LISTEN/NOTIFY` purely to cut dequeue latency (wake a worker immediately on enqueue instead of waiting for the next poll tick). A missed or dropped notification only delays pickup until the next poll — it can never lose or double-dispatch work. This keeps the latency optimization off the correctness path.

**4. The substrate lands dormant first (3.17a), activation second (3.17b).** 3.17a builds and fully tests `enqueue`/`dequeue`/backpressure/`correlationId` persistence in isolation — `enqueue`/`dequeue` are pure data operations exercisable directly from test threads (the `SKIP LOCKED` concurrency proof needs only ≥2 racing connections, no worker pool). **No production code enqueues in 3.17a**; the 7 dispatch callers stay synchronous. 3.17b then refactors those callers onto `enqueue`, adds the worker pool, `LISTEN/NOTIFY`, graceful shutdown, and worker-crash lease reclamation — against a substrate already proven under concurrency. This is the house build-the-seam-before-the-call-site pattern (stories 3.15/3.16).

## Alternatives Considered

### Alt 1 — External broker (Redis / RabbitMQ / SQS)

A purpose-built message broker gives mature queueing semantics out of the box.

**Rejected.** Adds an operational dependency and a second consistency domain to a pilot whose stated infrastructure is "Postgres + Docker." Runner executions are already rows in Postgres with a state machine, FK relationships, and audit events; lifting the *queued* state into a separate store would split that lifecycle across two systems and require reconciling them on every crash. `SKIP LOCKED` gives us the one queueing primitive we need (safe concurrent lease) inside the system of record.

### Alt 2 — In-memory `BlockingQueue` + worker threads

A `BlockingQueue` fed by `dispatch` with a fixed worker pool, no DB table for the queue.

**Rejected.** Not durable — queued executions are lost on restart, and there is no way for `doctor`/inspection to observe queue depth or for a crashed worker's lease to be reclaimed. Backpressure would be a memory bound rather than an inspectable, configurable cap.

### Alt 3 — Per-stage worker pools

A separate pool (and separate `queue-max-depth`) for spec, plan, and pr-output stages.

**Rejected.** Fragments a small worker/container budget: with, say, 4 total workers, three pools cannot each get a useful share, and a backlog in one stage idles workers reserved for another. Priority ordering on a single shared pool achieves stage-favoring (a stage can be enqueued at a lower `queue_priority`) without statically partitioning capacity. Revisit if stages develop genuinely different resource profiles (e.g., one stage needs GPU hosts).

### Alt 4 — `LISTEN/NOTIFY` as the primary transport

Drive dequeue *only* off notifications, with no polling fallback.

**Rejected.** `NOTIFY` delivery is best-effort across reconnects; a worker that misses a notification (e.g., during a brief connection drop) would never pick up the waiting row. Making notifications the *optimization* over an always-correct poll keeps a dropped notification harmless (a one-poll-interval delay) instead of a stuck execution.

## Consequences

### Positive

- The queue lives in the system of record — depth is inspectable, leases are reclaimable, and queued state participates in the same backup/restore and audit story as every other row.
- `SKIP LOCKED` gives lock-free-feeling concurrent consumption with no external coordinator; correctness is a single SQL guarantee.
- One shared pool makes the concurrency bound a single tuned number and keeps graceful shutdown simple (drain one executor).
- Splitting substrate (3.17a) from activation (3.17b) lets the risky persistence/SQL/registry/contract surface land and be proven under concurrency before the behavioral caller refactor.

### Negative

- `SKIP LOCKED` dequeue is native SQL, outside JPA's `@Lock` abstraction — it must be maintained as raw SQL and covered by Testcontainers ITs (a real Postgres is required to prove the skip behavior; an H2/mock cannot).
- A single shared pool means a flood of one stage can, within the priority ordering, delay another stage's executions; mitigated by `queue_priority` but not eliminated.
- Backpressure is approximate under high concurrency: `enqueue` counts queued rows then inserts, so a burst of simultaneous enqueues can momentarily admit slightly past `queue-max-depth`. Acceptable for an advisory capacity cap (it is not a hard safety invariant).

### Neutral

- The worker pool, `LISTEN/NOTIFY`, graceful shutdown, and lease reclamation are all realized in story 3.17b; 3.17a ships the queue dormant (built + tested, no production caller).
- `RUNNER_QUEUE_FULL` is modeled as a transient/retryable `503` (mirroring `RUNNER_TIMEOUT`); the "run stays in its prior state on rejection" caller behavior is realized at the dispatch call-site in 3.17b.

## Upgrade Triggers

Revisit this ADR when any of the following changes:

- The worker/container budget grows large enough that a single shared pool's priority ordering no longer gives acceptable per-stage fairness (reconsider Alt 3 — per-stage pools).
- Runner executions need to span more than one PostgreSQL instance / the queue must be shared across processes that do not share a database (reconsider Alt 1 — external broker).
- Dequeue latency under the poll-interval fallback becomes user-visible in a way `LISTEN/NOTIFY` cannot mask (reconsider the notification model).
- A second class of queued work (not runner executions) needs the same substrate — generalize the table/columns rather than copy them.

## References

- Story 3.17a — queue substrate: V12 migration, `QUEUED` status, `RunnerExecutionQueue.enqueue/dequeue` (`SKIP LOCKED`), `RUNNER_QUEUE_FULL` backpressure, `correlation_id`, this ADR (built dormant).
- Story 3.17b — activation: `RunnerWorkerPool` (`ThreadPoolTaskExecutor`), `LISTEN/NOTIFY`, the `dispatch → enqueue` refactor of all 7 callers, graceful shutdown, worker-crash lease reclamation.
- `adapters/persistence/WorkflowRunPersistenceAdapter.java` — native `NamedParameterJdbcTemplate … RETURNING` precedent the dequeue mirrors.
- Stories 3.15 / 3.16 — the build-the-seam-before-the-call-site precedent for landing a capability dormant before its call-site.
- `domain/registry/RunnerExecutionStatus.java`, `V12__add_queue_state_columns.sql` — the `queued` status + queue columns.
