# Phase 1 execution plan: Boot 4 platform and telemetry tracer

> Parent plan: `plans/argus.md` → Phase 1. PRD: `docs/prd-argus.md`.

## Context

- **Repo today.** `news-service` is a Spring Boot 3.3.0 NewsCatcher proxy: RestTemplate, the v1 API, and the `ApiResponse` envelope.
  - Baseline `./mvnw test` on Java 21: 9 tests, **1 pre-existing error**. `NewsServiceApplicationTests.contextLoads` fails on an unresolved NewsCatcher placeholder. That code is deleted in this phase.
- **Shared CI library** (`/Users/joshua/Documents/works/jenkins-shared-lib`): pytest suite, 50 passing. Six pipelines hard-code `config.dockerRepoPath ?: "common"`.
- **Toolchain.** JDK 21 is at `$(/usr/libexec/java_home -v 21)`. The default `java` is 25, so JAVA_HOME is always set explicitly. Docker Desktop is available for Testcontainers. Python 3 is available for the shared lib's tests.
- **Base image.** `dhi.io/amazoncorretto:21-alpine3.23` is available locally. It has no shell, no wget/curl, an empty entrypoint, and runs as uid 65532.
- **Production database.** Postgres 18 (postgis image) behind **PgBouncer 1.25 in transaction pooling** (`max_prepared_statements=100`).
- **Prior art.** Hymenaios: Boot 4.1.1 OpenTelemetry config, CVE overrides, dashboard and validator, Promtail and Prometheus jobs.
- **Verified against Boot 4.1.1 sources and metadata:**
  - **OTLP endpoint.** `management.opentelemetry.tracing.export.otlp.endpoint` is used **verbatim**. Only the `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable gets `/v1/traces` appended.
  - **Test metrics.** `@SpringBootTest` disables metrics export unless `@AutoConfigureMetrics` or `spring.test.metrics.export=true` is set.
  - **Logging.** `logging.structured.format.console=logstash` flattens MDC into the JSON.
  - **Testcontainers 2.0.** The artifact is `testcontainers-postgresql`, and the container class is `org.testcontainers.postgresql.PostgreSQLContainer`.

## Decisions (resolved with the owner)

1. **Liveness checks the app only; readiness checks the app and the database.** A Postgres outage marks Argus not-ready but doesn't restart it. The PRD wording is corrected.
2. **Health check: a tiny Java probe class.** It calls `/actuator/health/liveness` and is copied into the image as a plain class. The Dockerfile uses an exec-form `HEALTHCHECK` every 30s with small JVM flags. The stack must not override it.
3. **The dashboard validator is a Java JUnit test.** It checks PromQL metrics against the Java metric catalogue directly, plus an explicit external-series list. It runs in `mvn verify`.
4. **Live verification happens at two gates:**
   - **Gate A, in this phase:** a throwaway local stack with Prometheus, Loki, Promtail, Tempo and Grafana runs the built image.
   - **Gate B, after the owner deploys:** a written checklist run against the real stack.

   Nothing touches the real Grafana, Prometheus, Loki, Tempo, Swarm, Nexus or Jenkins in this phase.
5. **`src/main/resources/application-local.yml` is deleted.** It contains the NewsCatcher key, which the owner should revoke. The `application-local.y*ml`, `config/`, `.env*` and `.DS_Store` patterns are git-ignored, and local config is kept out of the jar. Personal overrides go in the ignored `./config/application-local.yml`.
6. **Git.**
   - Argus branch: `feat/argus-phase-1` from `main`. Shared-lib branch: `feat/optional-docker-repo-path` from `main`.
   - Local commits only, with conventional messages and **no trailer**. No push.
   - The PRD and plans are committed on the Argus branch.
   - The untracked `interactive_visual_im_b13838ce8d7ace99.html` and `.DS_Store` are left untouched.
7. **Argus reaches Postgres through PgBouncer in transaction mode**, so it keeps no session state:
   - No `SET` statements and no `connection-init-sql`. UTC comes from Hibernate `jdbc.time_zone`, `timestamptz` columns, and JVM `-Duser.timezone=UTC`.
   - Only transaction-scoped advisory locks.
   - Integration tests use `postgres:18-alpine`. Phase 4 adds a PgBouncer-fronted integration test.

## Corrections to the parent plan and PRD (applied in this phase's docs commit)

- **OTLP endpoint.** The endpoint is **not** a base URL for the Boot property. Argus takes its own validated base URL, `argus.telemetry.otlp-base-url`, which must not contain a path. The YAML appends `/v1/traces`, and `management.opentelemetry.map-environment-variables=false` is set.
- **Tag rename.** The allowed metric tag `job` becomes `scheduled_job`. Otherwise it collides with Prometheus's own `job` label and turns into `exported_job`.
- **Local profile.** The tracked plain-text profile is `dev`. `local` stays reserved for the git-ignored personal file.
- **Liveness group** is `livenessState` only. **Readiness group** is `readinessState,db`.
- **Dashboard validator.** "Stdlib Python validator" becomes "JUnit validator over the metric catalogue".
- **Health check.** "A tool present in the base image" becomes "an exec-form Java probe".
- **Prometheus job:**
  - Discovery goes through `tcp://docker-proxy:2375`.
  - It keeps only `__meta_dockerswarm_network_name=grafana-overlay-network`. Argus sits on three networks, and Swarm task discovery yields one target per network.
- **Promtail job:**
  - It must extract `@timestamp` explicitly. Hymenaios' job silently falls back to fudging the timestamp.
  - The live `docker` job's drop regex must become `.*(hymenaois|argus).*`, or Argus logs are shipped twice.
  - No `$` in the fragment, because the stack runs Promtail with `-config.expand-env=true`.
- **Grafana provisioning.** Argus gets its own provider and a sibling directory (`/etc/grafana/provisioning/dashboards-argus`, folder "Artemis"). Otherwise the Hymenaios provider scans it recursively.
- **Prerequisites** gain three items: the PgBouncer `argus` entry in transaction mode, the Swarm secrets `argus_db_password` and `argus_admin_key` (the DB username is a plain `ARGUS_DB_USERNAME` env var), and confirming that the snapshot registry accepts root-level image names.
- **Error codes.** The list gains generic codes: `BAD_REQUEST`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR`.
- **Phase 1 acceptance** becomes "the dashboard renders without query errors at Gate A". "Live data on the real stack" moves to Gate B.

## Blueprint corrections (applied to the implementer prompts)

- **Admin key.** Validate it in Phase 1 (minimum 32 characters, using `@AssertTrue` so a bind failure never prints the key). The `X-Admin-Key` filter and the 401 entry point stay in **Phase 2**, as planned. The Phase 1 security chain:
  - stateless;
  - csrf, basic auth and form login disabled;
  - actuator `health` and `prometheus` permitted;
  - all other actuator endpoints denied;
  - `anyRequest().permitAll()`, so unknown routes reach the 404 problem response;
  - no generated-password log line.
- **Telemetry config:**
  - No `connection-init-sql` and no `SET` (PgBouncer, decision 7).
  - Tests never call `withReuse`. Testcontainers uses a `@Bean` container plus a `DynamicPropertyRegistrar` that sets `argus.db.*`. This deliberately replaces `@ServiceConnection`, because validated `argus.db.*` would otherwise stay blank.
  - Exact property names are taken from the resolved jars' `META-INF/spring-configuration-metadata.json`, not from memory. This covers the span-export toggle, the OTLP logging toggle and the test-metrics flag.
  - Dependencies come from Maven Central only.
- **Coordinates.** groupId `com.j11a`, artifactId `argus`, version `0.1.0-SNAPSHOT`, package root `com.j11a.argus`.
- **Plugins.** Surefire and Failsafe are Boot-managed: don't redeclare the Failsafe executions; split by `*IT`; both use `@{argLine}`. JaCoCo `report` and `check` (line coverage ≥ 0.80) bind to `verify`, after Failsafe. Remove the legacy Sonar properties.
- **Dependency-check.** Copy the Hymenaios CVE overrides: `jackson-bom.version=3.1.7`, `logback.version=1.6.3`.
- **Actuator spans.** An `ObservationPredicate` drops `/actuator/**` server observations, so Prometheus scrapes and probes don't flood Tempo.
- **Shared library.** Instance methods on `BuildUtils`, used by all six pipelines, including the release pipelines' `repoPath` for the manifest check:
  - `resolveDockerRepoPath(Map config)`: an absent or null value gives `'common'`; an empty or blank value gives `''`; otherwise the value is trimmed and leading or trailing slashes are stripped.
  - `imageRepository(String, String)`.
  - Tests: pytest static tests, plus a Groovy behavioural test that is skipped without a toolchain. The six pipeline files are added to `test_groovy_syntax.py`, and `CLAUDE.md` is updated.

## Execution model

- **Implementers** use this skill's `implementer` agent body through `general-purpose`, on `model: sonnet`, with TDD.
  - No contact with real infrastructure: any `*.jmulenga.home` or `*.jmeighty.com` host, Grafana, Prometheus, Loki, Tempo, Swarm, Nexus, Jenkins, Sonar or dhi.io pushes.
  - Explicit paths only when committing; local commits only.
  - Minimal comments, only where a reader would otherwise get something wrong.
- **WP-S (shared lib) runs in parallel with WP-1.** It is a different repo.
- **WP-1 → WP-2 run sequentially** on `feat/argus-phase-1`. One agent at a time per branch.
- **Gate A** is run by me after WP-2 and verified myself.
- **Review gate.** After Gate A, the six reviewers run read-only and in parallel over both repos' diffs. Then triage, owner approval, fixes, and re-verification.

## Work packages

### WP-S: `jenkins-shared-lib` (parallel)

- **Changes:**
  - Add the helpers to `src/com/j11a/BuildUtils.groovy`.
  - Edit the six `vars/standard*Pipeline.groovy` files: snapshot and release, Spring, Python, React and Next.js. This includes the release pipelines' `repoPath`.
- **Tests:**
  - New `tests/test_docker_repo_path.py`: static checks, plus a Groovy behavioural test. Cases: omitted → `common`, `''` → none, `' '` → none, `'artemis'`, `'/artemis/'`, `null` → `common`, `imageRepository('', 'argus') == 'argus'`.
  - Extend `GROOVY_FILES`.
- **Docs.** Update `CLAUDE.md`: there is now a test suite, and omitted vs empty `dockerRepoPath` is documented.
- **Commit:** `feat: support registry-root images via empty dockerRepoPath`.

### WP-1: Argus platform, errors, security skeleton and telemetry

- **Delete:** the old `com.j11a.dashboard.newsservice` main and test trees, the NewsCatcher fixture, and `application-local.yml`.
- **`.gitignore`:** add the patterns from decision 5.
- **`pom.xml`:**
  - Boot parent 4.1.1.
  - Starters: webmvc, actuator, security, validation, data-jpa, liquibase, and opentelemetry (excluding `micrometer-registry-otlp`). Plus `micrometer-registry-prometheus` and `postgresql`.
  - Test starters: webmvc-test, security-test, actuator-test, test. Plus `spring-boot-testcontainers` and `testcontainers-postgresql`.
  - Resources exclude `application-local.y*ml`.
  - Plugins and Sonar as listed under the blueprint corrections.
  - JaCoCo excludes `ArgusApplication` and `config/**`.
- **Configuration:**
  - `application.yml`, `application-dev.yml`, test `application-it.yml`.
  - `ArgusProperties` (validated record: db, admin, telemetry) and `ArgusConfiguration`.
  - Swarm secrets via `optional:configtree:/run/secrets/`.
  - Graceful shutdown with a 30s timeout.
  - Open-in-view off, `ddl-auto: none`, Hikari `pool-name: argus-pool` with a maximum of 10.
  - `spring.web.resources.add-mappings=false`.
- **Web:**
  - `ApiPaths.BASE="/news/v2"`, with a `WebMvcConfig` path prefix for `@RestController`.
  - The `ErrorCode` enum: the plan's codes plus the generic ones.
  - `ApiException`, and `GlobalExceptionHandler` (problem+json with `code`, a URN `type`, and field `errors`). A catch-all returns 500 without leaking the exception message.
- **Security:** the Phase 1 chain described under the blueprint corrections.
- **Liquibase:** a master changelog plus `01-enable-unaccent` (`CREATE EXTENSION IF NOT EXISTS unaccent`).
- **Observability:**
  - `MetricNames` (prefix `argus`, `Tags` including `SCHEDULED_JOB`).
  - `MeterKind`, `MeterSpec` (Prometheus series derivation), `MetricCatalogue` (empty `ALL`, `ALLOWED_TAGS`).
  - The `ObservationPredicate`.
  - Percentile histograms for `http.server.requests`, `http.client.requests`, `hikaricp.connections.acquire` and `argus`.
  - Metric tag `application=argus`, sampling 1.0, OTLP metrics and logs export off, Logstash console logs.
- **Health probe:** `probe/HealthProbe` (`java.net.http`, returns 0 on 200, otherwise 1).
- **Tests:**
  - **Unit:** `ArgusPropertiesTest` (including "the key is never in the failure message"), `ApplicationYamlTest`, `StartupFailFastTest`, `ErrorCodeTest`, `GlobalExceptionHandlerTest`, `SecurityConfigTest`, `MetricCatalogueTest`, `ObservabilityConfigTest`, `HealthProbeTest`.
  - **Integration, using a test-only `ProbeController` under `/news/v2/probe`:**
    - `ApplicationStartupIT`: liveness and readiness are UP without auth, there are no details, and readiness includes `db`.
    - `LiquibaseBaselineIT`: `unaccent('Ødegaard')='Odegaard'`.
    - `PrometheusEndpointIT`: HTTP, JVM, Hikari and process series; `application="argus"`; a test `argus.*` timer has buckets; there are no actuator series; other actuator endpoints are not 200.
    - `JsonLoggingIT`: a `traceparent` header leads to the same `traceId` in a JSON log line that has `@timestamp`, `level` and `logger_name`.
    - `TraceExportIT`: a stub OTLP server receives `POST /v1/traces` with `service.name=argus`.
    - `ErrorHandlingIT`.
- **Commits:**
  - `docs: add Argus PRD and plans`: the PRD, the plans, and the corrections above.
  - `feat: rebuild as Argus on Spring Boot 4 with telemetry foundation`.

### WP-2: Dashboard, validator, packaging, observability configuration

- **Dashboard validator** (test sources, `observability.dashboard`):
  - `PromqlMetricExtractor`, `DashboardValidator`, `AllowedSeries`. External series are listed by kind, with exact suffix sets.
  - Fixtures: one valid, plus one per rule.
  - Tests: `PromqlMetricExtractorTest`, `DashboardValidatorTest`, `ArgusDashboardTest`.
- **Dashboard and provisioning:**
  - `grafana/dashboards/argus-observability.json`: uid `argus-observability`, title "Argus — Observability", tags, 30s refresh, now-6h.
  - Variables: datasource variables `DS_*`; `source` and `feed` as custom stubs with an "All" option; `level`; `search`.
  - Seven rows, using the blueprint's queries:
    - Overview
    - API & HTTP
    - JVM & runtime
    - PostgreSQL & HikariCP
    - Container
    - Traces
    - Logs
  - Ratio and quantile queries must handle zero traffic without returning NaN.
  - `grafana/dashboards/dashboard.yaml`: its own provider.
- **Packaging:**
  - Dockerfile: a single `FROM dhi.io/amazoncorretto:21-alpine3.23`, `WORKDIR /app`, and `COPY --chown=65532:65532` of the jar and the probe class.
  - `JAVA_TOOL_OPTIONS` (MaxRAMPercentage 75, ExitOnOutOfMemoryError, UTC), `EXPOSE 8080`, `USER 65532:65532`.
  - The exec-form probe `HEALTHCHECK` (30s interval, 5s timeout, 60s start period, 3 retries) and an exec-form `ENTRYPOINT`.
  - `.dockerignore` keeps only the jar and the probe class.
  - Jenkinsfile: `@Library('jenkins-shared-lib') _` and `standardSpringSnapshotPipeline(dockerRepoPath: '')`.
  - `BuildContractTest` reads the pom, Jenkinsfile and Dockerfile, and fails if anything NewsCatcher-related remains.
- **Observability configuration:**
  - `observability/prometheus/argus-scrape.yml`, `observability/promtail/argus-job.yml`, and an `observability/README.md` that includes the Gate B checklist.
  - `ObservabilityFilesTest` covers them.
- **Gate A kit:** `observability/local/` holds a compose file plus config for Prometheus (static target), Loki, Promtail (Docker discovery on the local socket, Argus only), Tempo, Grafana (datasources with the derived field and `tracesToLogsV2`, and the provisioned dashboard) and a Postgres 18 container, together with a traffic script. Everything runs on localhost.
- **README.md:** what Argus is, environment variables and secrets, build and verify commands, a link to the observability README.
- **Commit:** `feat: add Argus dashboard, validator, container image and observability config`.

## Verification

```bash
# shared lib
cd /Users/joshua/Documents/works/jenkins-shared-lib && python3 -m pytest -q -rs

# argus
cd /Users/joshua/Documents/digital-dashboard/news-service
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B clean verify                     # unit + IT + validator + JaCoCo ≥ 80%
ls target/site/jacoco/jacoco.xml target/argus.jar
grep -rilE 'newscatcher|newsservice|RestTemplate|ApiResponse' src pom.xml   # expect nothing
docker run --rm -i hadolint/hadolint:latest-alpine hadolint --failure-threshold warning - < Dockerfile
docker build -t argus:local .
```

**Gate A (local):** run `docker compose` in `observability/local`, run the traffic script, then confirm all of the following:

- Prometheus has the target up, with HTTP, JVM and Hikari series and `application="argus"`.
- Tempo has traces for `service.name=argus` and no `/actuator` spans.
- Loki has `{service="argus"}` JSON lines, with `level` as a label and `traceId` that links to Tempo.
- Every dashboard panel renders without query errors. Any intentionally empty panels are documented.
- The container reports `healthy` through the Java probe.

**Gate B (owner, after deployment):** the checklist in `observability/README.md`.

**Done means:**

- Both repos' commits exist and `git status` is clean apart from the owner's untracked files.
- All the commands above pass and nothing is skipped.
- Gate A passes.
- The six-reviewer gate is triaged and the fixes are re-verified.
