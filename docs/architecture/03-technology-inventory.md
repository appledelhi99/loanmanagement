# 03 — Technology Inventory

Status: **Reviewed — final Phase 1 design** (revised after the [architecture review](08-architecture-review.md)). Versions are in the [version matrix](05-version-compatibility-matrix.md).

Rule for this list: **every item solves a named problem and has a home.** If we cannot say both, it is not in the project. The "Not used" table at the end lists what we left out on purpose.

Legend — **M** = mandatory (needed to build, test or run locally) · **O** = optional (cloud, comparison, or extra tooling).

---

## 1. Spring modules

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| Spring Boot 4.1 | all apps | Auto-configuration, embedded Tomcat/Netty, production defaults, one dependency BOM | M |
| Spring Boot 4 starter names | all modules | Boot 4 renamed starters: `spring-boot-starter-webmvc`, `-webflux`, `-restclient`, `-security-oauth2-resource-server`, `-security-oauth2-client`, `-flyway`, plus one `-test` starter per technology (`-webmvc-test`, `-data-jpa-test`, `-security-test`, `-flyway-test`, …). Deprecated Boot 3 names are banned by Enforcer | M |
| Spring Framework 7 (Web MVC) | business services | Blocking REST APIs. Simple to read and debug. Runs on **virtual threads** (`spring.threads.virtual.enabled=true`) so blocking I/O does not tie up platform threads | M |
| Spring Framework 7 HTTP Service Clients (`@HttpExchange`, `@ImportHttpServices`) | loan-service, notification-service | Declarative, typed REST clients for service-to-service calls (replaces OpenFeign, see [ADR-0005](../adr/0005-spring-http-service-clients-instead-of-openfeign.md)) | M |
| Spring Framework 7 core retry (`@Retryable`, `RetryTemplate`) | client adapters | Bounded retries with exponential backoff and jitter on idempotent calls. Replaces Spring Retry, which is archived | M |
| Spring Data JPA + Hibernate ORM 7 | customer, loan, payment, notification | Persistence with explicit transactions, optimistic locking (`@Version`), pagination (`Pageable`) | M |
| Spring Validation (Hibernate Validator 9) | all APIs | Reject bad input at the edge of each service (`@Valid`, custom constraints) | M |
| Spring Boot Actuator | all apps | Health, liveness, readiness, metrics, info — on a **separate management port** (app port + 1000) | M |
| Spring Cloud Config (Server + Client) | config-server, all clients | One place for non-secret config per service and per environment. `native` backend; fail-fast import, no client retry library | M |
| Spring Cloud Gateway Server **WebFlux** | api-gateway | Single entry point: routing, JWT check, rate limiting (Redis), circuit breaker, retry, header rules | M |
| Spring Cloud Netflix Eureka (Server + Client) | discovery-server, all clients (Compose profile) | Service registry for local Docker Compose, client-side load balancing across replicas | M (local) |
| Spring Cloud LoadBalancer | gateway, HTTP Service Clients (Compose profile) | Picks an Eureka instance for `lb://service-id`. In Kubernetes, base URLs point at the Service DNS name instead | M (local) |
| Spring Cloud CircuitBreaker + Resilience4j | gateway, loan, payment, notification | Circuit breaker, time limiter, bulkhead behind a Spring abstraction | M |
| Spring for Apache Kafka 4.1 | loan-service, payment-service, `platform-outbox` | Fine control over producers/consumers: error handlers, DLT recoverer, transactions with DB, headers | M |
| Spring Cloud Stream (Kafka binder) | notification-service | Binder-based consumers with built-in retry and DLQ settings; simple for a pure consumer service | M |
| Spring Cloud Function | notification-service | Consumers written as plain `java.util.function.Consumer` beans; **routing by `eventType` header** to one handler per event type; easy to unit test | M |
| Spring Cloud Contract (Verifier + Stub Runner) | customer-service (producer), loan- and notification-service (consumers) | Consumer-driven **HTTP** contracts: an API change that breaks a consumer fails the producer's build. Stubs resolved from the reactor (`stubs` classifier, `CLASSPATH` mode) | M |
| Spring Security 7 — OAuth2 Resource Server | gateway + all business services | Validate JWTs (signature, issuer, audience, expiry), map roles and scopes, method security | M |
| Spring Security 7 — OAuth2 Client | notification-service | Get and cache client-credentials tokens for service-to-service calls (`@ClientRegistrationId` on HTTP Service Client methods) | M |
| Spring HATEOAS | loan-service | Responses show **which actions are allowed now** (approve/reject/withdraw links depend on status and role) | M |
| Spring Cache abstraction + Spring Data Redis | loan-service | Cache-aside for the loan product catalogue (read on every application and quote), TTL + evict on change | M |
| Spring Boot Mail | notification-service | Send simulated emails over SMTP to Mailpit | M |
| Spring Boot Docker Compose / Testcontainers support (`@ServiceConnection`) | tests, dev runs | Wire test containers into Spring config with no manual properties | M |
| Spring Cloud Kubernetes | — | **Not used**: Kubernetes Service DNS is enough (see [ADR-0006](../adr/0006-service-discovery-eureka-locally-kubernetes-native-in-clusters.md)) | — |

## 2. Java libraries (non-Spring)

| Library | Where | Problem it solves | M/O |
|---|---|---|---|
| Java 25 LTS | everything | Current LTS. Records, sealed types, pattern matching, virtual threads without `synchronized` pinning (JEP 491) | M |
| Resilience4j 2.x | via Spring Cloud CircuitBreaker | Proven resilience primitives (circuit breaker, time limiter, bulkhead) | M |
| Jackson 3 | all apps (Boot 4 default) | JSON. Events are serialized by our own code with `JsonMapper`, not by type headers | M |
| springdoc-openapi 3.1 | business services | OpenAPI 3.1 docs and Swagger UI from code | M |
| Flyway 12 (+ `flyway-database-postgresql`) | services with a DB | Versioned, repeatable schema migrations owned by each service | M |
| PostgreSQL JDBC driver | services with a DB | JDBC access | M |
| Micrometer (Observation, Tracing, Prometheus registry) | all apps | One API for metrics and traces | M |
| OpenTelemetry SDK + OTLP exporter (via `spring-boot-starter-opentelemetry`) | all apps | Vendor-neutral trace export | M |

> **No Lombok** and **no `var`**: explicit constructors, getters and types keep the code easy to review.

## 3. Security

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| Keycloak 26.x | identity provider | Users, roles, clients, scopes, OIDC sign-in, JWT signing keys | M |
| OAuth 2.0 Authorization Code + PKCE | user sign-in (`loan-web` public client) | Safe sign-in for public clients without client secrets | M |
| OAuth 2.0 Client Credentials | notification-service → customer-service | Machine identity with narrow scope `customer.contact.read` | M |
| Token relay (bearer propagation) | loan-service → customer-service | Call on behalf of the user (submit) or officer (approve); customer-service applies that person's rights | M |
| JWT (RS256) validation via JWKS | gateway + services | Stateless authentication. Public `issuer-uri` checks `iss`; internal `jwk-set-uri` fetches keys; `audiences` checks `aud` | M |
| Role + scope authorities | services | `ROLE_CUSTOMER`, `ROLE_LOAN_OFFICER`, `ROLE_ADMIN`, `SCOPE_customer.contact.read` | M |
| Spring SSL bundles | optional local TLS profile | TLS config without custom code | O |
| AWS Certificate Manager + ALB | AWS | Public TLS termination | O |
| AWS Secrets Manager + External Secrets Operator | AWS | Production secrets, rotated outside Git | O |
| `.env` (git-ignored) + `.env.example` | local | Local secrets without committing them; Keycloak realm reads them through `${ENV}` placeholders | M |
| Separate management port + Kubernetes NetworkPolicies | all apps / clusters | Probes and scraping isolated from API traffic; default-deny network ([ADR-0023](../adr/0023-separate-management-port-and-network-policies.md)) | M (clusters) |
| Pod Security "restricted" | clusters | Non-root, read-only root filesystem, no capabilities, `RuntimeDefault` seccomp | M (clusters) |
| Gitleaks, CodeQL, Trivy, Dependabot, CycloneDX SBOM | CI | Secret scanning, static analysis, image/IaC scanning, dependency updates, bill of materials | M (CI) |

## 4. Databases and caching

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| PostgreSQL 18 | 4 service DBs + Keycloak DB | Durable, transactional storage. `FOR UPDATE SKIP LOCKED` for the payment executor; `pg_try_advisory_xact_lock` for the single outbox relay; row locks for the repayment ledger | M |
| Database-per-service (separate DB, `<svc>_owner` + `<svc>_app` users) | all services | Independent schema evolution, no hidden coupling, DDL rights only for migrations | M |
| Redis 7.x | gateway rate limiter, loan product cache | Fast shared state with TTL; same version line as ElastiCache for Redis OSS 7.1. **No PII is cached** | M |
| Amazon RDS for PostgreSQL | AWS | Managed backups, patching | O |
| Amazon ElastiCache for Redis OSS 7.1 | AWS | Managed cache | O |

## 5. Messaging

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| Apache Kafka 4.x (KRaft, no ZooKeeper) | broker | Durable, ordered (per key), replayable business events | M |
| Topic per aggregate (`loan-events`, `payment-events`) | design | Keeps per-loan order across event types | M |
| Consumer groups | all consumers | Each service gets every event; replicas share partitions | M |
| Dead-letter topics `<topic>.<group>.dlt` | all consumers | Park bad records with error headers, keep the partition moving | M |
| Event envelope + `eventVersion` + `correlationId` | all events | Safe schema evolution (tolerant reader); support and DLT replay keep context | M |
| Transactional outbox + polling relay (`platform-outbox`) | loan, payment | DB change and event can never disagree | M |
| Payment executor (scheduled worker) | payment-service | Provider calls happen outside the Kafka listener; one path for first attempt, retries and crash recovery | M |
| Idempotent consumers (`processed_events`) | all consumers | At-least-once delivery without double effects | M |
| Topic init script (`kafka-topics.sh`) | local + CI | Explicit partitions, retention, cleanup policy — no auto-create | M |
| Amazon MSK (provisioned, 2 small brokers) | AWS (`kafka_mode = msk`, default) | Managed Kafka; `in-cluster` mode for quick, cheap demos | O |

## 6. Observability

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| Micrometer Tracing (OTel bridge) | all apps | Trace and span IDs, context across HTTP and Kafka | M |
| OpenTelemetry Collector | observability profile, cluster | One pipeline; swap backends (Jaeger, X-Ray) without touching apps | O (M for tracing demo) |
| Jaeger v2 | observability profile | Trace search and timeline view | O |
| Prometheus | observability profile | Metrics scraping, alert rules | O |
| Grafana (dashboards provisioned as code) | observability profile | Service, JVM, Kafka and business dashboards; alerts | O |
| Spring Boot structured logging (ECS JSON) | all apps | Machine-readable logs with `trace.id`; ELK-ready format | M |
| Grafana Loki + Grafana Alloy | observability profile | Central log search, lighter than ELK on a laptop. (Promtail reached end of life on 2 March 2026, so Alloy is the collector.) | O |
| Actuator health groups (liveness / readiness, on by default in Boot 4) + startup probes | all apps | Correct Kubernetes and Compose health checks | M |
| SLOs + burn-rate alerts, exemplars | Grafana / Prometheus | Alert on user impact, jump from a slow metric to its trace | O |

## 7. Testing

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| JUnit Jupiter (JUnit 6 line, managed by Boot 4) | all modules | Test engine. Same Jupiter API you know from JUnit 5 | M |
| Mockito 5 | unit tests | Isolate classes from collaborators | M |
| AssertJ | all tests | Readable assertions | M |
| `MockMvcTester` / `RestTestClient` (Spring), `@MockitoBean` | web tests, E2E | REST API tests without adding REST Assured. Boot 4 removed `@MockBean`; `@SpringBootTest` needs `@AutoConfigureMockMvc` / `@AutoConfigureRestTestClient` | M |
| Spring Security Test (`jwt()`, `@WithMockUser`) | web tests | Authorization tests per role and scope | M |
| Testcontainers 2.x — PostgreSQL, Kafka, generic Redis | integration tests | Real dependencies, not mocks or H2 | M |
| testcontainers-keycloak | security + E2E tests | Real token issuance with the real realm file | M |
| Spring Cloud Contract | producer/consumer builds | HTTP contracts (customer-service APIs) | M |
| Event contracts: golden JSON examples (`contracts/event-contracts`) + JSONAssert | producers and consumers of each event | Spring Cloud Contract documents no Kafka integration, so producers prove their output equals the example and consumers prove they parse it. No extra library (JSONAssert ships with the test starter) | M |
| WireMock (via Spring Cloud Contract) | client adapter tests | Simulate slow / failing downstreams to test timeouts and circuit breakers | M |
| Awaitility | async tests | Wait for events without `Thread.sleep` | M |
| ArchUnit | each service | Enforce hexagonal / layered dependency rules in tests | M |
| JaCoCo ≥ 0.8.14 | build | Coverage reports and minimum thresholds (0.8.14 is the first release with official Java 25 support) | M |
| Gatling (optional) | `tests/` | Smoke load test to show rate limiting and bulkheads under load | O |

## 8. Build and code quality

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| Maven 3.9 + Maven Wrapper | repo | Reproducible builds, same Maven everywhere | M |
| Maven multi-module (parent + BOM import) | repo | One version source, independent deployables | M |
| Maven Enforcer | parent | Fail on wrong Java/Maven and on banned deps: Lombok, OpenFeign, Sleuth, Spring Retry, JUnit 4, deprecated Boot 3 starter names; service→service deps only for `stubs` (test). No `dependencyConvergence` (too noisy with the BOMs) | M |
| Maven Failsafe | services | Separate `*IT` integration tests from fast unit tests | M |
| CycloneDX Maven plugin | services | SBOM per image | M (CI) |

## 9. Containers and local runtime

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| Docker (multi-stage Dockerfile, Temurin 25 JRE, non-root, extracted layers) | each app | Small, cache-friendly, safe images | M |
| Docker Compose v2 with profiles (`core`, `observability`) | `deploy/compose` | One command to run the stack, fit on a laptop | M |
| Mailpit | Compose | Catch emails locally; API used by E2E tests | M |
| kind | `deploy/kind` | Local Kubernetes to test the Helm chart before AWS | O |
| Helm chart | `deploy/helm/loan-platform` | One parameterised chart for all services, values per environment | O |

## 10. Cloud (AWS) — optional, explicit approval before any `apply`

| Service | Problem it solves |
|---|---|
| Amazon EKS (primary) | Runs the same Helm chart as kind |
| Amazon ECR | Private image registry, scan on push |
| Amazon RDS for PostgreSQL | Managed database |
| Amazon ElastiCache for Redis OSS | Managed cache |
| Amazon MSK | Managed Kafka |
| AWS Secrets Manager | Production secrets |
| AWS Load Balancer Controller + ALB + ACM | HTTPS ingress |
| EKS Pod Identity | Pods get AWS permissions without static keys |
| CloudWatch Logs (optional) | Log retention outside the cluster |

## 11. DevOps and IaC

| Technology | Where | Problem it solves | M/O |
|---|---|---|---|
| GitHub Actions | `.github/workflows` | CI (build, unit, integration, contract, E2E), image build + scan, Terraform plan, manual deploy | M (CI) / O (deploy) |
| GitHub OIDC → AWS IAM role | deploy workflows | No long-lived AWS keys in GitHub | O |
| Terraform (AWS provider 6.x) | `deploy/terraform` | Repeatable AWS environment; `destroy` to stop cost | O |
| tflint, `terraform validate`, Trivy IaC scan | CI | Catch IaC mistakes and insecure settings before apply | O |
| Jenkins (declarative `Jenkinsfile`) | `ci/jenkins` | Comparison with GitHub Actions, common in enterprises | O |

---

## Not used (on purpose)

| Technology | Why not |
|---|---|
| Spring Cloud OpenFeign | Official docs: feature-complete, "suggest migrating over to Spring HTTP Service Clients". Kept as a review option in ADR-0005 |
| Spring Cloud Sleuth | Removed for Boot 3+. Replaced by Micrometer Tracing |
| Netflix Ribbon / Hystrix / Zuul | Removed long ago. Replaced by Spring Cloud LoadBalancer / Resilience4j / Spring Cloud Gateway |
| Keycloak Spring adapters | Deprecated by Keycloak. Spring Security's standard OAuth2 support is used |
| Spring Cloud Vault | Vault is extra infra. Env vars locally, AWS Secrets Manager in cloud |
| Spring Cloud Bus | Config refresh across instances is not a requirement. Restart / rolling deploy is enough |
| Spring Retry | Archived — "superseded by Spring Framework 7". Boot 4 no longer manages its version. Spring Framework retry is used instead; config clients fail fast and the platform restarts them |
| Spring Cloud simple discovery in Kubernetes | Extra layer that resolved to one fixed URL. Profile-specific base URLs are clearer |
| Config Server Git backend in clusters | Runtime dependency on GitHub and its credentials. Config is baked into the config-server image instead |
| Customer / contact caching | Primary-key reads are already fast, and caching would copy PII into another store |
| Spring Batch / Spring Cloud Task | No real batch requirement yet. The payment executor, outbox relay and key purge are small scheduled jobs. Candidate later: end-of-day payment reconciliation report ([ADR-0022](../adr/0022-spring-batch-and-spring-cloud-task-deferred.md)) |
| Kafka Streams | No stream joins or aggregations needed |
| Schema Registry (Avro/Protobuf) | Adds infra. JSON + versioned envelope + golden-example contract tests are enough at this size. Documented as the next step |
| Debezium (CDC outbox) | Needs Kafka Connect. Polling relay is simpler to run and explain |
| Kafka transactions / exactly-once | They cover Kafka-to-Kafka only, not the database. Outbox + idempotent consumers solve the real problem |
| Service mesh (Istio, Linkerd) | Too heavy for this scope. mTLS inside the cluster is listed as a production hardening item |
| OpenTelemetry Java agent | Bytecode instrumentation can clash with library versions. Spring's Micrometer-based starter is used |
| OTel Logback appender (log export over OTLP) | Still alpha. Logs go to stdout and Alloy collects them |
| ELK / OpenSearch locally | Heavy on RAM. Logs are ECS JSON, so ELK can be swapped in later |
| Bitnami Helm charts | Licensing and image distribution changes. We use official images and small dev-only manifests |
| Lombok | Your requirement. Explicit Java is easier to review |
| REST Assured (directly) | Spring's own `RestTestClient` / `MockMvcTester` cover our tests. Spring Cloud Contract's generated producer tests use it internally; that is fine |
