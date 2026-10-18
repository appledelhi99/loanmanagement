# ADR-0007: Spring Cloud Gateway (WebFlux) at the edge

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F01 (issuer/JWKS split), F14 (management port), F17 (explicit time limiters), F21 (edge hygiene)

## Context

Clients need one entry point with security, rate limiting and protection from slow back ends. Spring Cloud Gateway 5.0 ships two server flavours: `spring-cloud-starter-gateway-server-webflux` and `spring-cloud-starter-gateway-server-webmvc`. Routes for WebFlux live under `spring.cloud.gateway.server.webflux.*`.

## Decision

Use **Spring Cloud Gateway Server WebFlux** in `api-gateway`:

| Concern | How |
|---|---|
| Routing | Route URIs come from config per profile (`lb://…` in Compose, `http://<service>:<port>` in Kubernetes): `/api/v1/customers/**` → customer-service, `/api/v1/loan-applications/**` and `/api/v1/loan-products/**` → loan-service, `/api/v1/payments/**` → payment-service, `/api/v1/notifications/**` → notification-service |
| Authentication | OAuth2 Resource Server (reactive): keys from the **internal** `jwk-set-uri`, `iss` checked against the **public** `issuer-uri`, **audience `loan-platform-api`**, expiry |
| Coarse authorization | Path + method rules (e.g. decision endpoints need `ROLE_LOAN_OFFICER`, notifications need `ROLE_ADMIN`) |
| Rate limiting | `RequestRateLimiter` + Redis rate limiter on Redis (needs `spring-boot-starter-data-redis-reactive`); key = JWT `sub` |
| Resilience | `CircuitBreaker` filter per route with fallback → `503` Problem Details and an **explicit time limiter per route** (≥ the response timeout; no defaults relied on); `Retry` filter for `GET` only; HTTP client connect/response timeouts |
| Tracing | Micrometer observation on every request; response header `X-Trace-Id` for support |
| Hygiene | Actuator is on the management port (9080), never routed; drop hop-by-hop and spoofable headers from clients; security headers; request-size limit; CORS denied by default |

Services **still validate the JWT and check roles** themselves (defence in depth).

## Consequences

Good: non-blocking edge that handles many slow connections cheaply; mature reactive filter set (Redis rate limiter, circuit breaker); same behaviour locally and in AWS.

Bad / costs: reactive code at the edge is a different programming model from the MVC services (kept small: config + a few filters); JWT is checked twice (small CPU cost, cached keys).

## Alternatives considered

- **Gateway Server Web MVC** — blocking, simpler to read, has its own rate-limit filter (Bucket4j-style). Fine choice too; WebFlux chosen for the Redis rate limiter and edge efficiency.
- **AWS API Gateway** — see [trade-offs §3](../architecture/07-trade-offs.md#3-spring-cloud-gateway-vs-a-cloud-managed-api-gateway).
- **Token Relay / BFF (gateway as OAuth2 client with sessions)** — right for browser apps; we have API clients only, so the gateway stays a stateless resource server.

## How we verify it

- `WebTestClient` tests: 401 without token, 403 with wrong role, 200 with right role, 429 after the burst, 503 fallback when a route's back end is down (WireMock), `/actuator/**` not routed.
