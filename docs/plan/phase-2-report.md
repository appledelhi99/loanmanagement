# Phase 2 Report — Parent Build, Configuration and Discovery

| | |
|---|---|
| Date | 2026-10-18 |
| Status | **Code complete. Maven build, tests and Docker build not yet run — they run on GitHub Actions (owner decision D7) in [appledelhi99/loanmanagement](https://github.com/appledelhi99/loanmanagement) after the first push.** |
| Owner decisions applied | D1 HTTP Service Clients · D2 EKS · D3 Loki · D4 **Redis** · D5 `com.loanplatform` · D6 self-signed cert for demos · D7 **GitHub Actions** |

## 1. Files created or changed

### Build and repository

| File | Purpose |
|---|---|
| `pom.xml` | Parent + aggregator. Boot 4.1.1 parent, Spring Cloud 2025.1.3 BOM, Java 25, Enforcer rules, Failsafe, JaCoCo 0.8.15 |
| `mvnw` | Maven launcher: installs Maven 3.9.11 once, then runs it. Download via curl, wget or a Java fallback; optional SHA-256 check |
| `.mvn/wrapper/maven-wrapper.properties` | Maven version (3.9.11) |
| `.gitignore`, `.gitattributes`, `.editorconfig`, `.dockerignore` | Repository hygiene; LF line endings for scripts; small Docker build context |

### Configuration

| File | Purpose |
|---|---|
| `config-repo/application.yml` | Shared defaults for every config client: virtual threads, Problem Details, graceful shutdown, Actuator exposure, tracing sampling, Eureka off by default |
| `config-repo/application-docker.yml` | Compose: Eureka on (`discovery-server:8761`), ECS JSON logs |
| `config-repo/application-kubernetes.yml` | Clusters: Eureka off, ECS JSON logs |

### config-server

| File | Purpose |
|---|---|
| `services/config-server/pom.xml` | Spring Cloud Config Server + Actuator; packages `config-repo/` into the jar under `/config-repo` |
| `.../ConfigServerApplication.java` | `@EnableConfigServer` application |
| `.../resources/application.yml` | `native` profile always included, `classpath:/config-repo/` by default, API 8888, management 9888 |
| `.../resources/application-docker.yml` | ECS JSON logs when running in a container |
| `.../test/.../ConfigServerApplicationTest.java` | 6 tests against the running server (see §4) |
| `.../test/.../ConfigRepositoryHygieneTest.java` | 3 tests: required files exist, YAML parses, no literal secrets |

### discovery-server

| File | Purpose |
|---|---|
| `services/discovery-server/pom.xml` | Eureka server + Actuator; Awaitility for tests |
| `.../DiscoveryServerApplication.java` | `@EnableEurekaServer` application |
| `.../resources/application.yml` | Standalone registry, API 8761, management 9761, fast eviction for local use |
| `.../resources/application-docker.yml` | ECS JSON logs |
| `.../test/.../DiscoveryServerApplicationTest.java` | 3 tests (see §4) |
| `.../test/.../testsupport/eureka/EurekaTestClientApplication.java` | A small real Spring Boot app that registers like a service |

### Containers and CI

| File | Purpose |
|---|---|
| `deploy/docker/Dockerfile` | One multi-stage recipe for every service: build with `mvnw`, split Boot layers, run on the Temurin 25 JRE as user 10001 |
| `deploy/docker/healthcheck.sh` | Health check without curl: HTTP request over bash `/dev/tcp`, success only on status 200 |
| `deploy/compose/compose.yaml` | `core` profile: both servers, read-only root filesystem, no capabilities, `no-new-privileges`, health checks, restart on failure |
| `.github/workflows/ci.yml` | Job 1: `./mvnw -B -ntp verify` + reports. Job 2: Compose smoke test (build images, start, check config, Eureka, Actuator not on API ports, non-root user) |
| `.github/dependabot.yml` | Weekly updates for Maven, GitHub Actions and Docker base images |

### Documentation updated

ADR-0015 rewritten for **Redis**; ADR-0018 for the **self-signed certificate**; all ADRs set to **Accepted (2026-10-18)**; architecture overview §14 records D1–D7; version matrix §7 lists the Phase 2 pins; repository tree and plan aligned with what Phase 2 actually ships; README has a "Getting started" section.

## 2. Design decisions in this phase

| Decision | Why |
|---|---|
| Config is **packaged into the config-server jar** (`classpath:/config-repo/`) and **mounted** in Compose | Clusters get immutable, versioned config without a Git dependency at runtime; locally an edit needs only a restart (ADR-0017) |
| `spring.profiles.include: native` instead of relying on `SPRING_PROFILES_ACTIVE` | The native backend can never be switched off by accident when someone sets another profile |
| discovery-server is **not** a config client | It must start first and stay simple; it has nothing environment-specific except its hostname |
| One generic **Dockerfile** with `--build-arg MODULE=...` | Same hardening and layering for every service; less copy-paste |
| Image builds Maven **inside** Docker (with a BuildKit cache) | `docker compose up --build` works from a clean checkout with only Docker installed |
| Health check script instead of installing curl | The runtime image stays minimal; the check only needs bash, which the JRE image has |
| Our own small `mvnw` | The official wrapper scripts are generated by a Maven plugin, which cannot be downloaded in this workspace. The script is short, tested here, and can be replaced by the official one with one command |
| Enforcer: **transitive** bans for Lombok, JUnit 4, OpenFeign, Sleuth; **direct-only** bans for Spring Retry and Boot 3 starter names | Our POMs must not declare them; framework modules may still reference some internally, which must not break the build |
| Tests use plain `RestClient` and `@Value("${local.server.port}")` | Avoids Boot 4 test-module package moves (`@LocalServerPort`, `TestRestTemplate`) that could not be compile-checked here |
| API-port check uses `/actuator`, not `/actuator/health` | The config server treats any `/{name}/{profile}` path as a config request, so `/actuator/health` on port 8888 returns config for an app named "actuator" |
| Moved out of Phase 2: `.env.example` (P3), `contracts/event-contracts` (P3), CycloneDX SBOM (P7), SHA-pinned actions (P7), `mvnw.cmd` (not shipped) | No empty or placeholder files; SHA pinning needs GitHub access to read real commit SHAs |

## 3. Commands

```bash
./mvnw -B verify                                                     # build + all tests
./mvnw -B -pl services/config-server -am verify                      # one module
docker compose -f deploy/compose/compose.yaml --profile core up -d --build --wait
curl -s http://localhost:8888/loan-service/docker                    # served config
curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps
docker compose -f deploy/compose/compose.yaml --profile core down
```

## 4. Tests written

| Test | What it proves |
|---|---|
| `servesTheProfileSourceBeforeTheSharedDefaults` | `/loan-service/docker` returns exactly two sources, `application-docker.yml` first |
| `dockerProfileTurnsEurekaOnAndKubernetesProfileTurnsItOff` | Profile precedence: docker → `true`, kubernetes → `false`, default → `false` |
| `sharedDefaultsReachEveryClient` | Virtual threads and Actuator exposure come from `application.yml` |
| `servesEveryProfileFileOfTheRepository` | Every `application-<profile>.yml` in the repo folder is served from the **packaged** copy (proves the packaging step) |
| `actuatorIsOnlyOnTheManagementPort` (config) | Liveness and readiness return `200` on the management port; `/actuator` is `404` on the API port |
| `exposesOnlyHealthAndInfo` | `env`, `configprops`, `beans` are `404` |
| `containsTheSharedFiles`, `everyFileIsValidYaml`, `secretsAreOnlyEnvironmentPlaceholders` | config-repo hygiene (ADR-0017) |
| `registersAServiceAndRemovesItWhenTheServiceStops` | A real Eureka client registers with status `UP` and disappears after shutdown |
| `servesTheRegistryAsJsonOnTheApiPort` | `/eureka/apps` answers JSON on 8761 |
| `actuatorIsOnlyOnTheManagementPort` (discovery) | Same split for the registry |

## 5. Verification actually performed (in Claude's workspace)

| Check | Tool | Result |
|---|---|---|
| POM structure | Maven 3.9.11's own strict model reader (`MavenXpp3Reader`, strict mode) | ✅ 3 POMs valid |
| POM XML | `xmllint --noout` | ✅ |
| Java syntax | `javac` parser (JDK 21, syntax only — no classpath) | ✅ 6 files, 0 syntax errors |
| No `var`, no Lombok | `grep` | ✅ |
| YAML | PyYAML, every `.yml`/`.yaml` | ✅ 10 files |
| config-repo hygiene + test expectations | Python re-implementation of the hygiene test; flattened keys compared with test assertions | ✅ 0 violations; values match |
| GitHub workflow | `actionlint` 1.7.12 (includes shellcheck of `run:` scripts) | ✅ (one YAML quoting bug found and fixed) |
| Dockerfile | `hadolint` 2.15.1 | ✅ |
| Compose file | `docker compose config --quiet` (Compose v5.5.1) | ✅ |
| Shell scripts | `shellcheck` 0.11.0 on `mvnw` (POSIX sh) and `healthcheck.sh` | ✅ |
| `mvnw` behaviour | Local fake Maven repository: curl + unzip path, cached second run, Java-downloader + tar.gz path (curl/wget/unzip removed from PATH), bad URL, correct and wrong SHA-256 | ✅ all 6 cases behave as designed |
| `healthcheck.sh` behaviour | Fake servers answering `HTTP/1.0 200 OK`, `HTTP/1.1 200`, `HTTP/1.1 200 `, `HTTP/1.1 503`, and nothing | ✅ (two bugs found and fixed: HTTP/1.0 status lines and missing reason phrases) |
| Docs | Mermaid CLI on all diagrams; internal link and anchor check | ✅ 14/14 diagrams; links checked after this report was added |

## 6. Not yet verified (and why)

| Check | Why not here | Where it will run |
|---|---|---|
| `./mvnw verify` (compile against Boot 4.1.1 / Cloud 2025.1.3, all 12 tests, Enforcer, JaCoCo) | Maven Central returns HTTP 403 from this workspace | GitHub Actions `build` job |
| Docker image build and Compose start | No Docker daemon; Docker Hub blocked here | GitHub Actions `compose-smoke` job |
| Resolved dependency versions for the matrix | Needs the Maven build | `build-reports` artifact (`dependency-tree.txt`) |

**I am not claiming Phase 2 passes until the first CI run is green.** If it fails, I will read the logs, fix, and re-run before Phase 3.

## 7. Phase 2 acceptance criteria

| # | Criterion | Status |
|---|---|---|
| 1 | `./mvnw -B -ntp verify` green from an empty cache | ⏳ first CI run |
| 2 | Enforcer rules in place | ✅ written · ⏳ proven by CI |
| 3 | Config server tests | ✅ written · ⏳ CI |
| 4 | Eureka registration test | ✅ written · ⏳ CI |
| 5 | Actuator only on management ports | ✅ written (tests + smoke check) · ⏳ CI |
| 6 | Compose smoke test (healthy, non-root, read-only) | ✅ written · ⏳ CI |
| 7 | Matrix updated with resolved versions | ⏳ after CI |
