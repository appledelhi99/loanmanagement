# 05 — Version Compatibility Matrix

| | |
|---|---|
| Verified on | **2026-10-16** — first pass in Phase 1, **re-verified in the [architecture review](08-architecture-review.md)** against the official Spring Cloud compatibility table |
| Method | Official Spring / vendor pages and release announcements (links below). Maven Central could not be queried from the build workspace (network policy returned `403`), so exact patch numbers are re-checked when Phase 2 resolves the BOMs |
| Rule | We pin **only the platform BOMs** (Spring Boot, Spring Cloud) and the few libraries they do not manage. Everything else comes from the BOMs, so the set stays mutually compatible |

Status key — **✅ Verified**: seen on an official source today · **🔒 BOM-managed**: version chosen by the Spring Boot / Spring Cloud BOM, confirmed by `mvn dependency:tree` in Phase 2 · **📌 Pin later**: image tag or tool version fixed in the named phase.

## 1. Core platform (the decision)

| Component | Selected | Status | Evidence | Notes |
|---|---|---|---|---|
| Java (JDK) | **25 LTS** (Eclipse Temurin) | ✅ | Spring Boot 4.1 system requirements: "Java 17 … compatible up to and including Java 26" [S1] | 25 is the newest LTS inside Boot 4.1's tested range. Do not build with a JDK newer than 26 on Boot 4.1 |
| Spring Boot | **4.1.x** — current GA **4.1.1** (2026-08-20) | ✅ | spring.io Spring Boot project page shows 4.1.1 as current GA [S36]; GitHub releases [S2]; blog [S3] | Use the newest 4.1.x patch at Phase 2. OSS support for 4.1 runs to mid-2027 (sources say June [S4] or July [S5] 2027) |
| Spring Framework | **7.0.x** (≥ 7.0.9) | ✅ 🔒 | Boot 4.1 system requirements [S1] | Brought in by Boot |
| Spring Cloud | **2025.1.x "Oakwood"** — current GA **2025.1.3** (2026-08-20) | ✅ | **Official compatibility table** on the Spring Cloud project page: "2025.1.x aka Oakwood — 4.0.x, 4.1.x (Starting with 2025.1.2)" [S33]; 2025.1.3 release post: "based on Spring Boot 4.0.x and is also compatible with the 4.1.x line" [S6] | Official pairing. Note: the train reference docs list "Supported Boot Version: 4.0.8-SNAPSHOT" [S35] — the train is **built** on 4.0 and **declared** compatible with 4.1. Fallback if a 4.1-only issue appears: Boot 4.0.x (OSS to December 2026). Keep Spring Cloud's compatibility verifier enabled |
| Maven | **3.9.x** via Maven Wrapper (3.9.11) | ✅ | Boot 4.1 needs Maven ≥ 3.6.3 [S1] | Wrapper pins it for everyone |
| Servlet container | Tomcat 11.0.x (Servlet 6.1) | ✅ 🔒 | Boot 4.1 system requirements [S1] | Business services |
| Reactive server | Reactor Netty | 🔒 | Boot BOM | Gateway only |

### Why not other combinations

| Option | Decision | Reason |
|---|---|---|
| Spring Boot 3.5 + Spring Cloud 2025.0 | ❌ | Boot 3.5 open-source support ended June 2026 [S4][S5]. A new project should not start on it |
| Spring Boot 4.0 + Spring Cloud 2025.1 | ❌ | Valid pair, but 4.0 OSS support ends December 2026 [S5]. 4.1 gives about 6 more months |
| Spring Boot 4.2 + Spring Cloud 2026.0 "Paddington" | ⏳ Later | Only milestones exist: Spring Cloud 2026.0.0-M1 (2026-09-24) is "based on Spring Boot 4.2.0-M2" [S8]. Boot 4.2 is expected in November 2026 [S9]. Upgrade after both are GA (tracked in [ADR-0001](../adr/0001-java-25-spring-boot-4-1-spring-cloud-2025-1.md)) |
| Java 21 LTS | Acceptable fallback | Fully supported by Boot 4.1. Choose it only if a tool in your environment lacks Java 25 support |

## 2. Spring Cloud modules (from the 2025.1 train)

| Module | Version in 2025.1.3 | Status | Used by |
|---|---|---|---|
| Spring Cloud Commons (LoadBalancer, HTTP Service Client integration) | 5.0.3 | ✅ [S6] | gateway, loan, notification |
| Spring Cloud Config | 5.0.5 | ✅ [S6] | config-server + all clients |
| Spring Cloud Gateway | 5.0.3 | ✅ [S6][S10] | api-gateway (`spring-cloud-starter-gateway-server-webflux`) |
| Spring Cloud CircuitBreaker (Resilience4j) | 5.0.3 | ✅ [S6] | gateway, loan, payment, notification |
| Spring Cloud Stream (Kafka binder) | 5.0.3 | ✅ [S6][S11] | notification-service |
| Spring Cloud Function | 5.0.4 | ✅ [S6] | notification-service |
| Spring Cloud Netflix (Eureka) | 5.0.x (5.0.2 listed in 2025.1.2) | ✅ [S7] | discovery-server + clients (local) |
| Spring Cloud Contract | 5.0.x (5.0.3 listed in 2025.1.2; 5.0.3 marked stable in docs) | ✅ [S7][S12] | **HTTP** contract tests only — no Kafka integration is documented in 4.2 or 5.0 [S12][S43]; events use golden examples |
| Spring Cloud OpenFeign | 5.0.3 | ✅ [S6] | **not used** — feature-complete [S13] |
| Spring Cloud Kubernetes | 5.0.3 | ✅ [S6] | not used |

## 3. Spring projects managed by Spring Boot 4.1

| Project | Version line | Status | Evidence / note |
|---|---|---|---|
| Spring Security | 7.1.x | ✅ 🔒 | Ships alongside Boot 4.1 [S9]; resource-server reference docs at 7.1.1 [S40] (`issuer-uri` + `jwk-set-uri` together, `audiences` property) |
| Spring for Apache Kafka | 4.1.x | ✅ 🔒 | Ships alongside Boot 4.1 [S9]. Jackson 3 classes: `JacksonJsonSerializer` etc. replace the deprecated Jackson 2 ones [S14]. `@RetryableTopic` now uses `@BackOff` (Spring Framework retry) [S14] |
| Spring Data (JPA, Redis) | 2026.0.x | 🔒 | Confirm in Phase 2 |
| Hibernate ORM | 7.x | 🔒 | Confirm in Phase 2 |
| Hibernate Validator | 9.1 | ✅ 🔒 | Boot 4.1 dependency upgrade [S9] |
| Flyway | 12.4.0 | ✅ 🔒 | Boot 4.1 dependency upgrade [S9]. Boot 4 needs `spring-boot-starter-flyway` [S15] + `flyway-database-postgresql` |
| Micrometer | 1.17 | ✅ 🔒 | Boot 4.1 dependency upgrade [S9] |
| Micrometer Tracing + OpenTelemetry | via `spring-boot-starter-opentelemetry` | ✅ 🔒 | Starter includes the OTel bridge and OTLP exporters [S16] |
| Spring HATEOAS | 3.1.x (GA 2026-06-08) | ✅ 🔒 | [S17]. Boot 4 is modular: also needs `spring-boot-hateoas` on the classpath when used with springdoc [S18] |
| Jackson | 3.x | 🔒 | Boot 4 baseline |
| JUnit | Jupiter 6.x | 🔒 | Boot 4 docs refer to JUnit 6 [S19]. Same Jupiter API as JUnit 5. Boot 4 removed `@MockBean`/`@SpyBean` (use `@MockitoBean`/`@MockitoSpyBean`) [S37] |
| Boot 4 starters | renamed + per-technology test starters | ✅ | Boot 4.0 migration guide [S37]: `spring-boot-starter-web` → `-webmvc`, `-oauth2-resource-server` → `-security-oauth2-resource-server`, `-oauth2-client` → `-security-oauth2-client`, `-aop` → `-aspectj`; Flyway needs `spring-boot-starter-flyway`; test starters `spring-boot-starter-<tech>-test`; Jackson 3 group `tools.jackson` |
| Spring Retry | **not used** | ✅ | README: "superseded by Spring Framework 7 and is now archived" [S38]; Boot 4 no longer manages its version [S37]. Spring Cloud Config 5.0.5 client retry still needs it [S39], so config clients **fail fast without retry** and the platform restarts them |
| Mockito, AssertJ, Awaitility | Boot-managed | 🔒 | Confirm in Phase 2 |
| Testcontainers | 2.0.x (2.0.5 seen on Central) | ✅ 🔒 | [S20]. Module names changed in 2.x (e.g. `testcontainers-postgresql`) |
| Resilience4j | 2.x (managed by Spring Cloud CircuitBreaker) | 🔒 | Spring Cloud 2026.0 moves to 2.4.0 [S8] |

## 4. Libraries we pin ourselves

| Library | Version | Status | Evidence |
|---|---|---|---|
| springdoc-openapi (`springdoc-openapi-starter-webmvc-ui`) | **3.1.x** | ✅ | 3.1.0 has "official support for Spring Boot 4.1.x" [S21] |
| testcontainers-keycloak (`com.github.dasniko`) | **4.4.0** (2026-10-01) | ✅ | Built on Testcontainers 2.x, default Keycloak 26.8 [S22] |
| ArchUnit | **1.4.1+** (1.5.0 available) | ✅ | 1.4.1: "Support Java 25 / class file major version 69" [S23] |
| JaCoCo | **≥ 0.8.14** (latest release 0.8.15, 2026-06-04) | ✅ | 0.8.14 (2025-10-11): "JaCoCo now officially supports Java 25" [S41] |
| CycloneDX Maven plugin, Enforcer, Failsafe | 📌 Phase 2 | — | Chosen when the parent POM is written (Failsafe/Enforcer versions come from the Boot parent) |

## 5. Runtime infrastructure (containers)

| Component | Version line | Status | Evidence / note |
|---|---|---|---|
| PostgreSQL | **18.x** | ✅ | Amazon RDS supports 18.4 (May 2026) [S24]. Local image tag 📌 Phase 3, same minor as RDS |
| Apache Kafka (KRaft) | **4.x** | ✅ | Latest upstream: 4.3.1 (2026-06-25), 4.2.2 (2026-09-29) [S25]. 📌 Phase 5: pick the newest version that Amazon MSK also offers, so local = cloud |
| Redis | **7.x line** (local) / **ElastiCache for Redis OSS 7.1** (AWS) | ✅ | ElastiCache supports "all Redis OSS versions 7.1 and before" [S44]. Local tag pinned in Phase 3 to match. Owner chose Redis over Valkey (2026-10-18) |
| Keycloak | **26.8.x** (26.8.1, Oct 2026) | ✅ | Keycloak blog [S27]. Realm import supports `${ENV}` placeholders and skips existing realms [S42]. 📌 Phase 4 |
| Mailpit | latest stable | 📌 Phase 5 | |
| OpenTelemetry Collector (contrib), Jaeger v2, Prometheus, Grafana, Loki, Alloy | current stable lines | 📌 Phase 6 | Promtail is EOL since 2026-03-02, so **Alloy** is the log collector [S28] |
| Base image | `eclipse-temurin:25-jre` (pinned by digest) | 📌 Phase 7 | |

## 6. Cloud and DevOps

| Component | Version | Status | Evidence |
|---|---|---|---|
| Amazon EKS Kubernetes | **1.37** (standard support to 2027-12-01) or 1.36 | ✅ | EKS version calendar [S29]. kind node image to match 📌 Phase 7 |
| EKS control-plane price | $0.10 per cluster-hour (standard), $0.60 (extended support) | ✅ | [S30] — always stay on a standard-support version |
| Terraform AWS provider | **6.x** (6.68.0, 2026-10-07) | ✅ | [S31] |
| Terraform CLI | 1.x, ≥ 1.11 (S3 native state locking) | 📌 Phase 7 | |
| Amazon MSK price examples (us-east-1) | `kafka.m7g.large` $0.204/broker-hour · storage $0.10/GB-month · Serverless $0.75/cluster-hour + $0.0015/partition-hour | ✅ | [S32] — used in the Phase 7 cost guide |
| Helm | 3.x / 4.x (chart `apiVersion: v2`) | 📌 Phase 7 | |
| GitHub Actions runners | `ubuntu-latest` + `actions/setup-java` (Temurin 25) | 📌 Phase 7 | Docker is available on hosted Linux runners, needed by Testcontainers |

## 7. Versions pinned in Phase 2 (2026-10-18)

| Item | Pinned value | Where |
|---|---|---|
| Spring Boot parent | 4.1.1 | `pom.xml` |
| Spring Cloud BOM | 2025.1.3 | `pom.xml` (`spring-cloud.version`) |
| Java release | 25 | `pom.xml` (`java.version`); Enforcer `[25,26)` |
| Maven | 3.9.11 | `.mvn/wrapper/maven-wrapper.properties`; Enforcer `[3.9,)` |
| JaCoCo | 0.8.15 | `pom.xml` |
| Build image | `eclipse-temurin:25.0.4.1_1-jdk-noble` [S45] | `deploy/docker/Dockerfile` |
| Runtime image | `eclipse-temurin:25.0.4.1_1-jre-noble` [S45] | `deploy/docker/Dockerfile` (digest pinning in Phase 7) |
| GitHub Actions | `actions/checkout@v7`, `actions/setup-java@v6`, `actions/upload-artifact@v7` [S47] | `.github/workflows/ci.yml` (SHA pinning in Phase 7) |

Everything else (Spring Framework, Spring Cloud modules, Jackson, JUnit, AssertJ, Awaitility, Tomcat, plugin versions) comes from the two BOMs. The exact resolved versions are written by CI to `target/dependency-tree.txt` (artifact `build-reports`) and copied here after the first green run.

## 8. How we keep this matrix true

1. Phase 2 runs `./mvnw -q dependency:tree` (on GitHub Actions or your Mac — Maven Central is blocked in Claude's cloud workspace) and `./mvnw versions:display-dependency-updates` and writes the resolved versions into this file.
2. Maven Enforcer fails the build on JDK ≠ 25 or Maven < 3.9.
3. Dependabot opens PRs for the Boot / Cloud BOMs, plugins, GitHub Actions and Docker base images.
4. When Spring Boot 4.2 **and** Spring Cloud 2026.0 are both GA, an ADR records the upgrade (or the reason to wait).

## Sources

- [S1] Spring Boot 4.1 — System Requirements: https://docs.spring.io/spring-boot/4.1/system-requirements.html
- [S2] spring-projects/spring-boot releases: https://github.com/spring-projects/spring-boot/releases
- [S3] Spring Boot 4.1.1 available now: https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/
- [S4] versionlog — Spring Boot 4.1: https://versionlog.com/spring-boot/4.1/
- [S5] Dan Vega — Spring Boot end of life: https://www.danvega.dev/blog/spring-boot-end-of-life
- [S6] Spring Cloud 2025.1.3 (Oakwood) released: https://spring.io/blog/2026/08/20/spring-cloud-2025-1-3-has-been-released/
- [S7] Spring Cloud 2025.1.2 (Oakwood) released: https://spring.io/blog/2026/06/11/spring-cloud-2025-1-2-aka-oakwood-has-been-released/
- [S8] Spring Cloud 2026.0.0-M1 (Paddington): https://spring.io/blog/2026/09/24/spring-cloud-2026-0-0-M1-has-been-released/
- [S9] InfoQ — Spring Boot 4.1: https://www.infoq.com/news/2026/06/spring-boot-4-1/
- [S10] Spring Cloud Gateway project page: https://spring.io/spring-cloud-gateway and RequestRateLimiter docs: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html
- [S11] Spring Cloud Stream Kafka binder DLQ: https://docs.spring.io/spring-cloud-stream/reference/kafka/kafka-binder/dlq.html
- [S12] Spring Cloud Contract messaging: https://docs.spring.io/spring-cloud-contract/reference/5.0-SNAPSHOT/project-features-messaging.html
- [S13] Spring Cloud OpenFeign reference (feature-complete note): https://docs.spring.io/spring-cloud-openfeign/reference/
- [S14] Spring for Apache Kafka change history: https://docs.spring.io/spring-kafka/reference/4.1/appendix/change-history.html
- [S15] OpenRewrite — add spring-boot-starter-flyway (Boot 4): https://docs.openrewrite.org/recipes/java/spring/boot4/addspringbootstarterflyway
- [S16] OpenTelemetry with Spring Boot: https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/
- [S17] Spring HATEOAS 3.1 GA: https://spring.io/blog/2026/06/08/spring-hateoas-3-1-GA-3-0-7-and-2-5-3-released/
- [S18] springdoc-openapi issue #3238: https://github.com/springdoc/springdoc-openapi/issues/3238
- [S19] Spring Boot 4.0 testing docs: https://docs.spring.io/spring-boot/4.0/reference/testing/index.html
- [S20] testcontainers-postgresql on Maven Central: https://central.sonatype.com/artifact/org.testcontainers/testcontainers-postgresql
- [S21] start.spring.io issue #2260 (springdoc 3.1.0 / Boot 4.1): https://github.com/spring-io/start.spring.io/issues/2260
- [S22] dasniko/testcontainers-keycloak releases: https://github.com/dasniko/testcontainers-keycloak/releases
- [S23] ArchUnit 1.4.1 release: https://newreleases.io/project/github/TNG/ArchUnit/release/v1.4.1
- [S24] Amazon RDS for PostgreSQL 18.4 etc.: https://aws.amazon.com/about-aws/whats-new/2026/05/amazon-rds-postgresql/
- [S25] Apache Kafka blog: https://kafka.apache.org/blog/
- [S26] ElastiCache version 8.0 for Valkey: https://aws.amazon.com/blogs/database/amazon-elasticache-version-8-0-for-valkey-brings-faster-scaling-and-improved-memory-efficiency/
- [S27] Keycloak blog archive: https://www.keycloak.org/blog-archive
- [S28] Grafana Loki — Promtail (EOL notice): https://grafana.com/docs/loki/latest/clients/promtail/
- [S29] Amazon EKS Kubernetes version lifecycle: https://docs.aws.amazon.com/eks/latest/userguide/kubernetes-versions.html
- [S30] Amazon EKS pricing: https://aws.amazon.com/eks/pricing/
- [S31] terraform-provider-aws releases: https://releases.sh/hashicorp/terraform-provider-aws
- [S32] Amazon MSK pricing: https://aws.amazon.com/msk/pricing/
- [S44] ElastiCache supported engines and versions: https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/supported-engine-versions.html
- [S45] Eclipse Temurin image tags (25-jdk-noble, 25-jre-noble, 25.0.4.1_1): https://hub.docker.com/_/eclipse-temurin/tags
- [S46] Spring Boot 4.1 Dockerfiles (jarmode tools extract --layers): https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html
- [S47] GitHub Actions releases: checkout v7 https://github.com/actions/checkout/releases · setup-java v6 https://github.com/actions/setup-java/releases · upload-artifact v7 https://github.com/actions/upload-artifact/releases
- [S33] Spring Cloud project page (official release-train / Boot compatibility table): https://spring.io/projects/spring-cloud/
- [S34] Spring Cloud Supported Versions wiki: https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions
- [S35] Spring Cloud train reference docs (2025.1.3): https://docs.spring.io/spring-cloud-release/reference/index.html
- [S36] Spring Boot project page: https://spring.io/projects/spring-boot#support
- [S37] Spring Boot 4.0 Migration Guide: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- [S38] Spring Retry README (archived): https://github.com/spring-attic/spring-retry/blob/main/README.md
- [S39] Spring Cloud Config client docs (5.0.5): https://docs.spring.io/spring-cloud-config/reference/client.html
- [S40] Spring Security resource server JWT docs (7.1.1): https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- [S41] JaCoCo change history: https://www.jacoco.org/jacoco/trunk/doc/changes.html
- [S42] Keycloak import and export: https://www.keycloak.org/server/importExport
- [S43] Spring Cloud Contract 4.2 messaging (no Kafka integration listed): https://docs.spring.io/spring-cloud-contract/reference/4.2/project-features-messaging.html
- Also used: Spring HTTP Service Client docs https://docs.spring.io/spring-boot/reference/io/rest-client.html · Spring Cloud LoadBalancer docs https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/loadbalancer.html · Spring Cloud CircuitBreaker docs https://docs.spring.io/spring-cloud-commons/reference/5.0/spring-cloud-circuitbreaker.html · Spring Framework 7 resilience docs https://docs.spring.io/spring-framework/reference/7.0/core/resilience.html · HTTP Service Client enhancements https://spring.io/blog/2025/09/23/http-service-client-enhancements/
