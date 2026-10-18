# ADR-0022: Spring Batch and Spring Cloud Task deferred

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16

## Context

You asked to include Spring Batch or Spring Cloud Task **only if** a real batch requirement exists.

Background jobs we have:

| Job | Size | Nature |
|---|---|---|
| Outbox relay | every 500 ms, ≤ 100 rows | continuous polling |
| Payment executor | every second, few rows | continuous polling (`SKIP LOCKED`) |
| Idempotency key purge | daily, one `DELETE` | housekeeping |
| Outbox cleanup | daily, one `DELETE` | housekeeping |

None of these needs chunking, restart from the last good item, job parameters, or a job repository.

## Decision

- **Do not add** Spring Batch or Spring Cloud Task now.
- Jobs above use `@Scheduled` with PostgreSQL locks (`SKIP LOCKED` for the executor, a transaction-scoped advisory lock for the relay and housekeeping) so they are safe with several replicas.
- A real candidate is recorded for later: **end-of-day payment reconciliation report** — read all of the day's payments, compare with the provider's settlement file (simulated CSV), write mismatches, and resume after a crash. That is a true chunk-oriented, restartable batch job and would justify **Spring Batch** (run as a Kubernetes `CronJob`; Spring Cloud Task only if we need task execution history).

## Consequences

Good: fewer dependencies; no idle job-repository tables.

Bad / costs: the portfolio does not show Spring Batch unless the optional story is built.

## How we verify it

Scheduled jobs have integration tests with two concurrent instances proving only one does the work.
