# ADR-0003: Service boundaries and database per service

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F08 (repayment ledger ownership), F15 (owner/app database users)

## Context

We need clear ownership so each service can change its schema and rules without asking others. We also need to run on a laptop and on a small AWS budget.

## Decision

Bounded contexts and owners:

| Service | Owns |
|---|---|
| customer-service | customer profile, contact details, KYC status, link to Keycloak `sub` |
| loan-service | loan products, applications, decisions, loan terms (principal, tenure, EMI, total payable), loan status |
| payment-service | payments (disbursement, repayment), **repayment ledger** `loan_accounts` (total payable, repaid, reserved), idempotency keys, provider calls |
| notification-service | notification log |

The repayment ledger sits in payment-service because the money check and the money movement must happen under **one lock in one database**. loan-service keeps a display copy of `remaining` from `RepaymentReceived` and closes the loan when it reaches zero.

Data rules:

1. **One database per service** and **two users per database**:
   - `<svc>_owner` — owns the schema; used only by Flyway (`spring.flyway.user`).
   - `<svc>_app` — `SELECT/INSERT/UPDATE/DELETE` on the service's tables only; `statement_timeout` set; used by the running app.
   Neither user can connect to another service's database.
2. No cross-database queries, no shared tables, no shared JPA entities.
3. Need another service's data? Call its API, or build **your own state from its events** (payment-service opens a ledger row from `LoanDisbursed`).
4. Each service owns its Flyway migrations, including `outbox_events` and `processed_events`.
5. Schema changes follow **expand → migrate → contract**, so the old and new version can run side by side during a rolling deploy.

Physical placement:

- Local: one PostgreSQL container hosting all databases (memory).
- AWS dev: one small RDS instance hosting all databases (cost), encrypted at rest, automated backups on.
- Production recommendation (documented, not built): separate instances (or Aurora clusters) for loan and payment to limit blast radius and scale separately.

## Consequences

Good: independent schema changes; clear owner for every column; least privilege at the database; no overpayment race; easy to split onto separate servers later — only connection strings change.

Bad / costs: one server is a shared failure point locally and in dev; no joins across contexts — reports need APIs or a reporting store; loan-service shows `remaining` with a small delay (eventual consistency); two users per service to configure.

## Alternatives considered

- **Shared database** — rejected (hidden coupling, risky migrations, one blast radius). See [trade-offs §6](../architecture/07-trade-offs.md#6-synchronous-distributed-transactions-vs-saga-and-eventual-consistency).
- **Ledger in loan-service** — payment-service would have to call loan-service synchronously and hold a lock across two services, or accept overpayments. Rejected.
- **Schema per service in one database** — workable, but separate databases make accidental cross-schema joins impossible.
- **A server per service everywhere** — best isolation, too costly for a portfolio environment.

## How we verify it

- Init script test: `loan_app` cannot connect to `customer_db`; `loan_app` cannot run `CREATE TABLE`.
- Concurrency test: two parallel repayments that together exceed `remaining` → exactly one succeeds.
- ArchUnit: no service references another service's packages.
