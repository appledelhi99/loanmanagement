# 06 — Repository Structure (Maven multi-module)

Status: **Reviewed — final Phase 1 design** (revised after the [architecture review](08-architecture-review.md)). Folders marked *(Pn)* are created in that phase.

```text
loan-management-platform/
├── pom.xml                                  # (P2) parent + aggregator: Java 25, Boot 4.1 parent, Spring Cloud 2025.1 BOM,
│                                            #      plugin management, Enforcer rules (P8 adds profile `e2e` → tests/e2e-tests)
├── mvnw · .mvn/wrapper/                     # (P2) Maven Wrapper pinned to 3.9.11 (Windows: `mvn -N wrapper:wrapper` once)
├── .editorconfig · .gitattributes · .gitignore
├── .env.example                             # (P3) every variable with a placeholder value — real .env is git-ignored
├── README.md
│
├── contracts/
│   └── event-contracts/                     # (P3, grows in P5) resources-only jar, no Java code:
│                                            #      events/loan-events/LoanApproved.v1.json, …/payment-events/RepaymentReceived.v1.json, …
│                                            #      one golden example + field table per event type and version
│
├── libs/                                    # shared code — infrastructure only, never domain or event classes
│   ├── platform-security/                   # (P3, extended P4) two auto-configs (SERVLET and REACTIVE): Keycloak JWT → ROLE_/SCOPE_,
│   │                                        #      issuer + audience validation, actuator rules, bearer-token relay for HTTP clients
│   └── platform-outbox/                     # (P3 writer, P5 relay) OutboxEvent entity, OutboxWriter, single-active relay
│                                            #      (pg_try_advisory_xact_lock), traceparent capture/restore, metrics.
│                                            #      Code only — each service owns its own Flyway migration for the tables
│
├── services/
│   ├── config-server/                       # (P2) Spring Cloud Config Server, native backend; image bakes config-repo/
│   ├── discovery-server/                    # (P2) Eureka server (Compose profile only)
│   ├── customer-service/                    # (P3) layered; Spring Cloud Contract producer → attaches `stubs` jar
│   ├── loan-service/                        # (P3) hexagonal
│   ├── payment-service/                     # (P5) hexagonal; listener + payment executor + repayment ledger
│   ├── notification-service/                # (P5) layered + Spring Cloud Function consumers
│   └── api-gateway/                         # (P4) Spring Cloud Gateway Server WebFlux
│
├── tests/
│   ├── e2e-tests/                           # (P8) black-box tests against the running Compose stack (profile `e2e` only)
│   └── load-tests/                          # (P8, optional) Gatling smoke simulation
│
├── config-repo/                             # (P2) Spring Cloud Config source — NO secrets, only ${ENV} placeholders
│   ├── application.yml                      #      shared defaults (management port, tracing, logging, problem details)
│   ├── application-docker.yml               #      Compose: Eureka on, base URLs lb://<service>
│   ├── application-kubernetes.yml           #      clusters: Eureka off, base URLs http://<service>:<port>
│   ├── customer-service.yml · loan-service.yml · payment-service.yml · notification-service.yml · api-gateway.yml
│   └── <service>-<profile>.yml              #      overrides per environment where needed
│
├── deploy/
│   ├── docker/                              # (P2) one multi-stage Dockerfile for every service + healthcheck script
│   ├── compose/                             # (P2 start, P7 final) local runtime
│   │   ├── compose.yaml                     #      profiles: core, observability; health-ordered start, restart: on-failure
│   │   ├── postgres/init/                   #      one DB + <svc>_owner + <svc>_app users per service (passwords from env)
│   │   ├── kafka/create-topics.sh           #      topics, partitions, retention, DLTs
│   │   ├── keycloak/realm-loan-platform.json#      realm with ${ENV} placeholders for client secrets and dev users
│   │   └── observability/                   #      prometheus.yml + rules, otel-collector.yaml, alloy config, loki config,
│   │                                        #      grafana provisioning + dashboards/*.json (incl. SLO board)
│   ├── kind/                                # (P7) kind cluster config + dev-only infra manifests (also used by kafka_mode=in-cluster)
│   ├── helm/loan-platform/                  # (P7) one chart: Deployments, Services, NetworkPolicies, PDBs, HPAs, probes,
│   │                                        #      Pod Security "restricted"; values-local.yaml, values-aws.yaml
│   └── terraform/                           # (P7) AWS — never applied without approval
│       ├── modules/{network,eks,rds,elasticache,msk,ecr,secrets,iam-github-oidc,budget}/
│       └── envs/dev/                        #      main.tf, variables.tf (kafka_mode, region, domain), outputs.tf, backend.tf
│
├── ci/jenkins/Jenkinsfile                   # (P7) optional comparison pipeline
├── .github/
│   ├── workflows/                           # (P2 ci.yml, rest P7) actions pinned by commit SHA
│   │   ├── ci.yml                           #      build, unit, integration (Testcontainers), contracts, coverage, SBOM
│   │   ├── e2e.yml                          #      Compose stack + E2E suite
│   │   ├── images.yml                       #      multi-arch build, Trivy scan, push to GHCR (ECR on release, via OIDC)
│   │   ├── terraform.yml                    #      fmt, validate, tflint, Trivy IaC, plan (apply manual + approval)
│   │   ├── deploy-aws.yml                   #      manual, environment protection rules
│   │   └── codeql.yml · gitleaks.yml
│   └── dependabot.yml
│
└── docs/
    ├── architecture/                        # (P1) 01–08
    ├── adr/                                 # (P1) decision records
    ├── plan/                                # (P1) implementation plan + requirement checklist
    ├── api/                                 # (P8) exported OpenAPI specs per service
    ├── runbooks/                            # (P8) troubleshooting, DLT replay, local reset
    ├── threat-model.md                      # (P8) STRIDE-lite
    ├── security-checklist.md                # (P8)
    └── aws-deployment-guide.md              # (P7) with cost table
```

## Reactor build order

| # | Module | Depends on (compile / test) |
|---|---|---|
| 1 | `contracts/event-contracts` | — |
| 2 | `libs/platform-security` | — |
| 3 | `libs/platform-outbox` | — |
| 4 | `services/config-server` | — |
| 5 | `services/discovery-server` | — |
| 6 | `services/customer-service` | platform-security · attaches `customer-service-<v>-stubs.jar` |
| 7 | `services/loan-service` | platform-security, platform-outbox · test: `customer-service:stubs`, event-contracts |
| 8 | `services/payment-service` | platform-security, platform-outbox · test: event-contracts |
| 9 | `services/notification-service` | platform-security · test: `customer-service:stubs`, event-contracts |
| 10 | `services/api-gateway` | platform-security (reactive) |
| — | `tests/e2e-tests` | only with `-Pe2e` |

Build commands:

| Command | Runs | Needs |
|---|---|---|
| `./mvnw -B verify` | unit + integration (`*IT`) + HTTP and event contracts + ArchUnit + coverage | JDK 25, Docker |
| `./mvnw -B -DskipITs verify` | unit + contracts that need no containers | JDK 25 |
| `./mvnw -B -pl services/loan-service -am verify` | one service and what it needs | JDK 25, Docker |
| `./mvnw -B -Pe2e verify` | everything + E2E against the running Compose stack | stack up |

## Inside a hexagonal service (loan-service, payment-service)

```text
services/loan-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/com/loanplatform/loan/
    │   ├── LoanServiceApplication.java
    │   ├── domain/                  # pure Java: LoanApplication (aggregate), LoanStatus, Money, EmiCalculator,
    │   │                            # EligibilityPolicy, domain events, domain exceptions — no Spring, no JPA
    │   ├── application/
    │   │   ├── port/in/             # use-case interfaces: SubmitLoanApplication, ApproveLoanApplication, …
    │   │   ├── port/out/            # LoanApplicationRepository, CustomerDirectory, EventPublisher, ProductCatalog
    │   │   └── service/             # use-case implementations — the only place with @Transactional
    │   ├── api/                     # inbound web adapter: controllers, request/response records, HAL assemblers,
    │   │                            # Problem Details advice, OpenAPI annotations
    │   └── infrastructure/
    │       ├── persistence/         # JPA entities + Spring Data repositories + mappers (implement port/out)
    │       ├── messaging/           # Kafka consumers, event mappers, outbox adapter
    │       ├── client/              # @HttpExchange CustomerClient + adapter (implements CustomerDirectory)
    │       └── config/              # security, cache, Kafka, OpenAPI, HTTP client configuration
    ├── main/resources/
    │   ├── application.yml          # only: app name, config import, ports, safe test defaults
    │   └── db/migration/            # V1__create_loan_tables.sql, V2__create_outbox_tables.sql, V3__seed_loan_products.sql
    └── test/
        ├── java/…                   # unit (domain), slice (web, JPA), integration (*IT), contract consumers, ArchUnit
        └── resources/
```

Dependency rule (checked by ArchUnit): `api` and `infrastructure` → `application` → `domain`. `domain` depends on nothing outside the JDK.

## Inside a layered service (customer-service, notification-service)

```text
com.loanplatform.customer/
├── web/          # controllers + DTO records + exception advice
├── service/      # business logic, @Transactional
├── persistence/  # JPA entities + repositories (entities never leave this package as API types)
└── config/
```

Why two styles: customer and notification are supporting subdomains with little logic. Loan and payment hold the money rules, so the extra layer pays for itself ([ADR-0004](../adr/0004-hexagonal-for-core-services-layered-for-supporting-services.md)).

## Module rules

| Rule | Why |
|---|---|
| Services never depend on other services' modules — **except** a test-scoped dependency on a producer's `stubs` classifier (Spring Cloud Contract) | Independent build and deploy; stubs resolve inside the reactor without `install` |
| `libs/*` contain **infrastructure code only** (security wiring, outbox mechanics); no Flyway migrations | Shared domain or event classes would force lock-step releases; migrations belong to the service that owns the table |
| `contracts/event-contracts` contains **JSON files only** — no classes | It is the published language of the events; each consumer still owns its own DTOs (tolerant reader) |
| Each lib is a Boot auto-configuration with `@ConditionalOnMissingBean` | A service can override any part |
| Parent POM manages versions; child POMs list dependencies without versions | One place to upgrade |
| `spring-boot-maven-plugin` only in `services/*`; `spring-cloud-contract-maven-plugin` only in customer-service | Libraries stay plain jars |
| Enforcer bans: Lombok, `spring-cloud-starter-openfeign`, `spring-cloud-sleuth-*`, `org.springframework.retry:*`, `junit:junit`, deprecated Boot 3 starter names | Decisions are enforced, not only documented |
| groupId `com.loanplatform`, artifactId = folder name | Predictable names (rename before Phase 2 if you prefer your own groupId) |
