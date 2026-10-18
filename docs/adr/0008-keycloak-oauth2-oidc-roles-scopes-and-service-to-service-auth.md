# ADR-0008: Keycloak, OAuth 2.0 / OIDC, roles, scopes and service-to-service auth

- Status: Accepted (2026-10-18, confirmed by the owner)
- Date: 2026-10-16
- Review changes: F01 (issuer/JWKS split), F10 (officer token relay at approval), F20 (realm placeholders instead of a bootstrap script)

## Context

We need user sign-in, role-based rules for three kinds of people, and narrow machine permissions — with no passwords or secrets in Git. In Docker, the host reaches Keycloak as `localhost:8180` but containers reach it as `keycloak:8080`; a naive setup rejects every token because the issuer does not match.

## Decision

**Identity provider:** Keycloak 26.x, realm `loan-platform`, own database `keycloak_db`. Hostname set to the **public URL** (`http://localhost:8180` locally, the ALB host in AWS), so every token has the same `iss`.

**Token validation (gateway and every service):**

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${LMP_ISSUER_URI}        # public URL, only used to check iss
          jwk-set-uri: ${LMP_JWKS_URI}         # internal URL, keys are fetched here
          audiences: loan-platform-api
```

Spring Security 7.1 docs: with both set, the resource server "will not ping the authorization server at startup" and still validates `iss`. Only RS256 is trusted (the default).

**Realm roles (people):** `CUSTOMER`, `LOAN_OFFICER`, `ADMIN` → `ROLE_CUSTOMER`, `ROLE_LOAN_OFFICER`, `ROLE_ADMIN` (mapped by `platform-security` from `realm_access.roles`).

**Client scopes (machines):** `customer.contact.read` → `SCOPE_customer.contact.read`.

**Clients:**

| Client | Type | Flow | Notes |
|---|---|---|---|
| `loan-web` | public | Authorization Code + **PKCE (S256)** | For Postman/Bruno/browser sign-in. Redirect URIs limited to known tools and localhost |
| `notification-service` | confidential, service account | **Client Credentials** | Only scope `customer.contact.read`. Secret from `${LMP_NOTIFICATION_CLIENT_SECRET}` |
| `loan-dev-cli` | public, **dev only** | Direct access grant | Only in the local realm file, for scripts and E2E tests. **Not in the AWS realm** |

**Audience:** an audience mapper adds `loan-platform-api` to access tokens.

**Token settings:** access token 5 min, refresh token 30 min, RS256 keys rotated by Keycloak; resource servers cache JWKS.

**Service-to-service:**

| Call | Pattern | Why |
|---|---|---|
| loan → customer `GET /customers/me` (submit) | **Token relay** of the customer's JWT | Acts on behalf of the user; customer-service applies the user's own rights |
| loan → customer `GET /customers/{id}` (approve) | **Token relay** of the officer's JWT | Re-checks KYC at decision time with the officer's rights (rule R1b) |
| notification → customer `GET /customers/{id}/contact` | **Client credentials** | No user present (event-driven); least privilege via one scope |

**Where checks happen:** gateway (authentication + coarse path rules), each service (authentication again + `@PreAuthorize` + ownership checks such as `loan.applicantSubject == jwt.sub`).

**Secrets and dev users:** the realm JSON uses Keycloak's `${ENV_VAR}` placeholders (supported by realm import) for the client secret and the dev users' passwords. Values come from `.env` locally and from Secrets Manager in AWS. Keycloak skips import when the realm already exists, so restarts keep data.

**AWS:** Keycloak runs in production mode with TLS at the ALB; the admin console is not exposed publicly (port-forward only).

## Consequences

Good: standard OAuth 2.0 / OIDC; works the same in Compose, kind and AWS; IdP can be swapped (Cognito, Entra ID, Okta) by changing issuer, JWKS URL and claim mapping; both service-auth patterns are shown; no bootstrap script.

Bad / costs: Keycloak is a heavy container (~700 MB RAM); tokens are short-lived so scripts must refresh; one shared audience for the platform (per-service audiences are a documented hardening step); a dev-only direct grant exists and must never reach AWS.

## Alternatives considered

- Keycloak Spring adapters — deprecated.
- `issuer-uri` only — breaks inside Docker (issuer mismatch / discovery call to an unreachable host).
- Spring Authorization Server — would mean building and running our own IdP.
- mTLS between services instead of tokens — needs a PKI or mesh; production hardening.

## How we verify it

- Spring Security tests with `jwt()` for every endpoint × role (allowed and denied).
- Integration test with testcontainers-keycloak and the real realm file: token → API → expected status; wrong audience → `401`; wrong issuer → `401`; service token without scope → `403`.
- CI check: the AWS realm file contains no `directAccessGrantsEnabled: true` and no literal secrets (only `${...}`).
