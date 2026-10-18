# ADR-0023: Separate management port, network policies and workload hardening

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Origin: architecture review findings F14, F21, F22

## Context

In the first draft, Actuator shared each service's API port. Then the only thing keeping `/actuator/**` away from the internet was a gateway route rule, and Kubernetes network policies could not tell "API traffic" from "probe and scrape traffic". The design also lacked explicit pod hardening and graceful-shutdown rules.

## Decision

**1. Management port = API port + 1000**, same in every environment.

| App | API | Management |
|---|---|---|
| api-gateway | 8080 | 9080 |
| customer / loan / payment / notification | 8081 / 8082 / 8083 / 8084 | 9081 / 9082 / 9083 / 9084 |
| config-server / discovery-server | 8888 / 8761 | 9888 / 9761 |

- Management port exposes only `health` (with `liveness`, `readiness` groups — on by default in Boot 4), `info`, `prometheus`. Other endpoints off.
- API port serves no Actuator endpoints at all; a test proves it.

**2. Kubernetes NetworkPolicies (default deny, then allow):**

| From | To | Ports |
|---|---|---|
| Ingress (ALB / ingress namespace) | api-gateway | 8080 |
| api-gateway | business services | their API ports |
| loan-service, notification-service | customer-service | 8081 |
| all apps | config-server | 8888 |
| apps | their data stores, Kafka, Keycloak, OTel Collector | as needed |
| Prometheus (monitoring namespace) | all apps | management ports |
| kubelet | all apps | management ports (probes) |

**3. Workload hardening (Pod Security "restricted"):** `runAsNonRoot`, `readOnlyRootFilesystem: true` (writable `emptyDir` for `/tmp`), `allowPrivilegeEscalation: false`, `capabilities: drop [ALL]`, `seccompProfile: RuntimeDefault`. Requests and limits on every container; JVM sized with `-XX:MaxRAMPercentage`.

**4. Lifecycle:** `server.shutdown=graceful`; Kafka listener containers stop before the web server; `preStop` sleep so the load balancer drains; startup, liveness and readiness probes on the management port; PodDisruptionBudgets (`minAvailable: 1`); HPA for api-gateway and loan-service.

## Consequences

Good: operational endpoints are unreachable from outside by construction; network policies are simple to write and review; pods meet the restricted profile; rolling updates do not drop requests.

Bad / costs: one more port per app to configure; NetworkPolicies need a CNI that enforces them — Phase 7 checks kind's default CNI and installs Calico if needed; on EKS the Amazon VPC CNI's network-policy support is switched on.

## Alternatives considered

- Single port + Spring Security rules for Actuator — works, but one mistake exposes operational data, and network policies cannot separate the traffic.
- Service mesh authorization policies — powerful, heavy for this scope.

## How we verify it

- Web test: `GET /actuator/health` on the API port → `404`; on the management port → `200`.
- Helm test in kind (with a policy-enforcing CNI): a pod without the allowed label cannot reach customer-service; Prometheus can scrape.
- `kubeconform` + a policy check (e.g. Trivy config scan) report no restricted-profile violations.
