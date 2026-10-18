# ADR-0020: Testing strategy and contract testing

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F03 (stubs classpath mode), F04 (golden-example event contracts), F06 (Boot 4 test APIs), F24 (actuator, migration and load tests)

## Context

The portfolio must *prove* behaviour: business rules, security, resilience, messaging, contracts, and one full workflow. Tests must assert real outcomes, not just that the context starts.

## Decision

| Level | Tools | Naming / plugin | Examples |
|---|---|---|---|
| Unit (domain) | JUnit Jupiter, AssertJ | `*Test`, Surefire | EMI calculation, state machine, eligibility policy, idempotency hash |
| Unit (application) | + Mockito | `*Test` | Use case writes outbox event; rejects approve by applicant |
| Web slice | `@WebMvcTest`, `MockMvcTester`, `@MockitoBean`, Spring Security Test `jwt()` (Boot 4 test starters: `spring-boot-starter-webmvc-test`, `-security-test`) | `*Test` | Validation errors → Problem Details; role matrix allowed/denied; HAL links per status/role; actuator **not** reachable on the API port |
| Persistence | `@DataJpaTest` + Testcontainers PostgreSQL | `*IT`, Failsafe | Flyway migrations apply on an empty schema and on the previous release's schema; `<svc>_app` cannot run DDL; optimistic lock; unique keys; ledger row lock |
| Integration | `@SpringBootTest` + Testcontainers (PostgreSQL, Kafka, Redis) | `*IT` | Outbox → Kafka; consumer idempotency; DLT; cache evict; transaction rollback leaves no event |
| Resilience | WireMock (Spring Cloud Contract WireMock) | `*IT` | Timeout, circuit open/half-open, bulkhead full, retry only on GET |
| Security integration | testcontainers-keycloak + real realm | `*IT` | Real token → API; wrong audience → 401; service token scope |
| Contract — HTTP | Spring Cloud Contract: customer-service = producer (generated tests + attached `stubs` jar); loan- and notification-service = consumers (Stub Runner, `stubsMode = CLASSPATH`, test dependency on the `stubs` classifier) | generated | `GET /customers/me`, `GET /customers/{id}`, `GET /customers/{id}/contact` |
| Contract — events | **Golden examples** in `contracts/event-contracts`: producer test serializes a fixed event and compares it with the example (JSONAssert); consumer tests parse every example they subscribe to. (Spring Cloud Contract documents no Kafka integration in 4.2 or 5.0 — Camel, Spring Integration, Stream, JMS only — so Kafka messaging contracts are an optional stretch) | `*Test` | every event type and version |
| Architecture | ArchUnit | `*Test` | Layer rules, no entities in API, no cross-service packages |
| End-to-end | `tests/e2e-tests` against the Compose stack: Keycloak tokens, `RestTestClient`/`RestClient`, Awaitility, Mailpit API | `*E2E`, profile `e2e` | Submit → approve → `DISBURSED` → email seen; decline path → `DISBURSEMENT_FAILED` |

Rules:

- Inject `java.time.Clock` everywhere time matters — no `Instant.now()` in domain code.
- Test data builders, not shared fixtures.
- No `Thread.sleep`; use Awaitility with a timeout.
- Coverage gates (JaCoCo, line): `domain` packages ≥ 90%; loan- and payment-service ≥ 80% overall; others ≥ 70%. Coverage supports, but does not replace, meaningful assertions.
- Containers are reused within a test JVM (singleton pattern) to keep the suite fast.
- Optional: Gatling smoke simulation (`tests/load-tests`) showing `429` from the rate limiter and fast-fail from bulkheads under load.

## Consequences

Good: each requirement has a test that fails when it breaks; contracts stop API drift between services.

Bad / costs: integration tests need Docker (not available in Claude's cloud workspace — they run on GitHub Actions or your Mac); the suite is slower than unit-only.

## Alternatives considered

- H2 instead of PostgreSQL — different SQL (no `SKIP LOCKED`, JSONB). Rejected.
- Embedded Kafka — fine for some tests, but Testcontainers matches the real broker better.
- Pact instead of Spring Cloud Contract — strong tool; you asked for Spring Cloud Contract, and it fits a Spring-only stack.

## How we verify it

Phase 8 publishes the test report: counts per level, coverage per module, and the list of requirements → tests.
