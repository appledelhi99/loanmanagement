# 07 — Key Trade-offs

Status: **Reviewed — final Phase 1 design** (revised after the [architecture review](08-architecture-review.md)). Each section: the options, what we chose, and when the other option would be better.

---

## 1. REST vs asynchronous messaging

| | REST (sync) | Kafka events (async) |
|---|---|---|
| Caller waits? | Yes | No |
| Coupling in time | Both must be up | Producer and consumer can be down at different times |
| Consistency | Immediate answer | Eventual |
| Failure handling | Timeouts, retries, circuit breakers | Retries, DLT, replay |
| Good for | Queries, validations the user waits for | Facts that happened ("loan approved"), fan-out to many consumers |

**Our rule:** use REST when the caller **cannot continue without the answer** (KYC check before accepting an application). Use events to **tell others that something happened** (approval, payment). The saga runs only on events, so a slow payment provider never blocks an officer's click.

**When the other is better:** if notification had to confirm delivery before the loan could move on, a sync call would be simpler. It does not, so async wins.

## 2. Eureka vs Kubernetes-native discovery

| | Eureka | Kubernetes Services + DNS |
|---|---|---|
| Runs where | Any (Compose, VMs) | Kubernetes only |
| Extra component | Yes (registry to run and secure) | No |
| Load balancing | Client side (Spring Cloud LoadBalancer) | kube-proxy (L4), or client side with Spring Cloud Kubernetes |
| Health-aware | Heartbeats | Readiness probes |

**Choice:** Eureka in Docker Compose (shows the book's pattern and client-side load balancing with `--scale`). In Kubernetes, Eureka is **switched off** and the base URLs change by profile: `lb://customer-service` in Compose, `http://customer-service:8081` (the Kubernetes Service) in clusters. Same code, different config. Running both in a cluster would be two sources of truth, and a discovery layer that resolves to one fixed URL adds nothing.

## 3. Spring Cloud Gateway vs a cloud-managed API gateway

| | Spring Cloud Gateway | AWS API Gateway (HTTP API) |
|---|---|---|
| Custom logic | Java filters, full control | Limited (Lambda authorizers, mapping) |
| Runs locally | Yes, same as prod | No (only emulators) |
| Cost | Pods you already pay for | Per request |
| Ops | You patch and scale it | Managed |
| Testing | Normal Spring tests | Harder |

**Choice:** Spring Cloud Gateway — same behaviour on a laptop and in AWS, and testable. In AWS an **ALB** sits in front for TLS and health checks. A managed API gateway would be better for public APIs that need usage plans, API keys for partners, or WAF-at-edge without running pods.

## 4. Declarative client (OpenFeign) vs Spring HTTP Service Clients vs direct HTTP clients

| | OpenFeign | Spring HTTP Service Clients | Direct `RestClient` |
|---|---|---|---|
| Style | Interface + annotations | Interface + `@HttpExchange` | Code per call |
| Project status | Feature-complete, bug fixes only | Core Spring Framework 7, actively developed | Core |
| Load balancer / circuit breaker | Spring Cloud integration | Spring Cloud 2025.1 group configurers (`LoadBalancer…GroupConfigurer`, `CircuitBreaker…GroupConfigurer`, `@HttpServiceFallback`) | `@LoadBalanced` builder, manual CB |
| OAuth2 | Interceptor | `@ClientRegistrationId` support in Spring Security 7 | Interceptor |
| Boilerplate | Low | Low | Medium |

**Choice:** HTTP Service Clients ([ADR-0005](../adr/0005-spring-http-service-clients-instead-of-openfeign.md)). Clients sit behind a port interface, so moving to OpenFeign (or back) touches one adapter class. Direct `RestClient` is better for one-off calls with unusual handling (streaming, multipart).

## 5. Redis caching vs database queries

| | Cache (Redis) | Database query |
|---|---|---|
| Latency | Sub-millisecond | Milliseconds |
| Freshness | Stale up to TTL | Always fresh |
| Failure mode | Cache down → fall back to DB | DB down → service down |
| Cost | Extra component | None |

**Choice:** cache only **read-heavy, rarely-changing, non-critical, non-personal** data — the loan product catalogue (TTL 1 h, evicted on change), read on every application and EMI quote. Customer data is **not** cached: primary-key reads are already fast and caching would copy PII into another store. **Never** cached: KYC status, idempotency keys, ledger values. If Redis is down, reads go to the DB (cache errors are logged, not thrown). The gateway also uses Redis for rate-limit buckets.

## 6. Synchronous (distributed) transactions vs saga and eventual consistency

**Why not one transaction across services (2PC / XA)?**

- Every participant must support XA and stay up for the whole commit. One slow service blocks all.
- Locks are held across the network → poor throughput and deadlock risk.
- Kafka does not join XA with PostgreSQL. Cloud managed services rarely support XA well.
- It couples services in time, the opposite of what microservices are for.

**Why not a shared database?**

- Any team can change a table another service reads → hidden coupling, risky migrations.
- One schema, one scaling unit, one blast radius.
- You lose the right to choose storage per service.

**Choice:** each service commits **one local transaction**. Cross-service work is a **saga** of local transactions linked by events, with a **compensating action** when a later step fails (W4 in [workflows](02-workflows.md)). The **outbox** makes "save + publish" atomic. **Idempotent consumers** make redelivery safe. The cost: a short window where loan is `APPROVED` but not yet `DISBURSED`. The API shows that state clearly.

Orchestration (a central saga coordinator, e.g. Temporal or a state table in loan-service) is better when a saga has **many steps, timeouts per step, or human tasks**. Ours has two participants, so choreography stays simpler.

## 7. Spring Cloud Stream vs Spring for Apache Kafka

| | Spring Cloud Stream (Kafka binder) | Spring for Apache Kafka |
|---|---|---|
| Abstraction | Binder-neutral bindings, functional beans | Kafka-native API |
| Retry / DLQ | Properties (`max-attempts`, `enable-dlq`) | `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, backoff objects |
| Control over headers, partitions, transactions | Less direct | Full |
| Testing | Test binder, plain function tests | Embedded / Testcontainers Kafka |

**Choice:** Spring for Apache Kafka in **loan-service and payment-service** (outbox relay, saga steps, exact error handling, DB + offset ordering). Spring Cloud Stream + Spring Cloud Function in **notification-service** — a pure consumer where binder properties and function routing remove code. This is a deliberate split, explained in [ADR-0010](../adr/0010-spring-kafka-for-core-services-spring-cloud-stream-for-notifications.md). Using one everywhere would be fine too; the split shows when each fits.

## 8. ECS vs EKS

| | Amazon ECS (Fargate) | Amazon EKS |
|---|---|---|
| Control-plane fee | None | $0.10 per cluster-hour (≈ $73 / month) |
| Learning curve | Low | Higher |
| Portability | AWS only | Kubernetes anywhere (kind locally) |
| Ecosystem | AWS-native | Helm, operators, External Secrets, Prometheus Operator |
| Same artefact local and cloud | No (task definitions) | Yes (same Helm chart) |
| Market signal for product companies | Good | Very strong |

**Choice:** **EKS primary** ([ADR-0018](../adr/0018-aws-target-eks-primary-with-managed-data-services.md)) because one Helm chart runs in kind and in AWS, and Kubernetes skills are what most product-company roles ask for. Cost is controlled by **short-lived environments** (`terraform apply` for a demo, `destroy` the same day). ECS Fargate is the better choice if the environment must stay up 24×7 on a small budget; the trade-off and a rough cost comparison go into the Phase 7 deployment guide.

---

## Summary table

| Topic | Chosen | Main reason |
|---|---|---|
| Sync vs async | Both, by rule | Ask when you must wait, tell when something happened |
| Discovery | Eureka local, K8s DNS in clusters | One registry per environment |
| Gateway | Spring Cloud Gateway (WebFlux) + ALB | Same locally and in AWS, testable |
| HTTP client | Spring HTTP Service Clients | Officially recommended successor to OpenFeign |
| Cache | Redis for the product catalogue and rate limits only | Speed without risking correctness or spreading PII |
| Consistency | Saga + outbox + idempotency | No distributed transactions, no shared DB |
| Kafka API | Spring Kafka (core), Cloud Stream (notifications) | Control where money moves, less code where it does not |
| AWS runtime | EKS | Portability and skills signal; cost via short-lived envs |
