# 08 — Independent Architecture Review of Phase 1

| | |
|---|---|
| Reviewed | Phase 1 proposal (docs 01–07, ADR-0001…0022, implementation plan) |
| Date | 2026-10-16 |
| Reviewer stance | Principal Java Architect, independent of the author. Goal: find what would break, what is obsolete, what is too complex, and what is missing — **before** any code exists |
| Outcome | **32 findings**: 6 × P0, 14 × P1, 12 × P2. All P0 and P1 corrections are applied to the design. P2 items are added to the phase acceptance criteria. Nothing blocks Phase 2 once you confirm |

Priority key — **P0** would break the build or the first workflow; must fix before Phase 2 · **P1** design change that is cheap now and expensive after code exists · **P2** missing requirement or hardening; scheduled into a phase.

---

## 1. Version verification against official documentation

| Check | Official source (2026-10-16) | Result |
|---|---|---|
| Spring Cloud ↔ Spring Boot | spring.io Spring Cloud project page, compatibility table: **"2025.1.x aka Oakwood — 4.0.x, 4.1.x (Starting with 2025.1.2)"** | ✅ Boot 4.1.x + Cloud 2025.1.x is an official pair |
| Latest Spring Cloud GA | Spring Cloud project page header and train reference docs: **2025.1.3** | ✅ |
| What Cloud 2025.1.3 is built on | Train reference docs: "Supported Boot Version: 4.0.8-SNAPSHOT" | ⚠️ Built on Boot 4.0, declared compatible with 4.1. See finding F19 |
| Latest Spring Boot GA | spring.io Spring Boot project page: **4.1.1** | ✅ |
| Java range for Boot 4.1 | Boot 4.1 system requirements: Java 17 up to and including 26 | ✅ Java 25 LTS is inside the range |
| Spring Cloud 2026.0 | Only 2026.0.0-M1 (on Boot 4.2.0-M2) | ✅ correctly not chosen |
| Spring Boot 3.5 | OSS support ended June 2026 | ✅ correctly not chosen |
| Spring Security | Resource server docs version 7.1.1 | ✅ matches Boot 4.1 line |
| JaCoCo for Java 25 | JaCoCo change history: 0.8.14 (2025-10-11) "officially supports Java 25"; latest 0.8.15 (2026-06-04) | ⚠️ was "pin later"; now pinned ≥ 0.8.14 (F26) |
| Spring Retry | Project README: "superseded by Spring Framework 7 and is now archived"; Boot 4 no longer manages its version | ❌ Our config-client retry plan needed it (F02) |

**Verdict:** the core choice — **Java 25 LTS + Spring Boot 4.1.x + Spring Cloud 2025.1.x** — is correct and officially supported. No version change is needed. Two supporting decisions change (F02, F26).

---

## 2. Prioritised findings and corrections

### P0 — must fix before Phase 2

| # | Area | Finding | Correction applied | Where |
|---|---|---|---|---|
| F01 | Security | **Issuer mismatch in Docker.** A token fetched from the host has `iss = http://localhost:8180/realms/loan-platform`; containers reach Keycloak as `keycloak:8080`. With `issuer-uri` discovery, every token would be rejected (or startup would fail). | Keycloak hostname = public URL. Services set **both** `issuer-uri` (public, used only to check `iss`) and `jwk-set-uri` (internal). Spring Security 7.1 docs confirm: with both set, the resource server "will not ping the authorization server at startup" and still validates `iss`. Audience via `spring.security.oauth2.resourceserver.jwt.audiences`; RS256 only (the default) | ADR-0008, overview §8, Phase 4 AC |
| F02 | Build / config | **Config-client retry needs Spring Retry**, which is archived and no longer managed by Boot 4 (Spring Cloud Config 5.0.5 docs still say "add `spring-retry` and `spring-boot-starter-aop`"). | **No Spring Retry anywhere.** Clients use `spring.config.import=configserver:…` without `optional:` (fail fast). Start order comes from the platform: Compose `depends_on: condition: service_healthy` + `restart: on-failure`; Kubernetes restart back-off + readiness. Enforcer bans `org.springframework.retry:*` | ADR-0017, inventory, Phase 2 AC |
| F03 | Build | **Contract stubs would not resolve in `./mvnw verify`.** Stub Runner `LOCAL` mode reads `~/.m2`, which only `install` fills. | Consumers declare a **test-scoped** dependency on `customer-service` with classifier `stubs` and use `stubsMode = CLASSPATH`; the reactor resolves it after `package`. Enforcer allows service→service dependencies **only** for classifier `stubs`, scope `test` | ADR-0002, ADR-0020, repo tree |
| F04 | Testing | **Spring Cloud Contract has no documented Kafka integration.** Both the 4.2 and 5.0 messaging docs list only Camel, Spring Integration, Spring Cloud Stream and JMS. Our event-contract plan depended on it. | New resources-only module **`contracts/event-contracts`**: one golden JSON example per event type and version. Producers prove their serialized event equals the example (JSONAssert, already in the test starter). Consumers prove they parse every example. No new library. Spring Cloud Contract stays mandatory for **HTTP** contracts; Kafka messaging contracts become an optional stretch | ADR-0009, ADR-0020, repo tree |
| F05 | Build / data | **Flyway SQL "shipped as a template" from `platform-outbox`** cannot work cleanly: version numbers would clash with each service's own migrations, and ownership becomes unclear. | Each service owns its `outbox_events` / `processed_events` migrations. The library ships **code only**, plus a test helper that checks the table matches what the code expects | ADR-0011, repo tree |
| F06 | Build | **Boot 4 renamed starters and test APIs.** Using Boot 3 names (`spring-boot-starter-web`, `…-oauth2-resource-server`, `@MockBean`) would compile against deprecated or removed artifacts. | Use Boot 4 names: `spring-boot-starter-webmvc`, `-webflux`, `-restclient`, `-security-oauth2-resource-server`, `-security-oauth2-client`, `-flyway`, `-aspectj` (if ever needed), and per-technology test starters (`-webmvc-test`, `-data-jpa-test`, `-security-test`, `-flyway-test`, …). Tests use `@MockitoBean`, `@AutoConfigureMockMvc`, `RestTestClient`. Jackson 3 group `tools.jackson`. Enforcer bans the deprecated names | Inventory, matrix, Phase 2 AC |

### P1 — design changes applied before implementation

| # | Area | Finding | Correction applied | Where |
|---|---|---|---|---|
| F07 | Reliability | payment-service **called the provider inside the Kafka listener** (up to ~10 s with retries), mixing message intake with remote I/O and creating two retry paths (listener + reconciler). | Listener only records a `PENDING` payment (one TX) and returns. A **payment executor** (scheduled worker, `FOR UPDATE SKIP LOCKED`) makes the first attempt **and** all retries. One code path for success, timeout, crash recovery | ADR-0012, W3/W4 |
| F08 | Correctness | **Repayment could overpay.** payment-service checked a stale read model; loan-service "capped and flagged" afterwards, but the money was already taken. Two services half-owned the balance. | payment-service owns the **repayment ledger** (`loan_accounts`: total payable, repaid, reserved). A repayment locks the row (`SELECT … FOR UPDATE`), reserves the amount, then charges. Overpayment is impossible. loan-service keeps a display copy of `remaining` from `RepaymentReceived` and closes the loan at zero | ADR-0003, ADR-0013, W5, rule R11 |
| F09 | Privacy / simplicity | **PII copied into Valkey** (customer cache, notification contact fallback) for little gain: they are primary-key reads. | Removed both. Valkey now holds only **gateway rate-limit buckets** and the **loan product catalogue**. Contact lookup failure → bounded retry → DLT → replay | ADR-0015, overview, inventory |
| F10 | Business rule | KYC was checked only at submission. KYC can be revoked before an officer approves. | **R1b:** approval re-reads KYC (officer token relayed to customer-service). Fail closed: no approval when customer-service is down | Overview §5.2, W2 |
| F11 | Reliability | Outbox relay used a **session-level advisory lock** (unsafe with a connection pool) **and** `SKIP LOCKED` (redundant once one relay runs). | `pg_try_advisory_xact_lock` taken inside each relay transaction (released at commit or crash). No `SKIP LOCKED` in the relay. Batch ≤ 100, producer `delivery.timeout.ms` bounded. Documented as the one allowed remote call inside a DB transaction | ADR-0011, W1 |
| F12 | Simplicity | Kubernetes discovery through Spring Cloud **simple discovery** was an extra layer that resolved to one fixed URL. | Per-profile base URLs: `lb://customer-service` (Compose, Eureka) vs `http://customer-service:8081` (Kubernetes Service). LoadBalancer is only active in Compose | ADR-0006, trade-offs §2 |
| F13 | Deployment | Config server with a **Git backend in clusters** added a runtime dependency on GitHub and its credentials. | `native` backend everywhere. Compose mounts `config-repo/`; for clusters the config files are **baked into the config-server image** at build time (immutable, versioned with the release) | ADR-0017, repo tree |
| F14 | Security | Actuator shared the API port, so network policies could not separate "API traffic" from "scrape/probe traffic". | **Management port = app port + 1000** (e.g. loan 8082 → 9082). Kubernetes **NetworkPolicies**: default deny; gateway → service API ports; services → their data stores; Prometheus → management ports | New ADR-0023, overview §8 & §12 |
| F15 | Security | One DB user did both DDL (Flyway) and DML (app). | Two users per service: `<svc>_owner` (runs Flyway) and `<svc>_app` (DML only, `statement_timeout` set) | ADR-0003, ADR-0017 |
| F16 | Observability | No correlation field in events; DLT records and replays could lose context; sampling policy undefined. | Envelope gains **`correlationId`** (trace ID of the originating request). Trace context is propagated even when a trace is not sampled. Sampling: 100% locally; in AWS 10% head sampling + keep all error traces (collector tail sampling) | ADR-0009, ADR-0016 |
| F17 | Resilience | Gateway circuit breaker relied on default time limiter behaviour (2025.1.3 also changed how the default `TimeLimiterConfig` is applied). | Every gateway route sets an **explicit** time limiter ≥ its response timeout. No resilience default is relied on | ADR-0014 |
| F18 | Build | `platform-security` must work for the **reactive** gateway and the **servlet** services. | Two conditional auto-configurations (`@ConditionalOnWebApplication(type = SERVLET / REACTIVE)`), tested separately | ADR-0002, repo tree |
| F19 | Versions | Spring Cloud 2025.1.x is built on Boot 4.0.x; 4.1 support is declared from 2025.1.2. | Keep Boot 4.1.x. Keep Spring Cloud's compatibility verifier on. Documented fallback: Boot 4.0.x (OSS to December 2026) if a 4.1-only incompatibility appears | ADR-0001, matrix |
| F20 | Simplicity | A Keycloak **bootstrap script** set secrets and passwords — extra moving part. | Keycloak realm import supports `${ENV_VAR}` placeholders (Keycloak docs). Realm JSON uses placeholders; `.env` supplies values. Script removed | ADR-0008, repo tree |

### P2 — missing requirements, added to phase acceptance criteria

| # | Area | Added requirement | Phase |
|---|---|---|---|
| F21 | Security | Pod Security "restricted": non-root, read-only root FS, drop all capabilities, `seccompProfile: RuntimeDefault`. Request-size limits and security headers at the gateway; CORS denied by default. Keycloak admin console not public; Keycloak in production mode in AWS. Encryption at rest for RDS, ElastiCache, EBS. GitHub Actions pinned by commit SHA. ECR tag immutability. Data-classification table. Audit log for officer decisions. **Threat model (STRIDE-lite)** document | P4, P7, P8 |
| F22 | Reliability | Graceful shutdown (`server.shutdown=graceful`, Kafka listeners stop first, `preStop` delay). Startup probes. PodDisruptionBudgets. HPA for gateway and loan-service. CPU/memory requests and limits; JVM `MaxRAMPercentage`. Hikari pool sized for virtual threads. DB `statement_timeout`. Producer `enable.idempotence=true`, `acks=all`. Consumer `cooperative-sticky` assignment. RDS automated backups. **Expand → migrate → contract** rule for schema changes during rolling deploys | P3, P5, P6, P7 |
| F23 | Observability | SLOs with burn-rate alerts (§3). Exemplars from metrics to traces. Kafka consumer-lag panel. Separate audit logger | P6 |
| F24 | Testing | Boot 4 test APIs (F06). Actuator exposure tests (only health on the app port). Migration test on an empty and on a previous-version schema. Optional Gatling smoke test to show rate limiting and bulkheads under load | P3, P6, P8 |
| F25 | Deployment | **ACM needs a domain you control** for HTTPS on the ALB — assumption added (fallback: import a self-signed cert, or demo via `kubectl port-forward`). MSK is slow to create compared with the rest of the stack and is often the largest cost line — Terraform variable `kafka_mode = "msk"` (default, as requested) or `"in-cluster"` (reuses the dev-only single-broker Kafka manifest from kind) for quick, cheap demos. EKS add-ons listed (AWS Load Balancer Controller, External Secrets, metrics-server) | P7 |
| F26 | Build | JaCoCo ≥ 0.8.14 (Java 25). Maven Enforcer: `requireJavaVersion [25,26)`, `requireMavenVersion [3.9,)`, `bannedDependencies`; **no** `dependencyConvergence` (too noisy with the Boot/Cloud BOMs) | P2 |
| F27 | Reliability | Email is a side effect outside the DB transaction, so a crash can send a duplicate email. Accepted and documented: notification row `PENDING → SENT`, dedupe by `eventId` before sending keeps the window tiny | P5 |
| F28 | Correctness | Idempotency request hash over raw JSON is fragile (key order, spaces). Hash the **canonical request fields** (`loanId|amount|currency`) instead | P5 |
| F29 | API | Repayment response codes were not defined for a slow provider. Now: `201` completed, `202` pending (finished by the executor), `422 PAYMENT_DECLINED` | P5 |
| F30 | Docs | Requirement "correlation IDs" mapped explicitly: W3C trace ID is the correlation ID (returned as `X-Trace-Id`, stored as `correlationId` in events) | P6 |
| F31 | Build | `e2e-tests` only in Maven profile `e2e`, so `./mvnw verify` never needs a running stack | P2 |
| F32 | Risk | springdoc + Spring HATEOAS on Boot 4's modular classpath: add `spring-boot-hateoas` explicitly; spike on day 1 of Phase 3 | P3 |

### Reviewed and kept (no change)

| Topic | Why it stays |
|---|---|
| Two Kafka APIs (Spring Kafka for loan/payment, Spring Cloud Stream for notification) | You asked for both "where appropriate". The boundary is clear: money paths need exact control; the pure consumer benefits from binder properties and function routing |
| Spring HTTP Service Clients instead of OpenFeign | OpenFeign docs: feature-complete, "suggest migrating over to Spring HTTP Service Clients". Still your call at confirmation |
| EKS as the primary AWS target | Same Helm chart in kind and AWS. Cost handled by short-lived environments |
| Loki + Alloy instead of ELK | Laptop memory. Logs stay in ECS format so ELK/OpenSearch is a swap |
| Choreography saga | Two participants. Orchestration would add a component without benefit |
| Eureka only in Compose | Book concept and `--scale` demo; Kubernetes already does discovery |

---

## 3. Every requested technology has a real job

| Requested | Use case in this design | Verdict |
|---|---|---|
| Spring Web MVC | REST APIs of 4 business services, on virtual threads | Meaningful |
| Spring Data JPA / Hibernate | Aggregates, optimistic locking, pagination | Meaningful |
| Spring Validation | Request DTOs, custom constraints (money, tenure) | Meaningful |
| Actuator | Probes, metrics, info — on a separate management port | Meaningful |
| Spring Cloud Config | One config source for 7 apps × 3 environments | Meaningful |
| Spring Cloud Gateway | Edge auth, routing, rate limits, circuit breakers | Meaningful |
| Eureka | Discovery and client-side balancing in Compose | Meaningful, **local only** |
| Spring Cloud LoadBalancer | `lb://` resolution with Eureka (gateway + HTTP clients) | Meaningful, **local only** |
| Spring Cloud OpenFeign | Replaced by Spring HTTP Service Clients (official recommendation) | **Replaced** — your call |
| Spring Cloud CircuitBreaker + Resilience4j | Customer calls, payment provider, gateway routes; semaphore bulkheads | Meaningful |
| Spring for Apache Kafka | Outbox relay, saga consumers, DLT handling in loan/payment | Meaningful |
| Spring Cloud Stream | notification-service consumers with binder retry/DLQ | Meaningful |
| Spring Cloud Function | Header-based routing of each event type to its own function | Meaningful |
| Spring Cloud Contract | HTTP contracts customer-service → loan/notification | Meaningful (events use golden examples, F04) |
| Spring Cloud Task / Spring Batch | No restartable chunk job exists yet | **Deferred** (ADR-0022) |
| Spring Security | Resource servers, method security, client credentials | Meaningful |
| Spring HATEOAS | Links that show which loan actions are allowed now | Meaningful |
| PostgreSQL | Four service databases + Keycloak | Meaningful |
| Redis (Valkey) | Gateway rate limiting + loan product catalogue cache | Meaningful, **reduced** (F09) |
| Kafka | Saga and notification events | Meaningful |
| Flyway | Per-service migrations with owner/app users | Meaningful |
| Keycloak / OAuth 2.0 / OIDC / JWT | Sign-in, roles, scopes, service tokens | Meaningful |
| Micrometer / Tracing / OpenTelemetry | Metrics and traces in every app | Meaningful |
| Prometheus / Grafana / Jaeger | Scrape, dashboards + alerts, trace search | Meaningful (optional profile) |
| ELK-style logging | Loki + Alloy with ECS JSON logs | Meaningful (**alternative** to ELK) |
| Docker / Compose | Local runtime with profiles | Meaningful |
| Kubernetes / Helm | kind and EKS from one chart | Meaningful (optional) |
| ECR, EKS, RDS, ElastiCache, MSK, Secrets Manager | AWS environment (MSK opt-in, F25) | Meaningful (optional) |
| GitHub Actions | CI, image build/scan, Terraform checks | Meaningful |
| Terraform | Repeatable, destroyable AWS environment | Meaningful (optional) |
| Jenkins | Comparison pipeline | Limited — optional by your request |

---

## 4. Maven multi-module review

| Question | Answer |
|---|---|
| Does the reactor order work? | Yes. Order: `contracts/event-contracts` → `libs/platform-security` → `libs/platform-outbox` → `config-server` → `discovery-server` → `customer-service` (attaches `stubs` jar) → `loan-service`, `payment-service`, `notification-service` → `api-gateway`. `tests/e2e-tests` only with `-Pe2e` |
| Can one service build alone? | Yes: `./mvnw -pl services/loan-service -am verify` builds its libs, the event contracts and customer-service (for stubs) |
| Shared code risk? | Limited to two infrastructure libs + one resources-only contracts module. No domain classes, no event classes, no DTOs shared |
| Plugin placement | `spring-boot-maven-plugin` (repackage, build-info) only in `services/*`. Libs are plain jars. `spring-cloud-contract-maven-plugin` only in customer-service |
| Version management | Parent inherits `spring-boot-starter-parent` 4.1.x and imports `spring-cloud-dependencies` 2025.1.x. Child POMs carry no versions except the few non-BOM libraries (springdoc, testcontainers-keycloak, ArchUnit) defined as parent properties |
| Guard rails | Enforcer: Java `[25,26)`, Maven `[3.9,)`, banned: Lombok, `spring-cloud-starter-openfeign`, `spring-cloud-sleuth-*`, `org.springframework.retry:*`, `junit:junit`, deprecated Boot 3 starter names; service→service only for `stubs` classifier in test scope |
| Build commands | `./mvnw -B verify` (unit + integration + contracts, needs Docker) · `./mvnw -B -Pe2e verify` (with running stack) · `./mvnw -B -DskipITs verify` (fast, no Docker) |

Conclusion: **maintainable and buildable** after F03, F04, F05, F06, F18, F26, F31.

---

## 5. Service level objectives (new, F23)

| SLI | SLO (local demo / AWS dev) | Alert |
|---|---|---|
| Gateway availability (non-5xx share) | ≥ 99.5% over 30 days | Burn rate > 14× for 5 min, > 6× for 30 min |
| `POST /loan-applications` latency | p95 < 800 ms | p95 > 800 ms for 10 min |
| Disbursement time (approved → disbursed) | p95 < 2 min | Loans `APPROVED` > 15 min ("stuck saga") |
| Outbox lag | < 5 s normal | > 60 s for 5 min |
| DLT records | 0 | Any record |

---

## 6. Sign-off

After applying the corrections above, the documents were re-checked together: all Mermaid diagrams render, all internal links resolve, and the names of ports, topics, clients, scopes, tables and modules match across documents (see the consistency check in the plan, §5). The design is **internally consistent** and ready for your confirmation.
