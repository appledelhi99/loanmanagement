# ADR-0014: Resilience with Resilience4j (via Spring Cloud CircuitBreaker) and Spring Framework retry

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F07 (executor owns provider retries), F17 (explicit gateway time limiters)

## Context

Slow or failing dependencies must not spread failure. Retries must not create retry storms or duplicate side effects.

## Decision

| Pattern | Tool | Where |
|---|---|---|
| **Timeouts** | HTTP client connect/read timeouts (`spring.http.serviceclient.<group>.*`); gateway HTTP client response timeout | Every remote call. Timeouts come from the HTTP client, not from a thread-hopping time limiter, for blocking clients |
| **Circuit breaker** | Spring Cloud CircuitBreaker + Resilience4j (`CircuitBreakerRestClientHttpServiceGroupConfigurer`, `CircuitBreakerFactory`, gateway `CircuitBreaker` filter) | customer calls, payment provider, gateway routes |
| **Bulkhead** | Resilience4j **semaphore** bulkhead through Spring Cloud CircuitBreaker's bulkhead support (the right type with virtual threads; a thread-pool bulkhead would add nothing) | customer calls (20), contact calls (10), provider calls (10) |
| **Retry** | Spring Framework 7 `@Retryable` (with `@EnableResilientMethods`) / `RetryTemplate`, exponential backoff + jitter, `maxRetries` 2 | Only idempotent GETs to customer-service. Provider retries are **not** in-call retries: the payment executor owns them with a budget (5 attempts / 15 min) |
| **Gateway time limiter** | Explicit per route, ≥ the route's response timeout | Default time-limiter behaviour is never relied on (Spring Cloud 2025.1.3 changed how the default `TimeLimiterConfig` is applied) |
| **Rate limit** | Gateway `RequestRateLimiter` (Redis) | Per user; stricter on payments |
| **Fallback** | Gateway fallback route → `503` Problem Details | No business fallbacks: KYC and money must fail closed; notifications fall back to Kafka retry → DLT |

Starting values (tuned with metrics in Phase 6):

| Instance | Window | Failure rate to open | Slow call | Open wait | Half-open calls |
|---|---|---|---|---|---|
| `customerService` | 20 calls | 50% | > 2 s | 10 s | 3 |
| `paymentProvider` | 20 calls | 50% | > 3 s | 30 s | 2 |

Order of decorators: **Retry (outer) → CircuitBreaker → Bulkhead → HTTP call (timeouts)**. `CallNotPermittedException` (open circuit) is **not** retried.

Configuration uses Spring Cloud CircuitBreaker's Resilience4j properties/customizers; exact keys are taken from the Spring Cloud CircuitBreaker 5.0.x docs when implemented (Phase 6).

## Consequences

Good: first-party retry (Spring Retry is no longer needed — Spring Kafka 4 also moved to Framework retry); one CB abstraction used by gateway and services; metrics for every breaker (`resilience4j_circuitbreaker_*`).

Bad / costs: two libraries cover resilience (Resilience4j for CB/bulkhead, Framework for retry). Accepted: Spring Cloud CircuitBreaker does not abstract retry, and Framework retry is the platform default now.

## Alternatives considered

- Resilience4j annotations everywhere (`@CircuitBreaker`, `@Retry`) — ties code to Resilience4j directly and needs its own Boot integration module.
- Retry inside the HTTP client — hides retries from metrics and makes them hard to bound.

## How we verify it

WireMock-based tests: timeout fires at the configured value; breaker opens after N failures and rejects fast; half-open recovers; bulkhead rejects call 21 when 20 are in flight; retry happens on GET but never on POST.
