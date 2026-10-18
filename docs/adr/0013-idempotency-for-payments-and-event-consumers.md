# ADR-0013: Idempotency for payments and event consumers

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F08 (ledger lock), F28 (canonical request hash), F29 (response codes)

## Context

Networks fail after the server did the work. Clients retry. Kafka redelivers. Two repayments can arrive at the same time. Without protection we could charge twice, disburse twice, or let a customer overpay.

## Decision

### 1. REST: `Idempotency-Key` on `POST /api/v1/payments/repayments`

```sql
CREATE TABLE idempotency_keys (
    owner_subject    VARCHAR(100) NOT NULL,      -- JWT sub; keys are scoped per caller
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,      -- SHA-256 of loanId|amount|currency
    status           VARCHAR(20)  NOT NULL,      -- IN_PROGRESS | COMPLETED
    response_status  INT,
    response_body    JSONB,
    payment_id       UUID,
    created_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (owner_subject, idempotency_key)
);
```

The hash covers the **canonical request fields**, not raw JSON (key order and spaces would change a raw hash).

| Situation | Result |
|---|---|
| Header missing | `400 IDEMPOTENCY_KEY_REQUIRED` |
| New key | Insert `IN_PROGRESS` (primary key blocks races), process, store response as `COMPLETED` |
| Same key, same hash, `COMPLETED` | Stored response + `Idempotent-Replayed: true` |
| Same key, same hash, `IN_PROGRESS` | `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` |
| Same key, different hash | `422 IDEMPOTENCY_KEY_REUSED` |
| Older than 24 h | Purged by a scheduled job |

### 2. Repayment ledger lock (no overpayment)

In the same TX as the key insert: `SELECT … FROM loan_accounts WHERE loan_id = ? FOR UPDATE`, check `amount ≤ total_payable − repaid − reserved`, then `reserved += amount` and insert the `PENDING` payment. After the provider answers: completed → `repaid += amount`; declined → release the reservation.

| Outcome | Response |
|---|---|
| Provider success | `201 Created` |
| Provider slow / circuit open | `202 Accepted` (`PENDING`, finished by the executor) |
| Provider decline | `422 PAYMENT_DECLINED` |
| Amount too high | `422 AMOUNT_EXCEEDS_REMAINING` |
| Ledger not open (not disbursed yet) | `409 LOAN_NOT_OPEN_FOR_REPAYMENT` |

### 3. Provider calls

Each provider call uses our `paymentId` as the provider's idempotency reference. The simulator stores references and returns the original result for repeats; the executor asks for status before retrying.

### 4. Event consumers

```sql
CREATE TABLE processed_events (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
```

Insert into `processed_events` **in the same transaction** as the business change. A duplicate key means "already done" → skip and acknowledge. Natural keys add a second guard (one disbursement per `loan_id`).

## Consequences

Good: safe retries end to end; no overpayment, even with parallel requests; clear client contract; works with at-least-once delivery.

Bad / costs: extra tables and writes; a row lock serialises repayments for the **same** loan (fine — they are rare); key purge job.

## Alternatives considered

- Keys in Redis with TTL — not transactional with the payment; lost on cache failure.
- Exactly-once Kafka — does not cover the database.
- Natural keys only — not enough for repayments, where two equal payments can be legitimate.
- Optimistic locking on the ledger — would need client retries on conflict; the pessimistic row lock is simpler for money.

## How we verify it

- 10 parallel requests with the same key → exactly one payment row.
- Two parallel requests with different keys that together exceed `remaining` → exactly one succeeds.
- Replay, conflict, reuse, decline and slow-provider cases each have a test.
- Same event delivered twice → one business effect; `lmp_duplicate_events_total` = 1.
