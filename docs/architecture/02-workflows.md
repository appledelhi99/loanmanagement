# 02 — Key Workflows and Failure Scenarios

Status: **Reviewed — final Phase 1 design** (revised after the [architecture review](08-architecture-review.md)). These diagrams are the target behaviour. Each phase's tests must prove them.

| # | Workflow | Required scenarios covered |
|---|---|---|
| W1 | Customer signs in and submits a loan application | 1, 2, 3, 4, 5 |
| W2 | Loan officer reviews and approves / rejects | 6 |
| W3 | Disbursement saga — happy path | 7, 8, 10 |
| W4 | Disbursement saga — failure and compensation | 7, 8, 9 |
| W5 | Repayment with an idempotency key and a locked ledger | 8 |
| W6 | Consumer failure → retries → dead-letter topic | 9, 10 |

Shared conventions in all diagrams:

- Every HTTP hop carries `Authorization: Bearer <JWT>` and W3C `traceparent`.
- Every Kafka record carries `eventType`, `eventVersion`, `traceparent` headers; the envelope carries `correlationId`.
- "TX" means one local database transaction. There is **no** distributed transaction anywhere.

---

## W1 — Sign in and submit a loan application

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant KC as Keycloak
    participant GW as api-gateway
    participant LS as loan-service
    participant CS as customer-service
    participant LDB as loan_db
    participant R as Outbox relay (in loan-service)
    participant K as Kafka loan-events
    participant NS as notification-service
    participant M as Mailpit

    C->>KC: Authorization Code + PKCE sign-in
    KC-->>C: access token (iss = public URL, aud = loan-platform-api, role CUSTOMER)
    C->>GW: POST /api/v1/loan-applications (Bearer JWT)
    GW->>GW: verify signature (keys from internal JWKS URL), iss, aud, exp
    GW->>GW: rate limit by token subject
    GW->>LS: forward request (same JWT, traceparent)
    LS->>LS: validate JWT again, require ROLE_CUSTOMER
    LS->>LS: Bean Validation on request DTO
    LS->>CS: GET /api/v1/customers/me (relayed user JWT)
    CS-->>LS: 200 customerId, kycStatus VERIFIED
    LS->>LS: domain rules R2 R3 R4 (limits, EMI vs income, open count)
    rect rgb(235, 245, 255)
    Note over LS,LDB: TX 1 (one local transaction)
    LS->>LDB: INSERT loan_applications (status SUBMITTED)
    LS->>LDB: INSERT outbox_events (LoanApplicationSubmitted v1, traceparent, correlationId)
    end
    LS-->>GW: 201 Created, Location, HAL body with links
    GW-->>C: 201 Created, X-Trace-Id
    loop every 500 ms
        R->>LDB: BEGIN, pg_try_advisory_xact_lock (only one relay runs)
        R->>LDB: SELECT up to 100 unpublished rows ORDER BY created_at
        R->>K: publish each (key loanId, acks all, idempotent producer)
        K-->>R: ack
        R->>LDB: set published_at, COMMIT (lock released)
    end
    K->>NS: LoanApplicationSubmitted (group notification-service)
    NS->>NS: route by eventType header to its function, dedupe by eventId
    NS->>CS: GET /customers/{id}/contact (client credentials token)
    CS-->>NS: email (masked in logs)
    NS->>M: SMTP "We received your application"
```

**What can go wrong**

| Step | Failure | Behaviour |
|---|---|---|
| 4 | Token expired / wrong issuer / wrong audience | Gateway returns `401` Problem Details. Request never reaches services |
| 5 | Too many requests | Gateway returns `429` with `X-RateLimit-*` headers |
| 9–10 | customer-service slow or down | 2 s timeout, 2 bounded retries, then circuit opens → `503 CUSTOMER_SERVICE_UNAVAILABLE`. Nothing is saved |
| 10 | KYC not verified | `422 KYC_NOT_VERIFIED`. Nothing is saved |
| 11 | Business rule fails | `422` with rule code, e.g. `EMI_EXCEEDS_INCOME_LIMIT` |
| 12–13 | DB error | Whole TX rolls back. No event exists. Client gets `500` with `traceId` |
| 18–19 | Kafka down | Relay stops the batch at the first failure (keeps order) and retries later. `lmp_outbox_lag_seconds` grows → alert. Application already saved; event goes out later |
| 21–25 | Notification fails | See W6. The loan flow is **not** blocked by email problems |

---

## W2 — Officer reviews and decides

```mermaid
sequenceDiagram
    autonumber
    actor O as Loan Officer
    participant GW as api-gateway
    participant LS as loan-service
    participant CS as customer-service
    participant LDB as loan_db

    O->>GW: GET /api/v1/loan-applications?status=SUBMITTED&page=0&size=20
    GW->>GW: path rule requires ROLE_LOAN_OFFICER or ROLE_ADMIN
    GW->>LS: forward
    LS-->>O: 200 PagedModel (HAL) with links per item
    O->>GW: POST /api/v1/loan-applications/{id}/review
    GW->>LS: forward
    LS->>LDB: TX update status UNDER_REVIEW, reviewer = officer sub
    LS-->>O: 200 HAL with approve and reject links
    O->>GW: POST /api/v1/loan-applications/{id}/approve
    GW->>LS: forward
    LS->>LS: @PreAuthorize ROLE_LOAN_OFFICER, rule R6 approver is not applicant
    LS->>CS: GET /api/v1/customers/{customerId} (relayed officer JWT)
    CS-->>LS: kycStatus VERIFIED (rule R1b)
    rect rgb(235, 245, 255)
    Note over LS,LDB: TX (optimistic lock on version)
    LS->>LDB: UPDATE status APPROVED, insert loan_decisions row, version+1
    LS->>LDB: INSERT outbox LoanApproved v1
    end
    LS-->>O: 200 HAL (no approve or reject links any more)
    Note over LS: relay publishes LoanApproved (key loanId). Audit logger records the decision.
```

| Failure | Behaviour |
|---|---|
| Two officers approve at the same time | One wins. The other gets `409 CONCURRENT_MODIFICATION` (JPA `@Version`) |
| Approve from wrong state (e.g. `SUBMITTED`) | `409 INVALID_STATE_TRANSITION` |
| KYC revoked since submission | `422 KYC_NOT_VERIFIED`; status stays `UNDER_REVIEW` |
| customer-service down during approve | `503 CUSTOMER_SERVICE_UNAVAILABLE`; no decision is stored (fail closed) |
| CUSTOMER token calls approve | Gateway `403`. If the gateway is bypassed, the service also returns `403` |
| Reject without reason | `400` validation error with field details |

---

## W3 — Disbursement saga (happy path)

Choreography saga. loan-service owns the loan status. payment-service owns the money movement. The Kafka listener never calls the provider; the **payment executor** does.

```mermaid
sequenceDiagram
    autonumber
    participant K1 as Kafka loan-events
    participant PL as payment-service listener
    participant PDB as payment_db
    participant EX as Payment executor (scheduled)
    participant SIM as Simulated provider
    participant K2 as Kafka payment-events
    participant LS as loan-service
    participant NS as notification-service

    K1->>PL: LoanApproved v1 (eventId E1, loanId L1, amount, payoutRef)
    rect rgb(235, 245, 255)
    Note over PL,PDB: TX A
    PL->>PDB: INSERT processed_events (E1, payment-service)
    PL->>PDB: INSERT payments (L1, DISBURSEMENT, PENDING, nextAttemptAt now) unique on loan_id and type
    end
    Note over PL: offset committed after TX A. The listener is done.
    EX->>PDB: SELECT PENDING due now FOR UPDATE SKIP LOCKED, mark IN_FLIGHT
    EX->>SIM: transfer(payoutRef, amount, reference = paymentId)
    Note right of SIM: circuit breaker, 3 s timeout, bulkhead 10
    SIM-->>EX: SUCCESS (providerTxnId)
    rect rgb(235, 245, 255)
    Note over EX,PDB: TX B
    EX->>PDB: UPDATE payments COMPLETED
    EX->>PDB: INSERT outbox DisbursementCompleted v1
    end
    Note over EX,K2: relay publishes
    K2->>LS: DisbursementCompleted v1
    LS->>LS: TX C dedupe, status DISBURSED, outbox LoanDisbursed v1 (totalPayable)
    Note over LS,PDB: payment-service consumes LoanDisbursed and opens the ledger row in loan_accounts
    K2->>NS: DisbursementCompleted
    NS->>NS: email "Your loan was paid out"
```

Why the provider call sits **between** two transactions and outside the listener: we never hold a DB transaction or a Kafka partition while waiting on a remote system. If the process crashes after TX A, the executor picks the payment up; if it crashes mid-call, `IN_FLIGHT` rows older than the call timeout return to `PENDING` and the executor first asks the provider for the status of our reference.

---

## W4 — Disbursement saga (failure and compensation)

```mermaid
sequenceDiagram
    autonumber
    participant PDB as payment_db
    participant EX as Payment executor
    participant SIM as Simulated provider
    participant K2 as Kafka payment-events
    participant LS as loan-service
    participant NS as notification-service

    EX->>PDB: claim PENDING payment for loan L2 (SKIP LOCKED)
    EX->>SIM: transfer(...)
    SIM--xEX: timeout (technical failure)
    EX->>PDB: attempts = 1, nextAttemptAt = now + backoff, back to PENDING
    loop until COMPLETED, FAILED or budget used (max 5 attempts / 15 min)
        EX->>SIM: getStatus(reference), then transfer only if not found
    end
    alt provider declines (business failure) or budget exhausted
        EX->>PDB: TX update FAILED, insert outbox DisbursementFailed v1 (reason)
        K2->>LS: DisbursementFailed
        LS->>LS: TX compensation, status DISBURSEMENT_FAILED, outbox LoanDisbursementFailed
        K2->>NS: DisbursementFailed
        NS->>NS: email customer, email officer group
    else provider recovers
        EX->>PDB: TX update COMPLETED, outbox DisbursementCompleted
    end
```

- The provider is called with our `paymentId` as its idempotency reference. Asking again never pays twice.
- An open circuit is a **technical** failure: the payment stays `PENDING`; the executor tries again after the back-off.
- Demo triggers in the simulator (configurable): payout reference starting `SIM-DECLINE` → business decline; `SIM-SLOW` → latency above the timeout; random failure rate (default 0).
- **Compensation** here is a semantic undo: the approval cannot complete, so the loan moves to `DISBURSEMENT_FAILED` and people are told. No money moved, so nothing needs reversing.

---

## W5 — Repayment with an idempotency key and a locked ledger

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant GW as api-gateway
    participant PS as payment-service
    participant PDB as payment_db
    participant SIM as Simulated provider
    participant K as Kafka payment-events
    participant LS as loan-service

    C->>GW: POST /api/v1/payments/repayments, Idempotency-Key K1, body loanId L1 amount 5000
    GW->>PS: forward (rate limit 5 req/s per user on this route)
    rect rgb(235, 245, 255)
    Note over PS,PDB: TX 1
    PS->>PDB: INSERT idempotency_keys (caller, K1, requestHash, IN_PROGRESS) - PK blocks races
    PS->>PDB: SELECT loan_accounts L1 FOR UPDATE (owner = caller, status OPEN)
    PS->>PDB: check 5000 <= totalPayable - repaid - reserved, then reserved += 5000
    PS->>PDB: INSERT payments (REPAYMENT, PENDING)
    end
    PS->>SIM: charge(amount, reference = paymentId), 3 s timeout
    SIM-->>PS: SUCCESS
    rect rgb(235, 245, 255)
    Note over PS,PDB: TX 2
    PS->>PDB: payment COMPLETED, reserved -= 5000, repaid += 5000
    PS->>PDB: store response in idempotency_keys (COMPLETED), outbox RepaymentReceived (amount, remaining)
    end
    PS-->>C: 201 Created (payment record)
    Note over C,PS: Network drops, client retries the same call
    C->>GW: POST same body, Idempotency-Key K1
    PS->>PDB: find (caller, K1), same requestHash, COMPLETED
    PS-->>C: 201 same body, header Idempotent-Replayed true (no second charge)
    C->>GW: POST different amount, Idempotency-Key K1
    PS-->>C: 422 IDEMPOTENCY_KEY_REUSED
    K->>LS: RepaymentReceived (remaining)
    LS->>LS: TX store remaining for display, if remaining = 0 then CLOSED and outbox LoanClosed
```

| Case | Response |
|---|---|
| Missing `Idempotency-Key` | `400 IDEMPOTENCY_KEY_REQUIRED` |
| Ledger row not open yet (loan not disbursed) | `409 LOAN_NOT_OPEN_FOR_REPAYMENT` |
| Amount above remaining (minus reserved) | `422 AMOUNT_EXCEEDS_REMAINING` |
| Provider slow / circuit open | `202 Accepted`, payment `PENDING`; executor finishes; status via `GET /api/v1/payments/{id}` |
| Provider declines | `422 PAYMENT_DECLINED`; reserved amount released; payment `FAILED` |
| Same key while first call still running | `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` |
| Same key, same request, finished | Stored response replayed, `Idempotent-Replayed: true` |
| Same key, different request | `422 IDEMPOTENCY_KEY_REUSED` |
| Key older than 24 h | Purged by a scheduled job. A new call is treated as new |

`requestHash` is SHA-256 of the canonical fields `loanId|amount|currency`, not of the raw JSON. Keys live in **PostgreSQL**, not in the cache, because they must survive restarts and be written in the same transaction as the payment.

---

## W6 — Consumer failure, retries and dead-letter topic

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka loan-events
    participant NS as notification-service
    participant CS as customer-service
    participant DLT as loan-events.notification-service.dlt
    participant P as Prometheus / Grafana

    K->>NS: LoanApproved (attempt 1)
    NS->>CS: GET contact
    CS--xNS: 503
    NS->>NS: attempt 2 after 1 s, attempt 3 after 2 s (blocking, keeps order)
    CS--xNS: 503
    NS->>DLT: publish original record + headers (exception, original topic, partition, offset)
    NS->>NS: commit offset, continue with next record
    P->>P: lmp_dlt_records_total increases, alert "DLT not empty"
    Note over DLT: Operator fixes the cause, then runs the replay command (Phase 5). Consumers are idempotent, so replay is safe.
```

Rules:

- **Non-retryable errors** (bad JSON, unknown `eventVersion`, validation failure) go to the DLT at once.
- **Retryable errors** (timeouts, `5xx`, DB connection errors) get 3 attempts in total.
- Retries are **blocking** so per-loan ordering is kept ([ADR-0010](../adr/0010-spring-kafka-for-core-services-spring-cloud-stream-for-notifications.md)).
- Email sending is a side effect outside the DB transaction. A crash between "sent" and "commit" can send one duplicate email; the notification row (`PENDING → SENT`) and the `eventId` check keep that window small. Accepted and documented.

---

## Tracing across the whole workflow

```mermaid
flowchart LR
    a["Client request<br/>trace created at gateway<br/>X-Trace-Id returned"] --> b["loan-service span<br/>HTTP server"]
    b --> c["customer-service span<br/>HTTP client → server"]
    b --> d["outbox row stores<br/>traceparent + correlationId"]
    d --> e["relay restores context<br/>Kafka producer span"]
    e --> f["payment-service<br/>Kafka consumer span"]
    f --> x["payment executor span<br/>(linked by correlationId)"]
    x --> g["payment-events producer span"]
    g --> h["loan-service consumer span"]
    e --> i["notification-service<br/>consumer span"]
```

One trace in Jaeger shows gateway → loan-service → customer-service → (outbox) → payment-service → loan-service → notification-service. The executor runs on its own schedule, so it starts a new span **linked** to the stored trace context; `correlationId` ties all logs together even when a trace was not sampled.

---

## Failure scenario catalogue

| # | Scenario | Detected by | System behaviour | Signal |
|---|---|---|---|---|
| F1 | customer-service down during submit or approve | timeout / circuit breaker | Fail fast `503`, nothing saved | `resilience4j_circuitbreaker_state`, error rate |
| F2 | Kafka down | producer errors in relay | Events wait in outbox, delivered later in order | `lmp_outbox_lag_seconds`, `lmp_outbox_pending` |
| F3 | Duplicate `LoanApproved` delivery | unique `processed_events` key + unique disbursement per loan | Second copy ignored | `lmp_duplicate_events_total` |
| F4 | Provider timeout | HTTP timeout / circuit breaker | Payment stays `PENDING`, executor retries within budget | `lmp_payments_total{outcome="pending"}` |
| F5 | Provider declines | provider response | Saga compensation → `DISBURSEMENT_FAILED` | `lmp_payments_total{outcome="failed"}` |
| F6 | Poison message | deserialization / validation | Straight to DLT | `lmp_dlt_records_total` + alert |
| F7 | Two officers decide at once | optimistic lock | One `409` | `lmp_concurrent_modification_total` |
| F8 | Client retries repayment | `Idempotency-Key` | Same response, no double charge | `lmp_idempotent_replays_total` |
| F9 | Two parallel repayments exceed remaining | ledger row lock + reserved amount | Second gets `422 AMOUNT_EXCEEDS_REMAINING` | `lmp_repayment_rejected_total` |
| F10 | Request flood | gateway rate limiter | `429` | gateway request metrics, count of `429` |
| F11 | Pod crash mid-payment | `IN_FLIGHT` older than timeout | Back to `PENDING`, status check first, then retry | executor metrics |
| F12 | Keycloak down | JWKS cached by resource servers | Existing tokens keep working until expiry. New sign-ins fail | Keycloak health, `401` rate |
| F13 | Config server down at startup | fail-fast config import | Service exits; Compose/Kubernetes restarts it until config is available | restart count, readiness |
