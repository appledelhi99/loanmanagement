# ADR-0015: Caching and rate-limit storage with Redis

- Status: Accepted (2026-10-18)
- Date: 2026-10-18
- History: proposed as Valkey (Redis-compatible) in Phase 1; the owner chose **Redis** on 2026-10-18. Review change F09 (no PII in the cache) still applies.

## Context

Some reads are frequent and change rarely, and the gateway rate limiter needs fast shared counters. The owner chose Redis, the store the book uses and the one most interviewers know.

Facts checked on 2026-10-18:

- Amazon ElastiCache supports **Redis OSS up to 7.1** (serverless and node-based) and Valkey up to 8.0.
- Redis 8 changed its licence model; ElastiCache does not offer Redis 8. Local and AWS versions must therefore stay on the Redis 7.x feature set.

## Decision

- **Local:** Redis from the official image on the 7.x line that matches ElastiCache 7.1 behaviour (exact tag pinned in Phase 3, when Redis first enters Compose).
- **AWS:** **ElastiCache for Redis OSS 7.1**, single small node, encryption in transit and at rest.
- Code uses **Spring Data Redis (Lettuce)** and the **Spring Cache** abstraction only — no engine-specific commands.

| Data | Service | Pattern | TTL | Invalidation |
|---|---|---|---|---|
| Loan product catalogue (+ EMI quote inputs) | loan-service | cache-aside (`@Cacheable`) | 1 h | `@CacheEvict` when an ADMIN changes a product; key prefix includes the app version |
| Rate-limit buckets | api-gateway | Spring Cloud Gateway Redis rate limiter | short | automatic |

- **Never cached:** customer profiles and contact details (PII), KYC status, idempotency keys, ledger values.
- Keys prefixed `lmp:<service>:<cache>:v1:`; values JSON (Jackson 3).
- Cache failures are logged and counted, then the database is used (`CacheErrorHandler`), so Redis is never a hard dependency for reads. If Redis is down, the gateway's Redis rate limiter lets requests through (its built-in fail-open behaviour) and logs the error; an alert fires. Services keep their own bulkheads.

## Consequences

Good: the familiar, widely asked-about store; same API locally and in AWS; lower latency and DB load on the hottest read; no PII outside `customer_db`.

Bad / costs: ElastiCache for Redis OSS costs more per node-hour than ElastiCache for Valkey (accepted for a short-lived demo environment); we must stay on Redis 7.x features to match ElastiCache; stale product data up to TTL if an eviction is missed.

## Alternatives considered

- Valkey — Redis-compatible and cheaper on ElastiCache; was the Phase 1 proposal; not chosen by the owner. Switching later changes only image names and the ElastiCache engine.
- Redis 8 locally — newer features we would not have in ElastiCache; rejected for parity.
- Caffeine (in-process) — no network hop, but each replica has its own copy. Could be added as L1 later.
- Caching customer data — removed in review (low gain, PII spread).

## How we verify it

- Testcontainers (Redis image via `GenericContainer` + `@ServiceConnection(name = "redis")`): second product read hits the cache; product change evicts.
- Cache down → product reads still succeed from the database (test stops the container).
- Gateway test: `429` after the burst with Redis up; requests pass (and the error is logged) with Redis down.
