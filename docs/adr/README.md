# Architecture Decision Records

An ADR records one important decision: the problem, the choice, and what it costs us. ADRs are never edited after they are accepted. A new ADR **supersedes** an old one instead.

Status values: `Proposed` → `Accepted` → (`Superseded by ADR-xxxx` | `Deprecated`).

All ADRs were revised in the [architecture review](../architecture/08-architecture-review.md) and **accepted by the owner on 2026-10-18** (ADR-0015 was changed from Valkey to Redis at that point). From now on, a change needs a new ADR that supersedes the old one.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-java-25-spring-boot-4-1-spring-cloud-2025-1.md) | Java 25 LTS, Spring Boot 4.1.x, Spring Cloud 2025.1.x | Accepted |
| [0002](0002-maven-multi-module-monorepo-with-minimal-shared-libraries.md) | Maven multi-module monorepo with minimal shared libraries | Accepted |
| [0003](0003-service-boundaries-and-database-per-service.md) | Service boundaries and database per service | Accepted |
| [0004](0004-hexagonal-for-core-services-layered-for-supporting-services.md) | Hexagonal for core services, layered for supporting services | Accepted |
| [0005](0005-spring-http-service-clients-instead-of-openfeign.md) | Spring HTTP Service Clients instead of OpenFeign | Accepted |
| [0006](0006-service-discovery-eureka-locally-kubernetes-native-in-clusters.md) | Eureka locally, Kubernetes-native discovery in clusters | Accepted |
| [0007](0007-spring-cloud-gateway-webflux-at-the-edge.md) | Spring Cloud Gateway (WebFlux) at the edge | Accepted |
| [0008](0008-keycloak-oauth2-oidc-roles-scopes-and-service-to-service-auth.md) | Keycloak, OAuth 2.0 / OIDC, roles, scopes, service-to-service auth | Accepted |
| [0009](0009-kafka-event-model-topics-envelope-and-versioning.md) | Kafka event model: topics, envelope, versioning | Accepted |
| [0010](0010-spring-kafka-for-core-services-spring-cloud-stream-for-notifications.md) | Spring Kafka for core services, Spring Cloud Stream for notifications | Accepted |
| [0011](0011-transactional-outbox-with-polling-publisher.md) | Transactional outbox with a polling publisher | Accepted |
| [0012](0012-choreography-saga-for-loan-disbursement.md) | Choreography saga for loan disbursement | Accepted |
| [0013](0013-idempotency-for-payments-and-event-consumers.md) | Idempotency for payments and event consumers | Accepted |
| [0014](0014-resilience-with-resilience4j-and-spring-framework-retry.md) | Resilience with Resilience4j and Spring Framework retry | Accepted |
| [0015](0015-caching-with-redis.md) | Caching and rate-limit storage with Redis | Accepted |
| [0016](0016-observability-micrometer-opentelemetry-prometheus-grafana-jaeger-loki.md) | Observability: Micrometer, OpenTelemetry, Prometheus, Grafana, Jaeger, Loki | Accepted |
| [0017](0017-centralized-configuration-and-secrets.md) | Centralised configuration and secrets | Accepted |
| [0018](0018-aws-target-eks-primary-with-managed-data-services.md) | AWS target: EKS primary with managed data services | Accepted |
| [0019](0019-ci-cd-github-actions-primary-jenkins-optional.md) | CI/CD: GitHub Actions primary, Jenkins optional | Accepted |
| [0020](0020-testing-strategy-and-contract-testing.md) | Testing strategy and contract testing | Accepted |
| [0021](0021-api-design-rest-v1-problem-details-openapi-hateoas.md) | API design: REST v1, Problem Details, OpenAPI, HATEOAS | Accepted |
| [0022](0022-spring-batch-and-spring-cloud-task-deferred.md) | Spring Batch and Spring Cloud Task deferred | Accepted |
| [0023](0023-separate-management-port-and-network-policies.md) | Separate management port, network policies and workload hardening | Accepted |

## Template

```markdown
# ADR-NNNN: <short decision title>

- Status: Proposed | Accepted | Superseded by ADR-NNNN
- Date: YYYY-MM-DD
- Deciders: <names>

## Context
What problem are we solving? What forces and constraints apply?

## Decision
What we will do, stated plainly.

## Consequences
Good: …
Bad / costs: …

## Alternatives considered
Option — why not.

## How we verify it
Tests, metrics, or checks that prove the decision holds.
```
