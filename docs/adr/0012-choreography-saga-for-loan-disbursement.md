# ADR-0012: Choreography saga for loan disbursement

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F07 (payment executor; the Kafka listener never calls the provider)

## Context

Approving a loan (loan-service) and paying it out (payment-service) are two local transactions in two databases. They must end consistent without a distributed transaction. An earlier draft called the provider inside the Kafka listener, which held a partition for up to ~10 s and created two retry paths.

## Decision

A **choreography saga** — services react to each other's events; there is no central coordinator.

| Step | Service | Local transaction | Emits |
|---|---|---|---|
| 1 | loan-service | Officer approves → `APPROVED` (KYC re-checked) | `LoanApproved` |
| 2 | payment-service **listener** | Create `PENDING` disbursement (unique per loan) + `processed_events` row. Nothing else | — |
| 3 | payment-service **executor** | Claim due `PENDING` rows (`FOR UPDATE SKIP LOCKED`, mark `IN_FLIGHT`), call the provider **outside** any DB transaction, then `COMPLETED` or `FAILED` | `DisbursementCompleted` / `DisbursementFailed` |
| 4a | loan-service | `DISBURSED`, store terms | `LoanDisbursed` (with `totalPayable`) |
| 4b | loan-service | **Compensation:** `DISBURSEMENT_FAILED` | `LoanDisbursementFailed` |
| 5 | payment-service | Open ledger row in `loan_accounts` from `LoanDisbursed` | — |
| — | notification-service | Emails for every step | — |

**Executor rules:**

- Runs every second; several replicas may run (each claims different rows with `SKIP LOCKED`).
- Budget: max 5 attempts within 15 minutes, exponential spacing (`nextAttemptAt`).
- Before any retry, it asks the provider for the status of our reference (`paymentId`), so it never pays twice.
- `IN_FLIGHT` rows older than the call timeout (crash during a call) go back to `PENDING`.
- **Technical failure ≠ business failure.** Timeout or open circuit → stays `PENDING`. Only a provider **decline** or an exhausted budget → `FAILED`.

**Saga visibility:** loan status is the saga state (`APPROVED` = in progress). Grafana shows loans in `APPROVED` for more than 15 minutes ("stuck sagas").

## Consequences

Good: the listener is fast and never blocked by the provider; one code path for first try, retry and crash recovery; no coordinator to run; failures are explicit states.

Bad / costs: up to ~1 s extra latency before the first provider call; the flow is spread across services (documented in [workflows](../architecture/02-workflows.md) and visible in traces via `correlationId`); eventual consistency window.

## Alternatives considered

- **Provider call inside the listener** — removed in review (partition blocking, two retry paths).
- **Orchestration** (saga state machine in loan-service, or Temporal/Camunda) — better for long sagas with many steps or human tasks. Overkill for two participants.
- **2PC/XA** — rejected (see [trade-offs §6](../architecture/07-trade-offs.md#6-synchronous-distributed-transactions-vs-saga-and-eventual-consistency)).

## How we verify it

- Integration tests (PostgreSQL + Kafka Testcontainers): happy path; decline → compensation; timeout → executor completes within budget; duplicate `LoanApproved` → one payment; crash simulation (`IN_FLIGHT` aged) → completed once.
- E2E: approved application ends `DISBURSED`; `SIM-DECLINE` payout reference ends `DISBURSEMENT_FAILED`.
