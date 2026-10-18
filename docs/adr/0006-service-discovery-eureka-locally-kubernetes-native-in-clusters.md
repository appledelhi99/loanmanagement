# ADR-0006: Eureka locally, Kubernetes-native discovery in clusters

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F12 (per-profile base URLs replace Spring Cloud simple discovery)

## Context

The book teaches Eureka. Kubernetes already has service discovery (Services + DNS + readiness). Running Eureka inside Kubernetes means two registries that can disagree. An earlier draft used Spring Cloud "simple discovery" in Kubernetes, which only mapped a name to one fixed URL — a layer that adds nothing.

## Decision

| Environment | Discovery | Base URL in config | Load balancing |
|---|---|---|---|
| Docker Compose (`docker` profile) | **Eureka** (`discovery-server`) | `lb://customer-service` | Spring Cloud LoadBalancer picks a registered instance |
| kind / EKS (`kubernetes` profile) | **Kubernetes Service DNS** | `http://customer-service:8081` | kube-proxy spreads connections across ready pods |

- The code is the same in both. Only `config-repo/application-docker.yml` and `application-kubernetes.yml` differ (HTTP Service Client group `base-url`, gateway route `uri`, `eureka.client.enabled`).
- Spring Cloud LoadBalancer docs: a group whose `base-url` does not use the `lb` scheme gets no load-balancer integration — exactly what we want in Kubernetes.
- `discovery-server` is not deployed to Kubernetes.
- Every app keeps the same API port in every environment, so URLs never surprise anyone.

## Consequences

Good: shows the Eureka pattern and client-side load balancing locally (`docker compose up --scale loan-service=2`); no extra moving part in the cluster; config says plainly where each call goes.

Bad / costs: in clusters, HTTP keep-alive connections may stick to one pod (connection-level balancing). Acceptable at this scale; the fix (Spring Cloud Kubernetes discovery or a mesh) is documented if needed.

## Alternatives considered

- Eureka everywhere — two sources of truth in Kubernetes.
- Spring Cloud simple discovery in Kubernetes — removed in review: indirection without benefit.
- Spring Cloud Kubernetes discovery client — per-pod client-side balancing; needs RBAC to read endpoints. Not needed now.
- No Eureka at all — loses a core book concept.

## How we verify it

- Compose smoke test with two `loan-service` replicas: requests reach both (instance id in a response header in the `docker` profile).
- Helm test in kind: services reach each other by Service DNS with Eureka disabled.
- Config test: every `base-url` in `application-kubernetes.yml` matches a Service name and port in the Helm chart.
