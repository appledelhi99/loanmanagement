# ADR-0002: Maven multi-module monorepo with minimal shared libraries

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F03 (stubs from the reactor), F04 (event-contracts module), F05 (no migrations in libs), F18 (servlet + reactive security), F31 (e2e profile)

## Context

You asked for one Git repository with a Maven multi-module build. Microservices must still be **independently deployable**. Shared libraries are the usual way services become secretly coupled.

## Decision

- One repo, one parent `pom.xml` that is both the **aggregator** and the **dependency/plugin manager** (inherits `spring-boot-starter-parent`, imports `spring-cloud-dependencies`).
- Deployables live in `services/*`. Each produces its own jar and image.
- Shared code lives in `libs/*` and is limited to **infrastructure code**:
  - `platform-security` — Keycloak role/scope mapping, issuer/audience validation, resource-server defaults, token-relay interceptor. Two conditional auto-configurations: **servlet** (services) and **reactive** (gateway).
  - `platform-outbox` — outbox entity, writer, single-active relay, metrics. **No Flyway migrations**: each service owns its tables.
- `contracts/event-contracts` is a **resources-only** module with one golden JSON example per event type and version. No classes.
- **No shared domain model, no shared event classes, no shared DTOs.** Each consumer owns its event DTOs.
- One project version (`1.0.0-SNAPSHOT`) for all modules to keep the build simple. Images are tagged with the Git commit SHA, so deploys are per service.
- Consumers of HTTP contracts declare a **test-scoped** dependency on `customer-service` with classifier `stubs` and use Stub Runner `stubsMode = CLASSPATH`. The reactor resolves the attached stubs jar after `package`, so `./mvnw verify` works without `install`.
- `tests/e2e-tests` is only in the `e2e` Maven profile.
- CI can build one service and what it needs: `./mvnw -pl services/loan-service -am verify`.

## Consequences

Good: one place to upgrade versions; easy for a reviewer to clone and run; atomic changes across a contract (producer + consumer in one PR).

Bad / costs: a monorepo makes it *easy* to couple services by accident → guarded by the module rules in [06-repository-structure](../architecture/06-repository-structure.md) and by Enforcer bans. A shared lib change can require rebuilding several services; libs are kept small and backward compatible.

## Alternatives considered

- **Repo per service** — more realistic for large orgs, but heavy for a portfolio (7+ repos, cross-repo contract publishing).
- **Shared `common` library with DTOs and events** — rejected: classic distributed-monolith trap.
- **Per-module versions** — adds release tooling with little value here.

## How we verify it

- Enforcer `bannedDependencies`: services may not depend on other `services/*` artifacts, except classifier `stubs` in test scope.
- A clean `./mvnw -B verify` from an empty `~/.m2` passes in CI (proves no hidden `install` step).
- ArchUnit in libs: no class in `libs/*` may reference `com.loanplatform.<service>` packages.
