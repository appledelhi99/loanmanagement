# ADR-0021: API design — REST v1, Problem Details, OpenAPI, HATEOAS

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F29 (repayment response codes)

## Context

APIs must be consistent across services, easy to explore, and safe to evolve.

## Decision

**Shape**

- Base path with version: `/api/v1/...` (path versioning; simple for gateway routing and for clients). Spring Framework 7's built-in API versioning is noted as an option, not used.
- Plural nouns for resources (`/loan-applications`); domain **commands as sub-resources** with `POST` (`/loan-applications/{id}/approve`) — they map 1:1 to use cases and to HATEOAS links.
- JSON only. IDs are UUIDs. Times are ISO-8601 UTC (`Instant`). Money is `{"amount": "250000.00", "currency": "INR"}` (string decimal, avoids float errors in clients).
- `201 Created` + `Location` for creates; `200` for commands that return the updated resource; `202 Accepted` when a repayment is still pending at the provider (status at `GET /api/v1/payments/{id}`).

**Pagination:** `?page=0&size=20&sort=createdAt,desc`; max `size` 100; sort fields whitelisted. Response includes `page.number`, `page.size`, `page.totalElements`, `page.totalPages` (HAL `PagedModel` in loan-service; a `PageResponse<T>` record elsewhere).

**Errors — RFC 9457 Problem Details** (`application/problem+json`) everywhere, including the gateway:

```json
{
  "type": "https://problems.loanplatform.example/emi-exceeds-income-limit",
  "title": "EMI exceeds income limit",
  "status": 422,
  "detail": "Monthly EMI 32,450.00 INR is above 50% of declared income.",
  "instance": "/api/v1/loan-applications",
  "errorCode": "EMI_EXCEEDS_INCOME_LIMIT",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "violations": []
}
```

`violations` lists field errors for `400`. Messages never echo secrets or full PII.

**HATEOAS (loan-service):** `GET /loan-applications/{id}` returns HAL with links that depend on **status and caller role**: `self`, `customer`, and when allowed `review`, `approve`, `reject`, `withdraw`, `payments`. Clients can drive the workflow by following links instead of hard-coding rules.

**OpenAPI:** springdoc-openapi 3.1 per service (`/v3/api-docs`, Swagger UI in non-AWS profiles), security scheme = OAuth2 / bearer JWT. Specs exported to `docs/api/<service>.yaml` in CI and checked for unexpected changes.

**Headers:** `Idempotency-Key` (repayments), `X-Trace-Id` (responses), `Retry-After` (`503`), `X-RateLimit-Remaining` / `X-RateLimit-Burst-Capacity` / `X-RateLimit-Replenish-Rate` (`429`, added by the gateway's Redis rate limiter).

## Consequences

Good: one error format for every client; discoverable workflow; specs reviewers can read without running anything.

Bad / costs: HAL responses are more verbose; springdoc + HATEOAS on Boot 4's modular classpath needs `spring-boot-hateoas` explicitly (risk R2).

## Alternatives considered

- Header or media-type versioning — cleaner URLs, harder to route and test by hand.
- `PATCH /loan-applications/{id}` with `{"status": "APPROVED"}` — exposes the state field and hides intent; commands are clearer.
- GraphQL — not needed for these use cases.

## How we verify it

- Web-slice tests for each error code and for HAL links per status × role.
- CI diff on exported OpenAPI files.
