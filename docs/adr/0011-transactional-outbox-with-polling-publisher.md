# ADR-0011: Transactional outbox with a polling publisher

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F05 (migrations owned by each service), F11 (transaction-scoped advisory lock, no `SKIP LOCKED` in the relay)

## Context

"Save to DB, then send to Kafka" can fail between the two steps: the DB says *approved* but no event is sent (or the reverse). Kafka cannot join a PostgreSQL transaction.

## Decision

Implement the **transactional outbox**. Code lives in `libs/platform-outbox` (used by loan-service and payment-service). Each service ships **its own** Flyway migration for the table.

**Write side** — in the same `@Transactional` use case that changes the aggregate:

```sql
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    event_version  INT          NOT NULL,
    topic          VARCHAR(128) NOT NULL,
    payload        JSONB        NOT NULL,
    headers        JSONB        NOT NULL,   -- traceparent, correlationId
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000)
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
```

**Relay (polling publisher), every 500 ms:**

1. Begin a transaction and call `pg_try_advisory_xact_lock(<service lock id>)`. If it returns `false`, another replica is relaying — skip this round. The lock is **transaction-scoped**, so it is released at commit, rollback or connection loss (safe with a connection pool, unlike a session lock).
2. Read up to 100 unpublished rows `ORDER BY created_at`. No `SKIP LOCKED` is needed because only one relay runs.
3. Publish each with the stored key, headers and **restored trace context**; wait for the ack (`acks=all`, `enable.idempotence=true`, bounded `delivery.timeout.ms`).
4. Set `published_at`. On the first failure: `attempts + 1`, `last_error`, **stop the batch** (keeps order), commit, back off.
5. A daily job deletes published rows older than 7 days.

This relay is the **one place** where we talk to a remote system inside a DB transaction. It is allowed because the batch is small, the producer has strict timeouts, and only one relay runs.

**Metrics:** `lmp_outbox_pending`, `lmp_outbox_lag_seconds` (age of the oldest unpublished row), `lmp_outbox_publish_failures_total`.

## Consequences

Good: no lost events and no ghost events; publish order = commit order; simple to run (no Kafka Connect); traces continue through the outbox.

Bad / costs: **at-least-once** — a crash after the Kafka ack but before commit re-sends the event, so consumers must be idempotent ([ADR-0013](0013-idempotency-for-payments-and-event-consumers.md)); polling adds up to ~0.5 s latency; a single active relay limits throughput (fine here; partition by aggregate hash if needed).

## Alternatives considered

- **Debezium CDC** — lower latency, no polling; needs Kafka Connect and replication slots.
- **Kafka transactions** — atomic only across Kafka, not the DB.
- **Publish after commit (`@TransactionalEventListener(AFTER_COMMIT)`)** — loses events if the process dies right after commit.
- **Spring Modulith event externalization** — similar idea with less code, but no strict per-aggregate ordering guarantee; we build the outbox ourselves because ordering matters and explaining it is part of the portfolio.
- **Session-level advisory lock / `SKIP LOCKED` workers** — removed in review (pool-unsafe / breaks ordering).

## How we verify it

- Integration test: business change + outbox row in one TX; forced rollback → neither exists.
- Kafka stopped → rows stay pending, lag metric grows → Kafka started → all rows published in order.
- Two relay instances → only one publishes per round (advisory lock).
- Trace test: consumer span has the producer request's trace ID.
- Schema test helper in the lib: the service's migrated table matches what the code expects.
