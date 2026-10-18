# ADR-0004: Hexagonal architecture for core services, layered for supporting services

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16

## Context

Loan and payment hold the important rules (eligibility, state machine, money, idempotency). Customer and notification are mostly CRUD and message handling. One style everywhere would either under-protect the core or over-engineer the simple parts.

## Decision

- **loan-service, payment-service → hexagonal (ports and adapters).**
  - `domain` — plain Java: aggregates, value objects (`Money`, `LoanTerm`), policies, domain events, exceptions. No Spring, no JPA.
  - `application` — use cases (`port/in`), needed services (`port/out`), implementations with `@Transactional`.
  - `api` — inbound REST adapter (controllers, DTO records, HAL assemblers).
  - `infrastructure` — outbound adapters: JPA entities + repositories + mappers, Kafka, HTTP clients, config.
- **customer-service, notification-service → layered** (`web` → `service` → `persistence`). JPA entities stay inside `persistence`; the API returns DTO records only.
- In both styles: **DTOs at the API**, **explicit transactions in the service/use-case layer**, constructor injection, no field injection.

## Consequences

Good: domain rules are unit-testable without Spring (fast tests); infrastructure can change (e.g. OpenFeign ↔ HTTP Service Client) by swapping one adapter; reviewers see DDD used where it pays.

Bad / costs: mapping code between domain objects, JPA entities and DTOs in the core services; two styles to learn.

## Alternatives considered

- Hexagonal everywhere — extra mappers in CRUD services with no benefit.
- Layered everywhere — domain logic tends to leak into controllers and entities.
- JPA annotations on domain classes (pragmatic hexagonal) — fewer classes, but domain tests then need JPA rules in mind. Rejected for the core.

## How we verify it

ArchUnit rules per service:
- `..domain..` depends only on `java..` (and `..domain..`).
- `..api..` and `..infrastructure..` may depend on `..application..`; not the other way round.
- `@Entity` classes only in `..infrastructure.persistence..` (hexagonal) or `..persistence..` (layered).
- Controllers never return `@Entity` types.
