# ADR-0009: Kafka event model — topics, envelope and versioning

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F04 (golden examples in `contracts/event-contracts`), F16 (`correlationId`)

## Context

Events drive the saga and notifications. We need ordering per loan, safe schema change, traceability, and no PII leaks.

## Decision

**Topics (one per aggregate, created by script — auto-create off):**

| Topic | Partitions (local / AWS) | Replication (local / AWS) | Retention | Key |
|---|---|---|---|---|
| `loan-events` | 3 / 6 | 1 / 2 | 7 days | `loanId` |
| `payment-events` | 3 / 6 | 1 / 2 | 7 days | `loanId` |
| `<topic>.<consumer-group>.dlt` | 1 / 2 | 1 / 2 | 14 days | original key |

(Replication 2 in AWS matches a 2-broker MSK dev cluster; production would use 3 brokers and replication 3 with `min.insync.replicas=2`.)

**Envelope (JSON, UTF-8):**

```json
{
  "eventId": "8a6f0b8e-1f2c-4d0b-9c39-1d3f3c8b6a55",
  "eventType": "LoanApproved",
  "eventVersion": 1,
  "occurredAt": "2026-10-16T09:30:12.345Z",
  "aggregateType": "LoanApplication",
  "aggregateId": "5b8c…",
  "producer": "loan-service",
  "correlationId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "payload": {
    "loanId": "5b8c…",
    "customerId": "c19e…",
    "principal": { "amount": "250000.00", "currency": "INR" },
    "tenureMonths": 24,
    "payoutReference": "SIM-ACC-0001",
    "approvedBy": "officer-sub-…"
  }
}
```

`correlationId` is the trace ID of the request that started the flow. It survives sampling, DLT parking and replay.

**Event types (v1):** `loan-events`: `LoanApplicationSubmitted`, `LoanApplicationWithdrawn`, `LoanApproved`, `LoanRejected`, `LoanDisbursed` (carries `totalPayable`), `LoanDisbursementFailed`, `LoanClosed`. `payment-events`: `DisbursementCompleted`, `DisbursementFailed`, `RepaymentReceived` (carries `amount`, `remaining`).

**Record headers:** `eventType`, `eventVersion`, `contentType=application/json`, `traceparent` (+ `tracestate` if present). **No** `__TypeId__` class headers — consumers must not depend on producer class names.

**Serialization:** producers and consumers use `String` (de)serializers and map JSON with Jackson 3 `JsonMapper` in their own adapter. This keeps each service in charge of its own types.

**Versioning rules:**

1. Adding an optional field → same `eventVersion`. Consumers ignore unknown fields (tolerant reader).
2. Removing/renaming a field or changing meaning → new `eventVersion`. Producer publishes both versions until all consumers move, then stops the old one.
3. Consumers reject unknown **major** versions to the DLT (with a clear error) instead of guessing.
4. Each event type and version has a **golden JSON example** in `contracts/event-contracts` (`events/<topic>/<EventType>.v<N>.json`). The producer's test serializes a fixed event and compares it with the example (JSONAssert); every consumer's test parses every example it subscribes to. A change that breaks a consumer fails the build.

**PII:** events carry IDs and amounts only. No names, emails, phone numbers.

## Consequences

Good: per-loan ordering across all event types; independent consumer types; explicit, testable versioning; traces flow through Kafka.

Bad / costs: no Schema Registry, so compatibility is enforced by contract tests, not by the broker; JSON is larger than Avro/Protobuf.

## Alternatives considered

- Topic per event type — easier ACLs, but loses ordering between `LoanApproved` and `LoanDisbursed` of the same loan.
- Avro/Protobuf + Schema Registry — stronger guarantees; extra infra. Documented as the next step.
- CloudEvents format — close to our envelope; can be adopted by renaming fields if needed.

## How we verify it

- Golden-example tests per event type and version (producer and consumers).
- Integration test: two events for the same `loanId` are consumed in publish order.
- DLT test: unknown `eventVersion` goes straight to DLT with error headers.
