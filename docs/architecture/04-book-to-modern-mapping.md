# 04 — Book Concepts → Modern Implementation

Source: *Spring Microservices in Action, 2nd Edition* — John Carnell and Illary Huaylupo Sánchez (Manning, 2021). The book's examples use the Spring Boot 2.x / Spring Cloud Hoxton generation, Java 11, and the "O-stock" licensing/organization services.

Status: **Reviewed — final Phase 1 design** (revised after the [architecture review](08-architecture-review.md)).

This table maps each concept in the book to how this project does it in October 2026. "Kept" means the idea and the tool stay. "Modernised" means the idea stays and the tool or API changes. "Replaced" means a different tool now does the job.

| # | Book topic (chapter area) | Book-era implementation | This project | Change | Why |
|---|---|---|---|---|---|
| 1 | Microservice basics with Spring Boot | Spring Boot 2.x, Java 11, `javax.*` | Spring Boot **4.1**, Java **25 LTS**, `jakarta.*`, Jakarta EE 11 | Modernised | Boot 2.x is long out of support. 4.1 is the current GA line |
| 2 | REST controllers | `@RestController`, `RestTemplate` | `@RestController` + DTO records, `RestClient`, virtual threads | Modernised | `RestClient` is the modern blocking client. Virtual threads give high concurrency with simple blocking code |
| 3 | Example domain | O-stock licensing / organization | Loan management (customer, loan, payment, notification) | Replaced | A domain with real rules: eligibility, approval, money, idempotency |
| 4 | Error handling | Custom error objects | RFC 9457 **Problem Details** (`ProblemDetail`) with `errorCode` and `traceId` | Modernised | Standard error format clients and gateways understand |
| 5 | HATEOAS | Spring HATEOAS (intro) | Spring HATEOAS 3.x in loan-service, links depend on status and role | Kept | Shows a real use: the API tells the client which actions are allowed now |
| 6 | Docker | Dockerfile + a Maven Docker plugin / Compose v1 syntax | Multi-stage Dockerfile, Temurin 25 JRE, non-root, extracted Boot layers, **Compose v2 with profiles** | Modernised | Smaller, safer images. `docker compose` v2 is the standard CLI |
| 7 | Centralised config | Spring Cloud Config Server (Git / file / **Vault** backend) | Spring Cloud Config 5.0, `native` backend (folder mounted locally, baked into the image for clusters); clients fail fast — no Spring Retry (archived). Secrets: env vars locally, **AWS Secrets Manager** in cloud | Modernised | Same idea. Vault replaced by the cloud's secret manager to cut infra |
| 8 | Config encryption | Encrypted property values / Vault | **No secrets in the config repo at all** | Replaced | Simpler and safer: secrets never enter Git, even encrypted |
| 9 | Service discovery | Netflix Eureka | Eureka for local Compose; **Kubernetes Service DNS** in clusters (base URL per profile) | Modernised | Avoid two registries in Kubernetes ([ADR-0006](../adr/0006-service-discovery-eureka-locally-kubernetes-native-in-clusters.md)) |
| 10 | Client-side load balancing | Spring Cloud LoadBalancer (Ribbon before it) | Spring Cloud LoadBalancer 5.0 | Kept | Same role, current version |
| 11 | Declarative REST client | Netflix **Feign** / Spring Cloud OpenFeign | **Spring HTTP Service Clients** (`@HttpExchange` + `@ImportHttpServices`) with LoadBalancer + CircuitBreaker integration | Replaced (review point) | OpenFeign is feature-complete; its docs suggest migrating ([ADR-0005](../adr/0005-spring-http-service-clients-instead-of-openfeign.md)) |
| 12 | Resilience patterns | Resilience4j (circuit breaker, bulkhead, retry, rate limiter, fallback) | Resilience4j via Spring Cloud CircuitBreaker (CB, time limiter, bulkhead); **Spring Framework 7 `@Retryable`** for retries; gateway rate limiter | Modernised | Retry is now built into Spring Framework; one less library surface |
| 13 | Edge service | Spring Cloud Gateway (route predicates, filters, custom pre/post filters) | Spring Cloud Gateway **Server WebFlux** 5.0 (`spring-cloud-starter-gateway-server-webflux`, `spring.cloud.gateway.server.webflux.*`) | Modernised | Module and property names changed in the 2025 trains |
| 14 | Correlation ID | Custom gateway filter + `UserContext` + `ThreadLocal` | **W3C trace context** + Micrometer Tracing baggage; trace ID in every log line | Replaced | Standard propagation over HTTP and Kafka; no hand-made `ThreadLocal` |
| 15 | Security | Keycloak + **Keycloak Spring Boot adapter** | Keycloak 26 + **Spring Security 7 OAuth2 Resource Server / Client** | Replaced | Keycloak adapters are deprecated. Spring Security is IdP-neutral |
| 16 | OAuth2 grants | Password grant in examples | **Authorization Code + PKCE** for users, **Client Credentials** for services | Modernised | The OAuth 2.0 Security Best Current Practice (RFC 9700) says not to use the password grant. Dev-only direct grant client kept for scripts and tests, disabled in `aws` realm |
| 17 | Token propagation | `KeycloakRestTemplate` | Interceptor that relays the user's bearer token; `@ClientRegistrationId` for service tokens | Modernised | Works with any OIDC provider |
| 18 | Event-driven architecture | Spring Cloud Stream **annotation model** (`@EnableBinding`, `@StreamListener`, `Source`/`Sink`) | Spring Cloud Stream 5.0 **functional model** (`Consumer<Message<…>>` beans) + Spring Cloud Function routing | Modernised | The annotation model was removed in Stream 4.x |
| 19 | Kafka use | Simple publish/subscribe | Outbox, idempotent consumers, DLT, blocking retries, envelope versioning, ordering by key | Extended | Production concerns the book only touches |
| 20 | Caching | Redis via Spring Data Redis (cache invalidated by events) | Redis via Spring Data Redis + Spring Cache for the loan product catalogue only (TTL + evict on change); gateway rate limits in Redis | Kept / narrowed | Same store as the book. Customer data is not cached (no PII in a second store) |
| 21 | Distributed tracing | **Spring Cloud Sleuth** + **Zipkin** | **Micrometer Tracing** (OTel bridge) → **OTLP** → OpenTelemetry Collector → **Jaeger v2** | Replaced | Sleuth does not support Boot 3+. OTLP is the vendor-neutral standard |
| 22 | Log aggregation | **ELK** (Logstash appender) | Spring Boot **structured logging (ECS JSON)** → Grafana **Alloy** → **Loki** → Grafana | Replaced (review point) | Lighter on a laptop. ECS format keeps ELK as a drop-in option |
| 23 | Monitoring | Micrometer + Prometheus + Grafana (appendix) | Same, plus business metrics, alerts and dashboards as code | Kept / extended | — |
| 24 | Health checks | Actuator `/health` | Actuator **liveness / readiness** groups used by Compose health checks and Kubernetes probes | Modernised | Correct restart vs. traffic decisions |
| 25 | Deployment to AWS | Manual set-up of EKS, RDS, ElastiCache, ECR | **Terraform** (AWS provider 6.x) + **Helm** + EKS Pod Identity + External Secrets Operator | Modernised | Repeatable, reviewable, destroyable |
| 26 | Build / deploy pipeline | **Jenkins** pipeline | **GitHub Actions** (primary) with OIDC to AWS; Jenkinsfile kept as an optional comparison | Modernised | GitHub-hosted CI is free for public repos and needs no server |
| 27 | Testing | Basic unit tests | JUnit Jupiter, Mockito, Testcontainers 2, Spring Cloud Contract (HTTP), golden-example event contracts, security tests, ArchUnit, E2E | Extended | Portfolio-grade proof that it works |
| 28 | Database access | Spring Data JPA + PostgreSQL, schema scripts | Spring Data JPA + PostgreSQL 18 + **Flyway** per service, DB-per-service users | Modernised | Versioned migrations and real ownership |
| 29 | Transactions across services | Not covered in depth | **Saga (choreography)** + **transactional outbox** + idempotency | Added | The core problem of microservices with money |
| 30 | API docs | — | springdoc-openapi 3.1 (OpenAPI 3.1), versioned paths `/api/v1` | Added | Reviewers and clients can explore the API |

## Spring Cloud module status (2025.1 "Oakwood" train)

| Module | Status for us |
|---|---|
| Config, Gateway, Netflix Eureka, LoadBalancer (Commons), CircuitBreaker, Stream, Function, Contract | **Used** |
| OpenFeign | Supported but feature-complete; **not used** (review point) |
| Kubernetes | Available; not needed |
| Bus, Vault, Consul, Zookeeper, Task | Not needed for this scope |
