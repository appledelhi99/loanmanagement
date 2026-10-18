# ADR-0017: Centralised configuration and secrets

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F02 (no Spring Retry; fail fast), F13 (native backend everywhere, config baked for clusters), F15 (owner/app DB users), F20 (Keycloak placeholders)

## Context

Seven apps × three environments (Compose, kind, AWS) need consistent config. Secrets must never be in Git, logs or examples. Spring Cloud Config 5.0.5's client retry still needs Spring Retry, which is archived ("superseded by Spring Framework 7") and no longer managed by Boot 4.

## Decision

**Non-secret config → Spring Cloud Config Server, `native` backend in every environment.**

| Environment | Where config-server reads `config-repo/` |
|---|---|
| Local Compose | Folder mounted read-only (edit, restart a service, see the change) |
| kind / EKS | Files **baked into the config-server image** at build time — immutable and versioned with the release; no runtime dependency on GitHub |

- Clients: `spring.config.import=configserver:http://config-server:8888` **without** `optional:` → fail fast. **No client retry library.** Start order comes from the platform:
  - Compose: `depends_on: { config-server: { condition: service_healthy } }` + `restart: on-failure`.
  - Kubernetes: restart back-off; readiness keeps traffic away until the app is up.
- Profiles: `docker`, `kubernetes`, `aws` layered on `application.yml`.
- Each app's own `application.yml` holds only its name, ports, the config import and safe test defaults.

**Secrets → never through the config server.**

| Environment | Source | Delivery to the app |
|---|---|---|
| Local Compose | `.env` (git-ignored); `.env.example` with `CHANGE_ME` placeholders | Compose `env_file` → environment variables; Keycloak realm reads `${ENV}` placeholders |
| Tests | Generated per run by Testcontainers | `@ServiceConnection` / dynamic properties |
| kind | Kubernetes Secrets created from `.env` by a make target | `envFrom: secretRef` |
| AWS | **AWS Secrets Manager** | External Secrets Operator (EKS Pod Identity) → Kubernetes Secret → env vars |

Database credentials per service: `<SVC>_DB_OWNER_PASSWORD` (Flyway only, `spring.flyway.user/password`) and `<SVC>_DB_APP_PASSWORD` (runtime datasource).

**Guards:** Gitleaks in CI and pre-commit; Actuator `env`/`configprops` not exposed; startup check fails if a secret still equals `CHANGE_ME` outside the `test` profile; CI check that `config-repo/` contains no secret values, only `${...}` placeholders.

## Consequences

Good: one place to read config; no archived dependency; no GitHub dependency at runtime; secrets follow each platform's native path; config changes are versioned and reviewed.

Bad / costs: a config change in clusters needs a new config-server image (accepted: same as any release); config-server is a startup dependency (handled by health-ordered start and restarts); no live refresh (no Spring Cloud Bus — not required).

## Alternatives considered

- Spring Retry for config-client retry — archived; rejected.
- Git backend in clusters — runtime GitHub dependency and credentials; documented alternative.
- Kubernetes ConfigMaps only — loses the book concept and the Compose story.
- Spring Cloud Vault / `{cipher}` values — extra infra / still secrets in Git.

## How we verify it

- Config-server test: each service/profile resolves without missing placeholders.
- Compose test: stop config-server, start a service → it exits; start config-server → the service starts after a restart.
- CI: Gitleaks; `config-repo/` secret check; image test that config-server serves the baked files.
