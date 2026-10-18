# ADR-0010: Spring for Apache Kafka for core services, Spring Cloud Stream + Function for notifications

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review: kept as is (two APIs with a clear boundary); payment-service listener now only records work (see ADR-0012)

## Context

You listed both Spring Cloud Stream and Spring for Apache Kafka, and Spring Cloud Function "where meaningful". Using two Kafka APIs needs a clear reason.

## Decision

| Service | API | Why |
|---|---|---|
| loan-service, payment-service | **Spring for Apache Kafka 4.1** (`KafkaTemplate`, `@KafkaListener`, `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, `ExponentialBackOff`) | Money and state. We need exact control: commit offsets only after the DB transaction, classify retryable vs non-retryable errors, custom DLT naming, outbox relay with `acks=all` and an idempotent producer |
| notification-service | **Spring Cloud Stream 5.0 (Kafka binder)** + **Spring Cloud Function** | Pure consumer. Retry and DLQ are binding properties (`max-attempts`, `enable-dlq`, DLQ name). Handlers are plain `Consumer<Message<String>>` beans. A **routing function** sends each record to the handler for its `eventType` header, so one binding per topic serves many event types |

Common rules for both:

- **Blocking retries** (3 attempts in total: retry after 1 s, then after 2 s), then DLT. Non-blocking retry topics (`@RetryableTopic`) are **not** used for these topics because they break per-loan ordering.
- Non-retryable exceptions (deserialization, validation, unknown version) → DLT immediately.
- Consumer groups: `loan-service`, `payment-service`, `notification-service`.
- Observation enabled on templates and listeners so traces cross Kafka.

## Consequences

Good: each API is used where it fits; reviewers see both; notification handlers are tiny and unit-testable as functions.

Bad / costs: two Kafka programming models in one repo; Stream's binder hides some Kafka details (accepted for a consumer with no money logic). Spring Cloud Stream routing behaviour must be verified in Phase 5 (risk R7 in the plan).

## Alternatives considered

- Spring Kafka everywhere — perfectly valid; less code variety.
- Spring Cloud Stream everywhere — the outbox relay and saga error handling would fight the binder abstraction.
- Kafka Streams — no stream processing need.

## How we verify it

- Testcontainers Kafka tests: retry count, DLT headers, order kept per key, duplicate delivery ignored.
- Notification routing test: each `eventType` reaches the right function; unknown type → DLT.
