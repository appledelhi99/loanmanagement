# ADR-0005: Spring HTTP Service Clients instead of OpenFeign

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F10 (approval call with officer token), F12 (base URL per profile)

## Context

Two services make synchronous calls to customer-service:

- loan-service → `GET /api/v1/customers/me` with the **customer's token** (token relay, at submission).
- loan-service → `GET /api/v1/customers/{id}` with the **officer's token** (token relay, at approval — KYC re-check).
- notification-service → `GET /api/v1/customers/{id}/contact` with a **service token** (client credentials).

The book uses Feign. Today the Spring Cloud OpenFeign reference says: *"we're now treating the Spring Cloud OpenFeign project as feature-complete. We are only going to be adding bugfixes … We suggest migrating over to Spring HTTP Service Clients instead."*

Spring Framework 7 / Boot 4 / Spring Cloud 2025.1 now provide, first-party:

- `@HttpExchange` interfaces registered with `@ImportHttpServices(group = …)`, configured under `spring.http.serviceclient.<group>.*` (base URL, connect and read timeouts).
- Load balancing: `LoadBalancerRestClientHttpServiceGroupConfigurer` — a group whose `base-url` uses the `lb://` scheme is load-balanced.
- Circuit breaking: `CircuitBreakerRestClientHttpServiceGroupConfigurer` and `@HttpServiceFallback` for fallbacks.
- OAuth 2.0: Spring Security 7 detects `@ClientRegistrationId` on `@HttpExchange` methods.

## Decision

Use **Spring HTTP Service Clients** for all service-to-service REST calls.

```java
@HttpExchange("/api/v1/customers")
public interface CustomerContactClient {

    @GetExchange("/{customerId}/contact")
    @ClientRegistrationId("notification-service")
    CustomerContactResponse getContact(@PathVariable UUID customerId);
}
```

```yaml
spring:
  http:
    serviceclient:
      customer:
        base-url: ${LMP_CUSTOMER_BASE_URL}   # lb://customer-service (Compose) | http://customer-service:8081 (Kubernetes)
        connect-timeout: 1s
        read-timeout: 2s
```

Each client sits behind an application **port** (`CustomerDirectory`, `CustomerContactLookup`). Only the adapter knows which HTTP technology is used.

(Exact annotation/property names are re-checked against the Boot 4.1 and Spring Cloud 5.0 docs when the code is written in Phase 4.)

## Consequences

Good: first-party, actively developed; no extra Spring Cloud module; same `RestClient` stack as the rest of the app (observability, SSL bundles, SSRF `InetAddressFilter` in Boot 4.1); tells reviewers you follow the official migration path.

Bad / costs: fewer blog posts and interview questions mention it than Feign; some Feign features (e.g. Feign-specific encoders/decoders) have no 1:1 equivalent.

## Alternatives considered

- **Spring Cloud OpenFeign 5.0.x** — still shipped in the 2025.1 train and works with Boot 4.1. Choose it if you want "Feign" visible on the CV. Switching = rewrite two adapter interfaces + config; ports and tests stay.
- **Plain `RestClient` calls** — fine for one-off calls; more boilerplate for typed APIs.
- **Both Feign and HTTP Service Clients** — rejected: two ways to do one job.

## How we verify it

- WireMock tests: read timeout triggers at the configured value; circuit opens after the failure threshold and then fails fast (mapped to `503`); with the `docker` profile settings, an `lb://` base URL is resolved by the LoadBalancer (a static instance list stands in for Eureka in the test).
- Security test: the service token is sent with `scope customer.contact.read`; user token relay sends the caller's JWT unchanged.
