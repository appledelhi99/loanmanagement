# ADR-0018: AWS target — EKS primary with managed data services

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F21 (encryption, ECR immutability, admin console), F25 (domain for ACM, `kafka_mode`, add-ons)

## Context

You asked to pick ECS or EKS as primary, to keep AWS optional and cost-conscious, and to never provision without approval.

## Decision

**Primary runtime: Amazon EKS** (Kubernetes 1.37, standard support until 2027-12-01; never run on extended support, which raises the control-plane price from $0.10 to $0.60 per hour).

| Layer | Choice (dev environment) | Cost note |
|---|---|---|
| Region | `ap-south-1` (Mumbai) by default, variable | Close to you; prices differ slightly from us-east-1 |
| Network | VPC, 2 AZs, public + private subnets, **1 NAT gateway** | One NAT instead of one per AZ |
| Compute | EKS managed node group, 2 × small **Graviton (arm64)** nodes | Images are built multi-arch (amd64 + arm64) |
| Ingress | AWS Load Balancer Controller → ALB with HTTPS | One ALB for the gateway (plus a path/host rule for Keycloak sign-in). **TLS for demos uses a self-signed certificate** (owner decision, 2026-10-18): Terraform creates it with the `tls` provider and **imports it into ACM**; clients use the ALB DNS name and trust the generated CA (or `curl -k` for quick checks). With a real domain later, switch to an ACM-issued certificate by changing one variable |
| Database | RDS PostgreSQL 18, single-AZ, smallest burstable class, 20 GB gp3, encrypted, automated backups | All service DBs on one instance ([ADR-0003](0003-service-boundaries-and-database-per-service.md)) |
| Cache | ElastiCache for Redis OSS 7.1, single small node, encryption in transit and at rest | Owner chose Redis ([ADR-0015](0015-caching-with-redis.md)); costs a little more than Valkey |
| Kafka | `kafka_mode = "msk"` (default): Amazon MSK provisioned, 2 small brokers, TLS + IAM auth. `kafka_mode = "in-cluster"`: the dev-only single-broker manifest from kind | MSK is often the biggest line item and is slow to create; in-cluster mode suits a quick demo |
| Images | ECR, scan on push, **immutable tags**, lifecycle policy (keep last 10) | |
| Secrets | Secrets Manager + External Secrets Operator + EKS Pod Identity | |
| Keycloak | In cluster, production mode, DB on the same RDS instance; admin console not exposed publicly | |
| Cluster add-ons | AWS Load Balancer Controller, External Secrets Operator, metrics-server (for HPA) | Installed by Terraform/Helm |
| Observability | In-cluster Helm charts (same tools as local) | |

**Cost controls:**

1. Terraform for everything; **no `apply` without your explicit approval**. `plan` runs in CI; `apply` is a manual workflow with GitHub environment protection.
2. The environment is meant to be **short-lived**: create for a demo or recording, `terraform destroy` the same day.
3. AWS Budgets alarm (e.g. USD 20) created first (Terraform `budget` module).
4. Verified price points (2026-10-16): EKS $0.10/cluster-hour; MSK `kafka.m7g.large` $0.204/broker-hour, storage $0.10/GB-month, MSK Serverless $0.75/cluster-hour (us-east-1). The full monthly estimate, with every line item, is produced in Phase 7 with the AWS Pricing Calculator.

## Consequences

Good: same Helm chart in kind and EKS; Kubernetes is the most requested platform skill; managed data services remove ops work.

Bad / costs: EKS control-plane fee (~$73/month if left on); more moving parts than ECS (controller add-ons, node groups); MSK minimum broker count adds fixed cost.

## Alternatives considered

- **ECS on Fargate** — no control-plane fee, simpler, AWS-native secrets in task definitions. Better if the environment must run 24×7 on a small budget. Documented in the Phase 7 guide as the alternative.
- **EKS Auto Mode** — less node management; adds a management fee on top of EC2 (~12% in AWS's example). Optional switch.
- **Self-managed Kafka on EC2** — cheapest, most ops work.

## How we verify it

- `terraform validate`, `tflint`, Trivy IaC scan and `plan` in CI (no credentials needed for validate/lint; `plan` only with your approved role).
- After an approved apply: smoke test through the ALB (sign in → submit → approve → disbursed), then `destroy`.
