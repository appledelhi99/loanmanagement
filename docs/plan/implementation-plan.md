# Implementation Plan, Acceptance Criteria and Requirement Checklist

Status: **Phase 1 accepted by the owner on 2026-10-18. Phase 2 code written; first CI run pending (see Phase 2 report below).**

---

## 0. Where builds and tests will run (important)

Checked on 2026-10-16 inside Claude's cloud workspace:

| Check | Result |
|---|---|
| JDK | 21 installed (project targets 25 → CI and your machine use Temurin 25) |
| Maven | 3.9.11 installed |
| Maven Central (`repo.maven.apache.org`, `repo1.maven.org`) | **Blocked by network policy (HTTP 403)** |
| Docker daemon | **Not running** (Testcontainers cannot start here) |

So I **cannot compile or run tests in the cloud workspace.** **Owner decision (2026-10-18): builds and tests run on GitHub Actions.** The options that were considered:

| Option | What it gives | What you do |
|---|---|---|
| **A. GitHub Actions** — **chosen** | Full `verify` incl. Testcontainers, E2E with Compose, public proof for recruiters | Create the GitHub repo and let me push to it (or push yourself) |
| B. Your Mac via a linked folder | Fast feedback, real Docker | Connect a folder; JDK 25 + Docker Desktop/OrbStack/Colima with ≥ 8 GB RAM for Docker |
| C. Allow Maven Central in Claude's network settings | I can compile and run unit tests here | Add `repo.maven.apache.org` (and `repo1.maven.org`) to the allowed domains; Docker-based tests still need A or B |

Each phase report includes the real command output (or the CI run link) and says plainly if something was not run.

---

## 1. Phases and acceptance criteria

General "done" rule for every phase: compiles on Java 25; all tests of that phase pass; no `TODO`, `...` or placeholder code in delivered files; no secrets in Git; Boot 4 starter names only; docs, matrix and checklist updated; build output attached.

### Phase 1 — Architecture and technology

| Deliverable | Acceptance criteria | Status |
|---|---|---|
| Architecture, C4 context/container | Diagrams render; every container has owner, tech, data, API + management port | ✅ |
| Workflows + failure scenarios | All 10 required workflow steps mapped; failure catalogue | ✅ |
| Technology inventory, book mapping | Every item has a "why" and a "where"; "not used" list | ✅ |
| Version matrix | Boot ↔ Cloud ↔ Java pairing checked against the **official compatibility table**, dated | ✅ |
| Repository tree | Modules, reactor order, build commands, rules | ✅ |
| ADRs + trade-offs | 23 ADRs; 8 required trade-offs | ✅ |
| Architecture review | Findings prioritised; P0/P1 applied; P2 scheduled below | ✅ |
| **Owner confirmation** | Decisions D1–D7 ([overview §14](../architecture/01-architecture-overview.md#14-decisions-confirmed-by-the-owner-2026-10-18)) | ✅ 2026-10-18 |

### Phase 2 — Parent build, configuration and discovery

Deliverables: parent `pom.xml` (Boot 4.1.1 parent, Spring Cloud 2025.1.3 BOM, compiler `release 25`, Surefire/Failsafe, JaCoCo 0.8.15, Enforcer rules; the `e2e` profile is added in Phase 8 with its module), Maven Wrapper (`mvnw` + `.mvn/wrapper/maven-wrapper.properties`, Maven 3.9.11), `.gitignore`, `.gitattributes`, `.editorconfig`, `.dockerignore`, `config-server` (native backend; `config-repo/` packaged into the jar for clusters, mounted in Compose), `discovery-server`, base `config-repo/` (shared defaults, `docker` and `kubernetes` profiles), one generic multi-stage `Dockerfile` + `healthcheck` script, first `compose.yaml` (`core` profile, health-ordered start, hardened containers), `ci.yml` (build + Compose smoke test), `dependabot.yml`.

Moved by the Phase 2 design (no empty files): `.env.example` → Phase 3 (first secrets); `contracts/event-contracts` → Phase 3 (first events); CycloneDX SBOM → Phase 7 (image pipeline); action SHA pinning → Phase 7 (needs GitHub access to read SHAs); `mvnw.cmd` → not shipped (Windows users run `mvn -N wrapper:wrapper` once).

Acceptance criteria:
1. `./mvnw -B -ntp verify` passes on GitHub Actions from an empty Maven cache; output linked.
2. Enforcer fails the build on JDK ≠ 25, Maven < 3.9, and on banned dependencies: Lombok, OpenFeign, Sleuth, JUnit 4 (also transitive); `org.springframework.retry:*` and deprecated Boot 3 starter names (declared directly).
3. Config server tests: profile-specific source comes before the default source; `kubernetes` profile turns Eureka off; every file in `config-repo/` is served and parses; no literal secrets in `config-repo/`.
4. Discovery server test: a real Eureka client application registers and is found in the registry.
5. Actuator only on management ports (9888, 9761): API ports return `404` for `/actuator`; only `health` and `info` are exposed. (`/actuator/health` is not a valid probe on the config server's API port, because the config server treats any `/{name}/{profile}` path as a config request.)
6. CI Compose smoke test: `docker compose --profile core up -d --build --wait` → both containers healthy (non-root, read-only root filesystem); config served over HTTP; Eureka dashboard reachable.
7. Version matrix updated with the versions Maven resolved in CI.

### Phase 3 — customer-service and loan-service

Deliverables: both services (customer layered, loan hexagonal), Flyway migrations with `<svc>_owner`/`<svc>_app` users, validation, pagination, Problem Details, HAL in loan-service (+ `spring-boot-hateoas` for springdoc), `platform-security` (servlet + reactive auto-config: role/scope mapping, issuer + audience, token relay), `platform-outbox` **writer**, loan → customer HTTP Service Client (submit + approve), Spring Cloud Contract (producer + consumer, `stubs` classifier, `CLASSPATH` mode), product-catalogue cache, Dockerfiles, Compose entries (PostgreSQL + init script).

Acceptance criteria:
1. Day-1 spike: springdoc + Spring HATEOAS start together on Boot 4.1 (risk R2).
2. Migrations apply on an empty schema; `loan_app` cannot run DDL or connect to `customer_db`.
3. Domain tests: EMI maths (known values), every allowed and forbidden state transition, rules R1–R8 and R1b.
4. Web tests: role matrix for every endpoint (allowed + denied), `400` with `violations`, `422`/`409` codes, HAL links per status × role, no Actuator on the API port.
5. Integration test: approve writes loan update **and** outbox row in one TX; forced failure leaves neither.
6. Optimistic-lock test: concurrent approvals → one `409`.
7. Contract tests: producer verification in customer-service; consumer tests in loan-service resolve stubs inside `./mvnw verify` (no `install`).
8. Product cache: second read from Redis, change evicts, cache down → DB.
9. ArchUnit rules pass; coverage gates met; OpenAPI at `/v3/api-docs`.

### Phase 4 — Gateway, OAuth 2.0 / Keycloak, service-to-service

Deliverables: `api-gateway`, Keycloak realm file with `${ENV}` placeholders (local + AWS variants), issuer/JWKS split everywhere, gateway routes/security/rate limit/circuit breaker with explicit time limiters/fallback/edge hygiene, officer and customer token relay, client registration for `notification-service`, `customer.contact.read` endpoint, Redis in Compose.

Acceptance criteria:
1. Realm imports in Compose and in testcontainers-keycloak; a token fetched from the host validates inside containers (issuer test).
2. Gateway tests: `401` no token / wrong issuer / wrong audience, `403` wrong role, `200` right role, `429` after burst, `503` fallback within the route time limit, oversized body rejected, CORS denied.
3. Integration test with a real Keycloak token: customer gets own profile; officer approval re-checks KYC; service token with scope reads contact; without scope → `403`.
4. Gitleaks clean; AWS realm has no direct-grant client and no literal secrets.

### Phase 5 — Kafka, payment simulation, idempotency, outbox, failure handling

Deliverables: topic script, outbox **relay** (`pg_try_advisory_xact_lock`), `payment-service` (listener that only records work, payment executor, simulated provider, repayment ledger with row lock, repayment API with `Idempotency-Key` and `201/202/422` codes), loan-service consumers (saga steps, compensation, `remaining` mirror, `LoanClosed`), `notification-service` (Spring Cloud Stream + Function routing, Mailpit, `PENDING → SENT` log), DLT handling + replay command, golden examples for every event type/version in `contracts/event-contracts`.

Acceptance criteria:
1. W1–W6 each have an integration test (PostgreSQL + Kafka Testcontainers) that passes.
2. Duplicate event → one effect; 10 parallel calls with one `Idempotency-Key` → one payment; two parallel repayments exceeding `remaining` → one succeeds.
3. Kafka down → outbox lag grows → Kafka up → events delivered in commit order; two relay instances → one publishes.
4. Decline → `DISBURSEMENT_FAILED` + emails; timeout → executor completes within budget; aged `IN_FLIGHT` → completed exactly once.
5. Poison message → DLT with error headers; replay command re-publishes after the fix.
6. Golden-example tests pass for every producer and consumer of every event version.
7. Producer `enable.idempotence=true`, `acks=all`; consumers `cooperative-sticky` (config test).

### Phase 6 — Resilience, tracing, metrics, dashboards, logging

Deliverables: final resilience config + tests, OpenTelemetry tracing (sampling policy), Prometheus metrics on management ports incl. business metrics and exemplars, Grafana dashboards + SLO board + alerts as code, ECS JSON logs with PII masking and a separate audit logger, Alloy + Loki, OTel Collector + Jaeger, Compose `observability` profile, graceful shutdown.

Acceptance criteria:
1. Resilience tests: timeout, open/half-open, semaphore bulkhead full, retry only on idempotent GETs, explicit gateway time limiters.
2. Trace test: consumer span shares the HTTP request's trace ID through the outbox; executor span linked; `correlationId` in every log line.
3. Demo script: one trace in Jaeger covering gateway, loan, customer, payment, notification.
4. Dashboards load from files; alert and SLO rules valid (`promtool check rules`).
5. Logs are JSON with `trace.id`; no email/phone in logs (test).
6. Graceful shutdown test: in-flight request completes; Kafka listener stops first.

### Phase 7 — Compose, CI/CD, Kubernetes, Terraform, optional AWS

Deliverables: final `compose.yaml` (profiles, health checks, restart policies, resource limits), multi-arch Dockerfiles (non-root, read-only FS ready), GitHub Actions workflows, Jenkinsfile + comparison, Helm chart (NetworkPolicies, PDBs, HPAs, startup/liveness/readiness probes, Pod Security "restricted"), kind setup (policy-enforcing CNI), Terraform modules + `envs/dev` (`kafka_mode`, `domain`, budget alarm, encryption, ECR immutability, add-ons), AWS deployment guide with cost table.

Acceptance criteria:
1. `docker compose --profile core up -d --wait` → all healthy; measured memory for `core` reported (target under ~8 GB).
2. `helm lint`, `kubeconform`, Trivy config scan pass with no restricted-profile violations; optional kind smoke test passes; a NetworkPolicy test shows blocked and allowed paths.
3. `terraform fmt -check`, `validate`, `tflint`, Trivy IaC → clean. **No AWS resources created.**
4. GitHub Actions: `ci.yml`, `images.yml` (no push on PRs), `terraform.yml` green; actions pinned by SHA.
5. Cost guide lists every AWS line item (hourly and monthly) for both `kafka_mode` values and the "create → demo → destroy" path; domain/ACM assumption documented with fallback.

### Phase 8 — Full verification and final docs

Deliverables: full clean build, E2E suite, optional Gatling smoke test, consistency check, final README, troubleshooting guide, security checklist, **threat model (STRIDE-lite)**, exported OpenAPI specs, test report, completed checklist.

Acceptance criteria:
1. Fresh clone → documented commands → stack up → E2E green (output attached).
2. Consistency script: ports, topic names, event types, client IDs, scopes, config keys and module names match between code, `config-repo`, Compose, Helm and docs.
3. Every requirement in §2 is ✅ with a link to code/test, or marked as not done with a reason.

---

## 2. Requirement checklist

Legend: **D** = designed (Phase 1, reviewed) · **B** = built and tested · ⏳ = planned phase.

| Area | Requirement | D | B (phase) |
|---|---|---|---|
| Domain | customer-service | ✅ | ⏳ P3 |
| | loan-service (applications, approval workflow, status) | ✅ | ⏳ P3/P5 |
| | payment-service (simulation, records, ledger) | ✅ | ⏳ P5 |
| | notification-service (simulated email, consumers) | ✅ | ⏳ P5 |
| | api-gateway | ✅ | ⏳ P4 |
| | config-server | ✅ | ⏳ P2 |
| | discovery-server (where appropriate) | ✅ local only | ⏳ P2 |
| | DTOs, validation, exception handling, pagination, persistence, business rules | ✅ | ⏳ P3 |
| | No real bank / simulated payments | ✅ | ⏳ P5 |
| Spring | Boot, Cloud, MVC, Data JPA, Validation, Actuator | ✅ | ⏳ P2–P3 |
| | Config, Gateway, Eureka, LoadBalancer | ✅ | ⏳ P2/P4 |
| | OpenFeign | ✅ replaced by HTTP Service Clients (D1) | ⏳ P3 |
| | CircuitBreaker + Resilience4j | ✅ | ⏳ P4/P6 |
| | Cloud Stream + Spring Kafka | ✅ | ⏳ P5 |
| | Cloud Contract | ✅ HTTP; events via golden examples | ⏳ P3/P5 |
| | Cloud Function (meaningful use) | ✅ routing in notification-service | ⏳ P5 |
| | Batch / Task only if justified | ✅ deferred with reason | — |
| | Security, HATEOAS | ✅ | ⏳ P3/P4 |
| Data | PostgreSQL, DB per service, owner/app users | ✅ | ⏳ P3 |
| | Redis cache | ✅ Redis (product cache, rate limits) | ⏳ P3/P4 |
| | Kafka: DLT, retries, consumer groups, idempotency, versioning | ✅ | ⏳ P5 |
| | Flyway migrations | ✅ | ⏳ P3 |
| | Transaction boundaries documented | ✅ | ⏳ P3/P5 |
| Security | OAuth 2.0 / OIDC with Keycloak, JWT validation (issuer + audience) | ✅ | ⏳ P4 |
| | Roles CUSTOMER / LOAN_OFFICER / ADMIN; scopes | ✅ | ⏳ P3/P4 |
| | Checks at gateway and in services | ✅ | ⏳ P4 |
| | Service-to-service auth | ✅ relay + client credentials | ⏳ P3–P5 |
| | TLS for production, encryption at rest | ✅ | ⏳ P7 |
| | Secrets: env locally, Secrets Manager in cloud; none in Git | ✅ | ⏳ P2–P7 |
| | Network policies, Pod Security, management port | ✅ | ⏳ P2/P7 |
| Resilience | Circuit breaker, timeouts, bounded retries, bulkhead | ✅ | ⏳ P3–P6 |
| | Gateway rate limiting | ✅ | ⏳ P4 |
| | Correlation / trace propagation | ✅ | ⏳ P6 |
| | Idempotency keys for payments | ✅ | ⏳ P5 |
| | Saga | ✅ | ⏳ P5 |
| | Transactional outbox | ✅ | ⏳ P3/P5 |
| | Why no distributed TX / shared DB | ✅ explained | — |
| Observability | Micrometer, Micrometer Tracing, OpenTelemetry | ✅ | ⏳ P6 |
| | Prometheus, Grafana, Jaeger | ✅ | ⏳ P6 |
| | JSON logs, central logs (Loki — D3) | ✅ | ⏳ P6 |
| | Health / readiness / liveness / startup | ✅ | ⏳ P2–P7 |
| | SLOs and alerts | ✅ | ⏳ P6 |
| Infra | Docker, Compose | ✅ | ⏳ P2–P7 |
| | Kubernetes (Helm) | ✅ | ⏳ P7 |
| | AWS: ECR, EKS, RDS, ElastiCache, MSK, Secrets Manager | ✅ | ⏳ P7 (no apply) |
| | GitHub Actions, Terraform, Jenkins (optional) | ✅ | ⏳ P7 |
| | Local setup without AWS | ✅ | ⏳ P7 |
| Trade-offs | REST vs async, Eureka vs K8s, SCG vs managed, Feign vs HTTP clients, cache vs DB, sync TX vs saga, Stream vs Spring Kafka, ECS vs EKS | ✅ | — |
| Testing | JUnit, Mockito, integration, Testcontainers, REST, security, contracts, Kafka failures, idempotency, retries, CB, TX boundaries, E2E | ✅ | ⏳ P3–P8 |
| Quality | SOLID, clean code, DDD where justified, hexagonal, DTOs, explicit TX, consistent errors, safe logging, API versioning, OpenAPI, no Lombok, no `var` | ✅ | ⏳ P3–P8 |
| Repo | Maven multi-module, parent POM, minimal shared libs, buildable reactor | ✅ | ⏳ P2 |
| Docs | ADRs, C4, sequence diagrams, review | ✅ | — |
| | README, troubleshooting, security checklist, threat model, cloud guide with costs, OpenAPI files | ⏳ | ⏳ P7/P8 |
| Workflows | Required scenarios 1–10 | ✅ | ⏳ P4–P8 |

---

## 3. Risks and how we handle them

| # | Risk | Impact | Plan | Status after review |
|---|---|---|---|---|
| R1 | Cloud workspace cannot reach Maven Central and has no Docker | Cannot prove builds here | Build on GitHub Actions (owner decision) | Open — repository `appledelhi99/loanmanagement` created; waiting for the first push |
| R2 | springdoc + HATEOAS on Boot 4's modular classpath | Startup failure in loan-service | Add `spring-boot-hateoas` explicitly; day-1 spike in Phase 3 | Open — mitigated |
| R3 | Spring Cloud Contract has no documented Kafka integration | Event contracts | Golden examples in `contracts/event-contracts` (F04) | **Closed by design** |
| R4 | Laptop memory | Stack does not start | Compose profiles; `MaxRAMPercentage`; measured in Phase 7 | Open — mitigated |
| R5 | Boot 4.2 + Cloud 2026.0 GA soon | Upgrade work later | ADR when both GA; BOM-only change expected | Open |
| R6 | Kafka version local vs MSK | Behaviour drift | Pick a version MSK offers (Phase 5) | Open |
| R7 | Spring Cloud Stream function routing details with the Kafka binder | Notification design change | Spike at Phase 5 start; fallback = one consumer bean per topic that dispatches by `eventType` | Open — fallback ready |
| R8 | Exact property keys (HTTP Service Client groups, CB bulkhead, gateway time limiter) | Wrong config | Verify against current docs per file; tests prove behaviour | Open — mitigated |
| R9 | AWS costs | Unexpected bill | No apply without approval; budget alarm; `kafka_mode=in-cluster`; destroy after demo | Open — mitigated |
| R10 | Spring Cloud 2025.1.x built on Boot 4.0, declared for 4.1 (F19) | Edge incompatibility | Compatibility verifier on; fallback Boot 4.0.x | Open — fallback ready |
| R11 | Issuer mismatch between host and containers (F01) | Every token rejected | Issuer/JWKS split; issuer test in Phase 4 | **Closed by design** |
| R12 | Spring Retry archived (F02) | Config retry dependency | Fail fast + platform restarts | **Closed by design** |

---

## 4. Decisions received (2026-10-18)

D1 Spring HTTP Service Clients · D2 EKS · D3 Loki · D4 Redis · D5 `com.loanplatform` · D6 self-signed certificate for demos · D7 GitHub Actions. Recorded in [overview §14](../architecture/01-architecture-overview.md#14-decisions-confirmed-by-the-owner-2026-10-18) and the ADRs.

---

## 5. Consistency check performed on the corrected design (2026-10-16)

| Check | Method | Result |
|---|---|---|
| Diagrams | Every Mermaid block rendered with Mermaid CLI 11.4.2 (Chromium) | ✅ 14 of 14 render, 0 errors |
| Links | Every relative link and heading anchor resolved by script | ✅ 89 internal links in 34 files, 0 broken |
| Stale terms | Search for removed designs (simple discovery, Git backend in clusters, contact/customer cache, session advisory lock, bootstrap script, `docs/events`, `LoanRepaymentApplied`, reconciler, Boot 3 starter names, "Proposed") | ✅ Only intentional mentions remain (in "removed / not used / alternatives" text) |
| Names | Ports (API + management), topics, event types, client IDs, scopes compared across documents | ✅ One port per app everywhere (e.g. `customer-service:8081` ×5); only the 10 canonical event types; topics `loan-events`, `payment-events` + `.dlt`; `loan-web`, `notification-service`, `loan-dev-cli`, `customer.contact.read`, `loan-platform-api` used consistently |
