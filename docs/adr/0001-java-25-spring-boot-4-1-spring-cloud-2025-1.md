# ADR-0001: Java 25 LTS, Spring Boot 4.1.x, Spring Cloud 2025.1.x

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F19 (built-on vs compatible-with note and fallback), F26 (JaCoCo ≥ 0.8.14)
- Deciders: Shiv (owner), Claude (proposer)

## Context

The book uses Spring Boot 2.x, Spring Cloud Hoxton and Java 11, all out of support. We need a set of versions that (a) are supported today, (b) are officially compatible with each other, and (c) stay supported while the portfolio is in use.

Facts checked on 2026-10-16 (sources in the [version matrix](../architecture/05-version-compatibility-matrix.md)):

- Spring Boot 4.1 is the current GA line (4.1.0 on 2026-06-10, 4.1.1 on 2026-08-20). It supports Java 17 up to Java 26.
- Spring Boot 3.5 OSS support ended in June 2026. Spring Boot 4.0 OSS support ends in December 2026.
- **Official compatibility table** (Spring Cloud project page): "2025.1.x aka Oakwood — 4.0.x, 4.1.x (Starting with 2025.1.2)". Latest GA 2025.1.3. The train is **built** on Boot 4.0.x (train docs: "Supported Boot Version: 4.0.8-SNAPSHOT") and **declared** compatible with 4.1.x.
- Spring Cloud 2026.0 "Paddington" exists only as M1, built on Boot 4.2.0-M2. Boot 4.2 is expected in November 2026.
- Java 25 is the newest LTS.

## Decision

- **Java 25 LTS** (Eclipse Temurin) for compiling and running.
- **Spring Boot 4.1.x** (newest patch at Phase 2) as the parent POM.
- **Spring Cloud 2025.1.x** (newest patch) imported as a BOM.
- No other framework versions are pinned by hand unless the BOMs do not manage them.
- Keep Spring Cloud's compatibility verifier enabled (do not set `spring.cloud.compatibility-verifier.enabled=false`).
- **Fallback:** if a Boot 4.1-only incompatibility appears in a Spring Cloud module, drop to the newest Boot 4.0.x (OSS to December 2026) and record it in an ADR.
- Build tooling that reads class files must support Java 25: JaCoCo ≥ 0.8.14.
- Upgrade to Boot 4.2 + Spring Cloud 2026.0 only when **both** are GA, recorded in a new ADR.

## Consequences

Good:
- Supported stack with the longest runway available today (Boot 4.1 OSS to mid-2027).
- Modern APIs: HTTP Service Clients, core retry, Jackson 3, JSpecify null-safety, OpenTelemetry starter, modular auto-configuration.
- Virtual threads with Java 25 work well with JDBC (no pinning on `synchronized` since JDK 24).

Bad / costs:
- Boot 4 is a big change from Boot 3 (modular starters, Jackson 3, Spring for Apache Kafka moved from Spring Retry to Spring Framework retry). Many blog posts and answers online are still Boot 3. We must read current docs.
- Some third-party libraries lag behind Boot 4 (see risks in the plan: springdoc + HATEOAS, Contract + Kafka).

## Alternatives considered

- **Boot 3.5 + Cloud 2025.0** — out of OSS support. Rejected.
- **Boot 4.0 + Cloud 2025.1** — supported only until December 2026. Rejected.
- **Wait for Boot 4.2 + Cloud 2026.0** — not GA yet; blocks progress. Rejected for now, planned as an upgrade.
- **Java 21** — valid fallback if a tool lacks Java 25 support.

## How we verify it

- Maven Enforcer: `requireJavaVersion [25,26)`, `requireMavenVersion [3.9,)`; bans deprecated Boot 3 starter names and Spring Retry.
- Phase 2 build prints the resolved dependency tree; versions are copied into the matrix.
- CI runs on Temurin 25.
