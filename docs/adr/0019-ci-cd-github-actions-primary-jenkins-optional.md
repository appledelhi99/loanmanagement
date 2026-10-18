# ADR-0019: CI/CD — GitHub Actions primary, Jenkins optional

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F21 (actions pinned by SHA), F26 (JaCoCo version), build location

## Context

The book uses Jenkins. The repo will live on GitHub as a public portfolio. Integration tests need Docker (Testcontainers). Deploys to AWS must not use long-lived keys.

## Decision

**GitHub Actions** is the primary pipeline:

| Workflow | Trigger | Steps |
|---|---|---|
| `ci.yml` | PR, push to `main` | Temurin 25 + Maven cache → `./mvnw -B verify` (unit, `*IT` with Testcontainers, contract tests, ArchUnit, JaCoCo thresholds) → CycloneDX SBOM → test reports |
| `e2e.yml` | push to `main`, nightly, manual | Build images → `docker compose --profile core up -d --wait` → E2E suite → collect logs on failure → `down -v` |
| `images.yml` | push to `main`, tags | Buildx multi-arch images → Trivy scan (fail on HIGH/CRITICAL with a fix available) → push to GHCR; on release tags, push to ECR via **OIDC role** |
| `terraform.yml` | changes under `deploy/terraform` | `fmt -check`, `validate`, `tflint`, Trivy IaC; `plan` only when the AWS role is configured |
| `deploy-aws.yml` | manual (`workflow_dispatch`) | Environment `aws-dev` with required reviewer → Helm upgrade → smoke test |
| `codeql.yml`, `gitleaks.yml` | PR, schedule | Static analysis, secret scan |
| `dependabot.yml` | weekly | Maven, Actions, Docker base images |

Least privilege: `permissions:` set per job (default `contents: read`); OIDC trust limited to this repo and branch/environment; third-party actions **pinned by commit SHA** (Dependabot updates them).

Build location: Claude's cloud workspace cannot reach Maven Central (HTTP 403) and has no Docker daemon, so GitHub Actions (or your Mac) is where every build and test result in this project comes from.

**Jenkins** (`ci/jenkins/Jenkinsfile`): declarative pipeline with the same stages (build, test, image, scan), plus a short comparison doc. Not required to run.

## Consequences

Good: free hosted runners with Docker for public repos; no server to maintain; reviewers see the pipeline next to the code; no AWS keys in GitHub.

Bad / costs: GitHub-specific YAML; minutes limits on private repos; Jenkins file may drift (kept small).

## Alternatives considered

- Jenkins primary — needs a server and plugins; still common in enterprises, hence kept as optional.
- GitLab CI — fine, but the repo is on GitHub.
- AWS CodePipeline — AWS-only, more setup.

## How we verify it

Phase 7: green runs on a PR for `ci.yml`, `images.yml` (without push), `terraform.yml` (validate/lint). Phase 8: green `e2e.yml`.
