# ADR-0016: Observability — Micrometer, OpenTelemetry, Prometheus, Grafana, Jaeger, Loki

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F14 (scrape management port), F16 (correlationId, sampling), F23 (SLOs, exemplars, consumer lag)

## Context

We must trace one loan through five services and Kafka, see metrics and alerts, and search logs — on a laptop with limited RAM, and later in AWS. Spring Cloud Sleuth is gone; Micrometer Tracing replaces it.

## Decision

**Instrumentation (in every app):**

- `spring-boot-starter-opentelemetry` → Micrometer Tracing with the OpenTelemetry bridge, OTLP export of traces (`management.opentelemetry.tracing.export.otlp.endpoint`).
- `micrometer-registry-prometheus` → `/actuator/prometheus` on the **management port**; exemplars link metrics to traces. OTLP metric export is switched off to keep **one** metrics path (pull by Prometheus).
- Observations on: HTTP server/client, JDBC (via datasource observation), Kafka template and listeners, Spring Cloud Stream bindings, scheduled jobs (outbox relay, payment executor).
- Baggage field `customerId` is **not** propagated (privacy). Only trace context crosses services.
- **Correlation:** the W3C trace ID is the correlation ID — returned as `X-Trace-Id`, stored as `correlationId` in events and printed in every log line, even when the trace is not sampled.
- **Sampling:** 100% locally; in AWS 10% head sampling plus every error trace (OpenTelemetry Collector tail sampling).
- **Structured logging**: `logging.structured.format.console=ecs` (Elastic Common Schema JSON) with `trace.id` and `span.id`. No PII in logs (masking helper for email/phone).
- Health groups: `liveness` (process alive) and `readiness` (DB, Kafka, config ready).

**Backends:**

| Signal | Local (Compose `observability` profile) | AWS (Helm, in cluster) | Managed alternative |
|---|---|---|---|
| Traces | OTel Collector → **Jaeger v2** | same | OTel Collector → AWS X-Ray |
| Metrics | **Prometheus** → Grafana | kube-prometheus-stack | Amazon Managed Service for Prometheus + Managed Grafana |
| Logs | stdout → **Grafana Alloy** → **Loki** → Grafana | same | Fluent Bit → CloudWatch Logs, or Amazon OpenSearch |

**Dashboards and alerts as code** (`deploy/compose/observability/grafana/dashboards/*.json`): service RED metrics, JVM, HikariCP, Kafka consumer lag, outbox lag, DLT count, circuit breaker state, business KPIs (applications per status, disbursement success rate). Alert rules: DLT not empty, outbox lag > 60 s, error rate > 5%, circuit open > 1 min, stuck sagas, Kafka consumer lag. **SLOs** with multi-window burn-rate alerts (see the [review §5](../architecture/08-architecture-review.md#5-service-level-objectives-new-f23)).

## Consequences

Good: vendor-neutral (OTLP everywhere); one Grafana for metrics, logs and traces with links between them; local stack fits in ~1.5 GB extra RAM; ECS log format keeps ELK as a drop-in option.

Bad / costs: several tools to configure; Loki's query language (LogQL) is less known than Kibana; in-cluster observability on EKS uses node capacity.

## Alternatives considered

- **ELK / OpenSearch** — familiar, strong full-text search; Elasticsearch alone typically needs 1–2 GB+ RAM. Rejected for local, documented as a swap.
- **Zipkin** — fine; Jaeger v2 is built on the OTel Collector and accepts OTLP natively.
- **OpenTelemetry Java agent** — zero code, but bytecode instrumentation can mismatch library versions; Spring's starter avoids that.
- **Promtail** — end of life since 2 March 2026; Alloy replaces it.

## How we verify it

- Integration test asserts a Kafka consumer span shares the trace ID of the HTTP request that caused it (through the outbox).
- Log test: JSON log line contains `trace.id`.
- Phase 6 demo script: submit + approve → one trace with ≥ 6 services/spans visible in Jaeger; Grafana dashboard shows the counts.
